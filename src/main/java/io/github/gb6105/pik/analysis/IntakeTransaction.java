package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.IntakePreparation.Prepared;
import io.github.gb6105.pik.analysis.IntakePreparation.PreparedPhoto;
import io.github.gb6105.pik.analysis.IntakeRecords.GroupRow;
import io.github.gb6105.pik.analysis.IntakeRecords.ItemRow;
import io.github.gb6105.pik.analysis.IntakeRecords.JobRow;
import io.github.gb6105.pik.analysis.IntakeRecords.RequestRow;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.consent.ConsentBasis;
import io.github.gb6105.pik.photo.Observation;
import io.github.gb6105.pik.photo.ObservationBatch;
import io.github.gb6105.pik.photo.ObservedPhoto;
import io.github.gb6105.pik.photo.PhotoObservations;
import io.github.gb6105.pik.photo.VectorToKeep;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.shared.idempotency.Outcome;
import io.github.gb6105.pik.usage.TrialUsage;
import io.github.gb6105.pik.usage.TrialUsage.Reservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * The business transaction of an intake request (design 1.2 step 5, plan 5a to 5g), run by the idempotency executor.
 * It takes its locks in the order of design 0.3: consent (shared), the principal's intake lock, photos in {@code id}
 * order, the live jobs it points at ({@code FOR SHARE}, {@code id} order), the usage allocation and device mark
 * (inside {@link TrialUsage#reserve}), and the change counter last. Its own rows are inserted after the reservation,
 * which the intake lock allows (V5 lock order note).
 * <p>
 * One exception: the jobs that hold photos of an {@link Reservation.AlreadyReserved} answer are not locked before
 * the usage allocation. The items that point at them take the FK {@code KEY SHARE} lock on those jobs after the usage
 * lock. This is safe only because {@code KEY SHARE} does not conflict with {@code FOR NO KEY UPDATE}: a transaction
 * that locks an {@code analysis_job} row before the usage lock (state transitions and the claim of stage 5-2) must not
 * take a lock stronger than {@code FOR NO KEY UPDATE} on it, which rules out {@code FOR UPDATE}, {@code DELETE} and
 * an {@code UPDATE} of {@code id} or {@code group_id}, unless it holds the principal's intake advisory lock first
 * ({@link IntakeLocks}). Job locks are written in native SQL ({@code FOR NO KEY UPDATE [SKIP LOCKED]}), since a JPA
 * lock with {@code SKIP_LOCKED} can emit {@code FOR UPDATE}.
 */
@Component
@RequiredArgsConstructor
class IntakeTransaction {

	private final AiConsents consents;

	private final IntakeLocks locks;

	private final PhotoObservations photoObservations;

	private final IntakeRecords records;

	private final TrialUsage trialUsage;

	private final GroupingRules groupingRules;

	private final CallManifests callManifests;

	private final IntakeProjection projection;

	private final AnalysisProperties properties;

	/**
	 * @return 202 when the request created a job, 200 otherwise, with the response built from the stored rows
	 * @throws PikException {@code FORBIDDEN} / {@code AI_CONSENT_REQUIRED} when the consent is no longer granted, to
	 * be stored for the key
	 * @throws StartRequiredSignal when the trial of an iOS device has to be started first; nothing is stored
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Outcome run(Prepared prepared) {
		UUID ownerId = prepared.ownerId();
		// 5a
		ConsentBasis basis = consents.lockGrantedBasis(ownerId)
			.orElseThrow(() -> new PikException(ErrorCode.FORBIDDEN, "AI_CONSENT_REQUIRED", false));
		// 5b
		locks.lock(ownerId);
		ObservationBatch batch = photoObservations.observe(ownerId, prepared.photos()
			.stream()
			.map((photo) -> new Observation(photo.clientRef(), photo.contentVersion()))
			.toList());
		Map<UUID, ObservedPhoto> observed = new HashMap<>();
		batch.photos().forEach((photo) -> observed.put(photo.clientRef(), photo));

		// 5c, 5c'
		Map<UUID, UUID> inProgress = inProgress(ownerId, prepared, observed);
		Map<UUID, GroupingCandidate> candidates = new LinkedHashMap<>();
		for (PreparedPhoto photo : prepared.photos()) {
			if (!inProgress.containsKey(photo.clientRef())) {
				candidates.put(photo.clientRef(), photo.candidate());
			}
		}
		GroupingRuleV1 rule = groupingRules.current();
		List<FormedGroup> groups = rule.group(List.copyOf(candidates.values()), prepared.similarities());

		// 5d
		Admission admission = admit(ownerId, prepared.trialMarkId(), groups, candidates, observed, rule,
				prepared.similarities());
		inProgress.putAll(admission.inProgress());

		// 5e
		Map<UUID, UUID> vectorIds = photoObservations.keepVectors(ownerId, vectorsToKeep(candidates, observed));
		Instant now = Instant.now();
		UUID requestId = UuidV7.generate();
		Map<UUID, UUID> groupIds = new HashMap<>();
		List<GroupRow> groupRows = new ArrayList<>();
		List<JobRow> jobRows = new ArrayList<>();
		Map<UUID, AdmittedGroup> groupByPhoto = new HashMap<>();
		AnalysisProperties.Intake deadlines = properties.intake();
		for (AdmittedGroup admitted : admission.groups()) {
			FormedGroup group = admitted.group();
			UUID groupId = UuidV7.generate();
			ObservedPhoto representative = observed.get(group.representativeId());
			groupIds.put(group.representativeId(), groupId);
			group.photoIds().forEach((clientRef) -> groupByPhoto.put(clientRef, admitted));
			groupRows.add(new GroupRow(groupId, ownerId, requestId, representative.photoId(), group.photoIds().size(),
					group.modelId(), group.modelVersion(), now));
			if (admitted.jobId() != null) {
				jobRows.add(new JobRow(admitted.jobId(), ownerId, requestId, groupId, representative.photoId(),
						representative.observedVersion(), Funding.TRIAL, callManifests.currentId(),
						prepared.trigger().basePriority(), now, now.plus(deadlines.uploadDeadline()),
						now.plus(deadlines.processingDeadline())));
			}
		}
		List<ItemRow> itemRows = new ArrayList<>();
		for (PreparedPhoto photo : prepared.photos()) {
			itemRows.add(item(requestId, ownerId, photo, observed.get(photo.clientRef()),
					inProgress.get(photo.clientRef()), groupByPhoto.get(photo.clientRef()), groupIds, vectorIds, now));
		}
		records.insertRequest(new RequestRow(requestId, ownerId, prepared.trigger(), prepared.deviceId(),
				prepared.trialMarkId(), basis.version(), groupingRules.currentId(), prepared.photos().size(),
				jobRows.size(), now));
		records.insertGroups(groupRows);
		records.insertJobs(jobRows);
		records.insertItems(itemRows);

		// 5f: the change counter is the last lock.
		photoObservations.issueChangeSeqs(batch);

		// 5g: the executor stores the outcome with the key.
		IntakeResponse response = Objects.requireNonNull(projection.build(requestId), "the request was just stored");
		return new Outcome(jobRows.isEmpty() ? HttpStatus.OK : HttpStatus.ACCEPTED, response);
	}

	/**
	 * Reserves the trial for the groups in order and decides each one (plan 5d). Without a trial mark nothing is
	 * reserved and every group is refused (decision Q10). A group whose photos already hold a reservation leaves those
	 * photos {@code IN_PROGRESS} on the job holding it; its other photos are grouped again and queued in order.
	 * @param candidates client photo ID to the photo as grouping sees it, for every photo of {@code groups}
	 * @param observed client photo ID to the observed row
	 * @throws StartRequiredSignal when the trial has to be started for a group first
	 */
	Admission admit(UUID ownerId, @Nullable UUID trialMarkId, List<FormedGroup> groups,
			Map<UUID, GroupingCandidate> candidates, Map<UUID, ObservedPhoto> observed, GroupingRuleV1 rule,
			SimilarityMatrix similarities) {
		List<AdmittedGroup> admitted = new ArrayList<>();
		Map<UUID, UUID> inProgress = new HashMap<>();
		if (trialMarkId == null) {
			groups.forEach((group) -> admitted.add(new AdmittedGroup(group, null, "TRIAL_UNAVAILABLE", false)));
			return new Admission(admitted, inProgress);
		}
		PriorityQueue<FormedGroup> queue = new PriorityQueue<>(reservationOrder(candidates));
		queue.addAll(groups);
		while (!queue.isEmpty()) {
			FormedGroup group = queue.poll();
			// Issued before the reservation, which records it.
			UUID jobId = UuidV7.generate();
			List<TrialUsage.Photo> photos = group.photoIds().stream().map((clientRef) -> {
				ObservedPhoto photo = observed.get(clientRef);
				return new TrialUsage.Photo(photo.photoId(), photo.observedVersion());
			}).toList();
			switch (trialUsage.reserve(ownerId, trialMarkId, jobId, photos)) {
				case Reservation.Reserved reserved -> admitted.add(new AdmittedGroup(group, jobId, null, false));
				case Reservation.NotCovered notCovered -> admitted
					.add(new AdmittedGroup(group, null, "SUBSCRIPTION_REQUIRED", notCovered.retryable()));
				case Reservation.AlreadyReserved already -> {
					List<GroupingCandidate> rest = new ArrayList<>();
					for (UUID clientRef : group.photoIds()) {
						UUID holder = already.jobsByPhoto().get(observed.get(clientRef).photoId());
						if (holder != null) {
							inProgress.put(clientRef, holder);
						}
						else {
							rest.add(candidates.get(clientRef));
						}
					}
					Assert.state(rest.size() < group.photoIds().size(),
							"An existing reservation must hold a photo of the group");
					queue.addAll(rule.group(rest, similarities));
				}
				case Reservation.StartRequired start -> throw new StartRequiredSignal(photos.size());
			}
		}
		return new Admission(admitted, inProgress);
	}

	/**
	 * Groups by their latest {@code takenAt}, newest first and groups without {@code takenAt} last, then by the
	 * unsigned order of the representative's {@code photoId} (plan 5d).
	 */
	static Comparator<FormedGroup> reservationOrder(Map<UUID, GroupingCandidate> candidates) {
		Comparator<FormedGroup> latestFirst = Comparator.comparing((FormedGroup group) -> latestTakenAt(group, candidates),
				Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
		return latestFirst.thenComparing(FormedGroup::representativeId, GroupingRuleV1.PHOTO_ID_ORDER);
	}

	private static @Nullable Instant latestTakenAt(FormedGroup group, Map<UUID, GroupingCandidate> candidates) {
		Instant latest = null;
		for (UUID clientRef : group.photoIds()) {
			Instant takenAt = candidates.get(clientRef).takenAt();
			if (takenAt != null && (latest == null || takenAt.isAfter(latest))) {
				latest = takenAt;
			}
		}
		return latest;
	}

	/**
	 * Finds the live jobs that analyze the photos (5c), then locks them in {@code id} order and checks them again
	 * (5c'): a job that ended in between gives its photos back as candidates.
	 * @return client photo ID to the live job analyzing it
	 */
	private Map<UUID, UUID> inProgress(UUID ownerId, Prepared prepared, Map<UUID, ObservedPhoto> observed) {
		Map<UUID, List<UUID>> found = records.findLiveJobs(ownerId,
				observed.values().stream().map(ObservedPhoto::photoId).toList());
		Map<UUID, UUID> inProgress = new HashMap<>();
		if (found.isEmpty()) {
			return inProgress;
		}
		Set<UUID> jobIds = new LinkedHashSet<>();
		found.values().forEach(jobIds::addAll);
		Map<UUID, JobStatus> statuses = records.lockJobs(ownerId, jobIds);
		for (PreparedPhoto photo : prepared.photos()) {
			List<UUID> jobs = found.getOrDefault(observed.get(photo.clientRef()).photoId(), List.of());
			jobs.stream()
				.filter((jobId) -> statuses.containsKey(jobId) && statuses.get(jobId).live())
				.min(GroupingRuleV1.PHOTO_ID_ORDER)
				.ifPresent((jobId) -> inProgress.put(photo.clientRef(), jobId));
		}
		return inProgress;
	}

	/**
	 * The valid vectors grouping compared, whatever the decision (decision Q5-a); none while grouping is off.
	 */
	private List<VectorToKeep> vectorsToKeep(Map<UUID, GroupingCandidate> candidates,
			Map<UUID, ObservedPhoto> observed) {
		List<VectorToKeep> vectors = new ArrayList<>();
		candidates.forEach((clientRef, candidate) -> {
			VectorValidator.Valid vector = candidate.vector();
			if (vector != null) {
				vectors.add(new VectorToKeep(observed.get(clientRef).photoId(), vector.model(), vector.modelVersion(),
						vector.dims(), vector.f32le()));
			}
		});
		return vectors;
	}

	private static ItemRow item(UUID requestId, UUID ownerId, PreparedPhoto photo, ObservedPhoto observed,
			@Nullable UUID inProgressJob, @Nullable AdmittedGroup admitted, Map<UUID, UUID> groupIds,
			Map<UUID, UUID> vectorIds, Instant now) {
		AdmissionResult result;
		String code = null;
		String reason = null;
		boolean retryable = false;
		UUID groupId = null;
		GroupRole role = null;
		UUID jobId = null;
		if (inProgressJob != null) {
			result = AdmissionResult.IN_PROGRESS;
			jobId = inProgressJob;
		}
		else {
			Assert.state(admitted != null, "Every candidate photo must be in a group");
			FormedGroup group = admitted.group();
			groupId = groupIds.get(group.representativeId());
			role = group.representativeId().equals(photo.clientRef()) ? GroupRole.REPRESENTATIVE : GroupRole.MEMBER;
			if (admitted.jobId() != null) {
				result = AdmissionResult.ACCEPTED;
				jobId = admitted.jobId();
			}
			else {
				result = AdmissionResult.NOT_ACCEPTED;
				code = ErrorCode.FORBIDDEN.name();
				reason = admitted.reason();
				retryable = admitted.retryable();
			}
		}
		GroupingCandidate candidate = photo.candidate();
		return new ItemRow(requestId, observed.photoId(), ownerId, photo.clientRef(), observed.observedVersion(),
				observed.sourceChangeSeq(), candidate.takenAt(), candidate.textStatus(), photo.ocrVersion(), result,
				code, reason, retryable, groupId, role, jobId, vectorIds.get(observed.photoId()),
				photo.vectorIgnored(), now);
	}

	/**
	 * The decisions of {@link #admit}.
	 *
	 * @param groups the groups that went through the reservation, in the order they did
	 * @param inProgress client photo ID to the job whose reservation holds it
	 */
	record Admission(List<AdmittedGroup> groups, Map<UUID, UUID> inProgress) {

		@Override
		public String toString() {
			return "Admission[groups=" + groups.size() + ", inProgress=" + inProgress.size() + "]";
		}

	}

	/**
	 * @param jobId the job of an accepted group, {@code null} when the group is not accepted
	 * @param reason why the group is not accepted ({@code FORBIDDEN})
	 * @param retryable whether the refused group may be sent again
	 */
	record AdmittedGroup(FormedGroup group, @Nullable UUID jobId, @Nullable String reason, boolean retryable) {

		@Override
		public String toString() {
			return "AdmittedGroup[photos=" + group.photoIds().size() + ", jobId=" + jobId + ", reason=" + reason + "]";
		}

	}

	/**
	 * The trial of the requesting iOS device must be started before a group can be reserved. Not a
	 * {@link PikException}, so the executor rolls the transaction back and stores nothing; {@link AnalysisIntake}
	 * starts the trial and runs the transaction once more (plan 5.1).
	 */
	static final class StartRequiredSignal extends RuntimeException {

		private final int photos;

		StartRequiredSignal(int photos) {
			super("trial start required", null, false, false);
			this.photos = photos;
		}

		/**
		 * @return the size of the group that was blocked
		 */
		int photos() {
			return photos;
		}

	}

}
