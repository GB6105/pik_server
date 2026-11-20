package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.ModelReply.Row;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The settlement transaction of every response but a stored result (design 2.5, plan 5.7, 5.9). Locks the job first;
 * a call that is no longer {@code SENDING} was settled before and changes nothing. A run that still wins the job
 * ends it by the row's failure code, or sends it back to {@code READY} with the call settled: after 401, 403 or 404,
 * after a 429 or 529 once {@code Retry-After} passed, and after a call that was not sent. An unknown outcome sends the
 * job back once per photo and ends it with {@code UNKNOWN_OUTCOME} the second time. A run that lost the job settles
 * only its call and the evidence for the circuits. An answer to a call confirmed unknown meanwhile only adjusts the
 * call's cost (plan 5.9).
 */
@Component
@RequiredArgsConstructor
class ResponseSettlement {

	private final JobRecords jobs;

	private final RunRecords runs;

	private final CallSettlements calls;

	private final JobTermination termination;

	private final RunDeferrals deferrals;

	private final TransactionTemplate transaction;

	/**
	 * Settles the response in a transaction of its own; called outside any transaction.
	 */
	Settled settle(ClaimedJob claimed, CallRow call, CallOutcome outcome) {
		return Objects.requireNonNull(transaction.execute((status) -> settleLocked(claimed, call, outcome)));
	}

	/**
	 * Settles the response in the caller's transaction, for the lease recovery that confirms a call unknown under the
	 * job lock it already holds (plan 5.8).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Settled settleLocked(ClaimedJob claimed, CallRow call, CallOutcome outcome) {
		Instant now = Instant.now();
		LockedJob job = jobs.lock(claimed.jobId()).orElseThrow();
		CallStatus status = runs.callStatus(call.id());
		if (status != CallStatus.SENDING) {
			if (status == CallStatus.UNKNOWN) {
				calls.adjustLate(call, outcome, now);
			}
			return Settled.SETTLED_BEFORE;
		}
		if (!job.wonBy(claimed.runToken(), now)) {
			calls.settle(call, outcome, false, now);
			return Settled.LOST;
		}
		FailureCode reason = failureCode(outcome);
		if (outcome.row().equals(Row.UNKNOWN.name())) {
			if (!runs.unknownRetryUsed(call.photoId())) {
				calls.settle(call, outcome, true, now);
				deferrals.defer(claimed.jobId(), claimed.runToken(), deferrals.retryAt(outcome.retryAfter(), now),
						claimed.probe(), now);
				return Settled.DEFERRED;
			}
			reason = FailureCode.UNKNOWN_OUTCOME;
		}
		if (reason == null) {
			calls.settle(call, outcome, false, now);
			deferrals.defer(claimed.jobId(), claimed.runToken(), deferrals.retryAt(outcome.retryAfter(), now),
					claimed.probe(), now);
			return Settled.DEFERRED;
		}
		termination.end(claimed.jobId(),
				new JobEnding(reason, claimed.runToken(), false, calls.of(call, outcome, false, now)));
		return Settled.ENDED;
	}

	/**
	 * The failure code a winning run ends its job with, {@code null} for the rows that send it back to
	 * {@code READY} (design 2.5).
	 */
	private static FailureCode failureCode(CallOutcome outcome) {
		if (CallOutcome.RESULT_CONFLICT.equals(outcome.row())) {
			return FailureCode.RESULT_CONFLICT;
		}
		return switch (Row.valueOf(outcome.row())) {
			case REFUSAL -> FailureCode.REFUSAL;
			case TRUNCATED -> FailureCode.TRUNCATED;
			case INVALID_OUTPUT -> FailureCode.INVALID_OUTPUT;
			case PROVIDER_REJECTED, PAYMENT_REQUIRED -> FailureCode.PROVIDER_REJECTED;
			case CONFIGURATION_ERROR, THROTTLED, UNKNOWN, NOT_SENT -> null;
			case OUTPUT -> throw new IllegalArgumentException("A valid output is stored by the completion");
		};
	}

	/**
	 * What the settlement did, for the worker's counts.
	 */
	enum Settled {

		ENDED, DEFERRED, LOST, SETTLED_BEFORE

	}

}
