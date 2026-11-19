package io.github.gb6105.pik.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The first trial start assertion port and its stand-in (B6-2, plan 6.4).
 */
class TrialStartAttestationTests {

	final TrialStartAttestations attestations = new TrialStartAttestations(new FakeAssertionVerifier());

	final UUID principal = UuidV7.generate();

	final UUID device = UuidV7.generate();

	final AttestationBinding binding = new AttestationBinding(UuidV7.generate(), "ab".repeat(32), "cd".repeat(32));

	@Test
	void challengesAreFreshRandomValuesForFiveMinutes() {
		Instant before = Instant.now();

		AttestationChallenge first = attestations.challenge(principal, device, binding);
		AttestationChallenge second = attestations.challenge(principal, device, binding);

		assertThat(first.challengeId()).isNotBlank().isNotEqualTo(second.challengeId());
		assertThat(first.nonce()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(second.nonce());
		assertThat(first.expiresAt()).isBetween(before.plus(Duration.ofMinutes(5)),
				Instant.now().plus(Duration.ofMinutes(5)));
		assertThat(first.keyId()).isNull();
	}

	@Test
	void validAssertionProvesTheDevice() {
		Instant before = Instant.now();

		AttestationProof proof = attestations.verify(principal, device, evidence(FakeAssertionVerifier.VALID), binding);

		assertThat(proof.deviceId()).isEqualTo(device);
		assertThat(proof.verifiedAt()).isBetween(before, Instant.now());
	}

	@Test
	void staleAssertionAsksAgainWithANewChallenge() {
		assertThatThrownBy(() -> attestations.verify(principal, device, evidence(FakeAssertionVerifier.STALE), binding))
			.isInstanceOfSatisfying(PikException.class, (ex) -> {
				assertUnstored(ex, ErrorCode.FORBIDDEN, "ATTESTATION_REQUIRED", true);
				assertThat(ex.getRetryAfter()).isNull();
				assertThat(ex.getProperties()).containsOnlyKeys("challenge");
				assertThat(ex.getProperties().get("challenge")).isInstanceOfSatisfying(AttestationChallenge.class,
						(challenge) -> {
							assertThat(challenge.challengeId()).isNotEqualTo("challenge");
							assertThat(challenge.expiresAt()).isAfter(Instant.now());
						});
			});
	}

	@Test
	void counterRaceIsAConflictToRetryLater() {
		assertThatThrownBy(() -> attestations.verify(principal, device, evidence(FakeAssertionVerifier.RACE), binding))
			.isInstanceOfSatisfying(PikException.class, (ex) -> {
				assertUnstored(ex, ErrorCode.CONFLICT, "ATTESTATION_CONFLICT", true);
				assertThat(ex.getRetryAfter()).isPositive();
				assertThat(ex.getProperties()).isEmpty();
			});
	}

	@Test
	void anyOtherAssertionFails() {
		for (String assertion : new String[] { "forged", "", "FAKE-ASSERTION", FakeAssertionVerifier.VALID + " " }) {
			assertThatThrownBy(() -> attestations.verify(principal, device, evidence(assertion), binding))
				.isInstanceOfSatisfying(PikException.class, (ex) -> {
					assertUnstored(ex, ErrorCode.FORBIDDEN, "ATTESTATION_FAILED", false);
					assertThat(ex.getRetryAfter()).isNull();
					assertThat(ex.getProperties()).isEmpty();
				});
		}
	}

	@Test
	void evidenceIsKeptOutOfLogs() {
		assertThat(evidence(FakeAssertionVerifier.VALID).toString()).doesNotContain(FakeAssertionVerifier.VALID,
				"challenge", "nonce");
	}

	@Test
	void assertionsAreCheckedOutsideTransactions() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			assertThatIllegalStateException().isThrownBy(() -> attestations.challenge(principal, device, binding));
			assertThatIllegalStateException().isThrownBy(
					() -> attestations.verify(principal, device, evidence(FakeAssertionVerifier.VALID), binding));
		}
		finally {
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

	@Test
	void fakeAssertionsAreRefusedOutsideDevelopmentProfiles() {
		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");
		MockEnvironment test = new MockEnvironment();
		test.setActiveProfiles("test");

		assertThatIllegalStateException().isThrownBy(() -> new AccountConfig().assertionVerifier(production));
		assertThatIllegalStateException().isThrownBy(() -> new AccountConfig().assertionVerifier(new MockEnvironment()));
		assertThat(new AccountConfig().assertionVerifier(test)).isInstanceOf(FakeAssertionVerifier.class);
	}

	private static AttestationEvidence evidence(String assertion) {
		return new AttestationEvidence(assertion, "challenge", "nonce");
	}

	private static void assertUnstored(PikException ex, ErrorCode code, String reason, boolean retryable) {
		assertThat(ex.getCode()).isEqualTo(code);
		assertThat(ex.getReason()).isEqualTo(reason);
		assertThat(ex.isRetryable()).isEqualTo(retryable);
		assertThat(ex.isStorable()).isFalse();
	}

}
