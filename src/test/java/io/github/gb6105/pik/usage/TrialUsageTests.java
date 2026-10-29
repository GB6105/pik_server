package io.github.gb6105.pik.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.usage.TrialUsage.Photo;
import io.github.gb6105.pik.usage.TrialUsage.Reservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "pik.usage.trial.limit=5")
@AutoConfigureMockMvc
class TrialUsageTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	TrialUsage trialUsage;

	@Autowired
	TrialDevices trialDevices;

	@Autowired
	UsageRecords records;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvcTester mvc;

	@MockitoSpyBean
	DeviceCheck deviceCheck;

	@Test
	void reservationCountsOnThePrincipalAndTheDevice() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID job = UuidV7.generate();
		List<Photo> photos = photos(3);

		assertThat(reserve(principal, mark, job, photos)).isEqualTo(new Reservation.Reserved(3));

		assertThat(quota(principal)).containsEntry("limit_count", 5)
			.containsEntry("reserved_count", 3)
			.containsEntry("consumed_count", 0);
		assertThat(mark(mark)).containsEntry("reserved_count", 3).containsEntry("consumed_count", 0);
		assertThat(reservations(principal)).hasSize(3)
			.allSatisfy((row) -> assertThat(row).containsEntry("status", "RESERVED")
				.containsEntry("job_id", job)
				.containsEntry("trial_mark_id", mark)
				.containsEntry("quota_kind", "TRIAL")
				.containsEntry("period_key", "ONCE"));
	}

	@Test
	void resultConsumesTheReservationsOnce() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID job = UuidV7.generate();
		reserve(principal, mark, job, photos(3));

		consume(principal, job);
		consume(principal, job);
		release(principal, job);

		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 3);
		assertThat(mark(mark)).containsEntry("reserved_count", 0)
			.containsEntry("consumed_count", 3)
			.containsEntry("exhausted_at", null);
		assertThat(reservations(principal)).extracting((row) -> row.get("status")).containsOnly("CONSUMED");
	}

	@Test
	void failedJobReturnsItsReservationsAndThePhotosCanBeReservedAgain() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID failed = UuidV7.generate();
		List<Photo> photos = photos(3);
		reserve(principal, mark, failed, photos);

		release(principal, failed);
		release(principal, failed);

		assertThat(quota(principal)).containsEntry("reserved_count", 0).containsEntry("consumed_count", 0);
		assertThat(mark(mark)).containsEntry("reserved_count", 0);
		assertThat(reserve(principal, mark, UuidV7.generate(), photos)).isEqualTo(new Reservation.Reserved(3));
		assertThat(reservations(principal)).extracting((row) -> row.get("status"))
			.containsExactlyInAnyOrder("RELEASED", "RELEASED", "RELEASED", "RESERVED", "RESERVED", "RESERVED");
	}

	@Test
	void bundleLargerThanWhatIsLeftIsNotReserved() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		reserve(principal, mark, UuidV7.generate(), photos(3));

		assertThat(reserve(principal, mark, UuidV7.generate(), photos(3))).isEqualTo(new Reservation.NotCovered());
		assertThat(quota(principal)).containsEntry("reserved_count", 3);
		assertThat(mark(mark)).containsEntry("reserved_count", 3);
		assertThat(reservations(principal)).hasSize(3);

		assertThat(reserve(principal, mark, UuidV7.generate(), photos(2))).isEqualTo(new Reservation.Reserved(2));
	}

	@Test
	void exhaustedDeviceGivesNoTrialToAnotherPrincipal() {
		UUID first = UuidV7.generate();
		UUID mark = androidMark();
		UUID job = UuidV7.generate();
		reserve(first, mark, job, photos(5));
		consume(first, job);

		assertThat(mark(mark).get("exhausted_at")).isNotNull();
		assertThat(reserve(UuidV7.generate(), mark, UuidV7.generate(), photos(1)))
			.isEqualTo(new Reservation.NotCovered());
	}

	@Test
	void exhaustedPrincipalGetsNoTrialOnAnotherDevice() {
		UUID principal = UuidV7.generate();
		UUID job = UuidV7.generate();
		reserve(principal, androidMark(), job, photos(5));
		consume(principal, job);

		assertThat(reserve(principal, androidMark(), UuidV7.generate(), photos(1)))
			.isEqualTo(new Reservation.NotCovered());
	}

	@Test
	void photoWithAnUnreleasedReservationIsNotReservedAgainWhateverItsVersion() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID first = UuidV7.generate();
		Photo photo = new Photo(UuidV7.generate(), "v1");
		reserve(principal, mark, first, List.of(photo));

		Reservation again = reserve(principal, mark, UuidV7.generate(),
				List.of(new Photo(photo.id(), "v2"), new Photo(UuidV7.generate(), "v1")));

		assertThat(again).isEqualTo(new Reservation.AlreadyReserved(Map.of(photo.id(), first)));
		assertThat(quota(principal)).containsEntry("reserved_count", 1);
		consume(principal, first);
		assertThat(reserve(principal, mark, UuidV7.generate(), List.of(new Photo(photo.id(), "v2"))))
			.isEqualTo(new Reservation.AlreadyReserved(Map.of(photo.id(), first)));
	}

	@Test
	void concurrentReservationsOfAPrincipalStayWithinTheLimit() throws Exception {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();

		List<Reservation> results = parallel(8, () -> reserve(principal, mark, UuidV7.generate(), photos(1)));

		assertThat(results).filteredOn(Reservation.Reserved.class::isInstance).hasSize(5);
		assertThat(results).filteredOn(Reservation.NotCovered.class::isInstance).hasSize(3);
		assertThat(quota(principal)).containsEntry("reserved_count", 5);
		assertThat(mark(mark)).containsEntry("reserved_count", 5);
	}

	@Test
	void concurrentReservationsOnADeviceStayWithinItsLimit() throws Exception {
		UUID mark = androidMark();

		List<Reservation> results = parallel(8,
				() -> reserve(UuidV7.generate(), mark, UuidV7.generate(), photos(1)));

		assertThat(results).filteredOn(Reservation.Reserved.class::isInstance).hasSize(5);
		assertThat(mark(mark)).containsEntry("reserved_count", 5);
	}

	@Test
	void reservationsJoinTheCallersTransaction() {
		assertThatExceptionOfType(IllegalTransactionStateException.class).isThrownBy(
				() -> trialUsage.reserve(UuidV7.generate(), androidMark(), UuidV7.generate(), photos(1)));
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.consume(UuidV7.generate(), UuidV7.generate()));
		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> trialUsage.release(UuidV7.generate(), UuidV7.generate()));
	}

	@Test
	void reinstalledAndroidDeviceKeepsItsMarkAndWhatItUsed() {
		String androidId = androidId();
		UUID first = installAndroid(androidId);
		UUID principal = UuidV7.generate();
		UUID job = UuidV7.generate();
		reserve(principal, first, job, photos(2));
		consume(principal, job);

		UUID reinstalled = installAndroid(androidId);

		assertThat(reinstalled).isEqualTo(first);
		assertThat(mark(first)).containsEntry("platform", "ANDROID")
			.containsEntry("key_version", 1)
			.containsEntry("consumed_count", 2);
		assertThat((byte[]) mark(first).get("device_key_hmac")).hasSize(32)
			.isNotEqualTo(androidId.getBytes());
		assertThat(reserve(UuidV7.generate(), reinstalled, UuidV7.generate(), photos(4)))
			.isEqualTo(new Reservation.NotCovered());
	}

	@Test
	void concurrentInstallsOfAnAndroidDeviceShareOneMark() throws Exception {
		String androidId = androidId();

		List<UUID> marks = parallel(8, () -> installAndroid(androidId));

		assertThat(marks).hasSize(8).containsOnly(marks.get(0));
	}

	@Test
	void olderKeyVersionStillFindsTheDeviceAfterRotation() {
		String androidId = androidId();
		String first = key();
		String second = key();

		UUID mark = androidMark(keys("1:" + first), androidId);

		assertThat(androidMark(keys("1:" + first + ", 2:" + second), androidId)).isEqualTo(mark);
		assertThat(mark(mark)).containsEntry("key_version", 1);
		assertThat(androidMark(keys("2:" + second), androidId)).isNotEqualTo(mark);
	}

	@Test
	void iosDeviceRecordsTheStartBeforeItsFirstReservation() {
		String token = deviceCheckToken();
		UUID mark = installIos(token);
		UUID principal = UuidV7.generate();
		assertThat(mark(mark)).containsEntry("device_check_state", null).containsEntry("exhausted_at", null);

		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.StartRequired());
		assertRejected(() -> trialUsage.recordStart(principal, mark, 1, null), ErrorCode.INVALID_INPUT,
				"DEVICE_CHECK_TOKEN_REQUIRED");

		trialUsage.recordStart(principal, mark, 1, token);

		assertThat(mark(mark)).containsEntry("device_check_state", "TRIAL_STARTED");
		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.Reserved(1));
		trialUsage.recordStart(principal, mark, 1, null);
		verify(deviceCheck).recordTrialStarted(token);
	}

	@Test
	void iosReinstallAfterTheStartGetsNoTrial() {
		String token = deviceCheckToken();
		UUID first = installIos(token);
		trialUsage.recordStart(UuidV7.generate(), first, 1, token);

		UUID reinstalled = installIos(token);

		assertThat(reinstalled).isNotEqualTo(first);
		assertThat(mark(reinstalled)).containsEntry("device_check_state", "TRIAL_STARTED");
		assertThat(mark(reinstalled).get("exhausted_at")).isNotNull();
		trialUsage.recordStart(UuidV7.generate(), reinstalled, 1, null);
		assertThat(reserve(UuidV7.generate(), reinstalled, UuidV7.generate(), photos(1)))
			.isEqualTo(new Reservation.NotCovered());
	}

	@Test
	void iosReinstallBeforeTheStartKeepsTheTrial() {
		String token = deviceCheckToken();
		installIos(token);

		UUID reinstalled = installIos(token);

		assertThat(mark(reinstalled)).containsEntry("device_check_state", null).containsEntry("exhausted_at", null);
	}

	@Test
	void startIsNotRecordedWithoutTrialLeftForThePrincipal() {
		UUID principal = UuidV7.generate();
		UUID job = UuidV7.generate();
		reserve(principal, androidMark(), job, photos(5));
		consume(principal, job);
		UUID mark = installIos(deviceCheckToken());

		trialUsage.recordStart(principal, mark, 1, null);

		assertThat(mark(mark)).containsEntry("device_check_state", null);
		verify(deviceCheck, never()).recordTrialStarted(any());
	}

	@Test
	void installsRegisteredBeforeTheStartShareOneTrial() {
		String token = deviceCheckToken();
		UUID first = installIos(token);
		UUID second = installIos(token);

		trialUsage.recordStart(UuidV7.generate(), first, 1, token);
		trialUsage.recordStart(UuidV7.generate(), second, 1, token);

		assertThat(mark(first)).containsEntry("device_check_state", "TRIAL_STARTED").containsEntry("exhausted_at", null);
		assertThat(mark(second)).containsEntry("device_check_state", "TRIAL_STARTED");
		assertThat(mark(second).get("exhausted_at")).isNotNull();
		assertThat(reserve(UuidV7.generate(), second, UuidV7.generate(), photos(1)))
			.isEqualTo(new Reservation.NotCovered());
		verify(deviceCheck).recordTrialStarted(token);
	}

	@Test
	void unconfirmedWriteIsRetriedWithoutReadingTheBits() {
		String token = deviceCheckToken();
		UUID mark = installIos(token);
		UUID principal = UuidV7.generate();
		PikException unavailable = new PikException(ErrorCode.TEMPORARY_FAILURE, "DEVICE_CHECK_UNAVAILABLE", true);
		// Apple wrote the bits but the answer was lost.
		doAnswer((invocation) -> {
			invocation.callRealMethod();
			throw unavailable;
		}).doCallRealMethod().when(deviceCheck).recordTrialStarted(token);

		assertThatThrownBy(() -> trialUsage.recordStart(principal, mark, 1, token)).isSameAs(unavailable);
		assertThat(mark(mark)).containsEntry("device_check_state", "STARTING");
		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.StartRequired());
		clearInvocations(deviceCheck);

		trialUsage.recordStart(principal, mark, 1, token);

		assertThat(mark(mark)).containsEntry("device_check_state", "TRIAL_STARTED").containsEntry("exhausted_at", null);
		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.Reserved(1));
		verify(deviceCheck, never()).trialStarted(any());
	}

	@Test
	void refusedTokenLeavesTheMarkUnstarted() {
		UUID mark = installIos(deviceCheckToken());
		String refused = deviceCheckToken();
		PikException failed = new PikException(ErrorCode.FORBIDDEN, "DEVICE_CHECK_FAILED", false);
		doThrow(failed).when(deviceCheck).recordTrialStarted(refused);

		assertThatThrownBy(() -> trialUsage.recordStart(UuidV7.generate(), mark, 1, refused)).isSameAs(failed);
		assertThat(mark(mark)).containsEntry("device_check_state", null);
	}

	@Test
	void refusedRetryKeepsTheMarkStarting() {
		String token = deviceCheckToken();
		UUID mark = installIos(token);
		UUID principal = UuidV7.generate();
		PikException unavailable = new PikException(ErrorCode.TEMPORARY_FAILURE, "DEVICE_CHECK_UNAVAILABLE", true);
		PikException refused = new PikException(ErrorCode.FORBIDDEN, "DEVICE_CHECK_FAILED", false);
		// Apple wrote the bits but the answer was lost, then refused the token of the retry.
		doAnswer((invocation) -> {
			invocation.callRealMethod();
			throw unavailable;
		}).doThrow(refused).doCallRealMethod().when(deviceCheck).recordTrialStarted(token);

		assertThatThrownBy(() -> trialUsage.recordStart(principal, mark, 1, token)).isSameAs(unavailable);
		assertThatThrownBy(() -> trialUsage.recordStart(principal, mark, 1, token)).isSameAs(refused);
		assertThat(mark(mark)).containsEntry("device_check_state", "STARTING");

		trialUsage.recordStart(principal, mark, 1, token);

		assertThat(mark(mark)).containsEntry("device_check_state", "TRIAL_STARTED").containsEntry("exhausted_at", null);
		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.Reserved(1));
	}

	@Test
	void startingMarkIsResumedOnlyWithATokenAndTrialLeft() {
		String token = deviceCheckToken();
		UUID mark = installIos(token);
		PikException unavailable = new PikException(ErrorCode.TEMPORARY_FAILURE, "DEVICE_CHECK_UNAVAILABLE", true);
		doThrow(unavailable).when(deviceCheck).recordTrialStarted(token);
		assertThatThrownBy(() -> trialUsage.recordStart(UuidV7.generate(), mark, 1, token)).isSameAs(unavailable);
		UUID exhausted = UuidV7.generate();
		UUID job = UuidV7.generate();
		reserve(exhausted, androidMark(), job, photos(5));
		consume(exhausted, job);
		clearInvocations(deviceCheck);

		trialUsage.recordStart(exhausted, mark, 1, null);
		trialUsage.recordStart(UuidV7.generate(), mark, 6, null);
		assertRejected(() -> trialUsage.recordStart(UuidV7.generate(), mark, 1, null), ErrorCode.INVALID_INPUT,
				"DEVICE_CHECK_TOKEN_REQUIRED");

		assertThat(mark(mark)).containsEntry("device_check_state", "STARTING");
		verify(deviceCheck, never()).recordTrialStarted(any());
	}

	@Test
	void startNeedsTheLimitsOfTheMarkAndOfANewQuota() {
		UUID smaller = installIos(deviceCheckToken());
		UUID larger = installIos(deviceCheckToken());
		jdbc.sql("UPDATE device_trial_mark SET limit_count = 2 WHERE id = :id").param("id", smaller).update();
		jdbc.sql("UPDATE device_trial_mark SET limit_count = 10 WHERE id = :id").param("id", larger).update();

		trialUsage.recordStart(UuidV7.generate(), smaller, 3, null);
		trialUsage.recordStart(UuidV7.generate(), larger, 6, null);

		assertThat(mark(smaller)).containsEntry("device_check_state", null);
		assertThat(mark(larger)).containsEntry("device_check_state", null);
		verify(deviceCheck, never()).recordTrialStarted(any());
	}

	@Test
	void jobTheTrialCannotCoverIsNotCoveredBeforeTheStart() {
		UUID mark = installIos(deviceCheckToken());

		assertThat(reserve(UuidV7.generate(), mark, UuidV7.generate(), photos(6)))
			.isEqualTo(new Reservation.NotCovered());
	}

	@Test
	void startIsNotRecordedWhenTheTrialCannotCoverTheJob() {
		UUID principal = UuidV7.generate();
		reserve(principal, androidMark(), UuidV7.generate(), photos(3));
		UUID mark = installIos(deviceCheckToken());

		trialUsage.recordStart(principal, mark, 3, null);
		trialUsage.recordStart(UuidV7.generate(), mark, 6, null);

		assertThat(mark(mark)).containsEntry("device_check_state", null);
		verify(deviceCheck, never()).recordTrialStarted(any());
	}

	@Test
	void oneTransactionChangesOnePrincipalOnOneDevice() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID otherMark = androidMark();
		UUID job = UuidV7.generate();

		Integer consumed = transaction.execute((status) -> {
			trialUsage.reserve(principal, mark, job, photos(1));
			return trialUsage.consume(principal, job);
		});
		assertThat(consumed).isEqualTo(1);
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1));
			trialUsage.reserve(UuidV7.generate(), mark, UuidV7.generate(), photos(1));
		})).withMessageContaining("one principal on one device");
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1));
			trialUsage.reserve(principal, otherMark, UuidV7.generate(), photos(1));
		}));
		assertThat(reserve(UuidV7.generate(), otherMark, UuidV7.generate(), photos(1)))
			.isEqualTo(new Reservation.Reserved(1));
	}

	@Test
	void settlementsKeepToThePrincipalAndDeviceOfTheTransaction() {
		UUID principal = UuidV7.generate();
		UUID other = UuidV7.generate();
		UUID mark = androidMark();
		UUID otherMark = androidMark();
		UUID jobOnOtherMark = UuidV7.generate();
		reserve(other, mark, UuidV7.generate(), photos(1));
		reserve(principal, otherMark, jobOnOtherMark, photos(1));

		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1));
			trialUsage.release(other, UuidV7.generate());
		}));
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1));
			trialUsage.consume(principal, jobOnOtherMark);
		}));
		assertThat(reservations(principal)).extracting((row) -> row.get("status")).containsExactly("RESERVED");
	}

	@Test
	@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void nestedTransactionMayNotChangeTheUsageAgain() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		TransactionTemplate nested = new TransactionTemplate(transaction.getTransactionManager());
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		// Without the check the nested reservation would wait for the quota lock of the outer one.
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1));
			nested.executeWithoutResult((inner) -> trialUsage.reserve(principal, mark, UuidV7.generate(), photos(1)));
		})).withMessageContaining("nested");
		assertThat(reserve(principal, mark, UuidV7.generate(), photos(1))).isEqualTo(new Reservation.Reserved(1));
	}

	@Test
	void afterCommitCallbackMayChangeTheUsageInANewTransaction() {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID job = UuidV7.generate();
		TransactionTemplate next = new TransactionTemplate(transaction.getTransactionManager());
		next.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		transaction.executeWithoutResult((status) -> {
			trialUsage.reserve(principal, mark, job, photos(1));
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

				@Override
				public void afterCommit() {
					next.executeWithoutResult((inner) -> trialUsage.release(principal, job));
				}

			});
		});

		assertThat(quota(principal)).containsEntry("reserved_count", 0);
		assertThat(reservations(principal)).extracting((row) -> row.get("status")).containsExactly("RELEASED");
	}

	@Test
	void concurrentReservationsOfAPhotoReserveItOnce() throws Exception {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		List<Photo> photo = photos(1);

		List<Reservation> results = parallel(8, () -> reserve(principal, mark, UuidV7.generate(), photo));

		assertThat(results).filteredOn(Reservation.Reserved.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(Reservation.AlreadyReserved.class::isInstance).hasSize(7);
		assertThat(quota(principal)).containsEntry("reserved_count", 1);
	}

	@Test
	void concurrentSettlementsOfAJobApplyOnce() throws Exception {
		UUID principal = UuidV7.generate();
		UUID mark = androidMark();
		UUID job = UuidV7.generate();
		reserve(principal, mark, job, photos(3));
		AtomicInteger next = new AtomicInteger();

		List<Integer> settled = parallel(8, () -> (next.getAndIncrement() % 2 == 0)
				? transaction.execute((status) -> trialUsage.consume(principal, job))
				: transaction.execute((status) -> trialUsage.release(principal, job)));

		assertThat(settled).containsOnlyOnce(3).containsOnly(0, 3);
		Map<String, Object> quota = quota(principal);
		assertThat(quota).containsEntry("reserved_count", 0);
		assertThat(mark(mark)).containsEntry("reserved_count", 0)
			.containsEntry("consumed_count", quota.get("consumed_count"));
		assertThat(reservations(principal)).extracting((row) -> row.get("status")).containsOnly(
				(int) quota.get("consumed_count") == 3 ? "CONSUMED" : "RELEASED");
	}

	@Test
	void newDeviceIsHashedWithTheCurrentKey() {
		UUID mark = androidMark(keys("1:" + key() + ",2:" + key()), androidId());

		assertThat(mark(mark)).containsEntry("key_version", 2);
	}

	@Test
	void startupFailsWhileMarksUseAKeyVersionThatIsNotConfigured() {
		String key = key();
		androidMark(keys("9:" + key), androidId());
		String others = "1:" + key() + ",2:" + key();

		assertThatIllegalStateException()
			.isThrownBy(() -> new DeviceKeyCheck(records, keys(others)).afterSingletonsInstantiated())
			.withMessageContaining("[9]");
		assertThatNoException()
			.isThrownBy(() -> new DeviceKeyCheck(records, keys(others + ",9:" + key)).afterSingletonsInstantiated());
	}

	@Test
	void androidIdIsComparedInNormalForm() {
		String androidId = androidId();

		assertThat(installAndroid(" " + androidId.toUpperCase(Locale.ROOT) + " ")).isEqualTo(installAndroid(androidId));
	}

	@Test
	void failedAttestationDoesNotAskDeviceCheck() {
		assertThat(mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"platform": "IOS", "attestation": {"token": "forged"},
					 "trialDevice": {"deviceCheckToken": "%s"}}
					""".formatted(deviceCheckToken()))).hasStatus(403);
		verify(deviceCheck, never()).trialStarted(any());
	}

	@Test
	void deviceCheckFailureAtInstallCreatesNothing() {
		String token = deviceCheckToken();
		doThrow(new PikException(ErrorCode.TEMPORARY_FAILURE, "DEVICE_CHECK_UNAVAILABLE", true)).when(deviceCheck)
			.trialStarted(token);
		long principals = count("principal");
		long marks = count("device_trial_mark");

		assertThat(install("IOS", "{\"deviceCheckToken\": \"%s\"}".formatted(token))).hasStatus(503)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "TEMPORARY_FAILURE", "reason": "DEVICE_CHECK_UNAVAILABLE", "retryable": true}
					""");
		assertThat(install("IOS", "{\"deviceCheckToken\": \"forged\"}")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "FORBIDDEN", "reason": "DEVICE_CHECK_FAILED", "retryable": false}
					""");
		assertThat(count("principal")).isEqualTo(principals);
		assertThat(count("device_trial_mark")).isEqualTo(marks);
	}

	@Test
	void deviceCallsRunOutsideTransactions() {
		UUID mark = installIos(deviceCheckToken());

		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult(
				(status) -> trialUsage.recordStart(UuidV7.generate(), mark, 1, deviceCheckToken())));
		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult(
				(status) -> trialDevices.identify(new TrialDevices.Evidence.Ios(deviceCheckToken()))));
	}

	@Test
	void fakeDeviceCheckAndEphemeralKeysAreRefusedOutsideDevelopmentProfiles() {
		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");
		UsageProperties withoutKeys = new UsageProperties(new UsageProperties.Trial(100), null);

		assertThatIllegalStateException().isThrownBy(() -> new UsageConfig().deviceCheck(production));
		assertThat(new UsageConfig().deviceCheck(local)).isInstanceOf(FakeDeviceCheck.class);
		assertThatIllegalStateException().isThrownBy(() -> new UsageConfig().deviceKeys(withoutKeys, production));
		assertThat(new UsageConfig().deviceKeys(withoutKeys, local).currentVersion()).isEqualTo(1);
	}

	private Reservation reserve(UUID principal, UUID mark, UUID job, List<Photo> photos) {
		return transaction.execute((status) -> trialUsage.reserve(principal, mark, job, photos));
	}

	private void consume(UUID principal, UUID job) {
		transaction.executeWithoutResult((status) -> trialUsage.consume(principal, job));
	}

	private void release(UUID principal, UUID job) {
		transaction.executeWithoutResult((status) -> trialUsage.release(principal, job));
	}

	private UUID androidMark() {
		TrialDevices.IdentifiedDevice device = trialDevices.identify(new TrialDevices.Evidence.Android(androidId()));
		return transaction.execute((status) -> trialDevices.register(device));
	}

	private UUID androidMark(DeviceKeys keys, String androidId) {
		return transaction.execute((status) -> records.androidMark(keys.hashes(androidId), 5, Instant.now()));
	}

	private UUID installAndroid(String androidId) {
		return installedMark(install("ANDROID", "{\"androidId\": \"%s\"}".formatted(androidId)));
	}

	private UUID installIos(String deviceCheckToken) {
		return installedMark(install("IOS", "{\"deviceCheckToken\": \"%s\"}".formatted(deviceCheckToken)));
	}

	private MvcTestResult install(String platform, String trialDevice) {
		return mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"platform": "%s", "attestation": {"token": "fake-attestation"}, "trialDevice": %s}
					""".formatted(platform, trialDevice))
			.exchange();
	}

	private UUID installedMark(MvcTestResult result) {
		assertThat(result).hasStatus(201);
		String installId = (String) JSON.readValue(result.getResponse().getContentAsByteArray(), Map.class)
			.get("installId");
		return jdbc.sql("SELECT trial_mark_id FROM device WHERE install_id = :id")
			.param("id", UUID.fromString(installId))
			.query(UUID.class)
			.single();
	}

	private Map<String, Object> quota(UUID principal) {
		return jdbc.sql("SELECT * FROM usage_quota WHERE principal_id = :id").param("id", principal).query().singleRow();
	}

	private Map<String, Object> mark(UUID mark) {
		return jdbc.sql("SELECT * FROM device_trial_mark WHERE id = :id").param("id", mark).query().singleRow();
	}

	private List<Map<String, Object>> reservations(UUID principal) {
		return jdbc.sql("SELECT * FROM usage_reservation WHERE principal_id = :id")
			.param("id", principal)
			.query()
			.listOfRows();
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private static List<Photo> photos(int count) {
		return IntStream.range(0, count).mapToObj((i) -> new Photo(UuidV7.generate(), "v1")).toList();
	}

	private static String androidId() {
		byte[] id = new byte[8];
		ThreadLocalRandom.current().nextBytes(id);
		return HexFormat.of().formatHex(id);
	}

	private static String deviceCheckToken() {
		return FakeDeviceCheck.TOKEN_PREFIX + UUID.randomUUID();
	}

	private static DeviceKeys keys(String configured) {
		return DeviceKeys.load(configured, false);
	}

	private static String key() {
		byte[] key = new byte[32];
		ThreadLocalRandom.current().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

	private static void assertRejected(Runnable action, ErrorCode code, String reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(PikException.class, (ex) -> {
			assertThat(ex.getCode()).isEqualTo(code);
			assertThat(ex.getReason()).isEqualTo(reason);
			assertThat(ex.isRetryable()).isFalse();
		});
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
