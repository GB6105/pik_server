package io.github.gb6105.pik.account;

/**
 * A first trial start assertion from the headers {@code App-Attest-Assertion}, {@code App-Attest-Challenge-Id} and
 * {@code App-Attest-Nonce} (B6-2.E3). Kept out of logs and the idempotency hash.
 *
 * @param nonce the nonce of the challenge, echoed as issued
 */
public record AttestationEvidence(String assertion, String challengeId, String nonce) {

	@Override
	public String toString() {
		return "AttestationEvidence[hidden]";
	}

}
