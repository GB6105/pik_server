package io.github.gb6105.pik.consent;

/**
 * What a consent covers. The first release asks once for photo analysis and search terms together; records keep the
 * purpose so that it can be split later (ADR-0021 #6).
 */
enum ConsentPurpose {

	AI_ANALYSIS_AND_SEARCH

}
