package io.github.gb6105.pik.shared.security;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param issuer {@code iss} of issued tokens, checked on every request
 * @param accessTokenTtl access token lifetime (ADR-0023 #3: 15 minutes)
 * @param clockSkew tolerance for {@code exp} checks
 * @param jwkSet JWK set JSON with one private signing key and any previous public keys; may be empty only in the
 * local and test profiles, which then sign with an ephemeral key
 */
@ConfigurationProperties("pik.security.jwt")
record JwtProperties(String issuer, Duration accessTokenTtl, Duration clockSkew, @Nullable String jwkSet) {

	JwtProperties {
		Assert.hasText(issuer, "issuer must not be empty");
		Assert.isTrue(accessTokenTtl.isPositive(), "access-token-ttl must be positive");
		Assert.isTrue(!clockSkew.isNegative(), "clock-skew must not be negative");
	}

}
