package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.error.CommandResult;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The response of {@code POST /analysis-jobs/{requestId}/upload-attempts} (plan 4.1). The executor stores it without
 * {@link Item#upload()}; the URL is signed for every response after the executor returns, outside the transaction
 * (plan K-1).
 *
 * @param jobId the {@code Idempotency-Key}
 * @param result always {@link CommandResult#SUCCEEDED}: the items were decided, each with its own result
 * @param retryable always {@code false}; items say whether they may be sent again
 * @param items one per item of the request, in its order
 */
record UploadAttemptResponse(UUID jobId, CommandResult result, boolean retryable, List<Item> items) {

	enum Result {

		ISSUED, REJECTED

	}

	/**
	 * @param attemptId the attempt issued, also kept for {@code ATTEMPT_NOT_CURRENT}
	 * @param expiresAt when the attempt's URL stops working, fixed at issue
	 * @param upload where and how to upload, for {@code ISSUED} items of responses only
	 */
	record Item(UUID analysisJobId, Result result, @Nullable UUID attemptId, @Nullable Instant expiresAt,
			@Nullable Upload upload, @Nullable String code, @Nullable String reason, boolean retryable) {

		static Item issued(UUID analysisJobId, UUID attemptId, Instant expiresAt) {
			return new Item(analysisJobId, Result.ISSUED, attemptId, expiresAt, null, null, null, false);
		}

		static Item rejected(UUID analysisJobId, @Nullable UUID attemptId, UploadRejection rejection) {
			return new Item(analysisJobId, Result.REJECTED, attemptId, null, null, rejection.code().name(),
					rejection.reason(), rejection.retryable());
		}

		Item withUpload(Upload signed) {
			return new Item(analysisJobId, result, attemptId, expiresAt, signed, code, reason, retryable);
		}

	}

	/**
	 * A presigned PUT, a bearer credential: never logged nor stored.
	 *
	 * @param headers the headers to send, all of them signed
	 * @param expiresAt when the URL stops working, not after the attempt's {@code expiresAt}
	 */
	record Upload(URI url, String method, Map<String, String> headers, Instant expiresAt) {

		@Override
		public String toString() {
			return "Upload[hidden]";
		}

	}

}
