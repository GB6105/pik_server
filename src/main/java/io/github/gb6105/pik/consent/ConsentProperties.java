package io.github.gb6105.pik.consent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param noticeVersion the notice shown now ({@code classpath:consent/ai-notice-v<N>.json}); new consents must be given
 * on it
 * @param minimumNoticeVersion the oldest notice whose consents still count. Raised only when the transfers widen, so
 * that wording changes keep existing consents (ADR-0028 #4)
 */
@ConfigurationProperties("pik.consent.ai")
record ConsentProperties(int noticeVersion, int minimumNoticeVersion) {

	ConsentProperties {
		Assert.isTrue(minimumNoticeVersion >= 1, "minimum-notice-version must be at least 1");
		Assert.isTrue(minimumNoticeVersion <= noticeVersion, "minimum-notice-version must not exceed notice-version");
	}

}
