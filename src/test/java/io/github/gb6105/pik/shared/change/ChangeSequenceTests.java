package io.github.gb6105.pik.shared.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeSequenceTests {

	@Autowired
	ChangeSequence changes;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EntityManager entityManager;

	@Test
	void numbersIncreasePerOwner() {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		List<Long> issued = transactions
			.execute((status) -> List.of(changes.next(owner), changes.next(owner), changes.next(other)));
		long afterCommit = transactions.execute((status) -> changes.next(owner));

		assertThat(issued).containsExactly(1L, 2L, 1L);
		assertThat(afterCommit).isEqualTo(3L);
	}

	@Test
	void laterCallerWaitsForEarlierCommit() throws Exception {
		UUID owner = UUID.randomUUID();
		CountDownLatch issued = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Long> first = CompletableFuture.supplyAsync(() -> transactions.execute((status) -> {
			long seq = changes.next(owner);
			issued.countDown();
			awaitLatch(release);
			return seq;
		}));
		assertThat(issued.await(5, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Long> second = CompletableFuture
			.supplyAsync(() -> transactions.execute((status) -> changes.next(owner)));

		await().atMost(Duration.ofSeconds(5)).until(() -> waitingLocks() > 0);
		assertThat(second).isNotDone();
		release.countDown();
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1L);
		assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2L);
	}

	@Test
	void blockIssuesConsecutiveNumbersAmongSingleOnes() {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		List<Long> issued = transactions.execute((status) -> List.of(changes.nextBlock(owner, 3), changes.next(owner),
				changes.nextBlock(owner, 2), changes.nextBlock(other, 4), changes.nextBlock(owner, 1)));
		long afterCommit = transactions.execute((status) -> changes.next(owner));

		// Blocks of 3 (1-3) and 2 (5-6) around 4, then 7; the other owner starts at 1.
		assertThat(issued).containsExactly(1L, 4L, 5L, 1L, 7L);
		assertThat(afterCommit).isEqualTo(8L);
		assertThat(jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", other)
			.query(Long.class)
			.single()).isEqualTo(4L);
	}

	@Test
	void blockHoldsAtLeastOneNumber() {
		UUID owner = UUID.randomUUID();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> transactions.executeWithoutResult((status) -> changes.nextBlock(owner, 0)));
		long first = transactions.execute((status) -> changes.next(owner));
		assertThat(first).isEqualTo(1L);
	}

	@Test
	void pendingJpaChangesAreFlushedBeforeTheNumber() {
		UUID principal = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO principal (id, kind, status, created_at, updated_at, version)
				VALUES (:id, 'ANONYMOUS', 'ACTIVE', now(), now(), 0)
				""").param("id", principal).update();

		List<Long> rows = transactions.execute((status) -> {
			entityManager.remove(entityManager.createQuery("SELECT p FROM Principal p WHERE p.id = :id")
				.setParameter("id", principal)
				.getSingleResult());
			long beforeNumber = principalRows(principal);
			changes.nextBlock(UUID.randomUUID(), 1);
			long afterNumber = principalRows(principal);
			status.setRollbackOnly();
			return List.of(beforeNumber, afterNumber);
		});

		// JDBC reads do not flush, so the pending removal reaches the database only through nextBlock.
		assertThat(rows).containsExactly(1L, 0L);
	}

	@Test
	void requiresCallerTransaction() {
		assertThatThrownBy(() -> changes.next(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> changes.nextBlock(UUID.randomUUID(), 2))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	private long principalRows(UUID id) {
		return jdbc.sql("SELECT count(*) FROM principal WHERE id = :id").param("id", id).query(Long.class).single();
	}

	private long waitingLocks() {
		return jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted").query(Long.class).single();
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
