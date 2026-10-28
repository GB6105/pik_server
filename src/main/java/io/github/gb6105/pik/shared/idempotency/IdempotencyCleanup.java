package io.github.gb6105.pik.shared.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes records past their retention (data-retention.md). Each batch commits on its own so a backlog does not
 * hold one long transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IdempotencyCleanup {

	private final IdempotencyRecords records;

	private final IdempotencyProperties properties;

	@Scheduled(fixedDelayString = "${pik.idempotency.cleanup-interval}",
			initialDelayString = "${pik.idempotency.cleanup-initial-delay}")
	@SchedulerLock(name = "idempotency-cleanup")
	public void deleteExpired() {
		long total = 0;
		int deleted;
		do {
			deleted = records.deleteExpired(properties.cleanupBatchSize());
			total += deleted;
		}
		while (deleted == properties.cleanupBatchSize());
		if (total > 0) {
			log.info("Deleted {} expired idempotency records", total);
		}
	}

}
