package io.github.gb6105.pik.analysis;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.springframework.util.Assert;

/**
 * The share of the global Claude send rate of one instance (design 2.7, plan 3.2): a token bucket in memory that fills
 * at {@code send-rate.global-per-second / send-rate.max-instances} and holds one token, so sends are spaced and never
 * burst. The global rate holds only while at most {@code max-instances} instances send. Thread-safe.
 */
final class SendRateLimiter {

	private final Duration interval;

	private final InstantSource clock;

	private final Sleeper sleeper;

	// When the next token is due. Guarded by this.
	private Instant next = Instant.MIN;

	SendRateLimiter(double perSecond) {
		this(perSecond, InstantSource.system(), Thread::sleep);
	}

	SendRateLimiter(double perSecond, InstantSource clock, Sleeper sleeper) {
		Assert.isTrue(Double.isFinite(perSecond) && perSecond > 0 && perSecond <= 1e9,
				"perSecond must be in (0, 1e9]");
		this.interval = Duration.ofNanos(Math.round(1e9 / perSecond));
		this.clock = clock;
		this.sleeper = sleeper;
	}

	/**
	 * Takes the next token, waiting for it when it is due by {@code deadline}.
	 * @param deadline the latest time to take the token
	 * @return {@code true} once the token is taken; {@code false} at once, taking nothing, when it is due after the
	 * deadline
	 * @throws InterruptedException if interrupted while waiting; the token stays taken
	 */
	boolean acquire(Instant deadline) throws InterruptedException {
		Instant due;
		synchronized (this) {
			Instant now = clock.instant();
			due = next.isAfter(now) ? next : now;
			if (due.isAfter(deadline)) {
				return false;
			}
			next = due.plus(interval);
		}
		Duration wait = Duration.between(clock.instant(), due);
		if (wait.isPositive()) {
			sleeper.sleep(wait);
		}
		return true;
	}

	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;

	}

}
