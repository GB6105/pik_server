package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Stand-in until App Attest assertions are verified (D-21): the assertion text picks the outcome, and challenges are
 * random values that are not stored. Created in the local and test profiles only ({@link AccountConfig}, ADR-0027).
 */
class FakeAssertionVerifier implements AssertionVerifier {

	static final String VALID = "fake-assertion";

	/** Expired, consumed or mismatched challenge: a new one is issued. */
	static final String STALE = "fake-assertion-stale";

	/** Another request moved the key's counter first. */
	static final String RACE = "fake-assertion-race";

	// B6-2.E4: challenges live 5 minutes.
	private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);

	private static final Duration RACE_RETRY_AFTER = Duration.ofSeconds(1);

	private final SecureRandom random = new SecureRandom();

	@Override
	public AttestationChallenge challenge(UUID principalId, UUID deviceId, AttestationBinding binding) {
		byte[] nonce = new byte[32];
		random.nextBytes(nonce);
		return new AttestationChallenge(UuidV7.generate().toString(),
				Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), Instant.now().plus(CHALLENGE_LIFETIME),
				null);
	}

	@Override
	public AttestationProof verify(UUID principalId, UUID deviceId, AttestationEvidence evidence,
			AttestationBinding binding) {
		return switch (evidence.assertion()) {
			case VALID -> new AttestationProof(deviceId, Instant.now());
			case STALE -> throw PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true, null,
					Map.of("challenge", challenge(principalId, deviceId, binding)));
			case RACE -> throw PikException.unstored(ErrorCode.CONFLICT, "ATTESTATION_CONFLICT", true,
					RACE_RETRY_AFTER, Map.of());
			default -> throw PikException.unstored(ErrorCode.FORBIDDEN, "ATTESTATION_FAILED", false);
		};
	}

}
