package io.github.gb6105.pik.analysis;

/**
 * {@code analysis_job.failure_code}: why a job ended without a result, with the status it ends in (design 2.1, plan
 * 2.1). Exposed as the {@code reason} of the job in responses.
 */
enum FailureCode {

	UPLOAD_ATTEMPTS_EXHAUSTED(JobStatus.FAILED),
	UPLOAD_EXPIRED(JobStatus.CANCELLED),
	PROCESSING_EXPIRED(JobStatus.CANCELLED),
	CONSENT_WITHDRAWN(JobStatus.CANCELLED),
	PRINCIPAL_INACTIVE(JobStatus.INELIGIBLE),
	REPRESENTATIVE_DELETED(JobStatus.INELIGIBLE),
	INPUT_CHANGED(JobStatus.FAILED),
	INPUT_MISSING(JobStatus.FAILED),
	INPUT_INVALID(JobStatus.FAILED),
	REFUSAL(JobStatus.FAILED),
	TRUNCATED(JobStatus.FAILED),
	INVALID_OUTPUT(JobStatus.FAILED),
	PROVIDER_REJECTED(JobStatus.FAILED),
	UNKNOWN_OUTCOME(JobStatus.FAILED),
	ATTEMPTS_EXHAUSTED(JobStatus.FAILED),
	DEVICE_BUDGET_EXHAUSTED(JobStatus.FAILED),
	RESULT_CONFLICT(JobStatus.FAILED);

	private final JobStatus status;

	FailureCode(JobStatus status) {
		this.status = status;
	}

	/**
	 * The status a job that ends for this reason gets: {@code FAILED}, {@code CANCELLED} or {@code INELIGIBLE}.
	 */
	JobStatus status() {
		return status;
	}

}
