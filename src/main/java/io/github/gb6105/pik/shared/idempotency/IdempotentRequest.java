package io.github.gb6105.pik.shared.idempotency;

import java.util.UUID;

/**
 * @param ownerId the requesting principal; keys are scoped per owner (ADR-0023)
 * @param operation stable name of the change, part of the request hash
 * @param command the command record; a replay with a different command is {@code IDEMPOTENCY_KEY_REUSED}
 */
public record IdempotentRequest(UUID ownerId, IdempotencyKey key, String operation, Object command) {

}
