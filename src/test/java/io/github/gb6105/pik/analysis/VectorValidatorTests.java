package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * V-1 to V-7: the vector checks of design 1.4 and decision Q6.
 */
class VectorValidatorTests {

	static final String MODEL = "pik-embed";

	static final String VERSION = "1";

	static final VectorValidator ON = new VectorValidator(grouping(true, 1e-6));

	static final VectorValidator OFF = new VectorValidator(grouping(false, 1e-6));

	@ParameterizedTest
	@ValueSource(strings = { "not base64", "A", "AAAAA", "AAAA====", "AA=A", "=AAA", "AAA-", "AAA_", "AAAA\nAAAA",
			"ÿÿÿÿ" })
	void v1MalformedBase64IsTheFirstCheck(String data) {
		assertThat(ON.check(MODEL, VERSION, data).ignoredReason()).isEqualTo(VectorIgnoredReason.MALFORMED_BASE64);
		assertThat(ON.check("unregistered", VERSION, data).ignoredReason())
			.isEqualTo(VectorIgnoredReason.MALFORMED_BASE64);
		assertThat(OFF.check(MODEL, VERSION, data).ignoredReason()).isEqualTo(VectorIgnoredReason.MALFORMED_BASE64);
	}

	@Test
	void v2UnregisteredModelOrVersionComesBeforeLengthAndValues() {
		assertThat(ON.check("unregistered", VERSION, base64(1, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.UNKNOWN_MODEL);
		assertThat(ON.check(MODEL, "2", base64(1, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.UNKNOWN_MODEL);
		assertThat(ON.check(MODEL.toUpperCase(), VERSION, base64(1, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.UNKNOWN_MODEL);
		assertThat(ON.check("unregistered", VERSION, base64(Float.NaN)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.UNKNOWN_MODEL);
		assertThat(ON.check("unregistered", VERSION, base64(0, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.UNKNOWN_MODEL);
	}

	@Test
	void v3LengthMustBeTheRegisteredDimsTimesFourComingBeforeValues() {
		assertThat(ON.check(MODEL, VERSION, base64(1, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, base64(1, 0, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, encode(bytes(17))).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, encode(bytes(15))).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, "").ignoredReason()).isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, base64(Float.NaN, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		assertThat(ON.check(MODEL, VERSION, base64(0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
	}

	@Test
	void v4NanAndInfinityAreRejectedBeforeTheNorm() {
		assertThat(ON.check(MODEL, VERSION, base64(1, Float.NaN, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		assertThat(ON.check(MODEL, VERSION, base64(1, 0, Float.POSITIVE_INFINITY, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		assertThat(ON.check(MODEL, VERSION, base64(1, 0, 0, Float.NEGATIVE_INFINITY)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		// NaN payloads other than the canonical one, as raw bits.
		assertThat(ON.check(MODEL, VERSION, encode(intBits(0x3f800000, 0x7f800001, 0, 0))).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		assertThat(ON.check(MODEL, VERSION, encode(intBits(0x3f800000, 0xffffffff, 0, 0))).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		assertThat(ON.check(MODEL, VERSION, base64(0, 0, Float.NaN, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
	}

	@Test
	void v5NormMustBeAboveEpsilon() {
		assertThat(ON.check(MODEL, VERSION, base64(0, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(ON.check(MODEL, VERSION, base64(-0f, -0f, -0f, -0f)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(ON.check(MODEL, VERSION, base64(1e-7f, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(ON.check(MODEL, VERSION, base64(Float.MIN_VALUE, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(ON.check(MODEL, VERSION, base64(1e-5f, 0, 0, 0)).ignoredReason()).isNull();

		// 0.25 and 0.5 are exact in float and double: a norm equal to ε is ignored, a larger one is not.
		VectorValidator quarter = new VectorValidator(grouping(true, 0.25));
		assertThat(quarter.check(MODEL, VERSION, base64(0.25f, 0, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(quarter.check(MODEL, VERSION, base64(0, -0.25f, 0, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.ZERO_NORM);
		assertThat(quarter.check(MODEL, VERSION, base64(0.5f, 0, 0, 0)).ignoredReason()).isNull();
	}

	/**
	 * The finite check after normalization cannot fail for finite values, since no value is larger than the norm and
	 * the sum of squares of 2,250 float32 values cannot overflow a double. These are the extremes it has to pass.
	 */
	@Test
	void v6NormalizedExtremeValuesStayFinite() {
		VectorValidator wide = new VectorValidator(new AnalysisProperties.Grouping(true, Duration.ofSeconds(120), 20,
				Double.MIN_VALUE, List.of(new AnalysisProperties.Model(MODEL, VERSION, AnalysisProperties.MAX_DIMS,
						0.9))));
		float[] largest = new float[AnalysisProperties.MAX_DIMS];
		Arrays.fill(largest, Float.MAX_VALUE);
		largest[1] = -Float.MAX_VALUE;
		float[] smallest = new float[AnalysisProperties.MAX_DIMS];
		smallest[7] = Float.MIN_VALUE;

		VectorValidator.Valid large = (VectorValidator.Valid) wide.check(MODEL, VERSION, base64(largest));
		VectorValidator.Valid small = (VectorValidator.Valid) wide.check(MODEL, VERSION, base64(smallest));

		double expected = 1 / Math.sqrt(AnalysisProperties.MAX_DIMS);
		assertThat(Arrays.stream(large.unit()).map(Math::abs))
			.allSatisfy((value) -> assertThat(value).isCloseTo(expected, within(1e-15)));
		assertThat(large.unit()[1]).isNegative();
		assertThat(small.unit()[7]).isEqualTo(1.0);
		assertThat(Arrays.stream(small.unit()).filter((value) -> value != 0)).hasSize(1);
	}

	@Test
	void validVectorKeepsTheReceivedBytesAndItsDoubleNormalization() {
		byte[] received = floats(3, 0, -4, 0);

		VectorValidator.Result result = ON.check(MODEL, VERSION, encode(received));

		assertThat(result.ignoredReason()).isNull();
		VectorValidator.Valid valid = (VectorValidator.Valid) result;
		assertThat(valid.model()).isEqualTo(MODEL);
		assertThat(valid.modelVersion()).isEqualTo(VERSION);
		assertThat(valid.dims()).isEqualTo(4);
		assertThat(valid.f32le()).isEqualTo(received);
		assertThat(valid.unit()).containsExactly(0.6, 0.0, -0.8, 0.0);
		assertThat(valid.toString()).doesNotContain("0.6");
	}

	@Test
	void validVectorsWithTheSameContentAreEqual() {
		VectorValidator.Result valid = ON.check(MODEL, VERSION, base64(3, 0, -4, 0));
		VectorValidator.Result same = ON.check(MODEL, VERSION, base64(3, 0, -4, 0));

		assertThat(valid).isEqualTo(same).hasSameHashCodeAs(same).isNotSameAs(same);
		assertThat(valid).isNotEqualTo(ON.check(MODEL, VERSION, base64(3, 0, 4, 0)))
			.isNotEqualTo(OFF.check("other", VERSION, base64(3, 0, -4, 0)))
			.isNotEqualTo(OFF.check(MODEL, "2", base64(3, 0, -4, 0)));
		assertThat(valid.toString()).isEqualTo("Valid[model=pik-embed, modelVersion=1, dims=4]");
	}

	@Test
	void missingArgumentsAreRequestErrorsAndFailFast() {
		String data = base64(1, 0, 0, 0);

		assertThatNullPointerException().isThrownBy(() -> ON.check(null, VERSION, data))
			.withMessage("vector model must not be null");
		assertThatNullPointerException().isThrownBy(() -> ON.check(MODEL, null, data))
			.withMessage("vector modelVersion must not be null");
		assertThatNullPointerException().isThrownBy(() -> ON.check(MODEL, VERSION, null))
			.withMessage("vector data must not be null");
		assertThatNullPointerException().isThrownBy(() -> OFF.check(null, VERSION, data))
			.withMessage("vector model must not be null");
	}

	@Test
	void v7GroupingOffChecksOnlyTheStructure() {
		assertThat(OFF.check(MODEL, VERSION, "not base64").ignoredReason())
			.isEqualTo(VectorIgnoredReason.MALFORMED_BASE64);
		// Decoded lengths 0, 1, 3 and 5 bytes.
		for (String data : List.of("", "AA==", "AAAA", "AAAAAAA=")) {
			assertThat(OFF.check(MODEL, VERSION, data).ignoredReason()).isEqualTo(VectorIgnoredReason.BAD_DIMENSION);
		}
		assertThat(OFF.check(MODEL, VERSION, base64(Float.NaN, 0)).ignoredReason())
			.isEqualTo(VectorIgnoredReason.NOT_FINITE);
		assertThat(OFF.check(MODEL, VERSION, base64(0, 0)).ignoredReason()).isEqualTo(VectorIgnoredReason.ZERO_NORM);

		// Neither the model nor the registered dims are checked.
		assertThat(OFF.check("unregistered", "9", base64(1)).ignoredReason()).isNull();
		assertThat(OFF.check(MODEL, VERSION, base64(1, 2, 3)).ignoredReason()).isNull();
		assertThat(new VectorValidator(new AnalysisProperties.Grouping(false, Duration.ofSeconds(120), 20, 1e-6,
				List.of()))
			.check("any", "any", base64(1, 2))
			.ignoredReason()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "=", "==", "====", "A", "AB", "ABC", "AB==", "A===", "\u0000", "\uD800", "가나다라",
			"AAAA\r\n", " AAAA", "%%%%", "////", "++++", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
	void decodeProblemsNeverThrow(String data) {
		assertThatNoException().isThrownBy(() -> ON.check(MODEL, VERSION, data));
		assertThatNoException().isThrownBy(() -> OFF.check(MODEL, VERSION, data));
	}

	static AnalysisProperties.Grouping grouping(boolean enabled, double zeroNormEpsilon) {
		return new AnalysisProperties.Grouping(enabled, Duration.ofSeconds(120), 20, zeroNormEpsilon,
				List.of(new AnalysisProperties.Model(MODEL, VERSION, 4, 0.9)));
	}

	static String base64(float... values) {
		return encode(floats(values));
	}

	static String encode(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	static byte[] floats(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return buffer.array();
	}

	static byte[] intBits(int... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (int value : values) {
			buffer.putInt(value);
		}
		return buffer.array();
	}

	static byte[] bytes(int length) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, (byte) 0x3f);
		return bytes;
	}

}
