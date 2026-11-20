package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AttemptConfirmation.Confirmation;
import io.github.gb6105.pik.analysis.AttemptConfirmation.Locked;
import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.Outcome;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business transaction of an upload confirmation (design 2.3, plan 4.2), run by the idempotency executor for every
 * branch. Under the locks of {@link AttemptConfirmation#lock} it decides in order: a confirmed attempt answers 200
 * without a change, an attempt that is not current (or a job that ended) is a stored 409, a current attempt without
 * an observation, an absent object and an unavailable storage are unstored, and a stored object goes through the
 * shared confirmation condition. The observation is the HEAD made outside the transaction.
 */
@Component
@RequiredArgsConstructor
class CompletionTransaction {

	private final AttemptConfirmation confirmation;

	private final AnalysisProperties properties;

	/**
	 * @param observed the HEAD result, {@code null} when the attempt was not {@code ISSUED} when it was read
	 * @throws PikException {@code CONFLICT} / {@code ATTEMPT_NOT_CURRENT} (stored), {@code CONFLICT} /
	 * {@code UPLOAD_NOT_FOUND} with {@code Retry-After} and {@code TEMPORARY_FAILURE} / {@code STORAGE_UNAVAILABLE}
	 * (not stored), {@code CONFLICT} / {@code RESERVATION_MISSING} (stored)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Outcome run(UUID key, UUID attemptId, InputStore.@Nullable Head observed) {
		Locked locked = confirmation.lock(attemptId).orElseThrow();
		UUID jobId = locked.job().id();
		if (locked.attempt().status() == AttemptStatus.CONFIRMED) {
			return outcome(key, CommandResult.SUCCEEDED, null, null, false, jobId, attemptId,
					AttemptStatus.CONFIRMED, locked.job().status());
		}
		if (!locked.current()) {
			throw new PikException(ErrorCode.CONFLICT, "ATTEMPT_NOT_CURRENT", false);
		}
		return switch (observed) {
			case null -> throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "STORAGE_UNAVAILABLE", true);
			case InputStore.Head.Absent absent -> throw PikException.unstored(ErrorCode.CONFLICT, "UPLOAD_NOT_FOUND",
					true, properties.upload().notFoundRetryAfter(), Map.of());
			case InputStore.Head.Unavailable unavailable ->
				throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "STORAGE_UNAVAILABLE", true);
			case InputStore.Head.Present present -> switch (confirmation.apply(locked, present)) {
				case Confirmation.Confirmed confirmed -> outcome(key, CommandResult.SUCCEEDED, null, null, false, jobId,
						attemptId, AttemptStatus.CONFIRMED, JobStatus.READY);
				case Confirmation.Rejected rejected -> outcome(key, CommandResult.FAILED,
						UploadRejection.UPLOAD_INVALID.code(), UploadRejection.UPLOAD_INVALID.reason(),
						UploadRejection.UPLOAD_INVALID.retryable(), jobId, attemptId, AttemptStatus.REJECTED,
						JobStatus.AWAITING_UPLOAD);
				case Confirmation.Ended ended -> outcome(key,
						(ended.reason().status() == JobStatus.CANCELLED) ? CommandResult.CANCELLED : CommandResult.FAILED,
						endingCode(ended.reason()), ended.reason().name(), false, jobId, attemptId,
						AttemptStatus.ABANDONED, ended.reason().status());
				case Confirmation.Unconfirmable unconfirmable -> throw new PikException(unconfirmable.reason().code(),
						unconfirmable.reason().reason(), unconfirmable.reason().retryable());
				case Confirmation.NotCurrent notCurrent ->
					throw new IllegalStateException("The attempt was current under the same locks");
			};
		};
	}

	// The error class of the failure codes a confirmation can end a job with.
	private static ErrorCode endingCode(FailureCode reason) {
		return switch (reason) {
			case CONSENT_WITHDRAWN, PRINCIPAL_INACTIVE -> ErrorCode.FORBIDDEN;
			default -> ErrorCode.CONFLICT;
		};
	}

	private static Outcome outcome(UUID key, CommandResult result, @Nullable ErrorCode code, @Nullable String reason,
			boolean retryable, UUID jobId, UUID attemptId, AttemptStatus attemptStatus, JobStatus status) {
		return new Outcome(HttpStatus.OK, new UploadCompleteResponse(key, result, retryable,
				(code != null) ? code.name() : null, reason, jobId, attemptId, attemptStatus, status));
	}

}
