package io.github.gb6105.pik.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.gb6105.pik.consent.DecisionRequest.Choice;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Notice versions and the consent minimum, which the bundled configuration (one notice) cannot reach.
 */
class AiConsentDecisionTests {

	static final ConsentPurpose PURPOSE = ConsentPurpose.AI_ANALYSIS_AND_SEARCH;

	final ConsentRecords records = mock(ConsentRecords.class);

	final UUID principal = UuidV7.generate();

	@Test
	void grantMustBeGivenOnTheCurrentNotice() {
		latest(null);

		assertRejected(() -> consents(2, 1).decide(principal, request(Choice.GRANTED, 1, 0)), ErrorCode.CONFLICT,
				"NOTICE_OUTDATED");
		verify(records, never()).insert(any());
	}

	@Test
	void refusalOnAnOlderNoticeIsRecorded() {
		latest(null);

		ConsentState state = consents(2, 1).decide(principal, request(Choice.DENIED, 1, 0));

		assertThat(state.status()).isEqualTo(ConsentStatus.DENIED);
		assertThat(state.version()).isEqualTo(1);
		assertThat(state.latest().getNoticeVersion()).isEqualTo(1);
		verify(records).insert(state.latest());
	}

	@Test
	void noticeTheServerHasNotServedIsUnknown() {
		latest(null);

		assertRejected(() -> consents(2, 1).decide(principal, request(Choice.DENIED, 3, 0)), ErrorCode.INVALID_INPUT,
				"UNKNOWN_NOTICE_VERSION");
	}

	@Test
	void grantBelowTheMinimumNoLongerCountsUntilGivenAgain() {
		latest(consent(ConsentDecision.GRANTED, 1, 1));
		AiConsents consents = consents(2, 2);

		ConsentState state = consents.state(principal);
		assertThat(state.status()).isEqualTo(ConsentStatus.GRANTED);
		assertThat(state.granted()).isFalse();
		assertThat(state.reconsentRequired()).isTrue();
		assertThat(state.features()).isEqualTo(Feature.BASIC);
		assertThat(consents.isGranted(principal)).isFalse();

		ConsentState regranted = consents.decide(principal, request(Choice.GRANTED, 2, 1));
		assertThat(regranted.version()).isEqualTo(2);
		assertThat(regranted.granted()).isTrue();
		assertThat(regranted.reconsentRequired()).isFalse();
		assertThat(regranted.features()).isEqualTo(Feature.ALL);
	}

	@Test
	void grantAtTheMinimumStillCountsAndCanBeRenewedOnTheCurrentNotice() {
		latest(consent(ConsentDecision.GRANTED, 1, 1));
		AiConsents consents = consents(2, 1);

		assertThat(consents.isGranted(principal)).isTrue();
		assertThat(consents.state(principal).reconsentRequired()).isFalse();
		ConsentState renewed = consents.decide(principal, request(Choice.GRANTED, 2, 1));
		assertThat(renewed.version()).isEqualTo(2);
		assertThat(renewed.latest().getNoticeVersion()).isEqualTo(2);
	}

	@Test
	void refusingAGrantThatNoLongerCountsIsStillAWithdrawal() {
		latest(consent(ConsentDecision.GRANTED, 1, 1));

		ConsentState state = consents(2, 2).decide(principal, request(Choice.DENIED, 2, 1));

		assertThat(state.status()).isEqualTo(ConsentStatus.WITHDRAWN);
	}

	@Test
	void refusingAgainOnANewerNoticeIsRecorded() {
		latest(consent(ConsentDecision.WITHDRAWN, 1, 2));

		ConsentState state = consents(2, 1).decide(principal, request(Choice.DENIED, 2, 2));

		assertThat(state.status()).isEqualTo(ConsentStatus.DENIED);
		assertThat(state.version()).isEqualTo(3);
	}

	@Test
	void versionTakenConcurrentlyIsAConflict() {
		latest(null);
		doThrow(violation("uk_ai_consent_principal_purpose_version")).when(records).insert(any());

		assertRejected(() -> consents(1, 1).decide(principal, request(Choice.GRANTED, 1, 0)), ErrorCode.CONFLICT,
				"VERSION_CONFLICT");
	}

	@Test
	void otherIntegrityViolationsAreNotConflicts() {
		latest(null);
		DataIntegrityViolationException other = violation("ai_consent_pkey");
		doThrow(other).when(records).insert(any());

		assertThatThrownBy(() -> consents(1, 1).decide(principal, request(Choice.GRANTED, 1, 0))).isSameAs(other);
	}

	@Test
	void minimumNoticeStaysWithinServedNotices() {
		assertThatIllegalArgumentException().isThrownBy(() -> new ConsentProperties(1, 2));
		assertThatIllegalArgumentException().isThrownBy(() -> new ConsentProperties(1, 0));
	}

	private AiConsents consents(int noticeVersion, int minimumNoticeVersion) {
		return new AiConsents(records, new ConsentProperties(noticeVersion, minimumNoticeVersion));
	}

	private void latest(@Nullable AiConsent consent) {
		when(records.findLatest(principal, PURPOSE)).thenReturn(Optional.ofNullable(consent));
	}

	private AiConsent consent(ConsentDecision decision, int noticeVersion, long version) {
		return AiConsent.of(principal, PURPOSE, decision, noticeVersion, version, Instant.now());
	}

	private static DecisionRequest request(Choice decision, int noticeVersion, long version) {
		return new DecisionRequest(decision, noticeVersion, version);
	}

	private static DataIntegrityViolationException violation(String constraint) {
		return new DataIntegrityViolationException("violation",
				new ConstraintViolationException("violation", new SQLException("violation"), constraint));
	}

	private static void assertRejected(Runnable action, ErrorCode code, String reason) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(PikException.class, (ex) -> {
			assertThat(ex.getCode()).isEqualTo(code);
			assertThat(ex.getReason()).isEqualTo(reason);
			assertThat(ex.isRetryable()).isFalse();
		});
	}

}
