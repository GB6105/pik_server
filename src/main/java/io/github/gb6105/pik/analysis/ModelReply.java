package io.github.gb6105.pik.analysis;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What one {@link AnalysisModel#call} observed, with the settlement table row it falls in (plan 6.4, design 2.5).
 */
sealed interface ModelReply {

	/**
	 * @return the settlement table row of this reply
	 */
	Row row();

	/**
	 * Rows of the settlement table (design 2.5) that a reply falls in; statuses the table does not name follow K-7.
	 */
	enum Row {

		/** 2xx {@code end_turn} whose text is JSON: the validator tells success from {@code INVALID_OUTPUT}. */
		OUTPUT,

		/** 2xx {@code refusal}. */
		REFUSAL,

		/** 2xx {@code max_tokens}; alarms. */
		TRUNCATED,

		/**
		 * 2xx with another stop reason, without text, with text that is not JSON, or whose body is not a readable
		 * message ({@link Unreadable}).
		 */
		INVALID_OUTPUT,

		/** 400, 413, 422 and the other 4xx not named here. */
		PROVIDER_REJECTED,

		/** 402: settled as 400, and opens the configuration circuit with an alarm (K-7). */
		PAYMENT_REQUIRED,

		/** 401, 403, 404: the only failure whose risky sends are released; configuration circuit and alarm. */
		CONFIGURATION_ERROR,

		/** 429, 529: no cost, sends released, back to {@code READY} after {@code Retry-After}. */
		THROTTLED,

		/**
		 * 500, 502, 503, 504, 408, 409 and the other statuses, and failures after the request may have been sent.
		 */
		UNKNOWN,

		/** Failure before a connection to the provider was made. */
		NOT_SENT

	}

	/**
	 * The provider answered with an HTTP status: a readable message (2xx) or an error.
	 *
	 * @param status the HTTP status
	 * @param requestId the {@code request-id} header, {@code null} when missing
	 * @param usage the tokens used; always present for 2xx, present for errors only when their body has them
	 * @param stopReason the {@code stop_reason} of a message, {@code null} for errors
	 * @param output the text of an {@code end_turn} message parsed as JSON, {@code null} for other replies and when
	 * the text is missing or not JSON
	 * @param retryAfter the {@code Retry-After} header of an error, {@code null} when missing or unreadable
	 */
	record Answered(int status, @Nullable String requestId, @Nullable Usage usage, @Nullable String stopReason,
			@Nullable JsonNode output, @Nullable Duration retryAfter) implements ModelReply {

		@Override
		public Row row() {
			if (status >= 200 && status < 300) {
				return switch ((stopReason != null) ? stopReason : "") {
					case "end_turn" -> (output != null) ? Row.OUTPUT : Row.INVALID_OUTPUT;
					case "refusal" -> Row.REFUSAL;
					case "max_tokens" -> Row.TRUNCATED;
					default -> Row.INVALID_OUTPUT;
				};
			}
			return switch (status) {
				case 400, 413, 422 -> Row.PROVIDER_REJECTED;
				case 402 -> Row.PAYMENT_REQUIRED;
				case 401, 403, 404 -> Row.CONFIGURATION_ERROR;
				case 429, 529 -> Row.THROTTLED;
				case 408, 409 -> Row.UNKNOWN;
				default -> (status >= 400 && status < 500) ? Row.PROVIDER_REJECTED : Row.UNKNOWN;
			};
		}

		// The output is the analysis of a photo and must not reach logs.
		@Override
		public String toString() {
			return "Answered[status=" + status + ", requestId=" + requestId + ", usage=" + usage + ", stopReason="
					+ stopReason + ", output=" + ((output != null) ? "(present)" : null) + ", retryAfter="
					+ retryAfter + "]";
		}

	}

	/**
	 * A 2xx whose body is not a readable message envelope. Settled at the actual usage when it could be read,
	 * otherwise at the maximum cost with the risky sends spent (plan 6.4).
	 *
	 * @param status the HTTP status
	 * @param requestId the {@code request-id} header, {@code null} when missing
	 * @param usage the tokens of the {@code usage} block, {@code null} when it could not be read
	 */
	record Unreadable(int status, @Nullable String requestId, @Nullable Usage usage) implements ModelReply {

		@Override
		public Row row() {
			return Row.INVALID_OUTPUT;
		}

	}

	/**
	 * The request failed before a connection to the provider was made (connection refused, no route, unknown host,
	 * TLS handshake), so it was not sent (plan K-6).
	 */
	record NotSent() implements ModelReply {

		@Override
		public Row row() {
			return Row.NOT_SENT;
		}

	}

	/**
	 * The request failed without an HTTP status after it may have been sent: a timeout, a broken connection, a body
	 * that could not be read, or any failure that cannot be placed before the connection (plan K-6).
	 */
	record NoAnswer() implements ModelReply {

		@Override
		public Row row() {
			return Row.UNKNOWN;
		}

	}

	/**
	 * Tokens of the {@code usage} block; cache tokens are 0 when the block has none.
	 */
	record Usage(long inputTokens, long outputTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
	}

}
