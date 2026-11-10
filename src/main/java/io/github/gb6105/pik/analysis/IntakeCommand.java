package io.github.gb6105.pik.analysis;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code POST /analysis-jobs} (FR-I-01, design 1.1) and the command of its idempotency hash. One-time
 * evidence ({@code Device-Check-Token}, App Attest headers) stays out of it (ADR-0030 #4). The limits are request
 * contract values (plan 1). Its records carry photo IDs, {@code contentVersion} values and vectors, so none of them
 * shows its values in {@code toString}.
 *
 * @param photos 1 to 100 photos, each {@code photoId} once
 */
record IntakeCommand(@NotNull Trigger trigger,
		@NotNull @Size(min = 1, max = MAX_PHOTOS) List<@NotNull @Valid PhotoInput> photos) {

	static final int MAX_PHOTOS = 100;

	static final int MAX_CONTENT_VERSION_LENGTH = 128;

	static final int MAX_VECTOR_DATA_LENGTH = 12_000;

	static final int MAX_OCR_VERSION_LENGTH = 64;

	/** U+0020 to U+007E only: {@code vector.model}, {@code vector.modelVersion}, {@code text.ocrVersion}. */
	static final String PRINTABLE_ASCII = "[\\x20-\\x7E]*";

	/** No control character ({@code \p{Cc}}, NUL included): {@code contentVersion}. */
	static final String NO_CONTROL_CHARACTER = "\\P{Cc}*";

	@Override
	public String toString() {
		return "IntakeCommand[trigger=" + trigger + ", photos=" + ((photos != null) ? photos.size() : 0) + "]";
	}

	/**
	 * @param photoId the client's photo ID ({@code photo.client_ref})
	 * @param contentVersion opaque token of the photo's source version, 1 to 128 characters without a control
	 * character
	 * @param takenAt RFC 3339 with an offset, kept as sent so that the hash stays stable; parsed by
	 * {@link IntakeBodyReader#takenAt}. Missing = the photo stays single
	 * @param vector the device's image vector, missing = the photo stays single
	 * @param text whether the device found text, missing = {@link TextStatus#UNKNOWN}
	 */
	record PhotoInput(@NotNull UUID photoId,
			@NotNull @Size(min = 1, max = MAX_CONTENT_VERSION_LENGTH) @Pattern(
					regexp = NO_CONTROL_CHARACTER) String contentVersion,
			@Nullable String takenAt,
			@Valid @Nullable VectorInput vector, @Valid @Nullable TextInput text) {

		@Override
		public String toString() {
			return "PhotoInput[hidden]";
		}

	}

	/**
	 * @param model 1 to 64 printable ASCII characters
	 * @param modelVersion 1 to 64 printable ASCII characters
	 * @param data float32 little-endian values in RFC 4648 base64; checked by {@link VectorValidator}
	 */
	record VectorInput(
			@NotNull @Size(min = 1, max = AnalysisProperties.MAX_MODEL_LENGTH) @Pattern(
					regexp = PRINTABLE_ASCII) String model,
			@NotNull @Size(min = 1, max = AnalysisProperties.MAX_MODEL_LENGTH) @Pattern(
					regexp = PRINTABLE_ASCII) String modelVersion,
			@NotNull @Size(max = MAX_VECTOR_DATA_LENGTH) String data) {

		@Override
		public String toString() {
			return "VectorInput[model=" + model + ", modelVersion=" + modelVersion + ", data=(hidden)]";
		}

	}

	/**
	 * @param ocrVersion when present, 1 to 64 printable ASCII characters
	 */
	record TextInput(@NotNull TextStatus status,
			@Size(min = 1, max = MAX_OCR_VERSION_LENGTH) @Pattern(regexp = PRINTABLE_ASCII) @Nullable String ocrVersion) {

	}

}
