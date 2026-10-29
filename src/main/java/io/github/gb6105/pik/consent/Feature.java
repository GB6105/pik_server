package io.github.gb6105.pik.consent;

import java.util.List;

/**
 * Features reported with the consent state. Refusing keeps the basic ones (FR-O-02, ADR-0016); the others also depend
 * on trial and subscription, which the consent response does not check.
 */
enum Feature {

	MANUAL_ORGANIZE, MEMO, TAG, ATTRIBUTE_SEARCH, PHOTO_ANALYSIS, NATURAL_LANGUAGE_SEARCH;

	static final List<Feature> BASIC = List.of(MANUAL_ORGANIZE, MEMO, TAG, ATTRIBUTE_SEARCH);

	static final List<Feature> ALL = List.of(values());

}
