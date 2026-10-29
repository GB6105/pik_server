package io.github.gb6105.pik.shared.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated principal of the current request. Declare it as a controller method parameter; ownership checks
 * use {@link #id()} rather than IDs from the request (CLAUDE.md invariants).
 *
 * @param deviceId the device the access token was issued to (ADR-0029)
 */
public record CurrentPrincipal(UUID id, PrincipalKind kind, UUID deviceId) {

	// The decoder has already validated the claims (SecurityConfig).
	static CurrentPrincipal from(Jwt jwt) {
		return new CurrentPrincipal(UUID.fromString(jwt.getSubject()),
				PrincipalKind.valueOf(jwt.getClaimAsString(AccessTokens.KIND_CLAIM)),
				UUID.fromString(jwt.getClaimAsString(AccessTokens.DEVICE_CLAIM)));
	}

}
