package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link SendRateLimiter} on a fake clock: sleeping moves the clock unless a test holds it still (plan 3.2).
 */
class SendRateLimiterTests {

	static final Instant START = Instant.parse("2026-11-20T00:00:00Z");

	static final Duration HALF_SECOND = Duration.ofMillis(500);

	final AtomicReference<Instant> now = new AtomicReference<>(START);

	final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

	@Test
	void firstTokenIsImmediateAndTheNextAreSpacedByTheInterval() throws InterruptedException {
		SendRateLimiter limiter = limiter(2, true);

		for (int i = 0; i < 3; i++) {
			assertThat(limiter.acquire(START.plusSeconds(10))).isTrue();
		}

		assertThat(sleeps).containsExactly(HALF_SECOND, HALF_SECOND);
		assertThat(now.get()).isEqualTo(START.plusSeconds(1));
	}

	@Test
	void idleTimeSavesNoMoreThanOneToken() throws InterruptedException {
		SendRateLimiter limiter = limiter(2, true);
		assertThat(limiter.acquire(START)).isTrue();
		now.set(START.plusSeconds(60));

		assertThat(limiter.acquire(now.get())).isTrue();
		assertThat(limiter.acquire(now.get().plusSeconds(1))).isTrue();

		assertThat(sleeps).containsExactly(HALF_SECOND);
	}

	@Test
	void tokenDueAfterTheDeadlineTimesOutAtOnceAndStaysAvailable() throws InterruptedException {
		SendRateLimiter limiter = limiter(2, true);
		assertThat(limiter.acquire(START)).isTrue();

		assertThat(limiter.acquire(START.plus(HALF_SECOND).minusNanos(1))).isFalse();
		assertThat(sleeps).isEmpty();
		assertThat(limiter.acquire(START.plus(HALF_SECOND))).isTrue();
		assertThat(sleeps).containsExactly(HALF_SECOND);
	}

	@Test
	void deadlineInThePastTimesOut() throws InterruptedException {
		SendRateLimiter limiter = limiter(2, true);

		assertThat(limiter.acquire(START.minusNanos(1))).isFalse();
		assertThat(limiter.acquire(START)).isTrue();
		assertThat(sleeps).isEmpty();
	}

	@Test
	void rateIsTheGlobalRateDividedByTheInstances() throws InterruptedException {
		SendRateLimiter limiter = limiter(new AnalysisProperties.SendRate(1, 4).perInstancePerSecond(), true);

		assertThat(limiter.acquire(START.plusSeconds(10))).isTrue();
		assertThat(limiter.acquire(START.plusSeconds(10))).isTrue();

		assertThat(sleeps).containsExactly(Duration.ofSeconds(4));
	}

	// The clock stands still, so each caller sleeps until its own slot: the slots must all differ.
	@Test
	void concurrentCallersTakeDistinctSlots() throws Exception {
		SendRateLimiter limiter = limiter(2, false);
		int callers = 16;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < callers; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return limiter.acquire(START.plusSeconds(60));
				}));
			}
			start.countDown();
			for (Future<Boolean> result : results) {
				assertThat(result.get()).isTrue();
			}
		}

		assertThat(sleeps).containsExactlyInAnyOrderElementsOf(
				IntStream.range(1, callers).mapToObj((slot) -> HALF_SECOND.multipliedBy(slot)).toList());
	}

	@Test
	void interruptedWaitKeepsTheTokenTaken() throws InterruptedException {
		SendRateLimiter limiter = new SendRateLimiter(2, now::get, (duration) -> {
			throw new InterruptedException();
		});
		assertThat(limiter.acquire(START)).isTrue();

		assertThatThrownBy(() -> limiter.acquire(START.plusSeconds(10))).isInstanceOf(InterruptedException.class);
		assertThat(limiter.acquire(START.plus(HALF_SECOND))).isFalse();
	}

	@Test
	void rateMustBePositiveAndFinite() {
		for (double rate : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, 2e9 }) {
			assertThatIllegalArgumentException().isThrownBy(() -> new SendRateLimiter(rate))
				.withMessage("perSecond must be in (0, 1e9]");
		}
	}

	private SendRateLimiter limiter(double perSecond, boolean sleepingMovesTheClock) {
		return new SendRateLimiter(perSecond, now::get, (duration) -> {
			sleeps.add(duration);
			if (sleepingMovesTheClock) {
				now.updateAndGet((instant) -> instant.plus(duration));
			}
		});
	}

}
