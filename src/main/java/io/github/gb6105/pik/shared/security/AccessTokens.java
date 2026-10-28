package io.github.gb6105.pik.shared.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens: ES256 JWTs with {@code typ} and {@code kid} headers and the claims {@code iss}, {@code sub}
 * (principal ID), {@code kind}, {@code iat} and {@code exp}, without personal data (ADR-0023 #8).
 */
@Component
public class AccessTokens {

	static final String KIND_CLAIM = "kind";

	/**
	 * {@code typ} header, required on every request. Only access tokens are signed with these keys; another kind of
	 * JWT needs its own keys (ADR-0027 #13).
	 */
	static final String TYPE = "JWT";

	private final JwtEncoder encoder;

	private final String keyId;

	private final JwtProperties properties;

	AccessTokens(JwtKeys keys, JwtProperties properties) {
		this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
		this.keyId = keys.signingKey().getKeyID();
		this.properties = properties;
	}

	public AccessToken issue(UUID principalId, PrincipalKind kind) {
		Instant now = Instant.now();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).type(TYPE).keyId(keyId).build();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.subject(principalId.toString())
			.claim(KIND_CLAIM, kind.name())
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, properties.accessTokenTtl());
	}

	public record AccessToken(String value, Duration expiresIn) {
	}

}
