package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentCommand;
import io.github.gb6105.pik.shared.idempotency.IdempotentExecutor;
import io.github.gb6105.pik.shared.idempotency.IdempotentRequest;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The requesting principal's consent with the notice to show, and the user's decision on it (FR-O-02, ADR-0028).
 */
@RestController
@RequiredArgsConstructor
class AiConsentController {

	static final String PATH = "/consents/ai";

	private final AiConsents consents;

	private final AiNotice notice;

	private final IdempotentExecutor executor;

	@GetMapping(PATH)
	ConsentResponse current(CurrentPrincipal principal) {
		return new ConsentResponse(ConsentView.of(consents.state(principal.id())), notice);
	}

	@IdempotentCommand
	@PutMapping(PATH)
	ResponseEntity<JsonNode> decide(CurrentPrincipal principal, IdempotencyKey key,
			@Valid @RequestBody DecisionRequest body) {
		return executor.execute(new IdempotentRequest(principal.id(), key, "consent.ai.decide", body),
				() -> new DecisionResponse(key.value(), CommandResult.SUCCEEDED, false,
						ConsentView.of(consents.decide(principal.id(), body))))
			.toResponseEntity();
	}

}
