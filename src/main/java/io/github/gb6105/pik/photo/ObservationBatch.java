package io.github.gb6105.pik.photo;

import java.util.List;
import java.util.UUID;

/**
 * The result of {@link PhotoObservations#observe}, to pass to {@link PhotoObservations#issueChangeSeqs} at the end
 * of the same transaction.
 *
 * @param ownerId the principal the photos belong to
 * @param photos the observed photos in input order
 * @param changedPhotoIds photos created or with a raised {@code sourceChangeSeq}, which need a change number
 */
public record ObservationBatch(UUID ownerId, List<ObservedPhoto> photos, List<UUID> changedPhotoIds) {

	public ObservationBatch {
		photos = List.copyOf(photos);
		changedPhotoIds = List.copyOf(changedPhotoIds);
	}

}
