package io.github.gb6105.pik.analysis;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A group formed by grouping rule v1; a single photo is a group of one.
 *
 * @param representativeId the photo analyzed for the group
 * @param photoIds every photo of the group, the representative included, in {@code (takenAt, photoId)} order
 * @param modelId the vector model the group was formed with, {@code null} for a single photo
 * @param modelVersion the version of that model, {@code null} for a single photo
 */
record FormedGroup(UUID representativeId, List<UUID> photoIds, @Nullable String modelId,
		@Nullable String modelVersion) {

	FormedGroup {
		photoIds = List.copyOf(photoIds);
	}

}
