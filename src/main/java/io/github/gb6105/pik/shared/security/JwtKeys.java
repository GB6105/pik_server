package io.github.gb6105.pik.shared.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * ES256 keys for access tokens. The one private key signs; every key verifies, so a previous key can stay in the set
 * for verification while tokens it signed expire (ADR-0023 #8, ADR-0027).
 */
final class JwtKeys {

	private final ECKey signingKey;

	private final JWKSet verificationKeys;

	private JwtKeys(ECKey signingKey, List<ECKey> keys) {
		this.signingKey = signingKey;
		this.verificationKeys = new JWKSet(keys.stream().<JWK>map(ECKey::toPublicJWK).toList());
	}

	/**
	 * @param jwkSet configured JWK set JSON, or empty for an ephemeral key
	 * @param ephemeralAllowed whether an empty set may fall back to an ephemeral key (local and test profiles)
	 */
	static JwtKeys load(@Nullable String jwkSet, boolean ephemeralAllowed) {
		if (!StringUtils.hasText(jwkSet)) {
			Assert.state(ephemeralAllowed,
					"pik.security.jwt.jwk-set is required outside the local and test profiles (ADR-0027)");
			ECKey key = generate();
			return new JwtKeys(key, List.of(key));
		}
		List<ECKey> keys = parse(jwkSet);
		List<ECKey> signing = keys.stream().filter(ECKey::isPrivate).toList();
		Assert.state(signing.size() == 1, "The JWT key set must hold exactly one private key, the signing key");
		checkKeyPair(signing.get(0));
		return new JwtKeys(signing.get(0), keys);
	}

	ECKey signingKey() {
		return signingKey;
	}

	JWKSet verificationKeys() {
		return verificationKeys;
	}

	// Parse failures are not chained: their messages may quote the key material.
	private static List<ECKey> parse(String jwkSet) {
		JWKSet set;
		try {
			set = JWKSet.parse(jwkSet);
		}
		catch (ParseException ex) {
			throw new IllegalStateException("pik.security.jwt.jwk-set is not a valid JWK set");
		}
		Assert.state(!set.getKeys().isEmpty(), "The JWT key set is empty");
		Set<String> keyIds = new HashSet<>();
		List<ECKey> keys = new ArrayList<>();
		for (JWK key : set.getKeys()) {
			Assert.state(key instanceof ECKey ec && Curve.P_256.equals(ec.getCurve()),
					"JWT keys must be EC P-256 keys (ES256)");
			Assert.state(StringUtils.hasText(key.getKeyID()) && keyIds.add(key.getKeyID()),
					"JWT keys need distinct key IDs (kid)");
			Assert.state(key.getKeyUse() == null || KeyUse.SIGNATURE.equals(key.getKeyUse()),
					"JWT keys must be signing keys (use = sig)");
			Assert.state(key.getAlgorithm() == null || JWSAlgorithm.ES256.equals(key.getAlgorithm()),
					"JWT keys must be ES256 keys (alg)");
			keys.add((ECKey) key);
		}
		return keys;
	}

	// A private part that does not match its public key would otherwise surface only when every token fails.
	private static void checkKeyPair(ECKey key) {
		try {
			JWSObject probe = new JWSObject(new JWSHeader(JWSAlgorithm.ES256), new Payload("key check"));
			probe.sign(new ECDSASigner(key));
			Assert.state(probe.verify(new ECDSAVerifier(key.toPublicJWK())),
					"The JWT signing key does not match its public key");
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("The JWT signing key cannot sign");
		}
	}

	private static ECKey generate() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not generate an ephemeral JWT key", ex);
		}
	}

}
