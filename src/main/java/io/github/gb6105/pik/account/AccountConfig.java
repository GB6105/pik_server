package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.security.DevelopmentProfiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountProperties.class)
class AccountConfig {

	// Real attestation comes before the first deployment; until then other profiles fail at startup (ADR-0027).
	@Bean
	AttestationVerifier attestationVerifier(Environment environment) {
		Assert.state(DevelopmentProfiles.active(environment),
				"Device attestation is not implemented; only the local and test profiles may start with the fake");
		return new FakeAttestationVerifier();
	}

}
