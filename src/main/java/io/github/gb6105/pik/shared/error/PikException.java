package io.github.gb6105.pik.shared.error;

import java.time.Duration;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * Business failure rendered as a problem response. The message carries only code and reason, never request data.
 */
@Getter
public class PikException extends RuntimeException {

	private final ErrorCode code;

	private final String reason;

	private final boolean retryable;

	/** Sent as {@code Retry-After}, required for {@link ErrorCode#RATE_LIMITED}. */
	private final @Nullable Duration retryAfter;

	public PikException(ErrorCode code, String reason, boolean retryable) {
		this(code, reason, retryable, null);
	}

	public PikException(ErrorCode code, String reason, boolean retryable, @Nullable Duration retryAfter) {
		super(code + "/" + reason);
		this.code = code;
		this.reason = reason;
		this.retryable = retryable;
		this.retryAfter = retryAfter;
	}

}
