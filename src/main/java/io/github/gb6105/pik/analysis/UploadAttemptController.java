package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentCommand;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Issues presigned upload attempts for the jobs of an intake request and confirms their uploads (FR-I-03, design
 * 2.3). Both are changes processed at once: error responses carry the key as {@code jobId}.
 */
@RestController
@RequiredArgsConstructor
class UploadAttemptController {

	static final String ATTEMPTS = AnalysisIntakeController.PATH + "/{requestId}/upload-attempts";

	static final String COMPLETE = ATTEMPTS + "/{attemptId}/complete";

	private final UploadAttempts uploads;

	@IdempotentCommand
	@PostMapping(ATTEMPTS)
	ResponseEntity<JsonNode> issue(CurrentPrincipal principal, IdempotencyKey key, @PathVariable UUID requestId,
			@RequestBody UploadAttemptRequest body) {
		return uploads.issue(principal, key, requestId, body).toResponseEntity();
	}

	@IdempotentCommand
	@PostMapping(COMPLETE)
	ResponseEntity<JsonNode> complete(CurrentPrincipal principal, IdempotencyKey key, @PathVariable UUID requestId,
			@PathVariable UUID attemptId) {
		return uploads.complete(principal, key, requestId, attemptId).toResponseEntity();
	}

}
