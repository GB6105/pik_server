package io.github.gb6105.pik.usage;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * @param trial the free trial
 * @param deviceKeys HMAC keys for Android device IDs as comma-separated {@code <version>:<base64 key>} entries; may be
 * empty only in the local and test profiles, which then use an ephemeral key ({@link DeviceKeys})
 */
@ConfigurationProperties("pik.usage")
record UsageProperties(Trial trial, @Nullable String deviceKeys) {

	/**
	 * @param limit photos, representatives and members together, analyzed free once per principal and device (P01)
	 */
	record Trial(int limit) {

		Trial {
			Assert.isTrue(limit >= 1, "trial.limit must be at least 1");
		}

	}

	// The keys are secrets.
	@Override
	public String toString() {
		return "UsageProperties[trial=" + trial + ", deviceKeys=" + (deviceKeys != null ? "(hidden)" : "null") + "]";
	}

}
