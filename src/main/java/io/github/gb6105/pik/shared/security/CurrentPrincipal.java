package io.github.gb6105.pik.shared.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated principal of the current request. Declare it as a controller method parameter; ownership checks
 * use {@link #id()} rather than IDs from the request (CLAUDE.md invariants).
 */
public record CurrentPrincipal(UUID id, PrincipalKind kind) {

	// The decoder has already validated both claims (SecurityConfig).
	static CurrentPrincipal from(Jwt jwt) {
		return new CurrentPrincipal(UUID.fromString(jwt.getSubject()),
				PrincipalKind.valueOf(jwt.getClaimAsString(AccessTokens.KIND_CLAIM)));
	}

}
