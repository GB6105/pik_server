package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.consent.DecisionRequest.Choice;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent to external transfers (FR-O-02). Other modules check {@link #isGranted} right before sending data and
 * before storing a response, so a withdrawal stops pending work without an event (ADR-0021 #5, ADR-0028 #5).
 */
@Service
@RequiredArgsConstructor
public class AiConsents {

	private static final ConsentPurpose PURPOSE = ConsentPurpose.AI_ANALYSIS_AND_SEARCH;

	private static final String VERSION_CONSTRAINT = "uk_ai_consent_principal_purpose_version";

	private final ConsentRecords records;

	private final ConsentProperties properties;

	/**
	 * Whether photos and search terms of the principal may be sent to external services now.
	 */
	@Transactional(readOnly = true)
	public boolean isGranted(UUID principalId) {
		return state(principalId).granted();
	}

	@Transactional(readOnly = true)
	ConsentState state(UUID principalId) {
		return new ConsentState(PURPOSE, records.findLatest(principalId, PURPOSE).orElse(null),
				properties.minimumNoticeVersion());
	}

	/**
	 * Records the decision unless it repeats the latest one on the same notice. The request must carry the current
	 * version, so a delayed retransmission cannot overwrite a newer decision (ADR-0028 #2). A grant must be given on
	 * the current notice; a refusal is recorded on any notice the server has served, since it sends nothing.
	 */
	@Transactional
	ConsentState decide(UUID principalId, DecisionRequest request) {
		ConsentState current = state(principalId);
		if (request.version() != current.version()) {
			throw conflict("VERSION_CONFLICT");
		}
		int noticeVersion = request.noticeVersion();
		if (noticeVersion > properties.noticeVersion()) {
			throw new PikException(ErrorCode.INVALID_INPUT, "UNKNOWN_NOTICE_VERSION", false);
		}
		boolean grant = request.decision() == Choice.GRANTED;
		if (grant && noticeVersion != properties.noticeVersion()) {
			throw conflict("NOTICE_OUTDATED");
		}
		AiConsent latest = current.latest();
		boolean wasGranted = latest != null && latest.getDecision() == ConsentDecision.GRANTED;
		if (latest != null && grant == wasGranted && latest.getNoticeVersion() == noticeVersion) {
			return current;
		}
		ConsentDecision decision = grant ? ConsentDecision.GRANTED
				: wasGranted ? ConsentDecision.WITHDRAWN : ConsentDecision.DENIED;
		AiConsent next = AiConsent.of(principalId, PURPOSE, decision, noticeVersion, current.version() + 1,
				Instant.now());
		try {
			records.insert(next);
		}
		catch (DataIntegrityViolationException ex) {
			// Another decision took this version after it was read.
			if (ex.getCause() instanceof ConstraintViolationException violation
					&& VERSION_CONSTRAINT.equals(violation.getConstraintName())) {
				throw conflict("VERSION_CONFLICT");
			}
			throw ex;
		}
		return new ConsentState(PURPOSE, next, properties.minimumNoticeVersion());
	}

	private static PikException conflict(String reason) {
		return new PikException(ErrorCode.CONFLICT, reason, false);
	}

}
