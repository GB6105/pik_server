package io.github.gb6105.pik.shared.idempotency;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Client operation identifier from the {@code Idempotency-Key} header, a UUIDv7 (ADR-0023).
 */
public record IdempotencyKey(UUID value) {

	public static final String HEADER = "Idempotency-Key";

	// UUID.fromString accepts shortened groups and upper case, so the canonical form (lower case, as jobId echoes
	// it back) is checked first.
	private static final Pattern UUID_V7 = Pattern
		.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

	static @Nullable IdempotencyKey parse(@Nullable String text) {
		if (text == null || !UUID_V7.matcher(text).matches()) {
			return null;
		}
		return new IdempotencyKey(UUID.fromString(text));
	}

}
