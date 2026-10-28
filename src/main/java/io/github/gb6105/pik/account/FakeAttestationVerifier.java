package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;

/**
 * Stand-in until the platform checks are implemented: accepts {@link #VALID_TOKEN} only. Created in the local and
 * test profiles only ({@link AccountConfig}, ADR-0027).
 */
class FakeAttestationVerifier implements AttestationVerifier {

	static final String VALID_TOKEN = "fake-attestation";

	@Override
	public void verify(Platform platform, InstallRequest.Attestation attestation) {
		if (!VALID_TOKEN.equals(attestation.token())) {
			throw new PikException(ErrorCode.FORBIDDEN, "ATTESTATION_FAILED", false);
		}
	}

}
