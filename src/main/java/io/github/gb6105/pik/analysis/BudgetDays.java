package io.github.gb6105.pik.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The KST days the daily budgets count in (design 2.7): {@code ai_spend_day.day}, {@code ai_send_daily.day} and
 * {@code external_call.budget_day}.
 */
final class BudgetDays {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private BudgetDays() {
	}

	static LocalDate of(Instant instant) {
		return LocalDate.ofInstant(instant, KST);
	}

	/**
	 * The start of the KST day after the one of {@code instant}.
	 */
	static Instant nextStart(Instant instant) {
		return of(instant).plusDays(1).atStartOfDay(KST).toInstant();
	}

}
