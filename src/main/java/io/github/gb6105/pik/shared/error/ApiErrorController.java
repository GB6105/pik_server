package io.github.gb6105.pik.shared.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's error page so that {@code sendError} and exceptions escaping filters (e.g. Spring Security)
 * also return problem responses with a {@code code}. The cause is logged by {@code RequestLoggingFilter}.
 */
@RestController
class ApiErrorController implements ErrorController {

	@RequestMapping("${server.error.path:/error}")
	ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
		HttpStatusCode status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
				? HttpStatusCode.valueOf(code) : HttpStatus.INTERNAL_SERVER_ERROR;
		ApiError error = ApiError.forStatus(status);
		return new ResponseEntity<>(error.toProblemDetail(), error.code().status());
	}

}
