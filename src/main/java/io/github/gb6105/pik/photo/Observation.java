package io.github.gb6105.pik.photo;

import java.util.UUID;

/**
 * A photo as a client sees it now.
 *
 * @param clientRef the client's photo ID (API {@code photoId})
 * @param contentVersion opaque token that changes when the photo's source changes (1 to 128 characters)
 */
public record Observation(UUID clientRef, String contentVersion) {

}
