package io.github.gb6105.pik.account;

import java.util.UUID;

/**
 * Checks App Attest assertions of the first trial start (B6-2). The verification with Apple's key material and the
 * challenge store come with D-21; until then only {@link FakeAssertionVerifier} exists.
 */
interface AssertionVerifier {

	AttestationChallenge challenge(UUID principalId, UUID deviceId, AttestationBinding binding);

	/**
	 * @throws io.github.gb6105.pik.shared.error.PikException unstored, as {@link TrialStartAttestations} describes
	 */
	AttestationProof verify(UUID principalId, UUID deviceId, AttestationEvidence evidence, AttestationBinding binding);

}
