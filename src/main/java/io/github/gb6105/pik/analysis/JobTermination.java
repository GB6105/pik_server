package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one transition that ends a job without a call to settle (plan 1 "모든 종료 전이", 3.3 "공통 종료 (호출 없음)"):
 * the job gets its final status, failure code and end time and loses its run token and lease, every attempt's object
 * is deleted from now on (the {@code ISSUED} attempt is {@code ABANDONED}, a confirmed one is not kept either), and
 * the trial reservations of the whole job are released once. Locks in the order job, attempts, usage allocation,
 * device mark. Every terminal path calls it, so a job never ends without these.
 */
@Component
@RequiredArgsConstructor
class JobTermination {

	private final JobRecords jobs;

	private final UploadRecords attempts;

	private final TrialUsage trialUsage;

	/**
	 * Ends the job under its lock, which the caller may already hold. An ended job is never reopened or ended again.
	 * @return whether the job ended now; {@code false} when it had already ended
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean end(UUID jobId, JobEnding ending) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		if (job.status().over()) {
			return false;
		}
		Instant now = Instant.now();
		jobs.end(jobId, ending.reason(), now);
		attempts.abandonAll(jobId, now);
		trialUsage.release(job.ownerId(), jobId);
		return true;
	}

	/**
	 * How a job ends. The transitions that end a running job add what they discard with it here.
	 *
	 * @param reason the failure code, which also gives the final status
	 */
	record JobEnding(FailureCode reason) {

	}

}
