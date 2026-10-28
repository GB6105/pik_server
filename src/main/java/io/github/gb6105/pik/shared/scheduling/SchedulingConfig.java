package io.github.gb6105.pik.shared.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Batches run on every instance's scheduler; ShedLock lets only one instance run each job (ADR-0014).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfig {

	// Lock times come from the database clock so instances with skewed clocks agree.
	@Bean
	LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
		return new JdbcTemplateLockProvider(
				JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbcTemplate).usingDbTime().build());
	}

}
