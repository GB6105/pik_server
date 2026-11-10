package io.github.gb6105.pik.photo;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Observations insert in {@code client_ref} order and lock in {@code id} order whatever the input order, so that
 * concurrent observations of overlapping photos wait for each other instead of deadlocking (lock order 0.3).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PhotoLockOrderTests {

	// The lowest and highest UUIDs in PostgreSQL order (unsigned bytes).
	private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000000");

	private static final UUID HIGH = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");

	// Texts of the observer's insert and lock statements (PhotoRecords), to find them in pg_stat_activity.
	private static final String INSERT_STATEMENT = "ON CONFLICT (owner_id, client_ref) DO NOTHING";

	private static final String LOCK_STATEMENT = "ORDER BY id\nFOR UPDATE";

	@Autowired
	PhotoObservations observations;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	@Test
	void insertsMissingRowsInClientRefOrder() throws Exception {
		UUID owner = UUID.randomUUID();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// An uncommitted row for HIGH makes the observation wait when it reaches HIGH.
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult((status) -> {
			insertPhoto(owner, HIGH);
			held.countDown();
			awaitLatch(release);
			status.setRollbackOnly();
		}));
		assertThat(held.await(5, SECONDS)).isTrue();
		CompletableFuture<ObservationBatch> observing = CompletableFuture
			.supplyAsync(() -> observeAndIssue(owner, List.of(new Observation(HIGH, "v1"), new Observation(LOW, "v1"))));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitsInStatement(INSERT_STATEMENT));

		// LOW comes second in the input but is already inserted by the waiting observation.
		assertThat(waitsForLock(() -> insertPhoto(owner, LOW))).isTrue();
		release.countDown();
		holder.get(5, SECONDS);
		assertThat(observing.get(5, SECONDS).photos()).extracting(ObservedPhoto::clientRef).containsExactly(HIGH, LOW);
		assertThat(photoIdsInIdOrder(owner)).hasSize(2);
	}

	@Test
	void locksRowsInIdOrder() throws Exception {
		UUID owner = UUID.randomUUID();
		observeAndIssue(owner, List.of(new Observation(UUID.randomUUID(), "v1"), new Observation(UUID.randomUUID(), "v1")));
		List<ObservedPhoto> rows = photoIdsInIdOrder(owner);
		ObservedPhoto first = rows.get(0);
		ObservedPhoto last = rows.get(1);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult((status) -> {
			lockPhoto(last.photoId());
			held.countDown();
			awaitLatch(release);
		}));
		assertThat(held.await(5, SECONDS)).isTrue();
		CompletableFuture<ObservationBatch> observing = CompletableFuture.supplyAsync(() -> observeAndIssue(owner,
				List.of(new Observation(last.clientRef(), "v2"), new Observation(first.clientRef(), "v2"))));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitsInStatement(LOCK_STATEMENT));

		// The first row in id order is locked although it comes second in the input.
		assertThat(waitsForLock(() -> lockPhoto(first.photoId()))).isTrue();
		release.countDown();
		holder.get(5, SECONDS);
		ObservationBatch batch = observing.get(5, SECONDS);
		assertThat(batch.photos()).extracting(ObservedPhoto::clientRef)
			.containsExactly(last.clientRef(), first.clientRef());
		assertThat(batch.changedPhotoIds()).containsExactly(first.photoId(), last.photoId());
		assertThat(photoIdsInIdOrder(owner)).extracting(ObservedPhoto::sourceChangeSeq).containsExactly(1L, 1L);
	}

	@Test
	void otherOwnersRowForSameClientRefDoesNotBlock() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult((status) -> {
			insertPhoto(owner, ref);
			held.countDown();
			awaitLatch(release);
		}));
		assertThat(held.await(5, SECONDS)).isTrue();

		// The unique key is (owner_id, client_ref), so the uncommitted row of the owner is no conflict (C-4).
		assertThat(waitsForLock(() -> observations
			.issueChangeSeqs(observations.observe(other, List.of(new Observation(ref, "v1")))))).isFalse();
		release.countDown();
		holder.get(5, SECONDS);
		observeAndIssue(other, List.of(new Observation(ref, "v1")));
		assertThat(jdbc.sql("SELECT owner_id FROM photo WHERE client_ref = :ref")
			.param("ref", ref)
			.query(UUID.class)
			.list()).containsExactlyInAnyOrder(owner, other);
	}

	@Test
	void overlappingObservationsInOtherOrdersDoNotDeadlock() throws Exception {
		UUID owner = UUID.randomUUID();
		List<UUID> existing = Stream.generate(UUID::randomUUID).limit(6).toList();
		observeAndIssue(owner, existing.stream().map((ref) -> new Observation(ref, "v0")).toList());
		int rounds = 20;
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				List<UUID> refs = new ArrayList<>(existing);
				refs.addAll(Stream.generate(UUID::randomUUID).limit(4).toList());
				List<Observation> forward = observed(refs, "r" + round + "a");
				List<Observation> backward = observed(refs.reversed(), "r" + round + "b");
				CountDownLatch start = new CountDownLatch(1);
				Future<ObservationBatch> one = executor.submit(() -> {
					awaitLatch(start);
					return observeAndIssue(owner, forward);
				});
				Future<ObservationBatch> other = executor.submit(() -> {
					awaitLatch(start);
					return observeAndIssue(owner, backward);
				});
				start.countDown();
				// A deadlock fails one transaction with deadlock_detected.
				assertThat(one.get(10, SECONDS).changedPhotoIds()).hasSize(10);
				assertThat(other.get(10, SECONDS).changedPhotoIds()).hasSize(10);
			}
		}
		finally {
			executor.shutdownNow();
		}

		// Each round both observations change every photo: the later one sees the version of the earlier one.
		assertThat(jdbc.sql("""
				SELECT source_change_seq FROM photo WHERE owner_id = :owner AND client_ref IN (:refs)
				""").param("owner", owner).param("refs", existing).query(Long.class).list())
			.containsOnly(2L * rounds);
		assertThat(jdbc.sql("SELECT count(*) FROM photo WHERE owner_id = :owner AND change_seq = 0")
			.param("owner", owner)
			.query(Long.class)
			.single()).isZero();
		assertThat(jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.single()).isEqualTo(6L + rounds * 20L);
	}

	private ObservationBatch observeAndIssue(UUID owner, List<Observation> observed) {
		return transactions.execute((status) -> {
			ObservationBatch batch = observations.observe(owner, observed);
			observations.issueChangeSeqs(batch);
			return batch;
		});
	}

	private static List<Observation> observed(List<UUID> refs, String version) {
		return refs.stream().map((ref) -> new Observation(ref, version)).toList();
	}

	private void insertPhoto(UUID owner, UUID ref) {
		jdbc.sql("""
				INSERT INTO photo (id, owner_id, client_ref, observed_version, source_change_seq, change_seq,
				                   created_at, updated_at)
				VALUES (:id, :owner, :ref, 'v0', 0, 1, now(), now())
				""").param("id", UUID.randomUUID()).param("owner", owner).param("ref", ref).update();
	}

	private void lockPhoto(UUID photoId) {
		jdbc.sql("SELECT id FROM photo WHERE id = :id FOR UPDATE").param("id", photoId).query(UUID.class).single();
	}

	private List<ObservedPhoto> photoIdsInIdOrder(UUID owner) {
		return jdbc.sql("""
				SELECT id AS photo_id, client_ref, observed_version, source_change_seq
				FROM photo WHERE owner_id = :owner ORDER BY id
				""").param("owner", owner).query(ObservedPhoto.class).list();
	}

	/**
	 * Whether the statement has to wait for a lock held by another transaction; it is rolled back either way.
	 */
	private boolean waitsForLock(Runnable statement) {
		try {
			transactions.executeWithoutResult((status) -> {
				jdbc.sql("SELECT set_config('lock_timeout', '200ms', true)").query(String.class).single();
				statement.run();
				status.setRollbackOnly();
			});
			return false;
		}
		catch (DataAccessException ex) {
			// lock_not_available
			assertThat(ex.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
					(sql) -> assertThat(sql.getSQLState()).isEqualTo("55P03"));
			return true;
		}
	}

	/**
	 * Whether another session of this database waits for a lock in a statement that contains the text.
	 */
	private boolean waitsInStatement(String text) {
		return jdbc.sql("""
				SELECT count(*) > 0 FROM pg_stat_activity
				WHERE datname = current_database() AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'
				  AND position(:text IN query) > 0
				""").param("text", text).query(Boolean.class).single();
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
