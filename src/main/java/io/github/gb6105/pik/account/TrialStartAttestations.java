package io.github.gb6105.pik.account;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * App Attest assertions for the request that starts the free trial of an iOS device (B6-2, ADR-0032 #6-2). Evidence
 * errors are {@link io.github.gb6105.pik.shared.error.PikException#unstored unstored}, so the same key can retry with
 * new evidence: {@code FORBIDDEN} / {@code ATTESTATION_REQUIRED} with a new {@code challenge} property for missing or
 * stale evidence, {@code CONFLICT} / {@code ATTESTATION_CONFLICT} with {@code Retry-After} for a counter race and
 * {@code FORBIDDEN} / {@code ATTESTATION_FAILED} otherwise. Runs outside database transactions.
 */
@Service
@RequiredArgsConstructor
public class TrialStartAttestations {

	private final AssertionVerifier verifier;

	/**
	 * Issues a challenge for the device to sign with its next assertion.
	 */
	public AttestationChallenge challenge(UUID principalId, UUID deviceId, AttestationBinding binding) {
		assertOutsideTransaction();
		return verifier.challenge(principalId, deviceId, binding);
	}

	/**
	 * Verifies an assertion over a challenge issued for the same binding. The proof holds for the current HTTP request
	 * only.
	 */
	public AttestationProof verify(UUID principalId, UUID deviceId, AttestationEvidence evidence,
			AttestationBinding binding) {
		assertOutsideTransaction();
		return verifier.verify(principalId, deviceId, evidence, binding);
	}

	private static void assertOutsideTransaction() {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"Assertions are checked outside database transactions");
	}

}
