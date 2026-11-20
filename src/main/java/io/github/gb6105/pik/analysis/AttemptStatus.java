package io.github.gb6105.pik.analysis;

/**
 * {@code analysis_upload_attempt.status} (design 2.1). Only {@link #ISSUED} changes: it becomes {@link #CONFIRMED} or
 * {@link #REJECTED} when the upload is confirmed, {@link #SUPERSEDED} when a new attempt is issued for the job and
 * {@link #ABANDONED} when the job ends. A job has at most one of each of {@link #ISSUED} and {@link #CONFIRMED}.
 */
enum AttemptStatus {

	ISSUED, CONFIRMED, REJECTED, SUPERSEDED, ABANDONED

}
