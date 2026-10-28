package io.github.gb6105.pik.account;

/**
 * Checks that an install request comes from the genuine app (App Attest, Play Integrity; ADR-0020). Implementations
 * call platform services, so they run outside database transactions.
 */
interface AttestationVerifier {

	/**
	 * @throws io.github.gb6105.pik.shared.error.PikException {@code FORBIDDEN} / {@code ATTESTATION_FAILED} when the
	 * evidence does not hold
	 */
	void verify(Platform platform, InstallRequest.Attestation attestation);

}
