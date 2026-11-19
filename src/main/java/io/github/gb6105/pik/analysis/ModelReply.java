package io.github.gb6105.pik.analysis;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What one {@link AnalysisModel#call} observed, before the settlement table classifies it (plan 6.4, design 2.5).
 */
sealed interface ModelReply {

	/**
	 * The provider answered with an HTTP status: a message (2xx) or an error.
	 *
	 * @param status the HTTP status
	 * @param requestId the {@code request-id} header, {@code null} when missing
	 * @param usage the tokens used, {@code null} when the answer has none (errors) or its body could not be read
	 * @param stopReason the {@code stop_reason} of a message, {@code null} for errors and unreadable bodies
	 * @param text the text of the message, {@code null} when it has no text block
	 * @param retryAfter the {@code Retry-After} header (429, 529), {@code null} when missing
	 */
	record Answered(int status, @Nullable String requestId, @Nullable Usage usage, @Nullable String stopReason,
			@Nullable String text, @Nullable Duration retryAfter) implements ModelReply {

		// The text is the analysis of a photo and must not reach logs.
		@Override
		public String toString() {
			return "Answered[status=" + status + ", requestId=" + requestId + ", usage=" + usage + ", stopReason="
					+ stopReason + ", textLength=" + ((text != null) ? text.length() : null) + ", retryAfter="
					+ retryAfter + "]";
		}

	}

	/**
	 * The request failed before a connection to the provider was made (connection refused, unknown host, TLS
	 * handshake), so it was not sent (plan K-6).
	 */
	record NotSent() implements ModelReply {
	}

	/**
	 * The request failed without an HTTP status after it may have been sent: a timeout or a broken connection, or any
	 * failure that cannot be placed before the connection (plan K-6).
	 */
	record NoAnswer() implements ModelReply {
	}

	/**
	 * Tokens of the {@code usage} block.
	 */
	record Usage(long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
	}

}
