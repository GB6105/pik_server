package io.github.gb6105.pik.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param lastSeenInterval minimum time between {@code last_seen_at} updates
 */
@ConfigurationProperties("pik.account")
record AccountProperties(Duration lastSeenInterval) {

	AccountProperties {
		Assert.isTrue(lastSeenInterval.isPositive(), "last-seen-interval must be positive");
	}

}
