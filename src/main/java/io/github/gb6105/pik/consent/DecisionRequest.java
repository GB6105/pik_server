package io.github.gb6105.pik.consent;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param noticeVersion the notice the user saw
 * @param version the consent version the client last received, 0 before the first decision
 */
record DecisionRequest(@NotNull Choice decision, @NotNull @Positive Integer noticeVersion,
		@NotNull @PositiveOrZero Long version) {

	enum Choice {

		GRANTED, DENIED

	}

}
