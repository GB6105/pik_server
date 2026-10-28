package io.github.gb6105.pik.shared.error;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Error body members added to RFC 9457 problem responses (ADR-0023 #2).
 */
public record ApiError(ErrorCode code, String reason, boolean retryable) {

	/**
	 * Fallback for errors that carry only an HTTP status, such as {@code sendError} or framework exceptions.
	 */
	public static ApiError forStatus(HttpStatusCode status) {
		ErrorCode code = ErrorCode.fromStatus(status);
		String reason = switch (status.value()) {
			case 401 -> "AUTHENTICATION_REQUIRED";
			case 403 -> "ACCESS_DENIED";
			case 404 -> "RESOURCE_NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 409 -> "STATE_CONFLICT";
			case 422 -> "UNPROCESSABLE";
			case 429 -> "TOO_MANY_REQUESTS";
			case 500 -> "INTERNAL_ERROR";
			default -> status.is5xxServerError() ? "SERVER_UNAVAILABLE" : "BAD_REQUEST";
		};
		boolean retryable = code == ErrorCode.RATE_LIMITED || code == ErrorCode.TEMPORARY_FAILURE;
		return new ApiError(code, reason, retryable);
	}

	/**
	 * Problem body for a change request, which also carries {@code jobId} and {@code result} (ADR-0023 #12).
	 */
	public ProblemDetail toCommandProblemDetail(@Nullable UUID jobId) {
		ProblemDetail problem = toProblemDetail();
		problem.setProperty("jobId", jobId);
		problem.setProperty("result", CommandResult.FAILED.name());
		return problem;
	}

	public ProblemDetail toProblemDetail() {
		ProblemDetail problem = ProblemDetail.forStatus(code.status());
		problem.setProperty("code", code.name());
		problem.setProperty("reason", reason);
		problem.setProperty("retryable", retryable);
		return problem;
	}

}
