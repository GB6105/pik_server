package io.github.gb6105.pik.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtKeysTests {

	@Test
	void privateKeySignsAndAllKeysVerifyWithoutPrivateParts() throws JOSEException {
		ECKey current = ec(Curve.P_256, "current");
		ECKey previous = ec(Curve.P_256, "previous");

		JwtKeys keys = JwtKeys.load(json(current, previous.toPublicJWK()), false);

		assertThat(keys.signingKey().getKeyID()).isEqualTo("current");
		assertThat(keys.signingKey().isPrivate()).isTrue();
		assertThat(keys.verificationKeys().getKeys()).extracting(JWK::getKeyID).containsExactly("current", "previous");
		assertThat(keys.verificationKeys().getKeys()).noneMatch(JWK::isPrivate);
	}

	@Test
	void emptySetGeneratesEphemeralKeyOnlyWhenAllowed() {
		JwtKeys keys = JwtKeys.load("", true);

		assertThat(keys.signingKey().isPrivate()).isTrue();
		assertThat(keys.verificationKeys().getKeys()).singleElement()
			.satisfies((key) -> assertThat(key.isPrivate()).isFalse());
		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(null, false))
			.withMessageContaining("jwk-set is required");
		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(" ", false));
	}

	@Test
	void setNeedsExactlyOnePrivateKey() throws JOSEException {
		ECKey first = ec(Curve.P_256, "first");
		ECKey second = ec(Curve.P_256, "second");

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(first, second), false))
			.withMessageContaining("exactly one private key");
		assertThatIllegalStateException()
			.isThrownBy(() -> JwtKeys.load(json(first.toPublicJWK(), second.toPublicJWK()), false));
		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load("{\"keys\": []}", false));
	}

	@Test
	void keysNeedDistinctKeyIds() throws JOSEException {
		ECKey unnamed = new ECKeyGenerator(Curve.P_256).generate();
		ECKey current = ec(Curve.P_256, "same");
		ECKey previous = ec(Curve.P_256, "same");

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(unnamed), false))
			.withMessageContaining("key IDs");
		assertThatIllegalStateException()
			.isThrownBy(() -> JwtKeys.load(json(current, previous.toPublicJWK()), false));
	}

	@Test
	void onlyEcP256KeysAreAccepted() throws JOSEException {
		JWK rsa = new RSAKeyGenerator(2048).keyID("rsa").generate();
		ECKey p384 = ec(Curve.P_384, "p384");

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(rsa), false))
			.withMessageContaining("P-256");
		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(p384), false));
	}

	@Test
	void keysMustBeForEs256Signatures() throws JOSEException {
		ECKey encryption = new ECKeyGenerator(Curve.P_256).keyID("enc").keyUse(KeyUse.ENCRYPTION).generate();
		ECKey es384 = new ECKeyGenerator(Curve.P_256).keyID("es384").algorithm(JWSAlgorithm.ES384).generate();
		ECKey declared = new ECKeyGenerator(Curve.P_256).keyID("sig")
			.keyUse(KeyUse.SIGNATURE)
			.algorithm(JWSAlgorithm.ES256)
			.generate();

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(encryption), false))
			.withMessageContaining("use = sig");
		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(es384), false))
			.withMessageContaining("alg");
		assertThat(JwtKeys.load(json(declared), false).signingKey().getKeyID()).isEqualTo("sig");
	}

	@Test
	void signingKeyMustMatchItsPublicKey() throws JOSEException {
		ECKey key = ec(Curve.P_256, "current");
		ECKey other = ec(Curve.P_256, "other");
		ECKey mismatched = new ECKey.Builder(key.toPublicJWK()).d(other.getD()).build();

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(json(mismatched), false))
			.withMessageContaining("does not match");
	}

	@Test
	void securityConfigUsesEphemeralKeyOnlyInDevelopmentProfiles() {
		JwtProperties properties = new JwtProperties("pik-server", Duration.ofMinutes(15), Duration.ofSeconds(60), "");

		assertThatIllegalStateException()
			.isThrownBy(() -> new SecurityConfig().jwtKeys(properties, new MockEnvironment()));
		assertThat(new SecurityConfig().jwtKeys(properties, withProfiles("test")).signingKey().isPrivate()).isTrue();
	}

	@Test
	void propertiesHideTheKeySet() {
		String secret = "{\"keys\": [\"secret-material\"]}";

		assertThat(new JwtProperties("pik-server", Duration.ofMinutes(15), Duration.ofSeconds(60), secret))
			.asString()
			.doesNotContain("secret-material");
	}

	@Test
	void unparsableSetFailsWithoutEchoingIt() {
		String secret = "{\"keys\": [{\"kty\": \"EC\", \"d\": \"secret-material\"";

		assertThatIllegalStateException().isThrownBy(() -> JwtKeys.load(secret, false))
			.withMessageNotContaining("secret-material")
			.withNoCause();
	}

	@Test
	void developmentProfilesAreLocalAndTestOnly() {
		assertThat(DevelopmentProfiles.active(new MockEnvironment())).isFalse();
		assertThat(DevelopmentProfiles.active(withProfiles("prod"))).isFalse();
		assertThat(DevelopmentProfiles.active(withProfiles("local"))).isTrue();
		assertThat(DevelopmentProfiles.active(withProfiles("prod", "test"))).isTrue();

		MockEnvironment defaultLocal = new MockEnvironment();
		defaultLocal.setDefaultProfiles("local");
		assertThat(DevelopmentProfiles.active(defaultLocal)).isFalse();
	}

	private static MockEnvironment withProfiles(String... profiles) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		return environment;
	}

	private static ECKey ec(Curve curve, String keyId) throws JOSEException {
		return new ECKeyGenerator(curve).keyID(keyId).generate();
	}

	private static String json(JWK... keys) {
		return new JWKSet(List.of(keys)).toString(false);
	}

}
