package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.shared.security.DevelopmentProfiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsentProperties.class)
class ConsentConfig {

	// Read once at startup, so that a missing or incomplete notice fails the start instead of a request (ADR-0028).
	@Bean
	AiNotice aiNotice(ConsentProperties properties, JsonMapper jsonMapper, ResourceLoader resourceLoader,
			Environment environment) {
		return AiNotice.read(jsonMapper,
				resourceLoader.getResource("classpath:consent/ai-notice-v" + properties.noticeVersion() + ".json"),
				properties.noticeVersion(), DevelopmentProfiles.active(environment));
	}

}
