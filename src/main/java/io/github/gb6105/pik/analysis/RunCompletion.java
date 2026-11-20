package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.Principals;
import io.github.gb6105.pik.analysis.JobClaims.ClaimedJob;
import io.github.gb6105.pik.analysis.JobRecords.LockedJob;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.JobTermination.Settlement;
import io.github.gb6105.pik.analysis.ResultValidator.Outcome.Usable;
import io.github.gb6105.pik.analysis.RunRecords.CallRow;
import io.github.gb6105.pik.analysis.RunRecords.Member;
import io.github.gb6105.pik.analysis.RunRecords.NewResult;
import io.github.gb6105.pik.analysis.RunRecords.RunBasis;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.photo.PhotoObservations;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The completion transaction of a valid output (plan 5.6, design 2.4 steps 5 and 6). Locks in the order consent,
 * the photos of the job's own {@code ACCEPTED} items ({@code FOR SHARE}), request, job, attempts, call, usage
 * allocation, daily cost, gates, results, change counter. A call that is no longer {@code SENDING} was settled
 * before and changes nothing; a run that no longer wins the job settles only its call, so that a completion retried
 * after another committed takes this path. A winner whose representative was deleted, whose principal is inactive or
 * whose consent basis no longer holds discards the output and ends the job, the call settled at its usage. Otherwise
 * every member photo that still exists gets its result, {@code DIRECT} for the representative and {@code INHERITED}
 * for the others, the trial consumes their reservations and releases those of deleted members, the request records
 * its first result, the results get their change numbers, and {@link AnalysisCompleted} is published once per result
 * in this transaction. A photo that already has a result rolls everything back with {@link ResultConflict}.
 */
@Component
@RequiredArgsConstructor
class RunCompletion {

	private final AiConsents consents;

	private final PhotoObservations photos;

	private final RunRecords runs;

	private final JobRecords jobs;

	private final UploadRecords attempts;

	private final Principals principals;

	private final CallSettlements calls;

	private final TrialUsage trialUsage;

	private final JobTermination termination;

	private final AiGates gates;

	private final ChangeSequence changes;

	private final ApplicationEventPublisher events;

	private final JsonMapper jsonMapper;

	private final TransactionTemplate transaction;

	/**
	 * Completes the run in a transaction of its own; called outside any transaction.
	 * @throws ResultConflict when a member photo already has a result; nothing changed
	 */
	Completed complete(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call, CallOutcome outcome,
			Usable usable) {
		String content = jsonMapper.writeValueAsString(usable.content());
		return Objects.requireNonNull(
				transaction.execute((status) -> completeLocked(claimed, basis, manifest, call, outcome, usable, content)));
	}

	private Completed completeLocked(ClaimedJob claimed, RunBasis basis, CallManifest manifest, CallRow call,
			CallOutcome outcome, Usable usable, String content) {
		UUID ownerId = basis.ownerId();
		UUID jobId = claimed.jobId();
		UUID runToken = claimed.runToken();
		boolean consentHolds = consents.basisHolds(ownerId, basis.consentVersion());
		List<Member> members = runs.findMembers(basis.requestId(), jobId);
		Set<UUID> existing = photos.holdExisting(ownerId, members.stream().map(Member::photoId).toList());
		Instant firstResultAt = runs.lockRequest(basis.requestId());
		LockedJob job = jobs.lock(jobId).orElseThrow();
		Instant now = Instant.now();
		if (runs.callStatus(call.id()) != CallStatus.SENDING) {
			return Completed.SETTLED_BEFORE;
		}
		if (!job.wonBy(runToken, now)) {
			calls.settle(call, outcome, false, now);
			return Completed.LOST;
		}
		FailureCode discarded = !existing.contains(basis.photoId()) ? FailureCode.REPRESENTATIVE_DELETED
				: !principals.isActive(ownerId) ? FailureCode.PRINCIPAL_INACTIVE
						: !consentHolds ? FailureCode.CONSENT_WITHDRAWN : null;
		if (discarded != null) {
			termination.end(jobId, new JobEnding(discarded, runToken, false, calls.of(call, outcome, false, now)));
			return Completed.DISCARDED;
		}
		jobs.succeed(jobId, now);
		attempts.abandonAll(jobId, now);
		Settlement settlement = calls.of(call, outcome, false, now);
		settlement.settleCall();
		List<Member> kept = members.stream().filter((member) -> existing.contains(member.photoId())).toList();
		if (basis.funding() == Funding.TRIAL) {
			Set<UUID> consume = new HashSet<>();
			Set<UUID> release = new HashSet<>();
			members.forEach((member) -> (existing.contains(member.photoId()) ? consume : release).add(member.photoId()));
			trialUsage.settle(ownerId, jobId, consume, release);
		}
		settlement.settleBudgets();
		gates.releaseProbe(jobId, runToken, now);
		List<UUID> resultIds = new ArrayList<>(kept.size());
		for (Member member : kept) {
			UUID resultId = UUID.randomUUID();
			AnalysisResult.Verification verification = member.photoId().equals(basis.photoId())
					? AnalysisResult.Verification.DIRECT : AnalysisResult.Verification.INHERITED;
			if (!runs.insertResult(new NewResult(resultId, ownerId, member.photoId(), jobId, verification,
					member.photoVersion(), member.basisChangeSeq(), basis.manifestId(),
					manifest.settings().schemaVersion(), manifest.settings().outputLocale(),
					AnalysisResult.SearchState.valueOf(usable.searchState().name()), content), now)) {
				throw new ResultConflict();
			}
			resultIds.add(resultId);
		}
		if (firstResultAt == null) {
			runs.markFirstResult(basis.requestId(), now);
		}
		long first = changes.nextBlock(ownerId, resultIds.size());
		for (int i = 0; i < resultIds.size(); i++) {
			runs.issueChangeSeq(resultIds.get(i), first + i);
			events.publishEvent(new AnalysisCompleted(ownerId, kept.get(i).photoId(), resultIds.get(i), 1));
		}
		return Completed.STORED;
	}

	/**
	 * What the completion did, for the worker's counts.
	 */
	enum Completed {

		STORED, DISCARDED, LOST, SETTLED_BEFORE

	}

	/**
	 * A member photo already had a result (plan K-21): the completion rolled back, and the call is settled as
	 * {@code RESULT_CONFLICT}.
	 */
	static final class ResultConflict extends RuntimeException {

		ResultConflict() {
			super("A photo of the job already has a result", null, false, false);
		}

	}

}
