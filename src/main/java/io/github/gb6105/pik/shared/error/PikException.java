package io.github.gb6105.pik.shared.error;

import lombok.Getter;

/**
 * Business failure rendered as a problem response. The message carries only code and reason, never request data.
 */
@Getter
public class PikException extends RuntimeException {

	private final ErrorCode code;

	private final String reason;

	private final boolean retryable;

	public PikException(ErrorCode code, String reason, boolean retryable) {
		super(code + "/" + reason);
		this.code = code;
		this.reason = reason;
		this.retryable = retryable;
	}

}
