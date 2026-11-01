package io.github.gb6105.pik.account;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The device a request comes from, as named by its access token ({@code did}, ADR-0029).
 */
@Service
@RequiredArgsConstructor
public class RequestingDevices {

	private final AccountRecords records;

	/**
	 * @return the device, when it belongs to the principal; empty for any other device, so that the token's device ID
	 * alone never stands for ownership (B6-2.E3)
	 */
	@Transactional(readOnly = true)
	public Optional<RequestingDevice> find(UUID principalId, UUID deviceId) {
		return records.findDevice(principalId, deviceId)
			.map((device) -> new RequestingDevice(device.getId(), device.getInstallId(), device.getPlatform(),
					device.getTrialMarkId()));
	}

}
