package io.github.gb6105.pik.consent;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A principal's consent for a purpose, from its latest decision.
 *
 * @param latest {@code null} before the first decision
 */
record ConsentState(ConsentPurpose purpose, @Nullable AiConsent latest, int minimumNoticeVersion) {

	ConsentStatus status() {
		return (latest != null) ? ConsentStatus.valueOf(latest.getDecision().name()) : ConsentStatus.NONE;
	}

	long version() {
		return (latest != null) ? latest.getVersion() : 0;
	}

	/**
	 * Whether data may be sent now: granted on a notice that still counts.
	 */
	boolean granted() {
		return latest != null && latest.getDecision() == ConsentDecision.GRANTED
				&& latest.getNoticeVersion() >= minimumNoticeVersion;
	}

	/**
	 * Granted on a notice older than the minimum: the current notice must be shown and agreed to again.
	 */
	boolean reconsentRequired() {
		return latest != null && latest.getDecision() == ConsentDecision.GRANTED && !granted();
	}

	List<Feature> features() {
		return granted() ? Feature.ALL : Feature.BASIC;
	}

}
