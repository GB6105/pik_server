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

/**
 * The one transition that ends a job without a result (plan 1 "모든 종료 전이", 3.3 "공통 종료"): the job gets its final
 * status, failure code and end time and loses its run token and lease, every attempt's object is deleted from now on
 * (the {@code ISSUED} attempt is {@code ABANDONED}, a confirmed one is not kept either), and the trial reservations of
 * the whole job are released once. A run that ends its job also clears its probe, settles its call when it has one
 * and counts an invalid input of its photo. Locks in the order job, attempts, the call and the principal's daily sends
 * ({@link Settlement#settleCall}), usage allocation and device mark, the device's sends, the photo budget and the
 * daily cost ({@link Settlement#settleBudgets}), the photo budget of an invalid input, the gates. Every terminal path
 * calls it, so a job never ends without these.
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
	 */
	record JobEnding(FailureCode reason, @Nullable UUID runToken, boolean inputInvalid, Settlement settlement) {

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
