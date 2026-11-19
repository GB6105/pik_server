package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.security.DevelopmentProfiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisProperties.class)
class AnalysisConfig {

	@Bean
	VectorValidator vectorValidator(AnalysisProperties properties) {
		return new VectorValidator(properties.grouping());
	}

	// An endpoint replaces AWS S3 with S3Mock, a fake; other profiles fail at startup (ADR-0027).
	@Bean
	InputStore inputStore(AnalysisProperties properties, Environment environment) {
		Assert.state(properties.upload().endpoint() == null || DevelopmentProfiles.active(environment),
				"pik.analysis.upload.endpoint is for S3Mock; only the local and test profiles may set it");
		return S3InputStore.create(properties.upload());
	}

	@Bean
	SendRateLimiter sendRateLimiter(AnalysisProperties properties) {
		return new SendRateLimiter(properties.sendRate().perInstancePerSecond());
	}

}
