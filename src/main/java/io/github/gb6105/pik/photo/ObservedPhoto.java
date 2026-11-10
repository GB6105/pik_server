package io.github.gb6105.pik.photo;

import java.util.UUID;

/**
 * A photo row after an observation.
 *
 * @param photoId the server photo ID that other tables keep as {@code photo_id}
 * @param clientRef the client's photo ID
 * @param observedVersion the last observed {@code contentVersion}
 * @param sourceChangeSeq how many times the observed version changed
 */
public record ObservedPhoto(UUID photoId, UUID clientRef, String observedVersion, long sourceChangeSeq) {

}
