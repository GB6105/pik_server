package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobTermination.Settlement;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * Settles a permitted call by its settlement table row (design 2.5), once: the call row changes from {@code SENDING}
 * by compare-and-set, its cost replaces the reserved maximum on the day of its permit, and its risky sends (the
 * principal's daily sends, the device's sends for a trial job and the photo's lifetime sends) stay spent or are
 * released. The response then counts as evidence for the circuits. The caller holds the call's job lock and has
 * checked that the call is still {@code SENDING}; both halves run in its transaction. An answer to a call that was
 * confirmed unknown meanwhile only adjusts the call's cost, once.
 */
@Component
@RequiredArgsConstructor
class CallSettlements {

	private final RunRecords runs;

	private final TrialUsage trialUsage;

	private final AiGates gates;

	/**
	 * @param markUnknownRetry whether the photo uses its one call again after an unknown outcome now (design 2.6)
	 */
	Settlement of(CallRow call, CallOutcome outcome, boolean markUnknownRetry, Instant now) {
		return new Settlement() {

			@Override
			public void settleCall() {
				Assert.state(runs.settleCall(call.id(), outcome, now), "The call is SENDING under its job's lock");
				if (!outcome.sendSpent()) {
					runs.addDailySend(call.ownerId(), call.budgetDay(), -1);
				}
			}

			@Override
			public void settleBudgets() {
				if (!outcome.sendSpent() && call.deviceMarkId() != null) {
					trialUsage.releaseDeviceSend(call.deviceMarkId());
				}
				if (!outcome.sendSpent() || markUnknownRetry) {
					runs.changePhotoBudget(call.photoId(), outcome.sendSpent() ? 0 : -1, markUnknownRetry, now);
				}
				runs.settleSpend(call.budgetDay(), call.reservedCostMicros(), outcome.costMicros(), now);
				gates.observe(outcome.signal(), call.jobId(), call.runToken(), call.createdAt(), outcome.retryAfter(),
						now);
			}

		};
	}

	/**
	 * Applies an answer that came after its call was confirmed unknown (plan 5.9): when it reports usage, the call's
	 * cost becomes the cost of that usage once ({@code ADJUSTED}), also on the day of its permit, a day row that is
	 * gone staying gone. The risky sends stay spent, and the answer stores no result, consumes no trial photo,
	 * changes no job and counts for no circuit. Locks the call, then the daily cost.
	 * @return whether the call was adjusted now; {@code false} without usage, or when it was adjusted before
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean adjustLate(CallRow call, CallOutcome outcome, Instant now) {
		ModelReply.Usage usage = outcome.usage();
		if (usage == null) {
			return false;
		}
		OptionalLong replaced = runs.adjustUnknown(call.id(), usage, outcome.costMicros());
		if (replaced.isEmpty()) {
			return false;
		}
		runs.adjustSpend(call.budgetDay(), outcome.costMicros() - replaced.getAsLong(), now);
		return true;
	}

	/**
	 * Settles the call of a run that has no job transition with it: the call of a run that lost its job, or of a
	 * run that goes back to {@code READY}.
	 */
	void settle(CallRow call, CallOutcome outcome, boolean markUnknownRetry, Instant now) {
		Settlement settlement = of(call, outcome, markUnknownRetry, now);
		settlement.settleCall();
		settlement.settleBudgets();
	}

}
