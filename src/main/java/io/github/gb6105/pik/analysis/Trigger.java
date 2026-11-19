package io.github.gb6105.pik.analysis;

/**
 * Why the client sent a request ({@code trigger}, FR-I-01). It fixes the base priority of the request's jobs
 * (ADR-0031 #10); server batches use {@code PRO_BACKLOG} (priority 2), which clients cannot send.
 */
enum Trigger {

	NEW_PHOTOS(0), ORGANIZE(1);

	private final int basePriority;

	Trigger(int basePriority) {
		this.basePriority = basePriority;
	}

	/**
	 * @return {@code analysis_job.base_priority}; lower runs first
	 */
	int basePriority() {
		return basePriority;
	}

}
