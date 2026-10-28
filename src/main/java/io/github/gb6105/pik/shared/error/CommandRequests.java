package io.github.gb6105.pik.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ProblemDetail;

/**
 * Marks a request as a change request so that its error responses carry {@code jobId} and {@code result}.
 */
public final class CommandRequests {

	private static final String COMMAND = CommandRequests.class.getName() + ".command";

	private static final String JOB_ID = CommandRequests.class.getName() + ".jobId";

	private CommandRequests() {
	}

	/**
	 * @param jobId the job ID reported on failure; {@code null} when no job exists yet
	 */
	public static void mark(HttpServletRequest request, @Nullable UUID jobId) {
		request.setAttribute(COMMAND, Boolean.TRUE);
		request.setAttribute(JOB_ID, jobId);
	}

	static ProblemDetail problem(ApiError error, HttpServletRequest request) {
		if (request.getAttribute(COMMAND) == null) {
			return error.toProblemDetail();
		}
		return error.toCommandProblemDetail((UUID) request.getAttribute(JOB_ID));
	}

}
