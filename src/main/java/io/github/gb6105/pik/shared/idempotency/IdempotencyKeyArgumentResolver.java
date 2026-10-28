package io.github.gb6105.pik.shared.idempotency;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the key validated by {@link IdempotencyKeyInterceptor}.
 */
class IdempotencyKeyArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == IdempotencyKey.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Object key = webRequest.getAttribute(IdempotencyKeyInterceptor.KEY_ATTRIBUTE,
				RequestAttributes.SCOPE_REQUEST);
		if (key == null) {
			throw new IllegalStateException("IdempotencyKey parameter requires @IdempotentCommand on "
					+ parameter.getExecutable().toGenericString());
		}
		return key;
	}

}
