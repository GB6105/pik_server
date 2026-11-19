package io.github.gb6105.pik.analysis;

/**
 * Progress of a request, computed from the jobs it points at (design 1.5): its own jobs and those of other requests
 * that its {@code IN_PROGRESS} photos wait for.
 */
enum Phase {

	/** Every job waits for its image or its turn. */
	WAITING,

	/** Some job runs, or some are over while others are not. */
	RUNNING,

	/** No job, or every job is over. Generation of actions and events is not included. */
	DONE

}
