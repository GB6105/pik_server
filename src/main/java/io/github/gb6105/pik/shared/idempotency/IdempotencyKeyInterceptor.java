package io.github.gb6105.pik.shared.idempotency;

import io.github.gb6105.pik.shared.error.CommandRequests;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates {@code Idempotency-Key} on {@link IdempotentCommand} handlers before argument binding, so a missing key
 * is reported ahead of body validation errors.
 */
class IdempotencyKeyInterceptor implements HandlerInterceptor {

	static final String KEY_ATTRIBUTE = IdempotencyKeyInterceptor.class.getName() + ".key";

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(IdempotentCommand.class)) {
			return true;
		}
		String header = request.getHeader(IdempotencyKey.HEADER);
		IdempotencyKey key = IdempotencyKey.parse(header);
		CommandRequests.mark(request, key != null ? key.value() : null);
		if (header == null || header.isBlank()) {
			throw new PikException(ErrorCode.INVALID_INPUT, "IDEMPOTENCY_KEY_REQUIRED", false);
		}
		if (key == null) {
			throw new PikException(ErrorCode.INVALID_INPUT, "IDEMPOTENCY_KEY_INVALID", false);
		}
		request.setAttribute(KEY_ATTRIBUTE, key);
		return true;
	}

}
