package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The common transition that sends a running job back to {@code READY} (plan 5.3, 5.4, 5.7): only while the run still
 * holds the job, with its run token and lease discarded and its probe cleared. A probe run that goes back without
 * evidence keeps the job out of probes for {@code circuit.probe-exclusion}, so that the next claim names another job.
 * Locks the job, then the gates. A call of the run is settled before, in the same transaction.
 */
@Component
@RequiredArgsConstructor
class RunDeferrals {

	private final JobRecords jobs;

	private final AiGates gates;

	private final AnalysisProperties properties;

	/**
	 * @param until when the job may be claimed again
	 * @param probe whether the run was claimed as a probe
	 * @return {@code false}, with nothing changed, when the run no longer holds the job
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean defer(UUID jobId, UUID runToken, Instant until, boolean probe, Instant now) {
		LockedJob job = jobs.lock(jobId).orElseThrow();
		if (!job.heldBy(runToken)) {
			return false;
		}
		jobs.defer(jobId, until, probe ? now.plus(properties.circuit().probeExclusion()) : null, now);
		gates.releaseProbe(jobId, runToken, now);
		return true;
	}

	/**
	 * When a job sent back without a delay of its own may be claimed again: after {@code wait}, or after
	 * {@code worker.retry-backoff} without one, grown by up to the {@code worker.retry-jitter} share.
	 */
	Instant retryAt(@Nullable Duration wait, Instant now) {
		AnalysisProperties.Worker worker = properties.worker();
		Duration delay = (wait != null && !wait.isNegative()) ? wait : worker.retryBackoff();
		double jitter = worker.retryJitter() * ThreadLocalRandom.current().nextDouble();
		return now.plus(delay).plusMillis((long) (delay.toMillis() * jitter));
	}

}
