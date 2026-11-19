package io.github.gb6105.pik.analysis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Checks a client vector ({@code vector.model}, {@code vector.modelVersion}, {@code vector.data} as float32
 * little-endian base64) in the order of design 1.4 and reports the first failed check. A failed check never throws:
 * only that vector is ignored and its photo stays single.
 * <ul>
 * <li>Grouping on: base64 → registered model → {@code dims × 4} bytes → finite → norm → finite after
 * normalization.</li>
 * <li>Grouping off: base64 → positive multiple of 4 bytes → finite → norm → finite after normalization. Model
 * and dimension are not checked, since nothing is registered (design 1.4, decision Q6).</li>
 * </ul>
 * The norm and the normalized values are computed in double from the float32 values, summing in index order, so
 * that the similarities can be reproduced (plan 6.5).
 */
final class VectorValidator {

	private final AnalysisProperties.Grouping settings;

	VectorValidator(AnalysisProperties.Grouping settings) {
		this.settings = settings;
	}

	/**
	 * Checks one vector. The arguments must not be {@code null}: a missing value is a request error, not an ignored
	 * vector. Their length limits ({@code model} and {@code modelVersion} 1 to 64 characters, {@code data} at most
	 * 12,000) are checked by the intake request validation before this (plan 4.2, P-1, step 6).
	 * @param model the {@code vector.model} of the request
	 * @param modelVersion the {@code vector.modelVersion} of the request
	 * @param data the {@code vector.data} of the request
	 * @return the vector, or the reason of the first failed check
	 * @throws NullPointerException if an argument is {@code null}
	 */
	Result check(String model, String modelVersion, String data) {
		Objects.requireNonNull(model, "vector model must not be null");
		Objects.requireNonNull(modelVersion, "vector modelVersion must not be null");
		Objects.requireNonNull(data, "vector data must not be null");
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(data);
		}
		catch (IllegalArgumentException ex) {
			return new Ignored(VectorIgnoredReason.MALFORMED_BASE64);
		}
		if (settings.enabled()) {
			AnalysisProperties.Model registered = settings.registered(model, modelVersion);
			if (registered == null) {
				return new Ignored(VectorIgnoredReason.UNKNOWN_MODEL);
			}
			if (bytes.length != registered.dims() * Float.BYTES) {
				return new Ignored(VectorIgnoredReason.BAD_DIMENSION);
			}
		}
		else if (bytes.length == 0 || bytes.length % Float.BYTES != 0) {
			return new Ignored(VectorIgnoredReason.BAD_DIMENSION);
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		double[] values = new double[bytes.length / Float.BYTES];
		for (int i = 0; i < values.length; i++) {
			float value = buffer.getFloat(i * Float.BYTES);
			if (!Float.isFinite(value)) {
				return new Ignored(VectorIgnoredReason.NOT_FINITE);
			}
			values[i] = value;
		}
		double sumOfSquares = 0;
		for (double value : values) {
			sumOfSquares += value * value;
		}
		double norm = Math.sqrt(sumOfSquares);
		if (!(norm > settings.zeroNormEpsilon())) {
			return new Ignored(VectorIgnoredReason.ZERO_NORM);
		}
		double[] unit = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			unit[i] = values[i] / norm;
			// Unreachable for finite values (|value| <= norm); kept as the last check of design 1.4.
			if (!Double.isFinite(unit[i])) {
				return new Ignored(VectorIgnoredReason.NOT_FINITE);
			}
		}
		return new Valid(model, modelVersion, bytes, unit);
	}

	sealed interface Result permits Valid, Ignored {

		/**
		 * @return why the vector was ignored, {@code null} when it is valid
		 */
		@Nullable VectorIgnoredReason ignoredReason();

	}

	/**
	 * A vector that passed every check.
	 *
	 * @param f32le the received float32 little-endian bytes, to keep as they are
	 * @param unit the vector normalized in double
	 */
	record Valid(String model, String modelVersion, byte[] f32le, double[] unit) implements Result {

		Valid {
			f32le = f32le.clone();
			unit = unit.clone();
		}

		int dims() {
			return unit.length;
		}

		@Override
		public byte[] f32le() {
			return f32le.clone();
		}

		@Override
		public double[] unit() {
			return unit.clone();
		}

		@Override
		public @Nullable VectorIgnoredReason ignoredReason() {
			return null;
		}

		// By content: the generated methods would compare the arrays by identity.
		@Override
		public boolean equals(@Nullable Object other) {
			return this == other || (other instanceof Valid valid && model.equals(valid.model)
					&& modelVersion.equals(valid.modelVersion) && Arrays.equals(f32le, valid.f32le)
					&& Arrays.equals(unit, valid.unit));
		}

		@Override
		public int hashCode() {
			return Objects.hash(model, modelVersion, Arrays.hashCode(f32le), Arrays.hashCode(unit));
		}

		// The vector is client data and too long for messages.
		@Override
		public String toString() {
			return "Valid[model=" + model + ", modelVersion=" + modelVersion + ", dims=" + unit.length + "]";
		}

	}

	record Ignored(VectorIgnoredReason reason) implements Result {

		@Override
		public VectorIgnoredReason ignoredReason() {
			return reason;
		}

	}

}
