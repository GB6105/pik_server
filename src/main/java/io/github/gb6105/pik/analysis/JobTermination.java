package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * The one transition that ends a job without a result (plan 1 "모든 종료 전이", 3.3 "공통 종료"): the job gets its final
 * status, failure code and end time and loses its run token and lease, every attempt's object is deleted from now on
 * (the {@code ISSUED} attempt is {@code ABANDONED}, a confirmed one is not kept either), and the trial reservations of
 * the whole job are released once. A run that ends its job also clears its probe, settles its call when it has one
 * and counts an invalid input of its photo. Locks in the order job, attempts, the call and the principal's daily sends
 * ({@link Settlement#settleCall}), usage allocation and device mark, the device mark and photo budget of a
 * {@link Recheck}, the device's sends, the photo budget and the daily cost ({@link Settlement#settleBudgets}), the
 * photo budget of an invalid input, the gates. Every terminal path calls it, so a job never ends without these.
 */
@Component
@RequiredArgsConstructor
class JobTermination {

	private final JobRecords jobs;

	private final UploadRecords attempts;

	private final TrialUsage trialUsage;

	private final RunRecords runs;

	private final AiGates gates;

	private final AnalysisProperties properties;

	/**
	 * Ends the job under its lock, which the caller may already hold. An ended job is never reopened or ended again.
	 * @return whether the job ended now; {@code false} when it had already ended, or when the run of the ending no
	 * longer holds it, and then nothing changed
	 * @throws ReasonLifted when the ending's {@link Recheck} finds its reason gone; the caller's transaction must roll
	 * back
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean end(UUID jobId, JobEnding ending) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		UUID runToken = ending.runToken();
		if (job.status().over() || (runToken != null && !job.heldBy(runToken))) {
			return false;
		}
		Instant now = Instant.now();
		jobs.end(jobId, ending.reason(), now);
		attempts.abandonAll(jobId, now);
		ending.settlement().settleCall();
		trialUsage.release(job.ownerId(), jobId);
		if (!ending.recheck().holds()) {
			throw new ReasonLifted();
		}
		ending.settlement().settleBudgets();
		if (ending.inputInvalid()) {
			AnalysisProperties.Input input = properties.input();
			runs.countInvalidInput(job.photoId(), job.ownerId(), input.invalidThreshold(),
					now.plus(input.blockDuration()), now);
		}
		if (runToken != null) {
			gates.releaseProbe(jobId, runToken, now);
		}
		return true;
	}

	/**
	 * How a job ends. The transitions that end a running job add what they discard with it here.
	 *
	 * @param reason the failure code, which also gives the final status
	 * @param runToken the run that ends the job: the job ends only while that run holds it, and the run's probe is
	 * cleared; {@code null} for transitions of jobs that do not run
	 * @param inputInvalid whether the run found the input invalid, which counts against the photo (design 2.7)
	 * @param settlement the settlement of the run's call, {@link Settlement#NONE} without a call
	 * @param recheck the check of the reason under the ending's locks, {@link Recheck#NONE} for a reason that cannot
	 * go away; only for endings without a call
	 */
	record JobEnding(FailureCode reason, @Nullable UUID runToken, boolean inputInvalid, Settlement settlement,
			Recheck recheck) {

		JobEnding {
			Assert.isTrue(recheck == Recheck.NONE || settlement == Settlement.NONE,
					"An ending is checked again only without a call to settle");
		}

		JobEnding(FailureCode reason, @Nullable UUID runToken, boolean inputInvalid, Settlement settlement) {
			this(reason, runToken, inputInvalid, settlement, Recheck.NONE);
		}

		JobEnding(FailureCode reason) {
			this(reason, null, false, Settlement.NONE);
		}

		/**
		 * The ending of a run that sent nothing.
		 */
		static JobEnding ofRun(FailureCode reason, UUID runToken) {
			return new JobEnding(reason, runToken, reason == FailureCode.INPUT_INVALID, Settlement.NONE);
		}

	}

	/**
	 * A check of an ending's reason again under the ending's own locks, right after the usage release: a permit
	 * denial was decided on the snapshot of a permit transaction that rolled back (plan 5.4). It may lock the device
	 * mark again and the photo budget, which come after the usage release in the lock order.
	 */
	@FunctionalInterface
	interface Recheck {

		/** A reason that cannot go away. */
		Recheck NONE = () -> true;

		/**
		 * Whether the reason still holds.
		 */
		boolean holds();

	}

	/**
	 * The reason of an ending was gone under the ending's locks: everything the ending changed must roll back, and
	 * the job goes back to {@code READY} in a transaction of its own.
	 */
	static final class ReasonLifted extends RuntimeException {

		ReasonLifted() {
			super("The reason of the ending no longer holds", null, false, false);
		}

	}

	/**
	 * The settlement of a call, split around the usage release so that every lock comes in order.
	 */
	interface Settlement {

		/** No call to settle. */
		Settlement NONE = new Settlement() {

			@Override
			public void settleCall() {
			}

			@Override
			public void settleBudgets() {
			}

		};

		/**
		 * Settles the call row and the principal's daily sends.
		 */
		void settleCall();

		/**
		 * Settles the device's sends, the photo budget, the daily cost and the circuits.
		 */
		void settleBudgets();

	}

}
