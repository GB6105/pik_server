package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AiGates.Signal;
import io.github.gb6105.pik.analysis.ModelReply.Row;
import io.github.gb6105.pik.analysis.ModelReply.Usage;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How one call is settled, by the settlement table row of what it observed (design 2.5, plan 6.4): the call row's
 * final values, its cost, whether its risky sends are spent or released, and what it tells the circuits. A reply
 * without usage that is settled at a cost is settled at the maximum cost, and its risky sends are spent; only 401,
 * 403 and 404 release them (ADR-0031 #7).
 *
 * @param row the settlement table row, recorded as {@code external_call.outcome}
 * @param usage the tokens the provider reported, {@code null} when unknown
 * @param costMicros the cost settled, in micro dollars
 * @param usageCostMicros the cost of {@code usage} at the prices of the call's manifest, whatever the row settles;
 * {@code null} without usage
 * @param sendSpent whether the risky sends stay spent; released otherwise
 * @param sentAt when the request was sent, {@code null} when it was not
 * @param respondedAt when the provider answered, {@code null} without an answer
 * @param retryAfter the provider's {@code Retry-After}, {@code null} without one
 */
record CallOutcome(String row, CallStatus status, @Nullable Integer httpStatus, @Nullable String stopReason,
		@Nullable String requestId, @Nullable Usage usage, long costMicros, @Nullable Long usageCostMicros,
		boolean sendSpent, @Nullable Instant sentAt, @Nullable Instant respondedAt, Signal signal,
		@Nullable Duration retryAfter) {

	/** The row of a valid output whose result conflicted with a stored one (plan K-21). */
	static final String RESULT_CONFLICT = "RESULT_CONFLICT";

	private static final BigInteger MILLION = BigInteger.valueOf(1_000_000);

	/**
	 * @param reply what the call observed
	 * @param valid for an {@link Row#OUTPUT} reply, whether the validator accepted the output; ignored otherwise
	 * @param price the prices of the job's manifest
	 * @param maxCostMicros the maximum cost of the job's manifest, reserved at permit
	 * @param sentAt when the request was sent
	 * @param observedAt when the reply came back
	 */
	static CallOutcome of(ModelReply reply, boolean valid, AnalysisProperties.Price price, long maxCostMicros,
			Instant sentAt, Instant observedAt) {
		Integer httpStatus = null;
		String stopReason = null;
		String requestId = null;
		Usage usage = null;
		Duration retryAfter = null;
		Instant respondedAt = null;
		switch (reply) {
			case ModelReply.Answered answered -> {
				httpStatus = answered.status();
				stopReason = answered.stopReason();
				requestId = answered.requestId();
				usage = answered.usage();
				retryAfter = answered.retryAfter();
				respondedAt = observedAt;
			}
			case ModelReply.Unreadable unreadable -> {
				httpStatus = unreadable.status();
				requestId = unreadable.requestId();
				usage = unreadable.usage();
				respondedAt = observedAt;
			}
			case ModelReply.NotSent notConnected -> {
				return notSent();
			}
			// The answer broke off in its body: its status and request-id stay known (plan 6.4).
			case ModelReply.NoAnswer unanswered -> {
				httpStatus = unanswered.status();
				requestId = unanswered.requestId();
				respondedAt = (httpStatus != null) ? observedAt : null;
			}
		}
		Long actual = (usage != null) ? costMicros(usage, price) : null;
		long used = (actual != null) ? actual : maxCostMicros;
		Row row = reply.row();
		return switch (row) {
			case OUTPUT -> new CallOutcome(valid ? row.name() : Row.INVALID_OUTPUT.name(),
					valid ? CallStatus.SUCCEEDED : CallStatus.RESPONDED_FAILURE, httpStatus, stopReason, requestId,
					usage, used, actual, true, sentAt, respondedAt, Signal.ANSWERED, null);
			case REFUSAL, TRUNCATED, INVALID_OUTPUT -> new CallOutcome(row.name(), CallStatus.RESPONDED_FAILURE,
					httpStatus, stopReason, requestId, usage, used, actual, true, sentAt, respondedAt, Signal.ANSWERED,
					null);
			case PROVIDER_REJECTED -> new CallOutcome(row.name(), CallStatus.RESPONDED_FAILURE, httpStatus, stopReason,
					requestId, usage, used, actual, true, sentAt, respondedAt, Signal.REJECTED, null);
			// K-7: settled as a 400, so its sends stay spent; the circuit takes it as a configuration error.
			case PAYMENT_REQUIRED -> new CallOutcome(row.name(), CallStatus.RESPONDED_FAILURE, httpStatus, stopReason,
					requestId, usage, used, actual, true, sentAt, respondedAt, Signal.CONFIG_ERROR, null);
			case CONFIGURATION_ERROR -> new CallOutcome(row.name(), CallStatus.RESPONDED_FAILURE, httpStatus,
					stopReason, requestId, usage, used, actual, false, sentAt, respondedAt, Signal.CONFIG_ERROR, null);
			case THROTTLED -> new CallOutcome(row.name(), CallStatus.RESPONDED_FAILURE, httpStatus, stopReason,
					requestId, usage, 0, actual, false, sentAt, respondedAt, Signal.TRANSIENT, retryAfter);
			case UNKNOWN -> new CallOutcome(row.name(), CallStatus.UNKNOWN, httpStatus, stopReason, requestId, usage,
					maxCostMicros, actual, true, sentAt, respondedAt, Signal.TRANSIENT, retryAfter);
			case NOT_SENT -> notSent();
		};
	}

	/**
	 * A permitted call that was never sent: failed before a connection, or found its lease or processing deadline
	 * too close to send (design 2.5, last row).
	 */
	static CallOutcome notSent() {
		return new CallOutcome(Row.NOT_SENT.name(), CallStatus.NOT_SENT, null, null, null, null, 0, null, false, null,
				null, Signal.NOT_SENT, null);
	}

	/**
	 * A permitted call whose run stopped without settling it, confirmed unknown by the lease recovery or the processing
	 * deadline (plan 5.8, 5.9, 7.2): whether it was sent is not known, so it is settled at the maximum cost reserved at
	 * permit with its sends spent.
	 */
	static CallOutcome unknown(long maxCostMicros) {
		return new CallOutcome(Row.UNKNOWN.name(), CallStatus.UNKNOWN, null, null, null, null, maxCostMicros, null, true,
				null, null, Signal.TRANSIENT, null);
	}

	/**
	 * A valid output that the completion could not store (plan 5.6, K-21): settled at its usage with its sends spent.
	 */
	static CallOutcome resultConflict(CallOutcome succeeded) {
		return new CallOutcome(RESULT_CONFLICT, succeeded.status(), succeeded.httpStatus(), succeeded.stopReason(),
				succeeded.requestId(), succeeded.usage(), succeeded.costMicros(), succeeded.usageCostMicros(), true,
				succeeded.sentAt(), succeeded.respondedAt(), succeeded.signal(), null);
	}

	/**
	 * The cost of the tokens at the manifest's prices, rounded up to a micro dollar.
	 */
	static long costMicros(Usage usage, AnalysisProperties.Price price) {
		BigInteger microsPerMillion = BigInteger.valueOf(usage.inputTokens())
			.multiply(BigInteger.valueOf(price.inputMicrosPerMtok()))
			.add(BigInteger.valueOf(usage.cacheCreationInputTokens())
				.multiply(BigInteger.valueOf(price.cacheWriteMicrosPerMtok())))
			.add(BigInteger.valueOf(usage.cacheReadInputTokens())
				.multiply(BigInteger.valueOf(price.cacheReadMicrosPerMtok())))
			.add(BigInteger.valueOf(usage.outputTokens()).multiply(BigInteger.valueOf(price.outputMicrosPerMtok())));
		BigInteger[] division = microsPerMillion.divideAndRemainder(MILLION);
		return division[0].add((division[1].signum() > 0) ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
	}

}
