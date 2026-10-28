package io.github.gb6105.pik.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Assigns a trace ID to each request and logs one completion line with allow-listed fields only (ADR-0023 #9).
 * The raw URI is never logged because query strings may carry search terms.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter extends OncePerRequestFilter {

	static final String TRACE_ID = "trace_id";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long startedAt = System.nanoTime();
		MDC.put(TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
		try {
			chain.doFilter(request, response);
		}
		finally {
			log.atInfo()
				.addKeyValue("action", action(request))
				.addKeyValue("http_status", response.getStatus())
				.addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
				.log("request completed");
			MDC.remove(TRACE_ID);
		}
	}

	private static String action(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		return request.getMethod() + " " + (pattern != null ? pattern : "UNMATCHED");
	}

}
