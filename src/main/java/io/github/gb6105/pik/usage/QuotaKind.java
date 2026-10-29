package io.github.gb6105.pik.usage;

/**
 * Kind of a usage quota. Pro quotas follow with subscriptions (P03, stage 8).
 */
enum QuotaKind {

	TRIAL;

	/** {@code period_key} of the trial, which is given once and never reset (FR-B-01). */
	static final String ONCE = "ONCE";

}
