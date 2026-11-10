package io.github.gb6105.pik.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.consent.DecisionRequest.Choice;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The consent basis of checks and its lock against decisions (ADR-0030 #1).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AiConsentLockTests {

	@Autowired
	AiConsents consents;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	final UUID principal = UuidV7.generate();

	final UUID device = UuidV7.generate();

	@Test
	void basisIsTheVersionOfTheGrantInForce() {
		assertThat(basis()).isEmpty();
		decide(Choice.GRANTED, 0);
		assertThat(basis()).contains(new ConsentBasis(1));
		decide(Choice.GRANTED, 1);
		assertThat(basis()).contains(new ConsentBasis(2));
		decide(Choice.DENIED, 2);
		assertThat(basis()).isEmpty();
	}

	@Test
	void basisNeedsTheCallersTransaction() {
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> consents.lockGrantedBasis(principal));
	}

	@Test
	void decisionWaitsWhileACheckHoldsTheConsent() throws Exception {
		decide(Choice.GRANTED, 0);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Optional<ConsentBasis>> check = CompletableFuture
			.supplyAsync(() -> transactions.execute((status) -> {
				Optional<ConsentBasis> basis = consents.lockGrantedBasis(principal);
				locked.countDown();
				awaitLatch(release);
				return basis;
			}));
		assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<ConsentState> decision = CompletableFuture.supplyAsync(
				() -> transactions.execute((status) -> consents.decide(principal, device, request(Choice.DENIED, 1))));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingAdvisoryLocks() > 0);
		assertThat(decision).isNotDone();
		release.countDown();

		assertThat(check.get(5, TimeUnit.SECONDS)).contains(new ConsentBasis(1));
		assertThat(decision.get(5, TimeUnit.SECONDS).version()).isEqualTo(2);
		assertThat(basis()).isEmpty();
	}

	@Test
	void checkAfterADecisionSeesIt() throws Exception {
		decide(Choice.GRANTED, 0);
		CountDownLatch decided = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<ConsentState> decision = CompletableFuture
			.supplyAsync(() -> transactions.execute((status) -> {
				ConsentState state = consents.decide(principal, device, request(Choice.DENIED, 1));
				decided.countDown();
				awaitLatch(release);
				return state;
			}));
		assertThat(decided.await(5, TimeUnit.SECONDS)).isTrue();

		CompletableFuture<Optional<ConsentBasis>> check = CompletableFuture.supplyAsync(this::basis);
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingAdvisoryLocks() > 0);
		assertThat(check).isNotDone();
		release.countDown();

		assertThat(decision.get(5, TimeUnit.SECONDS).version()).isEqualTo(2);
		assertThat(check.get(5, TimeUnit.SECONDS)).isEmpty();
	}

	@Test
	void checksDoNotWaitForEachOther() throws Exception {
		decide(Choice.GRANTED, 0);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Optional<ConsentBasis>> first = CompletableFuture
			.supplyAsync(() -> transactions.execute((status) -> {
				Optional<ConsentBasis> basis = consents.lockGrantedBasis(principal);
				locked.countDown();
				awaitLatch(release);
				return basis;
			}));
		assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

		Optional<ConsentBasis> second = CompletableFuture.supplyAsync(this::basis).get(5, TimeUnit.SECONDS);
		release.countDown();

		assertThat(second).contains(new ConsentBasis(1));
		assertThat(first.get(5, TimeUnit.SECONDS)).contains(new ConsentBasis(1));
	}

	private Optional<ConsentBasis> basis() {
		return transactions.execute((status) -> consents.lockGrantedBasis(principal));
	}

	private void decide(Choice decision, long version) {
		transactions.executeWithoutResult((status) -> consents.decide(principal, device, request(decision, version)));
	}

	private static DecisionRequest request(Choice decision, long version) {
		return new DecisionRequest(decision, 1, version);
	}

	private long waitingAdvisoryLocks() {
		return jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
			.query(Long.class)
			.single();
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
