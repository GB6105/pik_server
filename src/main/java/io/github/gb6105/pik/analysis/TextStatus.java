package io.github.gb6105.pik.analysis;

/**
 * Whether the device found text in a photo ({@code text.status} of the request, missing = {@link #UNKNOWN}). Only
 * photos without text take part in grouping (design 1.3).
 */
enum TextStatus {

	UNKNOWN, NO_TEXT, TEXT

}
