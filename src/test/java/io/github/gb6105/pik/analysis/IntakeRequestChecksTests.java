package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.analysis.IntakeBodyReader.IntakeBody;
import io.github.gb6105.pik.analysis.IntakeCommand.PhotoInput;
import io.github.gb6105.pik.analysis.IntakeCommand.TextInput;
import io.github.gb6105.pik.analysis.IntakeCommand.VectorInput;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import jakarta.servlet.ServletInputStream;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-1: the request checks of plan 4.3 (#4 to #6 and #10 to #12) without a server.
 */
class IntakeRequestChecksTests {

	static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

	static final AnalysisProperties PROPERTIES = new AnalysisProperties(true,
			new AnalysisProperties.Intake(Duration.ofHours(24), Duration.ofHours(48), DataSize.ofBytes(1024)),
			new AnalysisProperties.Grouping(false, Duration.ofSeconds(120), 20, 1e-6, List.of()),
			new AnalysisProperties.Manifest("claude-haiku-5-5", "image/jpeg", 2_097_152, 1568));

	final IntakeBodyReader reader = new IntakeBodyReader(PROPERTIES, JsonMapper.builder().build(),
			VALIDATION.getValidator());

	@AfterAll
	static void close() {
		VALIDATION.close();
	}

	@Test
	void oneToOneHundredPhotos() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(List.of())));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(photos(101))));
		assertThatNoException().isThrownBy(() -> reader.validate(command(photos(1))));
		assertThatNoException().isThrownBy(() -> reader.validate(command(photos(100))));
	}

	@Test
	void triggerAndPhotoListAreRequired() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(new IntakeCommand(null, photos(1))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(new IntakeCommand(Trigger.NEW_PHOTOS, null)));
		assertRejected("VALIDATION_FAILED",
				() -> reader.validate(command(Arrays.asList(photo("v1"), null))));
		assertRejected("VALIDATION_FAILED", () -> reader
			.validate(command(List.of(new PhotoInput(null, "v1", null, null, null)))));
	}

	@Test
	void contentVersionHasOneTo128Characters() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(List.of(photo("")))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(List.of(photo("v".repeat(129))))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(List.of(photo(null)))));
		assertThatNoException().isThrownBy(() -> reader.validate(command(List.of(photo("v".repeat(128))))));
	}

	@Test
	void vectorModelHasOneTo64CharactersAndDataAtMost12000() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput("m".repeat(65), "1", ""))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput("", "1", ""))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput("m", "1".repeat(65), ""))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput("m", "1", null))));
		assertRejected("VALIDATION_FAILED",
				() -> reader.validate(withVector(new VectorInput("m", "1", "A".repeat(12_001)))));
		assertThatNoException().isThrownBy(
				() -> reader.validate(withVector(new VectorInput("m".repeat(64), "1".repeat(64), "A".repeat(12_000)))));
	}

	@Test
	void textNeedsItsStatus() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(
				command(List.of(new PhotoInput(UUID.randomUUID(), "v1", null, null, new TextInput(null, "ocr-1"))))));
		assertThatNoException().isThrownBy(() -> reader.validate(command(List.of(
				new PhotoInput(UUID.randomUUID(), "v1", null, null, new TextInput(TextStatus.NO_TEXT, null))))));
	}

	@Test
	void samePhotoIdTwiceIsRejectedAfterTheValueChecks() {
		UUID photoId = UUID.randomUUID();
		PhotoInput photo = new PhotoInput(photoId, "v1", null, null, null);

		assertRejected("DUPLICATE_PHOTO", () -> reader.validate(command(List.of(photo, photo))));
		assertRejected("VALIDATION_FAILED",
				() -> reader.validate(command(List.of(photo, new PhotoInput(photoId, "", null, null, null)))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-11-10T10:00:00", "2026-11-10", "2026-11-10T10:00+09:00", "2026-11-10 10:00:00Z",
			"2026-11-10T10:00:00+0900", "2026-02-30T10:00:00Z", "yesterday", "" })
	void takenAtMustBeRfc3339WithAnOffset(String takenAt) {
		assertRejected("TAKEN_AT_INVALID",
				() -> reader.validate(command(List.of(new PhotoInput(UUID.randomUUID(), "v1", takenAt, null, null)))));
	}

	@Test
	void takenAtDuplicatesAreReportedFirst() {
		UUID photoId = UUID.randomUUID();
		PhotoInput photo = new PhotoInput(photoId, "v1", "yesterday", null, null);

		assertRejected("DUPLICATE_PHOTO", () -> reader.validate(command(List.of(photo, photo))));
	}

	@Test
	void rfc3339TakenAtIsParsedToItsInstant() {
		assertThat(IntakeBodyReader.takenAt("2026-11-10T19:00:00+09:00")).isEqualTo(Instant.parse("2026-11-10T10:00:00Z"));
		assertThat(IntakeBodyReader.takenAt("2026-11-10t10:00:00.123456789z"))
			.isEqualTo(Instant.parse("2026-11-10T10:00:00.123456Z"));
		assertThat(IntakeBodyReader.takenAt("2026-11-10T10:00:00-00:00")).isEqualTo(Instant.parse("2026-11-10T10:00:00Z"));
		assertThat(IntakeBodyReader.takenAt(null)).isNull();
	}

	// Cut, not rounded, to the stored microseconds: 120 s and 900 ns apart stays inside a 120 s window.
	@Test
	void takenAtIsCutToMicroseconds() {
		Instant first = IntakeBodyReader.takenAt("2026-11-10T10:00:00Z");
		Instant last = IntakeBodyReader.takenAt("2026-11-10T10:02:00.0000009Z");

		assertThat(last).isEqualTo(Instant.parse("2026-11-10T10:02:00Z"));
		assertThat(Duration.between(first, last)).isEqualTo(Duration.ofSeconds(120));
		assertThat(IntakeBodyReader.takenAt("2026-11-10T10:02:00.0000019Z"))
			.isEqualTo(Instant.parse("2026-11-10T10:02:00.000001Z"));
		assertThat(IntakeBodyReader.takenAt("1969-12-31T23:59:59.9999999Z"))
			.isEqualTo(Instant.parse("1969-12-31T23:59:59.999999Z"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "v\0", "v\n1", "\u007f", "v\u00851" })
	void contentVersionHasNoControlCharacter(String version) {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(command(List.of(photo(version)))));
	}

	@Test
	void contentVersionMayHaveAnyOtherCharacter() {
		assertThatNoException()
			.isThrownBy(() -> reader.validate(command(List.of(photo("\ubc84\uc804 1 \u00e9 \ud83d\udcf7")))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "ocr-\u00e9", "ocr\t1", "ocr\u00001" })
	void ocrVersionHasOneTo64PrintableAsciiCharacters(String ocrVersion) {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withOcrVersion(ocrVersion)));
	}

	@Test
	void ocrVersionAtMost64() {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withOcrVersion("o".repeat(65))));
		assertThatNoException().isThrownBy(() -> reader.validate(withOcrVersion("o".repeat(64))));
		assertThatNoException().isThrownBy(() -> reader.validate(withOcrVersion(" !~ocr 1")));
		assertThatNoException().isThrownBy(() -> reader.validate(withOcrVersion(null)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pik\u0000embed", "pik\tembed", "pik-\u00e9", "pik\u007f" })
	void vectorModelAndVersionArePrintableAscii(String value) {
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput(value, "1", ""))));
		assertRejected("VALIDATION_FAILED", () -> reader.validate(withVector(new VectorInput("m", value, ""))));
	}

	@Test
	void vectorModelMayHaveSpaces() {
		assertThatNoException()
			.isThrownBy(() -> reader.validate(withVector(new VectorInput(" pik embed ~", "1 0", ""))));
	}

	@Test
	void compressedBodyIsRefused() {
		MockHttpServletRequest request = request("{}");
		request.addHeader("Content-Encoding", "gzip");

		assertRejected("MEDIA_TYPE_NOT_SUPPORTED", () -> reader.read(request));
	}

	@Test
	void everyContentEncodingIsChecked() {
		MockHttpServletRequest twoHeaders = request(validBody());
		twoHeaders.addHeader("Content-Encoding", "identity");
		twoHeaders.addHeader("Content-Encoding", "gzip");
		MockHttpServletRequest twoCodings = request(validBody());
		twoCodings.addHeader("Content-Encoding", "identity, br");

		assertRejected("MEDIA_TYPE_NOT_SUPPORTED", () -> reader.read(twoHeaders));
		assertRejected("MEDIA_TYPE_NOT_SUPPORTED", () -> reader.read(twoCodings));
	}

	@Test
	void unreadableBodyStreamIsMalformed() {
		MockHttpServletRequest broken = new MockHttpServletRequest("POST", "/analysis-jobs") {

			@Override
			public ServletInputStream getInputStream() {
				return new DelegatingServletInputStream(new InputStream() {

					@Override
					public int read() throws IOException {
						throw new IOException("connection reset");
					}

				});
			}

		};
		broken.setContentType("application/json");

		assertRejected("MALFORMED_BODY", () -> reader.read(broken));
	}

	@Test
	void identityEncodingIsAccepted() {
		MockHttpServletRequest request = request(validBody());
		request.addHeader("Content-Encoding", "identity");

		assertThat(reader.read(request).command().photos()).hasSize(1);
	}

	@Test
	void bodyMustBeJson() {
		MockHttpServletRequest request = request(validBody());
		request.setContentType("text/plain");

		assertRejected("MEDIA_TYPE_NOT_SUPPORTED", () -> reader.read(request));
	}

	@Test
	void bodyAboveTheLimitIsRefusedBeforeParsing() {
		byte[] padded = validBody().replace("}]}", "}], \"pad\": \"" + "x".repeat(1024) + "\"}")
			.getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest withLength = request(padded);
		// Chunked: the limit applies to the bytes read.
		MockHttpServletRequest withoutLength = new MockHttpServletRequest("POST", "/analysis-jobs") {

			@Override
			public long getContentLengthLong() {
				return -1;
			}

		};
		withoutLength.setContentType("application/json");
		withoutLength.setContent(padded);

		assertRejected("REQUEST_TOO_LARGE", () -> reader.read(withLength));
		assertRejected("REQUEST_TOO_LARGE", () -> reader.read(withoutLength));
	}

	@Test
	void bodyAtTheLimitIsRead() {
		String body = validBody();
		String exact = body.substring(0, body.length() - 1) + " ".repeat(1024 - body.length()) + "}";

		IntakeBody read = reader.read(request(exact));

		assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(1024);
		assertThat(read.sha256Hex()).isEqualTo(IntakeBodyReader.sha256Hex(exact.getBytes(StandardCharsets.UTF_8)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "null", "{", "[]", "{\"trigger\": \"LATER\", \"photos\": []}",
			"{\"trigger\": \"NEW_PHOTOS\", \"photos\": [{\"photoId\": \"not-a-uuid\", \"contentVersion\": \"v1\"}]}" })
	void unreadableBodyIsMalformed(String body) {
		assertRejected("MALFORMED_BODY", () -> reader.read(request(body)));
	}

	private static String validBody() {
		return "{\"trigger\": \"NEW_PHOTOS\", \"photos\": [{\"photoId\": \"" + UUID.randomUUID()
				+ "\", \"contentVersion\": \"v1\"}]}";
	}

	private static MockHttpServletRequest request(String body) {
		return request(body.getBytes(StandardCharsets.UTF_8));
	}

	private static MockHttpServletRequest request(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analysis-jobs");
		request.setContentType("application/json");
		request.setContent(body);
		return request;
	}

	private static IntakeCommand command(List<PhotoInput> photos) {
		return new IntakeCommand(Trigger.NEW_PHOTOS, photos);
	}

	private static IntakeCommand withVector(VectorInput vector) {
		return command(List.of(new PhotoInput(UUID.randomUUID(), "v1", null, vector, null)));
	}

	private static IntakeCommand withOcrVersion(String ocrVersion) {
		return command(List.of(new PhotoInput(UUID.randomUUID(), "v1", null, null,
				new TextInput(TextStatus.TEXT, ocrVersion))));
	}

	private static List<PhotoInput> photos(int count) {
		return IntStream.range(0, count).mapToObj((i) -> photo("v1")).toList();
	}

	private static PhotoInput photo(String version) {
		return new PhotoInput(UUID.randomUUID(), version, "2026-11-10T10:00:00Z", null, null);
	}

	private static void assertRejected(String reason, Executable check) {
		assertThatThrownBy(check::execute).isInstanceOfSatisfying(PikException.class, (ex) -> {
			assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_INPUT);
			assertThat(ex.getReason()).isEqualTo(reason);
			assertThat(ex.isRetryable()).isFalse();
		});
	}

}
