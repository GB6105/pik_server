package io.github.gb6105.pik.analysis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Grouping rule v1 (design 1.3, P05), a pure function of its settings and the photos of one request.
 * <ul>
 * <li>Eligible: grouping on, a valid vector of a registered (model, modelVersion), {@code takenAt} and
 * {@code text.status = NO_TEXT}. Every other photo is single.</li>
 * <li>In {@code (takenAt, photoId)} order each eligible photo joins, among the groups of its model where its cosine
 * similarity to every member is at least θ, the time span stays within W and the size is below the maximum, the group
 * with the largest minimum similarity; ties go to the group made first. Without such a group it starts one.</li>
 * <li>Representative: the largest mean similarity to the other members, then the earliest {@code takenAt}, then the
 * smallest {@code photoId}.</li>
 * </ul>
 * {@code photoId} order is unsigned 128-bit, the order of the lowercase canonical strings and of PostgreSQL
 * {@code uuid}; {@link UUID#compareTo} compares signed values and is not used (plan 6.5).
 *
 * @param settings the grouping settings the rule runs with
 */
record GroupingRuleV1(AnalysisProperties.Grouping settings) {

	static final Comparator<UUID> PHOTO_ID_ORDER = (photoId, other) -> {
		int most = Long.compareUnsigned(photoId.getMostSignificantBits(), other.getMostSignificantBits());
		return (most != 0) ? most : Long.compareUnsigned(photoId.getLeastSignificantBits(),
				other.getLeastSignificantBits());
	};

	private static final Comparator<GroupingCandidate> PHOTO_ORDER = Comparator
		.comparing(GroupingCandidate::takenAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
		.thenComparing(GroupingCandidate::photoId, PHOTO_ID_ORDER);

	/**
	 * @param photos the photos to group, in any order
	 * @param similarities similarities covering at least the photos with a valid vector
	 * @return every photo in exactly one group. Groups come in the order they were made, walking the photos in
	 * {@code (takenAt, photoId)} order with photos without {@code takenAt} last
	 */
	List<FormedGroup> group(List<GroupingCandidate> photos, SimilarityMatrix similarities) {
		Set<UUID> photoIds = new HashSet<>();
		photos.forEach((photo) -> Assert.isTrue(photoIds.add(photo.photoId()), "photo IDs must be unique"));
		List<GroupingCandidate> ordered = photos.stream().sorted(PHOTO_ORDER).toList();
		List<Forming> groups = new ArrayList<>();
		for (GroupingCandidate photo : ordered) {
			AnalysisProperties.Model model = eligibleModel(photo);
			if (model == null) {
				groups.add(new Forming(photo, null));
				continue;
			}
			Forming chosen = null;
			double chosenMinimum = Double.NEGATIVE_INFINITY;
			for (Forming group : groups) {
				Double minimum = minimumSimilarityIfJoinable(group, photo, model, similarities);
				// Strictly larger, so that ties keep the group made first.
				if (minimum != null && (chosen == null || minimum > chosenMinimum)) {
					chosen = group;
					chosenMinimum = minimum;
				}
			}
			if (chosen != null) {
				chosen.members.add(photo);
			}
			else {
				groups.add(new Forming(photo, model));
			}
		}
		return groups.stream().map((group) -> group.form(similarities)).toList();
	}

	private AnalysisProperties.@Nullable Model eligibleModel(GroupingCandidate photo) {
		VectorValidator.Valid vector = photo.vector();
		if (!settings.enabled() || vector == null || photo.takenAt() == null
				|| photo.textStatus() != TextStatus.NO_TEXT) {
			return null;
		}
		AnalysisProperties.Model model = settings.registered(vector.model(), vector.modelVersion());
		return (model != null && model.dims() == vector.dims()) ? model : null;
	}

	/**
	 * @return the smallest similarity of the photo to the members, or {@code null} when it may not join the group
	 */
	private @Nullable Double minimumSimilarityIfJoinable(Forming group, GroupingCandidate photo,
			AnalysisProperties.Model model, SimilarityMatrix similarities) {
		if (!model.equals(group.model) || group.members.size() >= settings.maxGroupSize()) {
			return null;
		}
		// Photos come in takenAt order, so the first member is the earliest and the photo the latest.
		Duration span = Duration.between(group.members.getFirst().takenAt(), photo.takenAt());
		if (span.compareTo(settings.window()) > 0) {
			return null;
		}
		double minimum = Double.POSITIVE_INFINITY;
		for (GroupingCandidate member : group.members) {
			double similarity = similarities.between(photo.photoId(), member.photoId());
			if (!(similarity >= model.threshold())) {
				return null;
			}
			minimum = Math.min(minimum, similarity);
		}
		return minimum;
	}

	/**
	 * A group while photos join it. {@code model} is {@code null} for a photo that is not eligible, which no other
	 * photo may join.
	 */
	private static final class Forming {

		private final List<GroupingCandidate> members = new ArrayList<>();

		private final AnalysisProperties.@Nullable Model model;

		private Forming(GroupingCandidate first, AnalysisProperties.@Nullable Model model) {
			this.members.add(first);
			this.model = model;
		}

		private FormedGroup form(SimilarityMatrix similarities) {
			List<UUID> photoIds = members.stream().map(GroupingCandidate::photoId).toList();
			if (members.size() == 1) {
				return new FormedGroup(photoIds.getFirst(), photoIds, null, null);
			}
			// Members are in (takenAt, photoId) order, so keeping the first of equal means applies both tie-breaks.
			UUID representative = null;
			double largestMean = Double.NEGATIVE_INFINITY;
			for (GroupingCandidate member : members) {
				double sum = 0;
				for (GroupingCandidate other : members) {
					if (other != member) {
						sum += similarities.between(member.photoId(), other.photoId());
					}
				}
				double mean = sum / (members.size() - 1);
				if (representative == null || mean > largestMean) {
					representative = member.photoId();
					largestMean = mean;
				}
			}
			return new FormedGroup(representative, photoIds, model.model(), model.modelVersion());
		}

	}

}
