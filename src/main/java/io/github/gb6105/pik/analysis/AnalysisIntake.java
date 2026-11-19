package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.AttestationBinding;
import io.github.gb6105.pik.account.AttestationEvidence;
import io.github.gb6105.pik.account.AttestationProof;
import io.github.gb6105.pik.account.RequestingDevice;
import io.github.gb6105.pik.account.RequestingDevices;
import io.github.gb6105.pik.account.TrialStartAttestations;
import io.github.gb6105.pik.analysis.IntakeBodyReader.IntakeBody;
import io.github.gb6105.pik.analysis.IntakePreparation.Prepared;
import io.github.gb6105.pik.analysis.IntakeTransaction.StartRequiredSignal;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentExecutor;
import io.github.gb6105.pik.shared.idempotency.IdempotentRequest;
import io.github.gb6105.pik.shared.idempotency.IdempotentResponse;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import io.github.gb6105.pik.usage.TrialUsage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Runs an intake request outside database transactions (design 1.2, plan 5): device check, idempotency lookup, value
 * checks, quick consent check, preparation, trial start with its first assertion, then the business transaction
 * through the executor, once more after {@link StartRequiredSignal}. Failures before the transaction go through
 * {@link IdempotentExecutor#reject}, which stores final ones for the key and replays a record stored meanwhile; evidence
 * errors and 503 are not stored (B6-2.E6). Every error response carries {@code jobId = null} (decision Q13).
 */
@Service
@RequiredArgsConstructor
class AnalysisIntake {

	static final String OPERATION = "analysis.intake";

	/** Longest App Attest header value accepted; longer evidence is malformed (B6-2.E5). */
	static final int MAX_EVIDENCE_LENGTH = 4096;

	private final IdempotentExecutor executor;

	private final IntakeProjection projection;

	private final IntakeBodyReader bodyReader;

	private final RequestingDevices devices;

	private final AiConsents consents;

	private final IntakePreparation preparation;

	private final IntakeTransaction transaction;

	private final TrialUsage trialUsage;

	private final TrialStartAttestations attestations;

	private final AnalysisProperties properties;

	IdempotentResponse accept(CurrentPrincipal principal, IdempotencyKey key, IntakeBody body,
			TrialStartHeaders headers) {
		// B6-2.E3 step 1: the token's device ID alone does not stand for the device.
		RequestingDevice device = devices.find(principal.id(), principal.deviceId())
			.orElseThrow(() -> new PikException(ErrorCode.UNAUTHENTICATED, "ACCESS_TOKEN_INVALID", false));
		IdempotentRequest request = new IdempotentRequest(principal.id(), key, OPERATION, body.command(), true);
		Optional<IdempotentResponse> stored = executor.lookup(request, projection);
		if (stored.isPresent()) {
			return stored.get();
		}
		if (!properties.intakeEnabled()) {
			throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "INTAKE_DISABLED", true);
		}
		bodyReader.validate(body.command());
		if (!consents.isGranted(principal.id())) {
			return executor.reject(request,
					new PikException(ErrorCode.FORBIDDEN, "AI_CONSENT_REQUIRED", false), projection);
		}
		Prepared prepared = preparation.prepare(principal.id(), device, body.command());
		StartGuard guard = new StartGuard(principal.id(), device.deviceId(), key, body.sha256Hex(), headers);
		try {
			startTrial(prepared, prepared.trialStartPhotos(), headers, guard);
		}
		catch (PikException ex) {
			return executor.reject(request, ex, projection);
		}
		try {
			return executor.commit(request, () -> transaction.run(prepared), projection);
		}
		catch (StartRequiredSignal blocked) {
			// A release since the start check made the trial cover a group: start it for that group and decide again.
			try {
				startTrial(prepared, blocked.photos(), headers, guard);
			}
			catch (PikException ex) {
				return executor.reject(request, ex, projection);
			}
		}
		try {
			return executor.commit(request, () -> transaction.run(prepared), projection);
		}
		catch (StartRequiredSignal again) {
			return executor.reject(request,
					PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "TRIAL_START_CONFLICT", true), projection);
		}
	}

	// TrialUsage returns at once for Android and started iOS devices; a device without a mark reserves nothing.
	private void startTrial(Prepared prepared, int photos, TrialStartHeaders headers, StartGuard guard) {
		UUID trialMarkId = prepared.trialMarkId();
		if (trialMarkId != null && photos >= 1) {
			trialUsage.recordStart(prepared.ownerId(), trialMarkId, photos, headers.deviceCheckToken(), guard);
		}
	}

	/**
	 * Headers with one-time evidence, kept out of the idempotency hash (ADR-0030 #4) and of logs.
	 *
	 * @param deviceCheckToken {@code Device-Check-Token}
	 * @param assertion {@code App-Attest-Assertion}
	 * @param challengeId {@code App-Attest-Challenge-Id}
	 * @param nonce {@code App-Attest-Nonce}
	 */
	record TrialStartHeaders(@Nullable String deviceCheckToken, @Nullable String assertion,
			@Nullable String challengeId, @Nullable String nonce) {

		@Override
		public String toString() {
			return "TrialStartHeaders[hidden]";
		}

	}

	/**
	 * Requires a first trial assertion right before Apple records the start (B6-2.E3). A verified assertion is kept
	 * for the HTTP request, so the retry after {@link StartRequiredSignal} does not move the counter again.
	 */
	private final class StartGuard implements TrialUsage.StartGuard {

		private final UUID principalId;

		private final UUID deviceId;

		private final IdempotencyKey key;

		private final String bodySha256Hex;

		private final TrialStartHeaders headers;

		private @Nullable AttestationProof proof;

		private StartGuard(UUID principalId, UUID deviceId, IdempotencyKey key, String bodySha256Hex,
				TrialStartHeaders headers) {
			this.principalId = principalId;
			this.deviceId = deviceId;
			this.key = key;
			this.bodySha256Hex = bodySha256Hex;
			this.headers = headers;
		}

		@Override
		public void beforeStart() {
			if (proof != null) {
				return;
			}
			String token = Objects.requireNonNull(headers.deviceCheckToken(),
					"TrialUsage requires the token before it calls the guard");
			AttestationBinding binding = new AttestationBinding(key.value(), bodySha256Hex,
					IntakeBodyReader.sha256Hex(token.getBytes(StandardCharsets.UTF_8)));
			String assertion = headers.assertion();
			String challengeId = headers.challengeId();
			String nonce = headers.nonce();
			if (!StringUtils.hasText(assertion) || !StringUtils.hasText(challengeId) || !StringUtils.hasText(nonce)) {
				throw PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true, null,
						Map.of("challenge", attestations.challenge(principalId, deviceId, binding)));
			}
			if (assertion.length() > MAX_EVIDENCE_LENGTH || challengeId.length() > MAX_EVIDENCE_LENGTH
					|| nonce.length() > MAX_EVIDENCE_LENGTH) {
				throw PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_FAILED", false);
			}
			proof = attestations.verify(principalId, deviceId, new AttestationEvidence(assertion, challengeId, nonce),
					binding);
		}

	}

}
