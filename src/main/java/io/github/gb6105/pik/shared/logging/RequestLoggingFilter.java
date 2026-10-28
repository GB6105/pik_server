package io.github.gb6105.pik.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * Assigns a trace ID to each request and logs one completion line with allow-listed fields only (ADR-0023 #9).
 * The raw URI is never logged because query strings may carry search terms.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter extends OncePerRequestFilter {

	static final String TRACE_ID = "trace_id";

	private final String managementBasePath;

	RequestLoggingFilter(@Value("${management.endpoints.web.base-path:/actuator}") String managementBasePath) {
		this.managementBasePath = managementBasePath;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.equals(managementBasePath) || path.startsWith(managementBasePath + "/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long startedAt = System.nanoTime();
		MDC.put(TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
		Throwable failure = null;
		try {
			chain.doFilter(request, response);
		}
		catch (Throwable ex) {
			failure = ex;
			throw ex;
		}
		finally {
			logCompletion(request, response, failure, (System.nanoTime() - startedAt) / 1_000_000);
			MDC.remove(TRACE_ID);
		}
	}

	// An exception escaping the chain is rendered by the error controller as 503 after this filter returns.
	private static void logCompletion(HttpServletRequest request, HttpServletResponse response,
			@Nullable Throwable failure, long durationMs) {
		boolean failed = failure != null && !DisconnectedClientHelper.isClientDisconnectedException(failure);
		LoggingEventBuilder event = failed
				? log.atError().setCause(failure).addKeyValue("reason", "INTERNAL_ERROR") : log.atInfo();
		event.addKeyValue("action", action(request))
			.addKeyValue("http_status", failed ? HttpStatus.SERVICE_UNAVAILABLE.value() : response.getStatus())
			.addKeyValue("duration_ms", durationMs)
			.log(failed ? "request failed" : "request completed");
	}

	private static String action(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		return request.getMethod() + " " + (pattern != null ? pattern : "UNMATCHED");
	}

}
