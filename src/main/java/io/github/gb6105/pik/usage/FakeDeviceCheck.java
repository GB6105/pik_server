package io.github.gb6105.pik.usage;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in until Apple DeviceCheck is called: a token {@code fake-device-check:<device>} names the device, and the bits
 * live in memory. A token {@code fake-device-check-unavailable:<device>} fails as if Apple could not be reached.
 * Created in the local and test profiles only ({@link UsageConfig}, ADR-0029).
 */
class FakeDeviceCheck implements DeviceCheck {

	static final String TOKEN_PREFIX = "fake-device-check:";

	static final String UNAVAILABLE_PREFIX = "fake-device-check-unavailable:";

	private final Set<String> started = ConcurrentHashMap.newKeySet();

	@Override
	public boolean trialStarted(String deviceToken) {
		return started.contains(device(deviceToken));
	}

	@Override
	public void recordTrialStarted(String deviceToken) {
		started.add(device(deviceToken));
	}

	private static String device(String deviceToken) {
		if (deviceToken.startsWith(UNAVAILABLE_PREFIX)) {
			throw new PikException(ErrorCode.TEMPORARY_FAILURE, "DEVICE_CHECK_UNAVAILABLE", true);
		}
		if (!deviceToken.startsWith(TOKEN_PREFIX) || deviceToken.length() == TOKEN_PREFIX.length()) {
			throw new PikException(ErrorCode.FORBIDDEN, "DEVICE_CHECK_FAILED", false);
		}
		return deviceToken.substring(TOKEN_PREFIX.length());
	}

}
