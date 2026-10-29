package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One consent decision. Rows are only added; a principal's consent for a purpose is its row with the highest
 * {@code version}.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AiConsent {

	@Id
	private UUID id;

	private UUID principalId;

	@Enumerated(EnumType.STRING)
	private ConsentPurpose purpose;

	@Enumerated(EnumType.STRING)
	private ConsentDecision decision;

	private int noticeVersion;

	/** Data version of the consent after this decision: 1 for the first decision, one more for each next. */
	private long version;

	private Instant decidedAt;

	private AiConsent(UUID principalId, ConsentPurpose purpose, ConsentDecision decision, int noticeVersion,
			long version, Instant now) {
		this.id = UuidV7.generate();
		this.principalId = principalId;
		this.purpose = purpose;
		this.decision = decision;
		this.noticeVersion = noticeVersion;
		this.version = version;
		this.decidedAt = now;
	}

	static AiConsent of(UUID principalId, ConsentPurpose purpose, ConsentDecision decision, int noticeVersion,
			long version, Instant now) {
		return new AiConsent(principalId, purpose, decision, noticeVersion, version, now);
	}

}
