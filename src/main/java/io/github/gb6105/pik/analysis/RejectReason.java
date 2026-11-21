package io.github.gb6105.pik.analysis;

/**
 * {@code analysis_upload_attempt.reject_reason}: why an uploaded object was not confirmed (plan 4.2).
 */
enum RejectReason {

	/** The object is not as long as the attempt was issued for. */
	SIZE_MISMATCH,

	/** The stored SHA-256 checksum is missing or not the one the attempt was issued for. */
	CHECKSUM_MISMATCH,

	/** The stored content type is not the upload policy's. */
	CONTENT_TYPE_MISMATCH,

	/** The object was stored after the attempt's URL expired. */
	LATE_UPLOAD

}
