package io.github.gb6105.pik.shared.idempotency;

import java.util.UUID;

/**
 * @param ownerId the requesting principal; keys are scoped per owner (ADR-0023)
 * @param operation stable name of the change, part of the request hash
 * @param command the command record; a replay with a different command is {@code IDEMPOTENCY_KEY_REUSED}. It must
 * carry every request input the change depends on (path variables and query parameters too), or a retransmission to
 * another target replays the first response. Fields added later must be nullable so that requests sent before the
 * change keep their hash
 * @param longRunning whether the request accepts a long-running job, as {@link IdempotentCommand#longRunning()};
 * stored error responses then carry {@code jobId = null}. Not part of the request hash
 */
public record IdempotentRequest(UUID ownerId, IdempotencyKey key, String operation, Object command,
		boolean longRunning) {

	/**
	 * A change processed at once, whose {@code jobId} is the key.
	 */
	public IdempotentRequest(UUID ownerId, IdempotencyKey key, String operation, Object command) {
		this(ownerId, key, operation, command, false);
	}

}
