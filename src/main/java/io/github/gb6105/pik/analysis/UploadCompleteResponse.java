package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.error.CommandResult;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The response of {@code POST /analysis-jobs/{requestId}/upload-attempts/{attemptId}/complete} (plan 4.2).
 *
 * @param jobId the {@code Idempotency-Key}
 * @param result {@code SUCCEEDED} when the upload is confirmed, {@code FAILED} when it was rejected or the job ended,
 * {@code CANCELLED} when the job was cancelled
 * @param retryable whether a new attempt may be issued for the job
 * @param code the error class of a confirmation that failed
 * @param reason {@code UPLOAD_INVALID}, or the failure code of the job that ended
 * @param attemptStatus the attempt's status after the confirmation
 * @param status the job's status after the confirmation
 */
record UploadCompleteResponse(UUID jobId, CommandResult result, boolean retryable, @Nullable String code,
		@Nullable String reason, UUID analysisJobId, UUID attemptId, AttemptStatus attemptStatus, JobStatus status) {

}
