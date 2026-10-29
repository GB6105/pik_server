package io.github.gb6105.pik.usage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * HMAC-SHA256 keys for Android device IDs, which are never stored in plain form (ADR-0020, ADR-0021 #8). The highest
 * version hashes new devices; older versions stay so that devices they hashed are still found. Removing a version
 * would forget those devices, so startup fails while stored marks use a version that is not configured
 * ({@link DeviceKeyCheck}).
 */
final class DeviceKeys {

	private static final String ALGORITHM = "HmacSHA256";

	private static final int MINIMUM_KEY_BYTES = 32;

	private final Map<Integer, SecretKeySpec> keys;

	private final int currentVersion;

	private DeviceKeys(Map<Integer, SecretKeySpec> keys) {
		this.keys = Map.copyOf(keys);
		this.currentVersion = keys.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
	}

	/**
	 * @param configured {@code <version>:<base64 key>} entries separated by commas, or empty for an ephemeral key
	 * @param ephemeralAllowed whether an empty value may fall back to an ephemeral key (local and test profiles)
	 */
	static DeviceKeys load(@Nullable String configured, boolean ephemeralAllowed) {
		if (!StringUtils.hasText(configured)) {
			Assert.state(ephemeralAllowed,
					"pik.usage.device-keys is required outside the local and test profiles (ADR-0029)");
			byte[] key = new byte[MINIMUM_KEY_BYTES];
			new SecureRandom().nextBytes(key);
			return new DeviceKeys(Map.of(1, new SecretKeySpec(key, ALGORITHM)));
		}
		Map<Integer, SecretKeySpec> keys = new TreeMap<>();
		for (String entry : configured.split(",")) {
			String[] parts = entry.trim().split(":", 2);
			Assert.state(parts.length == 2, "Each device key must be <version>:<base64 key>");
			int version;
			byte[] key;
			try {
				version = Integer.parseInt(parts[0].trim());
				key = Base64.getDecoder().decode(parts[1].trim());
			}
			catch (IllegalArgumentException ex) {
				// The cause's message may hold the key.
				throw new IllegalStateException("Each device key must be <version>:<base64 key>");
			}
			Assert.state(version >= 1, "Device key versions start at 1");
			Assert.state(key.length >= MINIMUM_KEY_BYTES, "Device keys must have at least 32 bytes");
			Assert.state(keys.put(version, new SecretKeySpec(key, ALGORITHM)) == null,
					"Device key versions must be unique");
		}
		return new DeviceKeys(keys);
	}

	int currentVersion() {
		return currentVersion;
	}

	Set<Integer> versions() {
		return keys.keySet();
	}

	/**
	 * The device ID hashed with every key, current version first.
	 */
	List<KeyedHash> hashes(String deviceId) {
		return keys.keySet()
			.stream()
			.sorted(Comparator.reverseOrder())
			.map((version) -> new KeyedHash(version, hash(version, deviceId)))
			.toList();
	}

	private byte[] hash(int version, String deviceId) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(keys.get(version));
			return mac.doFinal(deviceId.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
		}
	}

	record KeyedHash(int version, byte[] hash) {
	}

	// The keys are secrets.
	@Override
	public String toString() {
		return "DeviceKeys[versions=" + keys.keySet() + ", current=" + currentVersion + "]";
	}

}
