package io.github.gb6105.pik.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Observations of photo source versions and their sync change numbers (FR-I-01, plan 8.2 I-7 photo part, 8.3 C-4).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@RecordApplicationEvents
class PhotoObservationsTests {

	@Autowired
	PhotoObservations observations;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationEvents events;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void changeNumbersFollowIdOrderNotInputOrClientRefOrder() {
		UUID owner = UUID.randomUUID();
		// The new photo's UUIDv7 ID lies between the lowest and highest IDs in PostgreSQL order (unsigned bytes).
		UUID lowId = new UUID(0x0000_0000_0000_7000L, UUID.randomUUID().getLeastSignificantBits());
		UUID highId = new UUID(0xFFFF_FFFF_FFFF_7FFFL, UUID.randomUUID().getLeastSignificantBits());
		UUID newRef = withPrefix("10000000");
		UUID lowRef = withPrefix("50000000");
		UUID highRef = withPrefix("90000000");
		insertPhoto(lowId, owner, lowRef);
		insertPhoto(highId, owner, highRef);

		// Input order high, new, low and client_ref order new, low, high both differ from id order low, new, high.
		ObservationBatch batch = observeAndIssue(owner,
				List.of(observation(highRef, "v2"), observation(newRef, "v1"), observation(lowRef, "v2")));

		UUID newId = batch.photos().get(1).photoId();
		assertThat(batch.photos()).extracting(ObservedPhoto::clientRef).containsExactly(highRef, newRef, lowRef);
		assertThat(batch.photos()).extracting(ObservedPhoto::sourceChangeSeq).containsExactly(1L, 0L, 1L);
		assertThat(batch.changedPhotoIds()).containsExactly(lowId, newId, highId);
		assertThat(jdbc.sql("SELECT change_seq FROM photo WHERE owner_id = :owner ORDER BY id")
			.param("owner", owner)
			.query(Long.class)
			.list()).containsExactly(1L, 2L, 3L);
		assertThat(counter(owner)).isEqualTo(3L);
		assertThat(placeholders(owner)).isZero();
		assertThat(rows(owner)).extracting(PhotoRow::version).containsOnly(0L);
		assertThat(events.stream(PhotoSourceChanged.class)).containsExactly(new PhotoSourceChanged(owner, lowId, 1),
				new PhotoSourceChanged(owner, highId, 1));
	}

	@Test
	void sameVersionAgainChangesNothing() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		observeAndIssue(owner, List.of(observation(ref, "v1")));
		PhotoRow before = row(owner, ref);

		ObservationBatch again = observeAndIssue(owner, List.of(observation(ref, "v1")));

