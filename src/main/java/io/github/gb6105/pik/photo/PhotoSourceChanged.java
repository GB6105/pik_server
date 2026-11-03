package io.github.gb6105.pik.photo;

import java.util.UUID;

/**
 * The observed source version of a photo changed, published in the transaction that raised
 * {@code sourceChangeSeq} (ADR-0031 #4).
 */
public record PhotoSourceChanged(UUID ownerId, UUID photoId, long sourceChangeSeq) {

}
