package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.security.DevelopmentProfiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
class UsageConfig {

	@Bean
	DeviceKeys deviceKeys(UsageProperties properties, Environment environment) {
		return DeviceKeys.load(properties.deviceKeys(), DevelopmentProfiles.active(environment));
	}

	// Apple DeviceCheck comes before the first deployment; until then other profiles fail at startup (ADR-0029).
	@Bean
	DeviceCheck deviceCheck(Environment environment) {
		Assert.state(DevelopmentProfiles.active(environment),
				"DeviceCheck is not implemented; only the local and test profiles may start with the fake");
		return new FakeDeviceCheck();
	}

}
