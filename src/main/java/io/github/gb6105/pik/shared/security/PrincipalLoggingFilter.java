package io.github.gb6105.pik.shared.security;

import io.github.gb6105.pik.shared.logging.PrincipalLogging;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags the request logs with the principal once its access token is verified. Added to the security filter chain
 * only, not registered as a servlet filter.
 */
class PrincipalLoggingFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
			PrincipalLogging.bind(request, CurrentPrincipal.from(token.getToken()).id());
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			PrincipalLogging.clear();
		}
	}

}
