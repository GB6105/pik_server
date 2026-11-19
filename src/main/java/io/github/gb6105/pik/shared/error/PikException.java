package io.github.gb6105.pik.shared.error;

import java.time.Duration;
import java.util.Map;
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

	/**
	 * Whether a change request may store the failure for replays when it is otherwise final. {@code false} for
	 * failures that the same key must be able to get past, such as missing first trial evidence (ADR-0032 #6-2).
	 */
	private final boolean storable;

	/** Members added to the problem body besides the standard ones, such as {@code challenge}. */
	private final Map<String, Object> properties;

	public PikException(ErrorCode code, String reason, boolean retryable) {
		this(code, reason, retryable, null);
	}

	public PikException(ErrorCode code, String reason, boolean retryable, @Nullable Duration retryAfter) {
		this(code, reason, retryable, retryAfter, Map.of());
	}

	public PikException(ErrorCode code, String reason, boolean retryable, @Nullable Duration retryAfter,
			Map<String, ?> properties) {
		this(code, reason, retryable, retryAfter, true, properties);
	}

	private PikException(ErrorCode code, String reason, boolean retryable, @Nullable Duration retryAfter,
			boolean storable, Map<String, ?> properties) {
		super(code + "/" + reason);
		if (code == ErrorCode.RATE_LIMITED && retryAfter == null) {
			throw new IllegalArgumentException("RATE_LIMITED requires retryAfter");
		}
		this.code = code;
		this.reason = reason;
		this.retryable = retryable;
		this.retryAfter = retryAfter;
		this.storable = storable;
		this.properties = Map.copyOf(properties);
	}

	/**
	 * A failure that change requests never store, whatever its status.
	 */
	public static PikException unstored(ErrorCode code, String reason, boolean retryable) {
		return unstored(code, reason, retryable, null, Map.of());
	}

	/**
	 * A failure that change requests never store, whatever its status.
	 */
	public static PikException unstored(ErrorCode code, String reason, boolean retryable,
			@Nullable Duration retryAfter, Map<String, ?> properties) {
		return new PikException(code, reason, retryable, retryAfter, false, properties);
	}

}
