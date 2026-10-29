package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.consent.DecisionRequest.Choice;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
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
	 * Records the decision and moves the version, also when it repeats the latest one: the request must carry the
	 * current version, so any request sent before an accepted decision is rejected and a delayed grant cannot
	 * overwrite a later refusal (ADR-0028 #2·10). A grant must be given on the current notice; a refusal is recorded
	 * on any notice the server has served, since it sends nothing.
	 */
	@Transactional
	ConsentState decide(UUID principalId, UUID deviceId, DecisionRequest request) {
		ConsentState current = state(principalId);
		if (request.version() != current.version()) {
			throw conflict("VERSION_CONFLICT");
		}
		int noticeVersion = request.noticeVersion();
		// During a rolling deployment the client may have read a newer notice from another instance; it reads the
		// notice again and asks with a new key.
		if (noticeVersion > properties.noticeVersion()) {
			throw conflict("UNKNOWN_NOTICE_VERSION");
		}
		boolean grant = request.decision() == Choice.GRANTED;
		if (grant && noticeVersion != properties.noticeVersion()) {
			throw conflict("NOTICE_OUTDATED");
		}
		AiConsent latest = current.latest();
		boolean wasGranted = latest != null && latest.getDecision() == ConsentDecision.GRANTED;
		ConsentDecision decision = grant ? ConsentDecision.GRANTED
				: wasGranted ? ConsentDecision.WITHDRAWN : ConsentDecision.DENIED;
		AiConsent next = AiConsent.of(principalId, deviceId, PURPOSE, decision, noticeVersion,
				current.version() + 1, Instant.now());
		try {
			records.insert(next);
		}
		catch (DataIntegrityViolationException ex) {
			// Another decision took this version after it was read. The kind comes from the SQL state; the
			// constraint name is parsed from the message and missing when the server does not report in English.
			// The only other unique key is the UUIDv7 primary key.
			if (ex.getCause() instanceof ConstraintViolationException violation
					&& violation.getKind() == ConstraintKind.UNIQUE) {
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
