package io.github.gb6105.pik.shared.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
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
	void requiresCallerTransaction() {
		assertThatThrownBy(() -> changes.next(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
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
