package io.github.gb6105.pik.shared.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param retention how long a record answers replays (ADR-0023: 7 days)
 * @param lockTimeout how long a request waits for another request with the same key before
 * {@code IDEMPOTENCY_IN_PROGRESS}
 * @param cleanupInitialDelay delay before the first expired record cleanup after startup
 * @param cleanupInterval delay between expired record cleanups
 * @param cleanupBatchSize rows deleted per cleanup statement
 */
@ConfigurationProperties("pik.idempotency")
record IdempotencyProperties(Duration retention, Duration lockTimeout, Duration cleanupInitialDelay,
		Duration cleanupInterval, int cleanupBatchSize) {

	IdempotencyProperties {
		Assert.isTrue(retention.isPositive(), "retention must be positive");
		// PostgreSQL reads a lock_timeout of 0 as no limit.
		Assert.isTrue(lockTimeout.toMillis() > 0, "lock-timeout must be at least 1ms");
		Assert.isTrue(!cleanupInitialDelay.isNegative(), "cleanup-initial-delay must not be negative");
		Assert.isTrue(cleanupInterval.isPositive(), "cleanup-interval must be positive");
		// The cleanup loop ends on a batch smaller than this size.
		Assert.isTrue(cleanupBatchSize > 0, "cleanup-batch-size must be positive");
	}

}
