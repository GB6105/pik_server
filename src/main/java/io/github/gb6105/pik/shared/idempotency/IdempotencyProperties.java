package io.github.gb6105.pik.shared.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retention how long a record answers replays (ADR-0023: 7 days)
 * @param lockTimeout how long a request waits for another request with the same key before
 * {@code IDEMPOTENCY_IN_PROGRESS}
 * @param cleanupInterval delay between expired record cleanups
 * @param cleanupBatchSize rows deleted per cleanup statement
 */
@ConfigurationProperties("pik.idempotency")
record IdempotencyProperties(Duration retention, Duration lockTimeout, Duration cleanupInterval,
		int cleanupBatchSize) {

}
