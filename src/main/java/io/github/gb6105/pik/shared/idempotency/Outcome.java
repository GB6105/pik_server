package io.github.gb6105.pik.shared.idempotency;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

/**
 * A successful change request result for {@link IdempotentExecutor#commit}, such as 202 for an accepted long-running
 * job. Failures are thrown as {@link io.github.gb6105.pik.shared.error.PikException} instead.
 */
public record Outcome(HttpStatus status, @Nullable Object body) {

	public Outcome {
		Assert.isTrue(status.is2xxSuccessful(), "Outcome must be a success");
	}

}
