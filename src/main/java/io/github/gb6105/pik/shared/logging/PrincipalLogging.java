package io.github.gb6105.pik.shared.logging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Tags request logs with the requesting principal ({@code principal_id}, ADR-0023 #9).
 */
public final class PrincipalLogging {

	static final String PRINCIPAL_ID = "principal_id";

	private static final String ATTRIBUTE = PrincipalLogging.class.getName() + ".principalId";

	private PrincipalLogging() {
	}

	/**
	 * Tags the rest of the request's logs, including its completion line. {@code RequestLoggingFilter} removes the
	 * MDC entry when the request ends; callers that run outside it remove the entry with {@link #clear()}.
	 */
	public static void bind(HttpServletRequest request, UUID principalId) {
		request.setAttribute(ATTRIBUTE, principalId);
		MDC.put(PRINCIPAL_ID, principalId.toString());
	}

	public static void clear() {
		MDC.remove(PRINCIPAL_ID);
	}

	static @Nullable UUID principalId(HttpServletRequest request) {
		return (UUID) request.getAttribute(ATTRIBUTE);
	}

}
