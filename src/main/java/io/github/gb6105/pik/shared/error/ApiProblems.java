package io.github.gb6105.pik.shared.error;

import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem bodies with the {@code code}, {@code reason} and {@code retryable} members (ADR-0023 #2).
 */
public final class ApiProblems {

	private ApiProblems() {
	}

	public static ProblemDetail of(ErrorCode code, String reason, boolean retryable) {
		ProblemDetail problem = ProblemDetail.forStatus(code.status());
		problem.setProperty("code", code.name());
		problem.setProperty("reason", reason);
		problem.setProperty("retryable", retryable);
		return problem;
	}

}
