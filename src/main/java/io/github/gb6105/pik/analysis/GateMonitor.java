package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AiGates.Gate;
import io.github.gb6105.pik.analysis.RunRecords.AnswerShare;
import io.github.gb6105.pik.analysis.RunRecords.SpendDay;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The circuit and budget monitor batch (plan 7.3, 5.10, K-11), one transaction per cause. The budget transaction
 * locks the day's spend, then the budget gate: a daily cost limit setting that changed is written to today's row, and
 * an open budget gate closes once a call fits again. The configuration transaction locks only its gate, through the
 * generation compare-and-set of {@link AiGates#transition}: from the row as observed, a closed circuit opens when the
 * 400 share of the answers in the window reaches the threshold, and an open one turns half-open once the setting
 * fingerprint is no longer the one it opened under. An observation that a newer transition overtook changes nothing.
 * The monitor never locks a job and never clears a probe; it brings no deadline forward. A circuit half-open for longer
 * than {@code circuit.half-open-alarm} raises an alarm on every run. Logs carry counts and causes only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GateMonitor {

	private final AiGates gates;

	private final RunRecords runs;

	private final CallManifests manifests;

	private final TransactionTemplate transaction;

	private final AnalysisProperties properties;

	@Scheduled(fixedDelayString = "${pik.analysis.circuit.monitor-interval}",
			initialDelayString = "${pik.analysis.cleanup.initial-delay}")
	@SchedulerLock(name = "analysis-gate-monitor")
	public void monitor() {
		try {
			transaction.executeWithoutResult((status) -> refreshBudget(Instant.now()));
		}
		catch (RuntimeException ex) {
			log.warn("Budget monitor failed: {}", ex.getClass().getSimpleName());
		}
		try {
			transaction.executeWithoutResult((status) -> watchConfig(observeConfig(), Instant.now()));
		}
		catch (RuntimeException ex) {
			log.warn("Configuration circuit monitor failed: {}", ex.getClass().getSimpleName());
		}
		alarmHalfOpen(Instant.now());
	}

	/**
	 * The configuration circuit as last committed, read without a lock: the observation its transitions compare.
	 */
	Gate observeConfig() {
		return gates.read().get(GateCause.CONFIG_CIRCUIT);
	}

	/**
	 * Writes a changed daily cost limit setting to today's spend and closes an open budget gate when a call of the
	 * current manifest fits again. Locks the day's spend, then the budget gate only when the limit changed.
	 * @return whether the budget gate closed
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean refreshBudget(Instant now) {
		long limit = properties.budget().dailyCostLimitMicros();
		LocalDate day = BudgetDays.of(now);
		SpendDay spend = runs.lockSpendDay(day, limit, now);
		if (spend.limitMicros() == limit) {
			return false;
		}
		runs.changeSpendLimit(day, limit, now);
		Gate budget = gates.lock(List.of(GateCause.BUDGET)).get(GateCause.BUDGET);
		SpendDay changed = new SpendDay(limit, spend.reservedMicros(), spend.settledMicros());
		if (budget.state() != GateState.OPEN || !changed.fits(manifests.current().settings().maxCostMicros())) {
			return false;
		}
		gates.closeBudget(budget, now);
		log.info("Daily AI cost limit setting changed; the budget gate closed");
		return true;
	}

	/**
	 * The configuration circuit's transitions of the monitor, from {@code observed} (plan 7.3, 5.10). A closed circuit
	 * opens under the current fingerprint when, of the answers to calls permitted under that fingerprint since the
	 * later of the window start and the last close, at least {@code circuit.min-samples} came and the 400 share
	 * reaches {@code circuit.bad-request-ratio}; an open circuit whose fingerprint is not the current one turns
	 * half-open with the current one. A half-open circuit is left to its probe.
	 * @param observed the row as read without a lock; a transition only applies while the row is in its generation
	 * @return whether the row changed
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean watchConfig(Gate observed, Instant now) {
		String fingerprint = manifests.configFingerprint();
		return switch (observed.state()) {
			case CLOSED -> {
				AnalysisProperties.Circuit circuit = properties.circuit();
				Instant since = later(observed.resetAt(), now.minus(circuit.window()));
				AnswerShare share = runs.answerShare(fingerprint, since);
				if (share.answers() < circuit.minSamples()
						|| (double) share.rejected() / share.answers() < circuit.badRequestRatio()) {
					yield false;
				}
				boolean opened = gates.transition(observed, GateState.OPEN, observed.blockedUntil(), fingerprint, 0,
						now);
				if (opened) {
					log.warn("Configuration circuit opened: {} of {} answers in the window were 400; calls wait for a "
							+ "new key version or manifest", share.rejected(), share.answers());
				}
				yield opened;
			}
			// Half-open from now: the alarm counts from the row's deadline.
			case OPEN -> !fingerprint.equals(observed.configVersion()) && gates.transition(observed,
					GateState.HALF_OPEN, later(observed.blockedUntil(), now), fingerprint, 0, now);
			case HALF_OPEN -> false;
		};
	}

	/**
	 * Raises an alarm for each circuit that has been half-open, since its deadline, for longer than
	 * {@code circuit.half-open-alarm}.
	 */
	void alarmHalfOpen(Instant now) {
		Duration alarm = properties.circuit().halfOpenAlarm();
		for (Gate gate : gates.read().values()) {
			Instant since = gate.blockedUntil();
			if (gate.state() == GateState.HALF_OPEN && since != null && since.plus(alarm).isBefore(now)) {
				log.warn("{} has been half-open for longer than {}", gate.cause(), alarm);
			}
		}
	}

	private static Instant later(@Nullable Instant a, Instant b) {
		return (a != null && a.isAfter(b)) ? a : b;
	}

}
