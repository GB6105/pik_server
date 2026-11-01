package io.github.gb6105.pik.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.AuthenticatedMockMvc;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.id.UuidV7;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Failures that are never stored and problem members carried by {@link PikException}.
 */
@Import({ TestcontainersConfiguration.class, AuthenticatedMockMvc.class,
		IdempotentExecutorStageTests.StageProbeController.class })
@SpringBootTest
@AutoConfigureMockMvc
class IdempotentExecutorStageTests {

	static final Map<String, Object> CHALLENGE = Map.of("challenge", Map.of("challengeId", "probe"));

	@Autowired
	MockMvcTester mvc;

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ChangeSequence changes;

	final UUID owner = UUID.randomUUID();

	@Test
	void unstoredClientErrorRollsBackAndIsRethrown() {
		UUID key = UuidV7.generate();
		PikException failure = PikException.unstored(ErrorCode.FORBIDDEN, "PROBE_FAILED", true);

		assertThatThrownBy(() -> executor.execute(request(key), () -> {
			changes.next(owner);
			throw failure;
		})).isSameAs(failure);
		assertThat(failure.isStorable()).isFalse();
		assertThat(storedStatus(key)).isEmpty();
		assertThat(lastSeq()).isEmpty();
	}

	@Test
	void storedErrorKeepsItsPropertiesForReplays() {
		UUID key = UuidV7.generate();
		String expected = """
				{"status": 403, "code": "FORBIDDEN", "reason": "PROBE_FAILED", "jobId": "%s", "result": "FAILED",
				 "challenge": {"challengeId": "probe"}}
				""".formatted(key);

		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"stored\"}")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
		assertThat(storedStatus(key)).hasValue(403);
		assertThat(post(key, "{\"name\": \"a\", \"fail\": \"stored\"}")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo(expected);
	}

	@Test
	void unstoredErrorIsRenderedWithItsPropertiesAndRetryAfter() {
		UUID key = UuidV7.generate();

		MvcTestResult result = post(key, "{\"name\": \"a\", \"fail\": \"unstored\"}");

		assertThat(result).hasStatus(409)
			.hasHeader(HttpHeaders.RETRY_AFTER, "2")
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "CONFLICT", "reason": "PROBE_FAILED", "retryable": true, "jobId": "%s", "result": "FAILED",
					 "challenge": {"challengeId": "probe"}}
					""".formatted(key));
		assertThat(storedStatus(key)).isEmpty();
	}

	private IdempotentRequest request(UUID key) {
		return new IdempotentRequest(owner, new IdempotencyKey(key), "probe.stage", "x");
	}

	private MvcTestResult post(UUID key, String body) {
		return mvc.post()
			.uri("/test-stage-commands/{owner}/immediate", owner)
			.header("API-Version", "1")
			.header(IdempotencyKey.HEADER, key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private Optional<Long> lastSeq() {
		return jdbc.sql("SELECT last_seq FROM owner_change_counter WHERE owner_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.optional();
	}

	private Optional<Integer> storedStatus(UUID key) {
		return jdbc.sql("SELECT response_code FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", owner)
			.param("key", key)
			.query(Integer.class)
			.optional();
	}

	@RestController
	@RequestMapping("/test-stage-commands/{owner}")
	static class StageProbeController {

		private final IdempotentExecutor executor;

		StageProbeController(IdempotentExecutor executor) {
			this.executor = executor;
		}

		@IdempotentCommand
		@PostMapping("/immediate")
		ResponseEntity<JsonNode> immediate(@PathVariable UUID owner, IdempotencyKey key,
				@Valid @RequestBody ProbeCommand command) {
			return executor.execute(new IdempotentRequest(owner, key, "probe.immediate", command), () -> {
				if ("stored".equals(command.fail())) {
					throw new PikException(ErrorCode.FORBIDDEN, "PROBE_FAILED", false, null, CHALLENGE);
				}
				if ("unstored".equals(command.fail())) {
					throw PikException.unstored(ErrorCode.CONFLICT, "PROBE_FAILED", true, Duration.ofSeconds(2),
							CHALLENGE);
				}
				return command;
			}).toResponseEntity();
		}

		record ProbeCommand(@NotBlank String name, @Nullable String fail) {
		}

	}

}
