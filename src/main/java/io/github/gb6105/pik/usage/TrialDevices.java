package io.github.gb6105.pik.usage;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Trial marks of devices, registered when an install is (ADR-0020, ADR-0029 #3). {@link #identify} reads what the
 * device shows and {@link #register} stores it in the install's transaction.
 */
@Service
@RequiredArgsConstructor
public class TrialDevices {

	private final DeviceKeys keys;

	private final DeviceCheck deviceCheck;

	private final UsageRecords records;

	private final UsageProperties properties;

	/**
	 * Hashes an Android device ID or asks DeviceCheck whether an iOS device started a trial before. The latter calls
	 * Apple, so this runs outside database transactions.
	 * @throws io.github.gb6105.pik.shared.error.PikException from {@link DeviceCheck#trialStarted}
	 */
	public IdentifiedDevice identify(Evidence evidence) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"identify may call Apple and must run outside database transactions");
		return switch (evidence) {
			case Evidence.Android android -> new IdentifiedDevice(keys.hashes(android.deviceId()), false);
			case Evidence.Ios ios -> new IdentifiedDevice(null, deviceCheck.trialStarted(ios.deviceCheckToken()));
		};
	}

	/**
	 * The device's trial mark: an Android device's existing one, or a new one.
	 * @return the mark ID, to keep with the device ({@code device.trial_mark_id})
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID register(IdentifiedDevice device) {
		int limit = properties.trial().limit();
		Instant now = Instant.now();
		if (device.hashes != null) {
			return records.androidMark(device.hashes, limit, now);
		}
		DeviceTrialMark mark = DeviceTrialMark.ios(device.startedBefore, limit, now);
		records.persist(mark);
		return mark.getId();
	}

	/**
	 * What identifies a device again after a reinstall. Neither value is stored or logged.
	 */
	public sealed interface Evidence {

		/**
		 * @param deviceId {@code Settings.Secure.ANDROID_ID}, the same for the app across reinstalls until a factory
		 * reset; stored in lower case without surrounding spaces
		 */
		record Android(String deviceId) implements Evidence {

			// A 64-bit number in hex.
			private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{1,16}");

			public Android {
				deviceId = normalize(deviceId);
				Assert.isTrue(FORMAT.matcher(deviceId).matches(), "deviceId must be a 64-bit hex number");
			}

			public static boolean isWellFormed(String deviceId) {
				return FORMAT.matcher(normalize(deviceId)).matches();
			}

			private static String normalize(String deviceId) {
				return deviceId.strip().toLowerCase(Locale.ROOT);
			}

			// The device ID must not reach logs.
			@Override
			public String toString() {
				return "Android[deviceId=(hidden)]";
			}

		}

		/**
		 * @param deviceCheckToken token from {@code DCDevice.generateToken}
		 */
		record Ios(String deviceCheckToken) implements Evidence {

			public Ios {
				Assert.hasText(deviceCheckToken, "deviceCheckToken must not be empty");
			}

			// The token identifies the device and must not reach logs.
			@Override
			public String toString() {
				return "Ios[deviceCheckToken=(hidden)]";
			}

		}

	}

	/**
	 * A device as {@link #identify} read it, for {@link #register}.
	 */
	public static final class IdentifiedDevice {

		private final @Nullable List<DeviceKeys.KeyedHash> hashes;

		private final boolean startedBefore;

		private IdentifiedDevice(@Nullable List<DeviceKeys.KeyedHash> hashes, boolean startedBefore) {
			this.hashes = hashes;
			this.startedBefore = startedBefore;
		}

	}

}
