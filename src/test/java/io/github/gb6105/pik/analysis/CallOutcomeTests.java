package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The cost of a call and the KST budget day (design 2.5, 2.7).
 */
class CallOutcomeTests {

	private static final AnalysisProperties.Price PRICE = new AnalysisProperties.Price("test", 1_000_000, 1_250_000,
			100_000, 5_000_000);

	@Test
	void costIsRoundedUpToAMicroDollar() {
		assertThat(CallOutcome.costMicros(new ModelReply.Usage(1, 0, 0, 0), PRICE)).isEqualTo(1);
		assertThat(CallOutcome.costMicros(new ModelReply.Usage(1500, 300, 1200, 10), PRICE))
			.isEqualTo(1500 + 1500 + 1500 + 1);
		assertThat(CallOutcome.costMicros(new ModelReply.Usage(0, 0, 0, 0), PRICE)).isZero();
	}

	@Test
	void replyWithoutUsageIsSettledAtTheMaximumCost() {
		Instant now = Instant.now();
		CallOutcome unreadable = CallOutcome.of(new ModelReply.Unreadable(200, null, null), false, PRICE, 9000, now,
				now);
		assertThat(unreadable.costMicros()).isEqualTo(9000);
		assertThat(unreadable.sendSpent()).isTrue();
		CallOutcome throttled = CallOutcome.of(new ModelReply.Answered(429, null, null, null, null, null), false, PRICE,
				9000, now, now);
		assertThat(throttled.costMicros()).isZero();
		assertThat(throttled.sendSpent()).isFalse();
		assertThat(CallOutcome.notSent().status()).isEqualTo(CallStatus.NOT_SENT);
	}

	@Test
	void budgetDayStartsAtKstMidnight() {
		Instant beforeMidnight = Instant.parse("2026-11-20T14:59:59Z");
		assertThat(BudgetDays.of(beforeMidnight)).hasToString("2026-11-20");
		assertThat(BudgetDays.of(beforeMidnight.plusSeconds(1))).hasToString("2026-11-21");
		assertThat(BudgetDays.nextStart(beforeMidnight)).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));
	}

}