		assertThat(again.changedPhotoIds()).isEmpty();
		assertThat(again.photos()).containsExactly(new ObservedPhoto(before.id(), ref, "v1", 0));
		assertThat(row(owner, ref)).isEqualTo(before);
		assertThat(counter(owner)).isEqualTo(1L);
		assertThat(events.stream(PhotoSourceChanged.class)).isEmpty();
	}

	@Test
	void unchangedObservationCommitsWithoutIssuing() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		observeAndIssue(owner, List.of(observation(ref, "v1")));

		ObservationBatch again = transactions
			.execute((status) -> observations.observe(owner, List.of(observation(ref, "v1"))));

		assertThat(again.changedPhotoIds()).isEmpty();
		assertThat(counter(owner)).isEqualTo(1L);
	}

	@Test
	void newVersionRaisesSourceChangeSeqAndIssuesNumber() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		observeAndIssue(owner, List.of(observation(ref, "v1")));
		PhotoRow before = row(owner, ref);

		ObservationBatch batch = observeAndIssue(owner, List.of(observation(ref, "v2")));

		PhotoRow after = row(owner, ref);
		assertThat(batch.photos()).containsExactly(new ObservedPhoto(before.id(), ref, "v2", 1));
		assertThat(batch.changedPhotoIds()).containsExactly(before.id());
		assertThat(after.observedVersion()).isEqualTo("v2");
		assertThat(after.sourceChangeSeq()).isEqualTo(1L);
		assertThat(after.changeSeq()).isGreaterThan(before.changeSeq()).isEqualTo(counter(owner));
		assertThat(after.version()).isZero();
		assertThat(events.stream(PhotoSourceChanged.class))
			.containsExactly(new PhotoSourceChanged(owner, before.id(), 1));
	}

	@Test
	void returningToEarlierVersionIsAnotherChange() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		observeAndIssue(owner, List.of(observation(ref, "A")));
		long created = row(owner, ref).changeSeq();
		observeAndIssue(owner, List.of(observation(ref, "B")));
		long toB = row(owner, ref).changeSeq();

		observeAndIssue(owner, List.of(observation(ref, "A")));

		PhotoRow after = row(owner, ref);
		assertThat(after.observedVersion()).isEqualTo("A");
		assertThat(after.sourceChangeSeq()).isEqualTo(2L);
		assertThat(List.of(created, toB, after.changeSeq())).containsExactly(1L, 2L, 3L);
		assertThat(after.version()).isZero();
		assertThat(events.stream(PhotoSourceChanged.class)).containsExactly(
				new PhotoSourceChanged(owner, after.id(), 1), new PhotoSourceChanged(owner, after.id(), 2));
	}

	@Test
	void rolledBackObservationLeavesRowsAndCounter() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		UUID added = UUID.randomUUID();
		observeAndIssue(owner, List.of(observation(ref, "v1")));
		PhotoRow before = row(owner, ref);

		transactions.executeWithoutResult((status) -> {
			observations.issueChangeSeqs(
					observations.observe(owner, List.of(observation(ref, "v2"), observation(added, "v1"))));
			status.setRollbackOnly();
		});

		assertThat(row(owner, ref)).isEqualTo(before);
		assertThat(rows(owner)).hasSize(1);
		assertThat(counter(owner)).isEqualTo(1L);
	}

	@Test
	void commitFailsWhenChangeNumbersAreNotIssued() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();

		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> observations.observe(owner, List.of(observation(ref, "v1")))));
		assertThat(rows(owner)).isEmpty();

		observeAndIssue(owner, List.of(observation(ref, "v1")));
		PhotoRow before = row(owner, ref);
		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> observations.observe(owner, List.of(observation(ref, "v2")))));
		assertThat(row(owner, ref)).isEqualTo(before);
		assertThat(counter(owner)).isEqualTo(1L);
	}

	@Test
	void changeNumbersAreIssuedOnceInTheObservingTransaction() {
		UUID owner = UUID.randomUUID();
		ObservationBatch committed = observeAndIssue(owner, List.of(observation(UUID.randomUUID(), "v1")));

		assertThatIllegalStateException()
			.isThrownBy(() -> transactions.executeWithoutResult((status) -> observations.issueChangeSeqs(committed)));
		assertThatIllegalStateException().isThrownBy(() -> transactions.executeWithoutResult((status) -> {
			ObservationBatch batch = observations.observe(owner, List.of(observation(UUID.randomUUID(), "v1")));
			observations.issueChangeSeqs(batch);
			observations.issueChangeSeqs(batch);
		}));
		assertThat(counter(owner)).isEqualTo(1L);
		assertThat(rows(owner)).hasSize(1);
	}

	@Test
	void transactionObservesOnce() {
		UUID owner = UUID.randomUUID();

		assertThatIllegalStateException().isThrownBy(() -> transactions.executeWithoutResult((status) -> {
			observations.observe(owner, List.of(observation(UUID.randomUUID(), "v1")));
			observations.observe(owner, List.of(observation(UUID.randomUUID(), "v1")));
		}));
		assertThat(rows(owner)).isEmpty();
		assertThat(observeAndIssue(owner, List.of(observation(UUID.randomUUID(), "v1"))).changedPhotoIds()).hasSize(1);
	}

	@Test
	void nestedTransactionMustNotUseTheOuterObservation() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		TransactionTemplate nested = new TransactionTemplate(transactionManager);
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// Without the check the nested transaction would wait for the outer one's row locks on the same thread.
		transactions.executeWithoutResult((status) -> {
			ObservationBatch batch = observations.observe(owner, List.of(observation(ref, "v1")));
			VectorToKeep vector = new VectorToKeep(batch.photos().get(0).photoId(), "model", "1", 1, new byte[4]);
			assertThatIllegalStateException().isThrownBy(() -> nested.executeWithoutResult(
					(inner) -> observations.observe(owner, List.of(observation(ref, "v2")))));
			assertThatIllegalStateException().isThrownBy(
					() -> nested.executeWithoutResult((inner) -> observations.keepVectors(owner, List.of(vector))));
			assertThatIllegalStateException()
				.isThrownBy(() -> nested.executeWithoutResult((inner) -> observations.issueChangeSeqs(batch)));
			observations.issueChangeSeqs(batch);
		});

		assertThat(row(owner, ref).observedVersion()).isEqualTo("v1");
		assertThat(row(owner, ref).changeSeq()).isEqualTo(1L);
	}

	@Test
	void ownersKeepSeparateRowsForSameClientRef() {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID ref = UUID.randomUUID();

		UUID ownerPhoto = observeAndIssue(owner, List.of(observation(ref, "v1"))).photos().get(0).photoId();
		UUID otherPhoto = observeAndIssue(other, List.of(observation(ref, "v2"))).photos().get(0).photoId();

		assertThat(ownerPhoto).isNotEqualTo(otherPhoto);
		assertThat(row(owner, ref).observedVersion()).isEqualTo("v1");
		assertThat(row(other, ref).observedVersion()).isEqualTo("v2");
		assertThat(row(owner, ref).sourceChangeSeq()).isZero();
	}

	@Test
	void repeatedClientRefIsRejected() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();

		assertThatIllegalArgumentException().isThrownBy(() -> transactions.executeWithoutResult(
				(status) -> observations.observe(owner, List.of(observation(ref, "v1"), observation(ref, "v2")))));
		assertThat(rows(owner)).isEmpty();
	}

	@Test
	void findIdsMapsTheOwnersClientRefs() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		UUID missing = UUID.randomUUID();
		UUID photoId = observeAndIssue(owner, List.of(observation(ref, "v1"))).photos().get(0).photoId();

		assertThat(observations.findIds(owner, List.of(ref, missing))).isEqualTo(Map.of(ref, photoId));
		assertThat(observations.findIds(UUID.randomUUID(), List.of(ref))).isEmpty();
		assertThat(observations.findIds(owner, List.of())).isEmpty();
	}

	@Test
	void requiresCallerTransaction() {
		UUID owner = UUID.randomUUID();
		ObservationBatch batch = observeAndIssue(owner, List.of(observation(UUID.randomUUID(), "v1")));
		VectorToKeep vector = new VectorToKeep(batch.photos().get(0).photoId(), "m", "1", 1, new byte[4]);

		assertThatThrownBy(() -> observations.observe(owner, List.of(observation(UUID.randomUUID(), "v1"))))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> observations.issueChangeSeqs(batch))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> observations.keepVectors(owner, List.of(vector)))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(rows(owner)).hasSize(1);
	}

	private ObservationBatch observeAndIssue(UUID owner, List<Observation> observed) {
		return transactions.execute((status) -> {
			ObservationBatch batch = observations.observe(owner, observed);
			observations.issueChangeSeqs(batch);
			return batch;
		});
	}

	private static Observation observation(UUID ref, String version) {
		return new Observation(ref, version);
	}

	private static UUID withPrefix(String firstEightHexDigits) {
		return UUID.fromString(firstEightHexDigits + UUID.randomUUID().toString().substring(8));
	}

	private void insertPhoto(UUID id, UUID owner, UUID ref) {
		jdbc.sql("""
				INSERT INTO photo (id, owner_id, client_ref, observed_version, source_change_seq, change_seq,
				                   created_at, updated_at)
				VALUES (:id, :owner, :ref, 'v1', 0, 1, now(), now())
				""").param("id", id).param("owner", owner).param("ref", ref).update();
	}

	private PhotoRow row(UUID owner, UUID ref) {
		return jdbc.sql("""
				SELECT id, observed_version, source_change_seq, change_seq, version
				FROM photo WHERE owner_id = :owner AND client_ref = :ref
				""").param("owner", owner).param("ref", ref).query(PhotoRow.class).single();
	}

	private List<PhotoRow> rows(UUID owner) {
		return jdbc.sql("""
				SELECT id, observed_version, source_change_seq, change_seq, version
				FROM photo WHERE owner_id = :owner
				""").param("owner", owner).query(PhotoRow.class).list();
	}

	private long placeholders(UUID owner) {
		return jdbc.sql("SELECT count(*) FROM photo WHERE owner_id = :owner AND change_seq = 0")
			.param("owner", owner)
			.query(Long.class)
			.single();
	}

	private long counter(UUID owner) {
		return jdbc.sql("SELECT coalesce(max(last_seq), 0) FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.single();
	}

	record PhotoRow(UUID id, String observedVersion, long sourceChangeSeq, long changeSeq, long version) {
	}

}
