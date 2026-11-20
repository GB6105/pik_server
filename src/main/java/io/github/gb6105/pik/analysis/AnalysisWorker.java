package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The analysis worker of one instance (plan 5.1): enabled only on the worker service. Every poll claims as many jobs
 * as the instance has free slots, at most {@code worker.global-slots}, and runs each on a virtual thread of its own.
 * Every instance polls; the claim lock keeps the global and per-principal slots across instances. A run that fails
 * leaves its job to the lease recovery.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("pik.analysis.worker.enabled")
class AnalysisWorker implements DisposableBean {

	private final JobClaims claims;

	private final AnalysisRun run;

	private final Semaphore slots;

	private final long shutdownWaitMillis;

	private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();

	AnalysisWorker(JobClaims claims, AnalysisRun run, AnalysisProperties properties) {
		this.claims = claims;
		this.run = run;
		this.slots = new Semaphore(properties.worker().globalSlots());
		this.shutdownWaitMillis = properties.claude().requestTimeout().toMillis();
	}

	@Scheduled(fixedDelayString = "${pik.analysis.worker.poll-interval}",
			initialDelayString = "${pik.analysis.worker.initial-delay}")
	public void poll() {
		int free = slots.drainPermits();
		int started = 0;
		try {
			List<ClaimedJob> claimed = claims.claim(free);
			for (ClaimedJob job : claimed) {
				runs.execute(() -> runAndRelease(job));
				started++;
			}
		}
		finally {
			slots.release(free - started);
		}
	}

	private void runAndRelease(ClaimedJob job) {
		try {
			log.info("Analysis run stopped: {}", run.run(job));
		}
		catch (RuntimeException ex) {
			log.warn("Analysis run failed ({}); the lease recovery takes the job", ex.getClass().getSimpleName());
		}
		finally {
			slots.release();
		}
	}

	@Override
	public void destroy() throws InterruptedException {
		runs.shutdown();
		if (!runs.awaitTermination(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
			runs.shutdownNow();
		}
	}

}
