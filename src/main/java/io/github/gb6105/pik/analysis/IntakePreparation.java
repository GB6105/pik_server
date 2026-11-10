package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.RequestingDevice;
import io.github.gb6105.pik.analysis.IntakeCommand.PhotoInput;
import io.github.gb6105.pik.analysis.IntakeCommand.VectorInput;
import io.github.gb6105.pik.photo.PhotoObservations;
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
 * (design 1.2 step 3): vector checks and the similarities of every valid vector, and a preliminary grouping of the
 * photos that no live job analyzes, read without locks. The transaction decides again under its locks; the
 * preliminary groups only size the trial start.
 */
@Component
@RequiredArgsConstructor
class IntakePreparation {

	private final VectorValidator vectorValidator;

	private final GroupingRules groupingRules;

	private final PhotoObservations photoObservations;

	private final IntakeRecords records;

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
		return new Prepared(ownerId, device.deviceId(), device.trialMarkId(), command.trigger(), photos, similarities,
				trialStartPhotos(ownerId, device, candidates, similarities));
	}

	/**
	 * The smallest preliminary group, so that the start is recorded when the trial can cover at least one group; 0 when
	 * nothing is to be reserved. A device without a trial mark reserves nothing (decision Q10).
	 */
	private int trialStartPhotos(UUID ownerId, RequestingDevice device, List<GroupingCandidate> photos,
			SimilarityMatrix similarities) {
		if (device.trialMarkId() == null) {
			return 0;
		}
		Map<UUID, UUID> photoIds = photoObservations.findIds(ownerId,
				photos.stream().map(GroupingCandidate::photoId).toList());
		Map<UUID, List<UUID>> live = records.findLiveJobs(ownerId, photoIds.values());
		List<GroupingCandidate> candidates = photos.stream().filter((photo) -> {
			UUID photoId = photoIds.get(photo.photoId());
			return photoId == null || !live.containsKey(photoId);
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
	 * @param trialStartPhotos the photos to record the trial start for before the transaction, 0 for none
	 */
	record Prepared(UUID ownerId, UUID deviceId, @Nullable UUID trialMarkId, Trigger trigger,
			List<PreparedPhoto> photos, SimilarityMatrix similarities, int trialStartPhotos) {

		Prepared {
			photos = List.copyOf(photos);
		}

		@Override
		public String toString() {
			return "Prepared[photos=" + photos.size() + ", trialStartPhotos=" + trialStartPhotos + "]";
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
