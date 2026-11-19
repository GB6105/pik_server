package io.github.gb6105.pik.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisProperties.class)
class AnalysisConfig {

	@Bean
	VectorValidator vectorValidator(AnalysisProperties properties) {
		return new VectorValidator(properties.grouping());
	}

}
