package io.github.gb6105.pik.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DeviceIdentityTests {

	static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	@Test
	void keysAreHashedCurrentVersionFirst() {
		byte[] other = new byte[40];
		Arrays.fill(other, (byte) 1);
		DeviceKeys keys = DeviceKeys.load("1:" + KEY + ", 3:" + Base64.getEncoder().encodeToString(other), false);

		assertThat(keys.currentVersion()).isEqualTo(3);
		assertThat(keys.hashes("abc")).extracting(DeviceKeys.KeyedHash::version).containsExactly(3, 1);
		assertThat(keys.hashes("abc").get(0).hash()).hasSize(32).isNotEqualTo(keys.hashes("abc").get(1).hash());
		assertThat(keys.toString()).doesNotContain(KEY);
	}

	@Test
	void malformedKeysAreRefused() {
		String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load("1:" + shortKey, false));
		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load("1:" + KEY + ",1:" + KEY, false));
		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load("0:" + KEY, false));
		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load(KEY, false));
		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load("", false));
		assertThat(DeviceKeys.load(" ", true).versions()).containsExactly(1);
	}

	@Test
	void malformedKeyEntryDoesNotShowTheKey() {
		assertThatIllegalStateException().isThrownBy(() -> DeviceKeys.load(KEY + ":1", false))
			.withMessageNotContaining(KEY)
			.withNoCause();
	}

	@Test
	void androidIdIsNormalizedAndHidden() {
		TrialDevices.Evidence.Android evidence = new TrialDevices.Evidence.Android(" ABCDEF0123456789 ");

		assertThat(evidence.deviceId()).isEqualTo("abcdef0123456789");
		assertThat(evidence.toString()).doesNotContain("abcdef0123456789");
		assertThat(TrialDevices.Evidence.Android.isWellFormed("12345678901234567")).isFalse();
		assertThat(TrialDevices.Evidence.Android.isWellFormed("xyz")).isFalse();
		assertThatIllegalArgumentException().isThrownBy(() -> new TrialDevices.Evidence.Android("xyz"));
		assertThat(new TrialDevices.Evidence.Ios("secret-token").toString()).doesNotContain("secret-token");
	}

}
