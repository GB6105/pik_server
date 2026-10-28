package io.github.gb6105.pik.shared.security;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotentCommands;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Renders failures of the security filter chain through the controllers' exception handler, so they are problem
 * responses like any other. A change request also gets {@code jobId} and {@code result} (ADR-0023 #12, ADR-0027).
 */
@Component
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	// Looked up lazily: the filter chain is built before the MVC infrastructure.
	private final ObjectProvider<HandlerMapping> handlerMapping;

	private final ObjectProvider<HandlerExceptionResolver> exceptionResolver;

	SecurityProblemHandler(@Qualifier("requestMappingHandlerMapping") ObjectProvider<HandlerMapping> handlerMapping,
			@Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> exceptionResolver) {
		this.handlerMapping = handlerMapping;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		if (ex instanceof OAuth2AuthenticationException invalid) {
			// RFC 6750 error codes from Spring Security (invalid_token, invalid_request), never request data.
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
					"Bearer error=\"" + invalid.getError().getErrorCode() + "\"");
			render(request, response, new PikException(ErrorCode.UNAUTHENTICATED, "ACCESS_TOKEN_INVALID", false));
			return;
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		render(request, response, new PikException(ErrorCode.UNAUTHENTICATED, "ACCESS_TOKEN_REQUIRED", false));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		render(request, response, new PikException(ErrorCode.FORBIDDEN, "ACCESS_DENIED", false));
	}

	private void render(HttpServletRequest request, HttpServletResponse response, PikException failure)
			throws IOException {
		markIfCommand(request);
		ModelAndView rendered = exceptionResolver.getObject().resolveException(request, response, null, failure);
		if (rendered == null && !response.isCommitted()) {
			// The error controller renders the status instead; error dispatches are permitted.
			response.sendError(failure.getCode().status().value());
		}
	}

	private void markIfCommand(HttpServletRequest request) {
		RequestPath previous = ServletRequestPathUtils.hasParsedRequestPath(request)
				? ServletRequestPathUtils.getParsedRequestPath(request) : null;
		ServletRequestPathUtils.parseAndCache(request);
		try {
			HandlerExecutionChain chain = handlerMapping.getObject().getHandler(request);
			if (chain != null) {
				IdempotentCommands.markIfCommand(request, chain.getHandler());
			}
			// As DispatcherServlet does before exception handling: the problem response ignores the handler's produces.
			request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
		}
		catch (Exception ex) {
			// No handler (unknown path, method or API version): the response goes out without jobId.
		}
		finally {
			ServletRequestPathUtils.setParsedRequestPath(previous, request);
		}
	}

}
