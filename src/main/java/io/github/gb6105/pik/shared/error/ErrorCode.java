package io.github.gb6105.pik.shared.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

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

	/**
	 * Closest code for a status the requirements do not list: 405 becomes 422, 5xx becomes 503 (ADR-0025 #10).
	 */
	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 401 -> UNAUTHENTICATED;
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 405, 422 -> UNSUPPORTED_OPERATION;
			case 409 -> CONFLICT;
			case 429 -> RATE_LIMITED;
			default -> status.is5xxServerError() ? TEMPORARY_FAILURE : INVALID_INPUT;
		};
	}

}
