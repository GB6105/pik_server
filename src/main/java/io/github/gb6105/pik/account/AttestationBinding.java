package io.github.gb6105.pik.account;

import java.util.UUID;

/**
 * What a first trial start assertion is bound to besides the device, so that it cannot be replayed for another
 * request (B6-2.E4·E5).
 *
 * @param bodySha256Hex SHA-256 of the request body bytes as sent, apart from the idempotency hash
 * @param deviceCheckTokenSha256Hex SHA-256 of the {@code Device-Check-Token} header
 */
public record AttestationBinding(UUID idempotencyKey, String bodySha256Hex, String deviceCheckTokenSha256Hex) {

}
