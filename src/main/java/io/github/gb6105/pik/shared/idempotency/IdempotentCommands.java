package io.github.gb6105.pik.shared.idempotency;

import io.github.gb6105.pik.shared.error.CommandRequests;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.method.HandlerMethod;

/**
 * Recognizes {@link IdempotentCommand} handlers, also for failures raised before the handler runs, such as
 * authentication in the security filter chain (ADR-0027).
 */
public final class IdempotentCommands {

	private IdempotentCommands() {
	}

	/**
	 * Marks the request as a change request when {@code handler} is an {@link IdempotentCommand}, so that its error
	 * response carries {@code jobId} (the {@code Idempotency-Key}, when valid) and {@code result}.
	 */
	public static void markIfCommand(HttpServletRequest request, Object handler) {
		if (isCommand(handler)) {
			mark(request);
		}
	}

	static boolean isCommand(Object handler) {
		return handler instanceof HandlerMethod method && method.hasMethodAnnotation(IdempotentCommand.class);
	}

	static @Nullable IdempotencyKey mark(HttpServletRequest request) {
		IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
		CommandRequests.mark(request, key != null ? key.value() : null);
		return key;
	}

}
