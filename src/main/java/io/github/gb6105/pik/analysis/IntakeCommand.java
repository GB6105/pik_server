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

	/**
	 * ASCII from U+0020 (space) to U+007E: {@code vector.model}, {@code vector.modelVersion}, {@code text.ocrVersion}.
	 * Settings take the narrower visible ASCII without space ({@link AnalysisProperties}). Nothing outside ASCII
	 * matches, so the UTF-16 length of a value that matches is its number of characters.
	 */
	static final String ASCII_WITH_SPACE = "[\\x20-\\x7E]*";

	/**
	 * {@code contentVersion}: 1 to 128 code points, as PostgreSQL's {@code char_length} counts them, none of them a
	 * control character ({@code \p{Cc}}, NUL included) or a surrogate without its pair ({@code \p{Cs}}), which UTF-8
	 * cannot hold. The pattern matches by code point, so a surrogate pair counts once.
	 */
	static final String CONTENT_VERSION = "[^\\p{Cc}\\p{Cs}]{1," + MAX_CONTENT_VERSION_LENGTH + "}";

	@Override
	public String toString() {
		return "IntakeCommand[trigger=" + trigger + ", photos=" + ((photos != null) ? photos.size() : 0) + "]";
	}

	/**
	 * @param photoId the client's photo ID ({@code photo.client_ref})
	 * @param contentVersion opaque token of the photo's source version, 1 to 128 code points without a control
	 * character or an unpaired surrogate
	 * @param takenAt RFC 3339 with an offset, kept as sent so that the hash stays stable; parsed by
	 * {@link IntakeBodyReader#takenAt}. Missing = the photo stays single
	 * @param vector the device's image vector, missing = the photo stays single
	 * @param text whether the device found text, missing = {@link TextStatus#UNKNOWN}
	 */
	record PhotoInput(@NotNull UUID photoId,
			@NotNull @Pattern(regexp = CONTENT_VERSION) String contentVersion,
			@Nullable String takenAt,
			@Valid @Nullable VectorInput vector, @Valid @Nullable TextInput text) {

		@Override
		public String toString() {
			return "PhotoInput[hidden]";
		}

	}

	/**
	 * @param model 1 to 64 ASCII characters from U+0020 to U+007E
	 * @param modelVersion 1 to 64 ASCII characters from U+0020 to U+007E
	 * @param data float32 little-endian values in RFC 4648 base64; checked by {@link VectorValidator}
	 */
	record VectorInput(
			@NotNull @Size(min = 1, max = AnalysisProperties.MAX_MODEL_LENGTH) @Pattern(
					regexp = ASCII_WITH_SPACE) String model,
			@NotNull @Size(min = 1, max = AnalysisProperties.MAX_MODEL_LENGTH) @Pattern(
					regexp = ASCII_WITH_SPACE) String modelVersion,
			@NotNull @Size(max = MAX_VECTOR_DATA_LENGTH) String data) {

		@Override
		public String toString() {
			return "VectorInput[model=" + model + ", modelVersion=" + modelVersion + ", data=(hidden)]";
		}

	}

	/**
	 * @param ocrVersion when present, 1 to 64 ASCII characters from U+0020 to U+007E
	 */
	record TextInput(@NotNull TextStatus status,
			@Size(min = 1, max = MAX_OCR_VERSION_LENGTH) @Pattern(regexp = ASCII_WITH_SPACE) @Nullable String ocrVersion) {

	}

}
