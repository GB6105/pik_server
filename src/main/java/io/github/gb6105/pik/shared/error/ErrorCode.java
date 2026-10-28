package io.github.gb6105.pik.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Error classification from the requirements, mapped 1:1 to an HTTP status (api-contract.md).
 */
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	CONFLICT(HttpStatus.CONFLICT),
	UNSUPPORTED_OPERATION(HttpStatus.UNPROCESSABLE_CONTENT),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
	TEMPORARY_FAILURE(HttpStatus.SERVICE_UNAVAILABLE);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
