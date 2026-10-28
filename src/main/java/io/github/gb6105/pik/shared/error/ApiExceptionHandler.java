package io.github.gb6105.pik.shared.error;

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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as a problem response whose {@code code} matches the HTTP status 1:1.
 */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(PikException.class)
	ResponseEntity<ProblemDetail> handlePikException(PikException ex) {
		return respond(ex.getCode(), ex.getReason(), ex.isRetryable(), HttpHeaders.EMPTY);
	}

	// No 500 in the requirements' error table: unexpected failures surface as a retryable TEMPORARY_FAILURE.
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.atError().setCause(ex).addKeyValue("reason", "INTERNAL_ERROR").log("unexpected error");
		return respond(ErrorCode.TEMPORARY_FAILURE, "INTERNAL_ERROR", true, HttpHeaders.EMPTY);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		Classified classified = classify(ex, statusCode);
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.addAll(headers);
		return new ResponseEntity<>(ApiProblems.of(classified.code(), classified.reason(), classified.retryable()),
				responseHeaders, classified.code().status());
	}

	private static Classified classify(Exception ex, HttpStatusCode statusCode) {
		if (ex instanceof MissingApiVersionException) {
			return new Classified(ErrorCode.INVALID_INPUT, "API_VERSION_REQUIRED", false);
		}
		if (ex instanceof InvalidApiVersionException) {
			return new Classified(ErrorCode.INVALID_INPUT, "API_VERSION_UNSUPPORTED", false);
		}
		if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
			return new Classified(ErrorCode.INVALID_INPUT, "VALIDATION_FAILED", false);
		}
		if (ex instanceof HttpMessageNotReadableException) {
			return new Classified(ErrorCode.INVALID_INPUT, "MALFORMED_BODY", false);
		}
		if (ex instanceof HttpMediaTypeException) {
			return new Classified(ErrorCode.INVALID_INPUT, "MEDIA_TYPE_NOT_SUPPORTED", false);
		}
		if (ex instanceof NoResourceFoundException) {
			return new Classified(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false);
		}
		if (ex instanceof HttpRequestMethodNotSupportedException) {
			return new Classified(ErrorCode.UNSUPPORTED_OPERATION, "METHOD_NOT_ALLOWED", false);
		}
		if (statusCode.is5xxServerError()) {
			return new Classified(ErrorCode.TEMPORARY_FAILURE, "SERVER_UNAVAILABLE", true);
		}
		return new Classified(ErrorCode.INVALID_INPUT, "BAD_REQUEST", false);
	}

	private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String reason, boolean retryable,
			HttpHeaders headers) {
		return new ResponseEntity<>(ApiProblems.of(code, reason, retryable), headers, code.status());
	}

	private record Classified(ErrorCode code, String reason, boolean retryable) {
	}

}
