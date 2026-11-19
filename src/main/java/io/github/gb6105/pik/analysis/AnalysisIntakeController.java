package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.AnalysisIntake.TrialStartHeaders;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentCommand;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Takes the photos a device asks to analyze and answers with the decision on each, their groups and the jobs to
 * upload for (FR-I-01, FR-I-02, design 1.1). A long-running job: 202 when a job was created, 200 otherwise, and error
 * responses carry {@code jobId = null}. The body is read by {@link IntakeBodyReader}, not a message converter.
 */
@RestController
@RequiredArgsConstructor
class AnalysisIntakeController {

	static final String PATH = "/analysis-jobs";

	static final String DEVICE_CHECK_TOKEN = "Device-Check-Token";

	static final String ASSERTION = "App-Attest-Assertion";

	static final String CHALLENGE_ID = "App-Attest-Challenge-Id";

	static final String NONCE = "App-Attest-Nonce";

	private final IntakeBodyReader bodyReader;

	private final AnalysisIntake intake;

	@IdempotentCommand(longRunning = true)
	@PostMapping(PATH)
	ResponseEntity<JsonNode> accept(CurrentPrincipal principal, IdempotencyKey key, HttpServletRequest request) {
		IntakeBodyReader.IntakeBody body = bodyReader.read(request);
		TrialStartHeaders headers = new TrialStartHeaders(request.getHeader(DEVICE_CHECK_TOKEN),
				request.getHeader(ASSERTION), request.getHeader(CHALLENGE_ID), request.getHeader(NONCE));
		return intake.accept(principal, key, body, headers).toResponseEntity();
	}

}
