package io.github.gb6105.pik.analysis;

/**
 * What intake decided for one photo of a request (design 1.5, plan 8).
 */
enum AdmissionResult {

	/** A job of this request analyzes the photo, as its representative or as a member. */
	ACCEPTED,

	/** The photo's stored result is reused, whatever version the request observes (ADR-0024 #6-1). */
	EXISTING_RESULT,

	/** A job that is not over yet, possibly of another request, already analyzes the photo. */
	IN_PROGRESS,

	/** The photo is not analyzed; {@code code} and {@code reason} say why. */
	NOT_ACCEPTED

}
