package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.RequestingDevices;
import io.github.gb6105.pik.analysis.IssueTransaction.CheckedItem;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptRow;
import io.github.gb6105.pik.analysis.UploadRecords.RequestBasis;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentExecutor;
import io.github.gb6105.pik.shared.idempotency.IdempotentRequest;
import io.github.gb6105.pik.shared.idempotency.IdempotentResponse;
import io.github.gb6105.pik.shared.idempotency.ReplayProjection;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Issues and confirms upload attempts outside database transactions (design 2.3, plan 4.1, 4.2). Both requests check
 * the requesting device, find a stored response first and run their business transaction through the executor. An
 * attempt response is signed after the executor returns, the first one and replays alike; a confirmation makes its
 * HEAD before the transaction and hands the result to it, so that every branch goes through the executor.
 */
@Service
@RequiredArgsConstructor
class UploadAttempts {

	static final String ISSUE = "analysis.upload.issue";

	static final String COMPLETE = "analysis.upload.complete";

	static final int MAX_ITEMS = 100;

	private static final int SHA256_BYTES = 32;

	private final IdempotentExecutor executor;

	private final RequestingDevices devices;

	private final UploadRecords attempts;

	private final InputStore store;

	private final IssueTransaction issueTransaction;

	private final CompletionTransaction completionTransaction;

	private final IssueReplay replay;

	private final UploadSigning signing;

	/**
	 * Order of plan 4.1: device, lookup, ownership, values, business transaction, signing.
	 */
	IdempotentResponse issue(CurrentPrincipal principal, IdempotencyKey key, UUID requestId,
			UploadAttemptRequest body) {
		requireDevice(principal);
		IssueCommand command = new IssueCommand(requestId, body.items());
		IdempotentRequest request = new IdempotentRequest(principal.id(), key, ISSUE, command);
		ReplayProjection projection = replay.of(principal.id(), requestId);
		Optional<IdempotentResponse> stored = executor.lookup(request, projection);
		if (stored.isPresent()) {
			return signing.sign(stored.get(), check(command.items()));
		}
		RequestBasis basis = attempts.findRequest(principal.id(), requestId).orElseThrow(UploadAttempts::notFound);
		List<CheckedItem> items = check(command.items());
		IdempotentResponse response = executor.commit(request,
				() -> issueTransaction.run(key.value(), basis, items), projection);
		return signing.sign(response, items);
	}

	/**
	 * Order of plan 4.2: device, ownership, lookup, unlocked read, HEAD when the attempt is {@code ISSUED}, business
	 * transaction with the HEAD result.
	 */
	IdempotentResponse complete(CurrentPrincipal principal, IdempotencyKey key, UUID requestId, UUID attemptId) {
		requireDevice(principal);
		attempts.findOfRequest(principal.id(), requestId, attemptId).orElseThrow(UploadAttempts::notFound);
		IdempotentRequest request = new IdempotentRequest(principal.id(), key, COMPLETE,
				new CompleteCommand(requestId, attemptId));
		Optional<IdempotentResponse> stored = executor.lookup(request, ReplayProjection.IDENTITY);
		if (stored.isPresent()) {
			return stored.get();
		}
		AttemptRow attempt = attempts.find(attemptId).orElseThrow();
		InputStore.Head observed = (attempt.status() == AttemptStatus.ISSUED) ? store.head(attempt.objectKey()) : null;
		return executor.commit(request, () -> completionTransaction.run(key.value(), attemptId, observed),
				ReplayProjection.IDENTITY);
	}

	// B6-2.E3 step 1: the token's device ID alone does not stand for the device.
	private void requireDevice(CurrentPrincipal principal) {
		if (devices.find(principal.id(), principal.deviceId()).isEmpty()) {
			throw new PikException(ErrorCode.UNAUTHENTICATED, "ACCESS_TOKEN_INVALID", false);
		}
	}

	/**
	 * The value checks of plan 4.1, not stored: 1 to 100 items, each with a job ID, a positive length and the
	 * standard base64 of a 32-byte SHA-256, and no job twice. Lengths above a job's policy are decided per item.
	 */
	static List<CheckedItem> check(@Nullable List<UploadAttemptRequest.Item> items) {
		if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
			throw invalid("VALIDATION_FAILED");
		}
		List<CheckedItem> checked = new ArrayList<>();
		for (UploadAttemptRequest.Item item : items) {
			Long length = (item != null) ? item.contentLength() : null;
			byte[] sha256 = (item != null) ? sha256(item.sha256()) : null;
			if (item == null || item.analysisJobId() == null || length == null || length < 1 || sha256 == null) {
				throw invalid("VALIDATION_FAILED");
			}
			checked.add(new CheckedItem(item.analysisJobId(), length, sha256));
		}
		Set<UUID> jobIds = new HashSet<>();
		for (CheckedItem item : checked) {
			if (!jobIds.add(item.analysisJobId())) {
				throw invalid("DUPLICATE_ANALYSIS_JOB");
			}
		}
		return checked;
	}

	// Standard base64 with padding, the form x-amz-checksum-sha256 carries.
	private static byte @Nullable [] sha256(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			byte[] sha256 = Base64.getDecoder().decode(text);
			return (sha256.length == SHA256_BYTES && Base64.getEncoder().encodeToString(sha256).equals(text)) ? sha256
					: null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static PikException invalid(String reason) {
		return new PikException(ErrorCode.INVALID_INPUT, reason, false);
	}

	// Another principal's request or attempt is not found either; nothing is stored for the key.
	private static PikException notFound() {
		return new PikException(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false);
	}

	/**
	 * What an attempt request's hash covers: the path's request ID and the body.
	 */
	record IssueCommand(UUID requestId, @Nullable List<UploadAttemptRequest.Item> items) {

	}

	/**
	 * What a confirmation's hash covers: the path; it has no body.
	 */
	record CompleteCommand(UUID requestId, UUID attemptId) {

	}

}
