package io.github.gb6105.pik.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.usage.TrialUsage.DeviceSendCheck;
import io.github.gb6105.pik.usage.TrialUsage.Photo;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lifetime AI send budget of a trial device on its mark (design 2.7, plan 3.4).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DeviceSendTests {

	static final int MULTIPLIER = 2;

	@Autowired
	TrialUsage trialUsage;

	@Autowired
	TrialDevices trialDevices;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcClient jdbc;

	@Test
	void sendsAreCountedOnTheMark() {
		UUID mark = androidMark();
		int limit = limitCount(mark) * MULTIPLIER;

		assertThat(lock(mark)).isEqualTo(new DeviceSendCheck(0, limit));
		transaction.executeWithoutResult((status) -> {
			trialUsage.lockDeviceSend(mark, MULTIPLIER);
			trialUsage.reserveDeviceSend(mark);
			trialUsage.reserveDeviceSend(mark);
		});
		assertThat(lock(mark)).isEqualTo(new DeviceSendCheck(2, limit));
		assertThat(lock(mark).remaining()).isEqualTo(limit - 2);

		release(mark);

		assertThat(sendCount(mark)).isEqualTo(1);
		DeviceSendCheck tripled = transaction.execute((status) -> trialUsage.lockDeviceSend(mark, 3));
		assertThat(tripled).isEqualTo(new DeviceSendCheck(1, limitCount(mark) * 3));
	}

	@Test
	void releaseNeverGoesBelowZero() {
		UUID mark = androidMark();

		release(mark);
		reserve(mark);
		release(mark);
		release(mark);

		assertThat(sendCount(mark)).isZero();
	}

	@Test
	void exhaustionIsReadWithoutATransaction() {
		UUID mark = androidMark();
		int limit = limitCount(mark) * MULTIPLIER;
		jdbc.sql("UPDATE device_trial_mark SET send_count = :count WHERE id = :id")
			.param("count", limit - 1)
			.param("id", mark)
			.update();

		assertThat(trialUsage.deviceSendExhausted(mark, MULTIPLIER)).isFalse();
		reserve(mark);
		assertThat(trialUsage.deviceSendExhausted(mark, MULTIPLIER)).isTrue();
		assertThat(lock(mark)).satisfies((check) -> assertThat(check.remaining()).isZero());
		assertThat(trialUsage.deviceSendExhausted(mark, MULTIPLIER + 1)).isFalse();
	}

	@Test
	void lockHoldsTheMarkUntilTheTransactionEndsButNotTheQuickCheck() throws Exception {
		UUID mark = androidMark();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		CompletableFuture<DeviceSendCheck> holder = CompletableFuture
			.supplyAsync(() -> transaction.execute((status) -> {
				DeviceSendCheck check = trialUsage.lockDeviceSend(mark, MULTIPLIER);
				locked.countDown();
				awaitLatch(done);
				return check;
			}));
		assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(CompletableFuture.supplyAsync(() -> trialUsage.deviceSendExhausted(mark, MULTIPLIER))
			.get(5, TimeUnit.SECONDS)).isFalse();
		CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> reserve(mark));
		await().atMost(Duration.ofSeconds(5)).until(() -> waitingLocks() > 0);
		assertThat(waiting).isNotDone();
		done.countDown();

		assertThat(holder.get(5, TimeUnit.SECONDS).sent()).isZero();
		waiting.get(5, TimeUnit.SECONDS);
		assertThat(sendCount(mark)).isEqualTo(1);
	}

	@Test
	void sendsJoinTheCallersTransaction() {
		UUID mark = androidMark();

		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.lockDeviceSend(mark, MULTIPLIER));
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.reserveDeviceSend(mark));
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.releaseDeviceSend(mark));
	}

	@Test
	void sendsComeAfterTheUsageOfThePrincipalOnTheSameDevice() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID otherMark = androidMark();
		UUID job = UuidV7.generate();
		List<Photo> photos = List.of(new Photo(UuidV7.generate(), "v1"));

		transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, job, photos);
			trialUsage.consume(principal, job);
			trialUsage.lockDeviceSend(mark, MULTIPLIER);
			trialUsage.reserveDeviceSend(mark);
		});
		assertThat(sendCount(mark)).isEqualTo(1);
		// The quota would be locked after the mark.
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.lockDeviceSend(mark, MULTIPLIER);
			trialUsage.release(principal, job);
		})).withMessageContaining("before the device's sends");
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.lockDeviceSend(mark, MULTIPLIER);
			trialUsage.reserveDeviceSend(otherMark);
		}));
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), List.of(new Photo(UuidV7.generate(), "v1")));
			trialUsage.reserveDeviceSend(otherMark);
		}));
		assertThat(sendCount(otherMark)).isZero();
	}

	@Test
	void nestedTransactionMayNotChangeTheSendsAgain() {
		UUID mark = androidMark();
		TransactionTemplate nested = new TransactionTemplate(transaction.getTransactionManager());
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// Without the check the nested release would wait for the mark lock of the outer transaction.
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.lockDeviceSend(mark, MULTIPLIER);
			nested.executeWithoutResult((inner) -> trialUsage.releaseDeviceSend(mark));
		})).withMessageContaining("nested");
	}

	@Test
	void multiplierIsAtLeastOne() {
		UUID mark = androidMark();

		assertThatIllegalArgumentException().isThrownBy(() -> trialUsage.deviceSendExhausted(mark, 0));
	}

	private DeviceSendCheck lock(UUID mark) {
		return transaction.execute((status) -> trialUsage.lockDeviceSend(mark, MULTIPLIER));
	}

	private void reserve(UUID mark) {
		transaction.executeWithoutResult((status) -> trialUsage.reserveDeviceSend(mark));
	}

	private void release(UUID mark) {
		transaction.executeWithoutResult((status) -> trialUsage.releaseDeviceSend(mark));
	}

	private UUID androidMark() {
		byte[] id = new byte[8];
		ThreadLocalRandom.current().nextBytes(id);
		TrialDevices.IdentifiedDevice device = trialDevices
			.identify(new TrialDevices.Evidence.Android(HexFormat.of().formatHex(id)));
		return transaction.execute((status) -> trialDevices.register(device));
	}

	private int limitCount(UUID mark) {
		return jdbc.sql("SELECT limit_count FROM device_trial_mark WHERE id = :id")
			.param("id", mark)
			.query(Integer.class)
			.single();
	}

	private int sendCount(UUID mark) {
		return jdbc.sql("SELECT send_count FROM device_trial_mark WHERE id = :id")
			.param("id", mark)
			.query(Integer.class)
			.single();
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
