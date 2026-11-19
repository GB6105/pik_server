package io.github.gb6105.pik.shared.idempotency;

import tools.jackson.databind.JsonNode;

/**
 * Builds the body of a replayed success from the stored one, for responses that report current state, such as a
 * long-running job's progress (consistency-rules.md). Stored errors are replayed as they are.
 */
@FunctionalInterface
public interface ReplayProjection {

	/** Replays the stored body unchanged. */
	ReplayProjection IDENTITY = (status, storedBody) -> storedBody;

	JsonNode project(int status, JsonNode storedBody);

}
