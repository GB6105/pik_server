package io.github.gb6105.pik.analysis;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A photo of one request as grouping rule v1 sees it.
 *
 * @param photoId the {@code photoId} of the request
 * @param takenAt when the photo was taken, {@code null} when the request has none
 * @param textStatus whether the device found text
 * @param vector the photo's vector when it passed {@link VectorValidator}, otherwise {@code null}
 */
record GroupingCandidate(UUID photoId, @Nullable Instant takenAt, TextStatus textStatus,
		VectorValidator.@Nullable Valid vector) {

}
