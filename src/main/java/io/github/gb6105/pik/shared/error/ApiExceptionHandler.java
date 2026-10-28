package io.github.gb6105.pik.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;
import org.springframework.web.util.WebUtils;

/**
 * Renders errors raised inside the DispatcherServlet as problem responses whose {@code code} matches the HTTP
 * status 1:1. Errors outside it ({@code sendError}, filter exceptions) are rendered by {@link ApiErrorController}.
 */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(PikException.class)
	@Nullable ResponseEntity<ProblemDetail> handlePikException(PikException ex, HttpServletRequest request,
			HttpServletResponse response) {
		if (response.isCommitted()) {
			return null;
		}
		HttpHeaders headers = new HttpHeaders();
		Duration retryAfter = ex.getRetryAfter();
		if (retryAfter != null) {
			long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, seconds)));
		}
		// RFC 9110 requires the challenge on 401; the security filter chain sets a more specific one itself.
		if (ex.getCode() == ErrorCode.UNAUTHENTICATED && !response.containsHeader(HttpHeaders.WWW_AUTHENTICATE)) {
			headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		ApiError error = new ApiError(ex.getCode(), ex.getReason(), ex.isRetryable());
		return new ResponseEntity<>(CommandRequests.problem(error, request), headers, error.code().status());
	}

	// No 500 in the requirements' error table: unexpected failures surface as a retryable TEMPORARY_FAILURE.
	@ExceptionHandler(Exception.class)
	@Nullable ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request,
			HttpServletResponse response) {
		if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
			return null;
		}
		ApiError error = new ApiError(ErrorCode.TEMPORARY_FAILURE, "INTERNAL_ERROR", true);
		logFailure(ex, error);
		request.setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, ex);
		if (response.isCommitted()) {
			return null;
		}
		return new ResponseEntity<>(CommandRequests.problem(error, request), error.code().status());
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		ApiError error = classify(ex, statusCode);
		if (error.code() == ErrorCode.TEMPORARY_FAILURE) {
			logFailure(ex, error);
			request.setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, ex, WebRequest.SCOPE_REQUEST);
		}
		ServletWebRequest servletRequest = (ServletWebRequest) request;
		HttpServletResponse response = servletRequest.getResponse();
		if (response != null && response.isCommitted()) {
			return null;
		}
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.addAll(headers);
		return new ResponseEntity<>(CommandRequests.problem(error, servletRequest.getRequest()), responseHeaders,
				error.code().status());
	}

	private static ApiError classify(Exception ex, HttpStatusCode statusCode) {
		if (ex instanceof MissingApiVersionException) {
			return new ApiError(ErrorCode.INVALID_INPUT, "API_VERSION_REQUIRED", false);
		}
		if (ex instanceof InvalidApiVersionException) {
			return new ApiError(ErrorCode.INVALID_INPUT, "API_VERSION_UNSUPPORTED", false);
		}
		if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
			return new ApiError(ErrorCode.INVALID_INPUT, "VALIDATION_FAILED", false);
		}
		if (ex instanceof HttpMessageNotReadableException) {
			return new ApiError(ErrorCode.INVALID_INPUT, "MALFORMED_BODY", false);
		}
		if (ex instanceof HttpMediaTypeException) {
			return new ApiError(ErrorCode.INVALID_INPUT, "MEDIA_TYPE_NOT_SUPPORTED", false);
		}
		if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
			return new ApiError(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false);
		}
		if (ex instanceof HttpRequestMethodNotSupportedException) {
			return new ApiError(ErrorCode.UNSUPPORTED_OPERATION, "METHOD_NOT_ALLOWED", false);
		}
		return ApiError.forStatus(statusCode);
	}

	private static void logFailure(Exception ex, ApiError error) {
		log.atError().setCause(ex).addKeyValue("reason", error.reason()).log("server error");
	}

}
