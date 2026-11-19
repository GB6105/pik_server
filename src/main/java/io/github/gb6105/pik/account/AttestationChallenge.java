package io.github.gb6105.pik.account;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Sent as the {@code challenge} member of an {@code ATTESTATION_REQUIRED} response (B6-2.E3·E4).
 *
 * @param keyId the App Attest key expected to sign, when the server knows it
 */
public record AttestationChallenge(String challengeId, String nonce, Instant expiresAt, @Nullable String keyId) {

}
