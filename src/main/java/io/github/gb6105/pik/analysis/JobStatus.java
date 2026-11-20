package io.github.gb6105.pik.analysis;

/**
 * {@code analysis_job.status} (design 2.1). Intake creates jobs {@link #AWAITING_UPLOAD}; the transitions come with
 * stage 5-2.
 */
enum JobStatus {

	AWAITING_UPLOAD, READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, INELIGIBLE;

	/**
	 * Whether the job is waiting for its image or for its turn.
	 */
	boolean waiting() {
		return this == AWAITING_UPLOAD || this == READY;
	}

	/**
	 * Whether the job is over, successfully or not.
	 */
	boolean over() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INELIGIBLE;
	}

	/**
	 * Whether the job still holds its representative photo: every status but those that end without a result, as
	 * {@code uk_analysis_job_photo_active} counts them. Photos of such a job are not intake candidates; intake decides
	 * the photos with a result first, so a {@link #SUCCEEDED} job is in progress only for a photo it left without one.
	 */
	boolean live() {
		return this != FAILED && this != CANCELLED && this != INELIGIBLE;
	}

}
