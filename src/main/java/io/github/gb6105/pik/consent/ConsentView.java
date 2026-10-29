package io.github.gb6105.pik.consent;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param version sent back with the next decision
 * @param noticeVersion the notice of the latest decision, {@code null} before the first
 * @param reconsentRequired granted on a notice that no longer counts; show the current notice again
 * @param features available under this consent; trial and subscription are checked separately
 */
record ConsentView(ConsentPurpose purpose, ConsentStatus status, long version, @Nullable Integer noticeVersion,
		@Nullable Instant decidedAt, boolean reconsentRequired, List<Feature> features) {

	static ConsentView of(ConsentState state) {
		AiConsent latest = state.latest();
		return new ConsentView(state.purpose(), state.status(), state.version(),
				(latest != null) ? latest.getNoticeVersion() : null, (latest != null) ? latest.getDecidedAt() : null,
				state.reconsentRequired(), state.features());
	}

}
