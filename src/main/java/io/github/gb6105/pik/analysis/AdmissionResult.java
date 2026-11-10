package io.github.gb6105.pik.analysis;

/**
 * What intake decided for one photo of a request (design 1.5). {@link #EXISTING_RESULT} comes with the results of
 * stage 5-2 (decision Q11); stage 5-1 never decides it.
 */
enum AdmissionResult {

	/** A job of this request analyzes the photo, as its representative or as a member. */
	ACCEPTED,

	/** A stored result of the same photo version is reused. */
	EXISTING_RESULT,

	/** A job that is not over yet, possibly of another request, already analyzes the photo. */
	IN_PROGRESS,

	/** The photo is not analyzed; {@code code} and {@code reason} say why. */
	NOT_ACCEPTED

}
