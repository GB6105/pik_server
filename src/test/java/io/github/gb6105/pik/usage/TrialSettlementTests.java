package io.github.gb6105.pik.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.usage.TrialUsage.Photo;
import io.github.gb6105.pik.usage.TrialUsage.Reservation;
import io.github.gb6105.pik.usage.TrialUsage.SettleResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settlement of a job's reservations photo by photo when its result is stored (design 2.4 step 6, plan 3.4, C-16).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TrialSettlementTests {

	@Autowired
	TrialUsage trialUsage;

	@Autowired
	TrialDevices trialDevices;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcClient jdbc;

	final UUID principal = UuidV7.generate();

	final UUID job = UuidV7.generate();

	@Test
	void photosWithAResultAreConsumedAndDeletedMembersReleased() {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 3);

		SettleResult result = settle(job, Set.of(photos.get(0), photos.get(1)), Set.of(photos.get(2)));

		assertThat(result).isEqualTo(new SettleResult(2, 1));
		assertThat(result.unchanged()).isFalse();
		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(mark(mark)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(statuses(principal)).isEqualTo(Map.of(photos.get(0), "CONSUMED", photos.get(1), "CONSUMED",
				photos.get(2), "RELEASED"));
		// A released member can be reserved again.
		Reservation again = transaction.execute((status) -> trialUsage.reserve(principal, mark, UuidV7.generate(),
				List.of(new Photo(photos.get(2), "v2"))));
		assertThat(again).isEqualTo(new Reservation.Reserved(1));
	}

	@Test
	void everyPhotoCanBeConsumedOrEveryPhotoReleased() {
		UUID mark = androidMark();
		List<UUID> consumed = reserve(mark, job, 2);
		UUID other = UuidV7.generate();
		List<UUID> released = reserve(mark, other, 3);

		assertThat(settle(job, Set.copyOf(consumed), Set.of())).isEqualTo(new SettleResult(2, 0));
		assertThat(settle(other, Set.of(), Set.copyOf(released))).isEqualTo(new SettleResult(0, 3));

		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(mark(mark)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(statuses(principal).values()).containsOnly("CONSUMED", "RELEASED").hasSize(5);
	}

	@Test
	void countersMoveAsWithTheWholeJobSettlements() {
		UUID mark = androidMark();
		UUID released = UuidV7.generate();
		List<UUID> consumedPhotos = reserve(mark, job, 4);
		List<UUID> releasedPhotos = reserve(mark, released, 2);
		UUID other = UuidV7.generate();
		UUID otherMark = androidMark();
		UUID otherJob = UuidV7.generate();
		UUID otherReleased = UuidV7.generate();
		reserve(other, otherMark, otherJob, 4);
		reserve(other, otherMark, otherReleased, 2);

		settle(job, Set.copyOf(consumedPhotos), Set.of());
		settle(released, Set.of(), Set.copyOf(releasedPhotos));
		transaction.executeWithoutResult((status) -> {
			trialUsage.consume(other, otherJob);
			trialUsage.release(other, otherReleased);
		});

		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 4);
		assertThat(counters(quota(principal))).isEqualTo(counters(quota(other)));
		assertThat(counters(mark(mark))).isEqualTo(counters(mark(otherMark)));
	}

	@Test
	void repeatedSettlementChangesNothing() {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 3);
		Set<UUID> consume = Set.of(photos.get(0));
		Set<UUID> release = Set.of(photos.get(1), photos.get(2));
		settle(job, consume, release);

		SettleResult again = settle(job, consume, release);

		assertThat(again).isEqualTo(new SettleResult(0, 0));
		assertThat(again.unchanged()).isTrue();
		Integer released = transaction.execute((status) -> trialUsage.release(principal, job));
		assertThat(released).isZero();
		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 1);
		assertThat(mark(mark)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 1);
	}

	@Test
	void settlementRetriedAfterARollbackAppliesOnce() {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 2);

		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.settle(principal, job, Set.of(photos.get(0)), Set.of(photos.get(1)));
			throw new IllegalStateException("completion failed after the settlement");
		}));
		assertThat(quota(principal)).containsEntry("reserved_count", 2).containsEntry("consumed_count", 0);

		assertThat(settle(job, Set.of(photos.get(0)), Set.of(photos.get(1)))).isEqualTo(new SettleResult(1, 1));
		assertThat(settle(job, Set.of(photos.get(0)), Set.of(photos.get(1)))).isEqualTo(new SettleResult(0, 0));
		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 1);
	}

	@Test
	void concurrentSettlementsOfAJobApplyOnce() throws Exception {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 3);
		Set<UUID> consume = Set.of(photos.get(0), photos.get(1));
		Set<UUID> release = Set.of(photos.get(2));

		List<SettleResult> results = parallel(6, () -> settle(job, consume, release));

		assertThat(results).containsOnlyOnce(new SettleResult(2, 1)).containsOnly(new SettleResult(2, 1),
				new SettleResult(0, 0));
		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(mark(mark)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
	}

	@Test
	void setsMustSplitExactlyThePhotosOfTheJob() {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 3);
		UUID stranger = UuidV7.generate();

		// Missing a photo.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> settle(job, Set.of(photos.get(0)), Set.of(photos.get(1))));
		// A photo the job did not reserve.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> settle(job, Set.copyOf(photos), Set.of(stranger)));
		// The same photo in both sets.
		assertThatIllegalArgumentException().isThrownBy(
				() -> settle(job, Set.copyOf(photos), Set.of(photos.get(0))));
		// A job without reservations.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> settle(UuidV7.generate(), Set.of(photos.get(0)), Set.of()));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> settleAs(UuidV7.generate(), job, Set.copyOf(photos), Set.of()));

		assertThat(settle(UuidV7.generate(), Set.of(), Set.of()).unchanged()).isTrue();
		assertThat(quota(principal)).containsEntry("reserved_count", 3).containsEntry("consumed_count", 0);
		assertThat(statuses(principal).values()).containsOnly("RESERVED");
	}

	@Test
	void finalStateIsNeverReversed() {
		UUID mark = androidMark();
		List<UUID> photos = reserve(mark, job, 3);
		settle(job, Set.of(photos.get(0), photos.get(1)), Set.of(photos.get(2)));
		UUID released = UuidV7.generate();
		List<UUID> releasedPhotos = reserve(mark, released, 2);
		transaction.executeWithoutResult((status) -> trialUsage.release(principal, released));

		assertThatIllegalStateException()
			.isThrownBy(() -> settle(job, Set.of(photos.get(0)), Set.of(photos.get(1), photos.get(2))))
			.withMessageContaining("CONSUMED");
		assertThatIllegalStateException()
			.isThrownBy(() -> settle(job, Set.copyOf(photos), Set.of()))
			.withMessageContaining("RELEASED");
		assertThatIllegalStateException().isThrownBy(() -> settle(released, Set.copyOf(releasedPhotos), Set.of()));

		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
		assertThat(mark(mark)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 2);
	}

	@Test
	void settlementJoinsTheCallersTransaction() {
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.settle(principal, job, Set.of(), Set.of()));
	}

	private List<UUID> reserve(UUID mark, UUID job, int count) {
		return reserve(principal, mark, job, count);
	}

	private List<UUID> reserve(UUID principal, UUID mark, UUID job, int count) {
		List<Photo> photos = IntStream.range(0, count).mapToObj((i) -> new Photo(UuidV7.generate(), "v1")).toList();
		Reservation reservation = transaction.execute((status) -> trialUsage.reserve(principal, mark, job, photos));
		assertThat(reservation).isEqualTo(new Reservation.Reserved(count));
		return photos.stream().map(Photo::id).toList();
	}

	private SettleResult settle(UUID job, Set<UUID> consume, Set<UUID> release) {
		return settleAs(principal, job, consume, release);
	}

	private SettleResult settleAs(UUID principal, UUID job, Set<UUID> consume, Set<UUID> release) {
		return transaction.execute((status) -> trialUsage.settle(principal, job, consume, release));
	}

	private UUID androidMark() {
		byte[] id = new byte[8];
		ThreadLocalRandom.current().nextBytes(id);
		TrialDevices.IdentifiedDevice device = trialDevices
			.identify(new TrialDevices.Evidence.Android(HexFormat.of().formatHex(id)));
		return transaction.execute((status) -> trialDevices.register(device));
	}

	private Map<String, Object> quota(UUID principal) {
		return jdbc.sql("SELECT * FROM usage_quota WHERE principal_id = :id").param("id", principal).query().singleRow();
	}

	private Map<String, Object> mark(UUID mark) {
		return jdbc.sql("SELECT * FROM device_trial_mark WHERE id = :id").param("id", mark).query().singleRow();
	}

	private Map<UUID, String> statuses(UUID principal) {
		return jdbc.sql("SELECT photo_id, status FROM usage_reservation WHERE principal_id = :id")
			.param("id", principal)
			.query((rs, row) -> Map.entry(rs.getObject("photo_id", UUID.class), rs.getString("status")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static List<@Nullable Object> counters(Map<String, Object> row) {
		return Arrays.asList(row.get("limit_count"), row.get("reserved_count"), row.get("consumed_count"),
				row.get("exhausted_at"));
	}

	private static <T> List<T> parallel(int count, Callable<T> task) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}

}
