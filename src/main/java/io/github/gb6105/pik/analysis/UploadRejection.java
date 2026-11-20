package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.error.ErrorCode;

/**
 * Why an item of an upload attempt or confirmation response was not issued or confirmed (plan 4.1, 4.2, 4.4), with
 * the error class it is reported under.
 */
enum UploadRejection {

	/** The job is not one of the request's. */
	NOT_FOUND(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false),

	/** The job no longer waits for its upload. */
	ATTEMPT_NOT_ALLOWED(ErrorCode.CONFLICT, "ATTEMPT_NOT_ALLOWED", false),

	/** The job's upload deadline has passed. */
	UPLOAD_EXPIRED(ErrorCode.CONFLICT, "UPLOAD_EXPIRED", false),

	PRINCIPAL_INACTIVE(ErrorCode.FORBIDDEN, "PRINCIPAL_INACTIVE", false),

	/** The job holds no trial reservation any more. */
	RESERVATION_MISSING(ErrorCode.CONFLICT, "RESERVATION_MISSING", false),

	/** Every attempt the job may have was issued. */
	UPLOAD_ATTEMPT_LIMIT(ErrorCode.CONFLICT, "UPLOAD_ATTEMPT_LIMIT", false),

	/** The image is larger than the job's upload policy allows. */
	VALIDATION_FAILED(ErrorCode.INVALID_INPUT, "VALIDATION_FAILED", false),

	/** The consent the request was accepted on no longer holds. */
	CONSENT_WITHDRAWN(ErrorCode.FORBIDDEN, "CONSENT_WITHDRAWN", false),

	/** A replayed attempt is no longer the job's current one; a new key issues a new attempt. */
	ATTEMPT_NOT_CURRENT(ErrorCode.CONFLICT, "ATTEMPT_NOT_CURRENT", true),

	/** The uploaded object does not match the attempt; a new attempt may be issued. */
	UPLOAD_INVALID(ErrorCode.INVALID_INPUT, "UPLOAD_INVALID", true);

	private final ErrorCode code;

	private final String reason;

	private final boolean retryable;

	UploadRejection(ErrorCode code, String reason, boolean retryable) {
		this.code = code;
		this.reason = reason;
		this.retryable = retryable;
	}

	ErrorCode code() {
		return code;
	}

	String reason() {
		return reason;
	}

	boolean retryable() {
		return retryable;
	}

}
