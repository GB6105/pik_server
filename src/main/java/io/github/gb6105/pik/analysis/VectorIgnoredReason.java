package io.github.gb6105.pik.analysis;

/**
 * Why a client vector was ignored, in check order (design 1.4). The photo stays single; the request goes on.
 */
enum VectorIgnoredReason {

	/** {@code data} is not RFC 4648 base64. */
	MALFORMED_BASE64,

	/** Grouping is on and ({@code model}, {@code modelVersion}) is not registered. */
	UNKNOWN_MODEL,

	/**
	 * The decoded length is not the registered {@code dims × 4} bytes, or with grouping off not a positive multiple
	 * of 4.
	 */
	BAD_DIMENSION,

	/** A value is NaN or infinite, before or after normalization. */
	NOT_FINITE,

	/** The norm is not above {@code grouping.zero-norm-epsilon}. */
	ZERO_NORM

}
