package io.github.gb6105.pik.usage;

import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Fails startup while stored Android marks use a key version that is not configured: those devices would no longer be
 * found and would get the trial again (ADR-0029 #21). Runs before the web server accepts requests.
 */
@Component
@RequiredArgsConstructor
class DeviceKeyCheck implements SmartInitializingSingleton {

	private final UsageRecords records;

	private final DeviceKeys keys;

	@Override
	public void afterSingletonsInstantiated() {
		Set<Integer> missing = new TreeSet<>(records.androidKeyVersions());
		missing.removeAll(keys.versions());
		Assert.state(missing.isEmpty(), () -> "Android trial marks use device key versions " + missing
				+ " that pik.usage.device-keys does not configure (ADR-0029 #21)");
	}

}
