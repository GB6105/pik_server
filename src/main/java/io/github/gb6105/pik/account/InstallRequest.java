package io.github.gb6105.pik.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * @param attestation evidence from the platform attestation service, whose kind follows from {@code platform}
 */
record InstallRequest(@NotNull Platform platform, @Size(max = 32) @Nullable String appVersion,
		@NotNull @Valid Attestation attestation) {

	/**
	 * @param token App Attest attestation object or Play Integrity token
	 * @param keyId App Attest key ID; absent on Android
	 */
	record Attestation(@NotBlank @Size(max = 16384) String token, @Size(max = 256) @Nullable String keyId) {
	}

}
