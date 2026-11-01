package io.github.gb6105.pik.account;

import java.time.Instant;
import java.util.UUID;

/**
 * A verified first trial start assertion, reused within the HTTP request that carried it (B6-2.E3 step 4).
 */
public record AttestationProof(UUID deviceId, Instant verifiedAt) {

}
