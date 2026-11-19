package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.IntakeCommand.PhotoInput;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the intake body itself instead of a message converter, so that its size is checked before it is parsed and
 * its bytes can be hashed as sent (design 1.1, B6-2.E5). Failures are the same problem responses as framework errors:
 * {@code INVALID_INPUT} with {@code MEDIA_TYPE_NOT_SUPPORTED}, {@code REQUEST_TOO_LARGE}, {@code MALFORMED_BODY},
 * then, after the idempotency lookup, {@code VALIDATION_FAILED}, {@code DUPLICATE_PHOTO} and {@code TAKEN_AT_INVALID}
 * (plan 4.3). None of them is stored for the key. Only a body that could not be read whole is retryable.
 */
@Component
@RequiredArgsConstructor
class IntakeBodyReader {

	/** RFC 3339 {@code date-time}: seconds and an offset are required. */
	static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder().parseCaseInsensitive()
		.appendValue(ChronoField.YEAR, 4)
		.appendLiteral('-')
		.appendValue(ChronoField.MONTH_OF_YEAR, 2)
		.appendLiteral('-')
		.appendValue(ChronoField.DAY_OF_MONTH, 2)
		.appendLiteral('T')
		.appendValue(ChronoField.HOUR_OF_DAY, 2)
		.appendLiteral(':')
		.appendValue(ChronoField.MINUTE_OF_HOUR, 2)
		.appendLiteral(':')
		.appendValue(ChronoField.SECOND_OF_MINUTE, 2)
		.optionalStart()
		.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
		.optionalEnd()
		.appendOffset("+HH:MM", "Z")
		.toFormatter()
		.withResolverStyle(ResolverStyle.STRICT)
		.withChronology(IsoChronology.INSTANCE);

	private static final MediaType JSON_SUFFIX = new MediaType("application", "*+json");

	private final AnalysisProperties properties;

	private final JsonMapper jsonMapper;

	private final Validator validator;

	/**
	 * Checks the encoding and the size, then parses the body (plan 4.3 #4 to #6).
	 */
	IntakeBody read(HttpServletRequest request) {
		if (!identity(request.getHeaders(HttpHeaders.CONTENT_ENCODING))) {
			throw invalid("MEDIA_TYPE_NOT_SUPPORTED");
		}
		if (!json(request.getContentType())) {
			throw invalid("MEDIA_TYPE_NOT_SUPPORTED");
		}
		int limit = Math.toIntExact(properties.intake().maxBodySize().toBytes());
		if (request.getContentLengthLong() > limit) {
			throw invalid("REQUEST_TOO_LARGE");
		}
		byte[] body;
		try {
			body = request.getInputStream().readNBytes(limit + 1);
		}
		catch (IOException ex) {
			// The body did not arrive whole; nothing was read for the key, so it may be sent again as it is.
			throw new PikException(ErrorCode.INVALID_INPUT, "MALFORMED_BODY", true);
		}
		if (body.length > limit) {
			throw invalid("REQUEST_TOO_LARGE");
		}
		IntakeCommand command;
		try {
			command = jsonMapper.readValue(body, IntakeCommand.class);
		}
		catch (JacksonException ex) {
			throw invalid("MALFORMED_BODY");
		}
		if (command == null) {
			throw invalid("MALFORMED_BODY");
		}
		return new IntakeBody(command, sha256Hex(body));
	}

	/**
	 * Checks the values of a parsed body (plan 4.3 #10 to #12).
	 */
	void validate(IntakeCommand command) {
		if (!validator.validate(command).isEmpty()) {
			throw invalid("VALIDATION_FAILED");
		}
		Set<UUID> photoIds = new HashSet<>();
		for (PhotoInput photo : command.photos()) {
			if (!photoIds.add(photo.photoId())) {
				throw invalid("DUPLICATE_PHOTO");
			}
		}
		for (PhotoInput photo : command.photos()) {
			try {
				takenAt(photo.takenAt());
			}
			catch (DateTimeParseException ex) {
				throw invalid("TAKEN_AT_INVALID");
			}
		}
	}

	/**
	 * @return the instant of an RFC 3339 {@code takenAt} cut to microseconds, the precision it is stored at, so that
	 * the stored items group as the request did; {@code null} when it is missing
	 * @throws DateTimeParseException when it is not RFC 3339 with an offset
	 */
	static @Nullable Instant takenAt(@Nullable String text) {
		return (text != null) ? OffsetDateTime.parse(text, RFC_3339).toInstant().truncatedTo(ChronoUnit.MICROS) : null;
	}

	static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	// Every Content-Encoding header and every coding in it: only identity, or none, leaves the body as sent.
	private static boolean identity(@Nullable Enumeration<String> headers) {
		if (headers == null) {
			return true;
		}
		for (String header : Collections.list(headers)) {
			for (String coding : header.split(",")) {
				if (!coding.isBlank() && !"identity".equalsIgnoreCase(coding.strip())) {
					return false;
				}
			}
		}
		return true;
	}

	// The media types the JSON message converter reads, so that this endpoint accepts what @RequestBody would.
	private static boolean json(@Nullable String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			MediaType type = MediaType.parseMediaType(contentType);
			return MediaType.APPLICATION_JSON.includes(type) || JSON_SUFFIX.includes(type);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	private static PikException invalid(String reason) {
		return new PikException(ErrorCode.INVALID_INPUT, reason, false);
	}

	/**
	 * @param sha256Hex SHA-256 of the body bytes as sent, which a first trial assertion signs (B6-2.E5)
	 */
	record IntakeBody(IntakeCommand command, String sha256Hex) {

	}

}
