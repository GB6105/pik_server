package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.security.DevelopmentProfiles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

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

	// Only instances that run the worker call Claude, and they fail at startup without an API key. A base URL replaces
	// the Anthropic API with a fake server; other profiles fail at startup (ADR-0027).
	@Bean
	@ConditionalOnBooleanProperty("pik.analysis.worker.enabled")
	ClaudeAnalysisModel analysisModel(AnalysisProperties properties, JsonMapper jsonMapper, Environment environment) {
		Assert.state(properties.claude().baseUrl() == null || DevelopmentProfiles.active(environment),
				"pik.analysis.claude.base-url is for a fake server; only the local and test profiles may set it");
		return ClaudeAnalysisModel.create(properties.claude(), jsonMapper);
	}

	@Bean
	SendRateLimiter sendRateLimiter(AnalysisProperties properties) {
		return new SendRateLimiter(properties.sendRate().perInstancePerSecond());
	}

}
