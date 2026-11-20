package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.RequestingDevice;
import io.github.gb6105.pik.analysis.IntakeCommand.PhotoInput;
import io.github.gb6105.pik.analysis.IntakeCommand.VectorInput;
import io.github.gb6105.pik.analysis.IntakeRecords.ExistingResult;
import io.github.gb6105.pik.analysis.IntakeRecords.PhotoLimits;
import io.github.gb6105.pik.photo.PhotoObservations;
import io.github.gb6105.pik.usage.TrialUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The intake work that needs no lock, done before the business transaction so that it holds its locks briefly
 * (design 1.2 step 3): vector checks and the similarities of every valid vector, whether the trial device has a send
 * left, and a preliminary grouping of the photos that 5c would keep as candidates (no result, no live job, no
 * refusal by their budgets), read without locks. The transaction decides again under its locks; the preliminary
 * groups only size the trial start, so a request whose photos all have results starts no trial.
 * <p>
 * The device's sends are read here and not in the transaction: that read loads the device mark into the persistence
 * context, where the reservation's lock on the same mark would not read it again.
 */
@Component
@RequiredArgsConstructor
class IntakePreparation {

	private final VectorValidator vectorValidator;

	private final GroupingRules groupingRules;

	private final PhotoObservations photoObservations;

	private final IntakeRecords records;

	private final TrialUsage trialUsage;

	private final AnalysisProperties properties;

	/**
	 * @param command a request that passed {@link IntakeBodyReader#validate}
	 */
	Prepared prepare(UUID ownerId, RequestingDevice device, IntakeCommand command) {
		boolean grouping = properties.grouping().enabled();
		List<PreparedPhoto> photos = new ArrayList<>(command.photos().size());
		for (PhotoInput input : command.photos()) {
			VectorInput vector = input.vector();
			VectorValidator.Result checked = (vector != null)
					? vectorValidator.check(vector.model(), vector.modelVersion(), vector.data()) : null;
			Instant takenAt = IntakeBodyReader.takenAt(input.takenAt());
			TextStatus textStatus = (input.text() != null) ? input.text().status() : TextStatus.UNKNOWN;
			// With grouping off no vector takes part, so none is compared or kept (design 1.4).
			VectorValidator.Valid valid = (grouping && checked instanceof VectorValidator.Valid v) ? v : null;
			photos.add(new PreparedPhoto(input.contentVersion(), checked,
					(input.text() != null) ? input.text().ocrVersion() : null,
					new GroupingCandidate(input.photoId(), takenAt, textStatus, valid)));
		}
		List<GroupingCandidate> candidates = photos.stream().map(PreparedPhoto::candidate).toList();
		SimilarityMatrix similarities = SimilarityMatrix.of(candidates);
		// Only TRIAL jobs count on the device's sends, and a device without a mark gets no job (decision Q10).
		boolean deviceSendExhausted = device.trialMarkId() != null && trialUsage
			.deviceSendExhausted(device.trialMarkId(), properties.budget().deviceSendMultiplier());
		return new Prepared(ownerId, device.deviceId(), device.trialMarkId(), command.trigger(), photos, similarities,
				deviceSendExhausted, trialStartPhotos(ownerId, device, deviceSendExhausted, candidates, similarities));
	}

	/**
	 * The smallest preliminary group, so that the start is recorded when the trial can cover at least one group; 0 when
	 * nothing is to be reserved. A device without a trial mark reserves nothing (decision Q10), nor does one without a
	 * send left.
	 */
	private int trialStartPhotos(UUID ownerId, RequestingDevice device, boolean deviceSendExhausted,
			List<GroupingCandidate> photos, SimilarityMatrix similarities) {
		if (device.trialMarkId() == null || deviceSendExhausted) {
			return 0;
		}
		Map<UUID, UUID> photoIds = photoObservations.findIds(ownerId,
				photos.stream().map(GroupingCandidate::photoId).toList());
		Map<UUID, ExistingResult> results = records.findResults(ownerId, photoIds.values());
		Map<UUID, List<UUID>> live = records.findLiveJobs(ownerId, photoIds.values());
		Map<UUID, PhotoLimits> budgets = records.findBudgets(ownerId, photoIds.values());
		int photoLifetimeSends = properties.budget().photoLifetimeSends();
		Instant now = Instant.now();
		List<GroupingCandidate> candidates = photos.stream().filter((photo) -> {
			UUID photoId = photoIds.get(photo.photoId());
			return photoId == null || (!results.containsKey(photoId) && !live.containsKey(photoId)
					&& IntakeTransaction.refusal(budgets.get(photoId), photoLifetimeSends, false, now) == null);
		}).toList();
		return groupingRules.current()
			.group(candidates, similarities)
			.stream()
			.mapToInt((group) -> group.photoIds().size())
			.min()
			.orElse(0);
	}

	/**
	 * A request ready for {@link IntakeTransaction}.
	 *
	 * @param photos in request order
	 * @param similarities between the valid vectors of all photos, also those that turn out to be in progress
	 * @param deviceSendExhausted whether the device's trial had no send left, read without a lock (5c
	 * {@code ANALYSIS_UNAVAILABLE}); the permit decides the send under the device lock
	 * @param trialStartPhotos the photos to record the trial start for before the transaction, 0 for none
	 */
	record Prepared(UUID ownerId, UUID deviceId, @Nullable UUID trialMarkId, Trigger trigger,
			List<PreparedPhoto> photos, SimilarityMatrix similarities, boolean deviceSendExhausted,
			int trialStartPhotos) {

		Prepared {
			photos = List.copyOf(photos);
		}

		@Override
		public String toString() {
			return "Prepared[photos=" + photos.size() + ", deviceSendExhausted=" + deviceSendExhausted
					+ ", trialStartPhotos=" + trialStartPhotos + "]";
		}

	}

	/**
	 * @param vector the check result of the photo's vector, {@code null} without one
	 * @param candidate the photo as grouping sees it; its vector is set only when grouping is on and the vector is
	 * valid
	 */
	record PreparedPhoto(String contentVersion, VectorValidator.@Nullable Result vector, @Nullable String ocrVersion,
			GroupingCandidate candidate) {

		UUID clientRef() {
			return candidate.photoId();
		}

		@Nullable VectorIgnoredReason vectorIgnored() {
			return (vector != null) ? vector.ignoredReason() : null;
		}

		@Override
		public String toString() {
			return "PreparedPhoto[hidden]";
		}

	}

}
