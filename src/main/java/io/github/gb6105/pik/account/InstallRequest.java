package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.usage.TrialDevices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * @param attestation evidence from the platform attestation service, whose kind follows from {@code platform}
 * @param trialDevice what identifies the device after a reinstall, for the free trial (ADR-0029 #3)
 */
record InstallRequest(@NotNull Platform platform, @Size(max = 32) @Nullable String appVersion,
		@NotNull @Valid Attestation attestation, @NotNull @Valid TrialDevice trialDevice) {

	/**
	 * The trial evidence of the request's platform.
	 * @throws PikException {@code INVALID_INPUT} / {@code TRIAL_DEVICE_INVALID} unless the platform's value, and only
	 * it, is given
	 */
	TrialDevices.Evidence trialEvidence() {
		String androidId = trialDevice.androidId();
		String deviceCheckToken = trialDevice.deviceCheckToken();
		return switch (platform) {
			case ANDROID -> {
				if (!StringUtils.hasText(androidId) || deviceCheckToken != null) {
					throw trialDeviceInvalid();
				}
				yield new TrialDevices.Evidence.Android(androidId);
			}
			case IOS -> {
				if (!StringUtils.hasText(deviceCheckToken) || androidId != null) {
					throw trialDeviceInvalid();
				}
				yield new TrialDevices.Evidence.Ios(deviceCheckToken);
			}
		};
	}

	private static PikException trialDeviceInvalid() {
		return new PikException(ErrorCode.INVALID_INPUT, "TRIAL_DEVICE_INVALID", false);
	}

	/**
	 * @param token App Attest attestation object or Play Integrity token
	 * @param keyId App Attest key ID; absent on Android
	 */
	record Attestation(@NotBlank @Size(max = 16384) String token, @Size(max = 256) @Nullable String keyId) {
	}

	/**
	 * Exactly one value, the platform's. Neither is stored or logged.
	 * @param androidId {@code Settings.Secure.ANDROID_ID}; Android only
	 * @param deviceCheckToken a token from {@code DCDevice.generateToken}; iOS only
	 */
	record TrialDevice(@Size(max = 64) @Nullable String androidId,
			@Size(max = 16384) @Nullable String deviceCheckToken) {
	}

}
