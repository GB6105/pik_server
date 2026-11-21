package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.UploadRecords.DueDelete;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The upload batches that clean up after attempts (plan 7.4, 7.5, K-13). The attempt rows are the delete queue: due
 * rows are read without locks, each object is deleted outside any transaction, and the row is then acknowledged on
 * the version it was read with. A delete counts as final only if it started after the URL expired and the in-flight
 * margin passed; an earlier one is repeated then. Logs hold counts and exception class names only, never object keys
 * or IDs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UploadCleanup {

	private static final UUID FIRST = new UUID(0, 0);

	// The current hourly window and the one before it are kept (plan 7.5).
	private static final Duration RATE_WINDOW_RETENTION = Duration.ofHours(2);

	private final UploadRecords attempts;

	private final JobRecords jobs;

	private final InputStore store;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	/**
	 * Deletes the objects of the attempts due by now. A failed delete stays pending for the next run.
	 */
	@Scheduled(fixedDelayString = "${pik.analysis.cleanup.delete-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-input-delete")
	public void deleteObjects() {
		Instant now = Instant.now();
		int batchSize = properties.cleanup().batchSize();
		int sent = 0;
		int rescheduled = 0;
		int failed = 0;
		UUID after = FIRST;
		List<DueDelete> page;
		do {
			page = attempts.findDueDeletes(now, after, batchSize);
			for (DueDelete due : page) {
				switch (delete(due)) {
					case SENT -> sent++;
					case RESCHEDULED -> rescheduled++;
					case FAILED -> failed++;
					case CHANGED -> {
					}
				}
			}
			after = page.isEmpty() ? after : page.getLast().id();
		}
		while (page.size() == batchSize);
		if (sent + rescheduled + failed > 0) {
			log.info("Input object deletes: {} sent, {} rescheduled, {} failed", sent, rescheduled, failed);
		}
	}

	private DeleteOutcome delete(DueDelete due) {
		try {
			Instant startedAt = Instant.now();
			if (!store.delete(due.objectKey())) {
				return DeleteOutcome.FAILED;
			}
			// R2-06: the start time decides, so a late acknowledgement does not make an early delete final.
			Instant finalFrom = due.urlExpiresAt().plus(properties.upload().inFlightMargin());
			if (!startedAt.isBefore(finalFrom)) {
				return attempts.markDeleteSent(due.id(), due.version(), Instant.now()) ? DeleteOutcome.SENT
						: DeleteOutcome.CHANGED;
			}
			return attempts.rescheduleDelete(due.id(), due.version(), finalFrom, Instant.now())
					? DeleteOutcome.RESCHEDULED : DeleteOutcome.CHANGED;
		}
		catch (RuntimeException ex) {
			log.warn("Input object delete failed: {}", ex.getClass().getSimpleName());
			return DeleteOutcome.FAILED;
		}
	}

	/**
	 * The safety net of the deletes (plan 7.4): attempts of ended jobs whose object is still kept are deleted from now
	 * on, each job under its lock, and deletes pending past {@code cleanup.overdue-alarm} raise a warning. The bucket
	 * lifecycle is the last guarantee.
	 */
	@Scheduled(fixedDelayString = "${pik.analysis.cleanup.reconcile-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-input-reconcile")
	public void reconcile() {
		Instant now = Instant.now();
		int batchSize = properties.cleanup().batchSize();
		int scheduled = 0;
		UUID after = FIRST;
		List<UUID> page;
		do {
			page = attempts.findEndedWithKeptObjects(after, batchSize);
			for (UUID jobId : page) {
				scheduled += schedule(jobId, now);
			}
			after = page.isEmpty() ? after : page.getLast();
		}
		while (page.size() == batchSize);
		if (scheduled > 0) {
			log.warn("Delete reconciliation scheduled {} input objects of ended jobs", scheduled);
		}
		Duration alarm = properties.cleanup().overdueAlarm();
		long overdue = attempts.countOverdueDeletes(now.minus(alarm));
		if (overdue > 0) {
			log.warn("{} input object deletes are pending longer than {} after they were due", overdue, alarm);
		}
	}

	// The attempts change under the job's lock, as in the common termination.
	private int schedule(UUID jobId, Instant now) {
		try {
			return Objects.requireNonNull(transaction.execute((status) -> {
				LockedJob job = jobs.lock(jobId).orElseThrow();
				return job.status().over() ? attempts.abandonAll(jobId, now) : 0;
			}));
		}
		catch (RuntimeException ex) {
			log.warn("Delete reconciliation of a job failed: {}", ex.getClass().getSimpleName());
			return 0;
		}
	}

	/**
	 * Deletes the input rate windows that no rate check reads any more (plan 7.5).
	 */
	@Scheduled(fixedDelayString = "${pik.analysis.cleanup.rate-window-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-input-rate-cleanup")
	public void deleteRateWindows() {
		Instant before = Instant.now().minus(RATE_WINDOW_RETENTION);
		int batchSize = properties.cleanup().batchSize();
		long total = 0;
		int deleted;
		do {
			deleted = attempts.deleteRateWindows(before, batchSize);
			total += deleted;
		}
		while (deleted == batchSize);
		if (total > 0) {
			log.info("Deleted {} expired input rate windows", total);
		}
	}

	private enum DeleteOutcome {

		SENT, RESCHEDULED, FAILED,

		/** The attempt changed since it was read; it is read again if it is still due. */
		CHANGED

	}

}
