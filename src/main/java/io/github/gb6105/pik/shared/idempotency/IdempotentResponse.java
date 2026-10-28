package io.github.gb6105.pik.shared.idempotency;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * A change request response as stored for replays: the same status and body are returned for every retransmission.
 */
public record IdempotentResponse(int status, JsonNode body) {

	public ResponseEntity<JsonNode> toResponseEntity() {
		MediaType type = status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON;
		return ResponseEntity.status(status).contentType(type).body(body);
	}

}
