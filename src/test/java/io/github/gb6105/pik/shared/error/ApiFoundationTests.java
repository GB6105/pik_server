package io.github.gb6105.pik.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiFoundationTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void missingApiVersionIsInvalidInput() {
		assertThat(mvc.get().uri("/test-probe/ok")).hasStatus(400)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "API_VERSION_REQUIRED", "retryable": false}
					""");
	}

	@Test
	void unsupportedApiVersionIsInvalidInput() {
		assertThat(mvc.get().uri("/test-probe/ok").header("API-Version", "2")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "API_VERSION_UNSUPPORTED", "retryable": false}
					""");
	}

	@Test
	void supportedApiVersionReachesHandler() {
		assertThat(mvc.get().uri("/test-probe/ok").header("API-Version", "1")).hasStatus(200)
			.bodyText()
			.isEqualTo("ok");
	}

	@Test
	void pikExceptionKeepsCodeReasonAndRetryable() {
		assertThat(mvc.get().uri("/test-probe/conflict").header("API-Version", "1")).hasStatus(409)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status": 409, "code": "CONFLICT", "reason": "PROBE_CONFLICT", "retryable": true}
					""");
		assertThat(mvc.get().uri("/test-probe/forbidden").header("API-Version", "1")).hasStatus(403)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "FORBIDDEN", "reason": "PROBE_FORBIDDEN", "retryable": false}
					""");
	}

	@Test
	void validationFailureIsInvalidInput() {
		assertThat(mvc.post()
			.uri("/test-probe/validated")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"\"}")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED", "retryable": false}
					""");
	}

	@Test
	void unknownPathIsNotFound() {
		assertThat(mvc.get().uri("/no-such-path").header("API-Version", "1")).hasStatus(404)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "NOT_FOUND", "reason": "RESOURCE_NOT_FOUND", "retryable": false}
					""");
	}

	@Test
	void unsupportedMethodMapsToUnsupportedOperation() {
		assertThat(mvc.delete().uri("/test-probe/ok").header("API-Version", "1")).hasStatus(422)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNSUPPORTED_OPERATION", "reason": "METHOD_NOT_ALLOWED", "retryable": false}
					""");
	}

	@Test
	void unexpectedFailureIsRetryableTemporaryFailure() {
		assertThat(mvc.get().uri("/test-probe/unexpected").header("API-Version", "1")).hasStatus(503)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "TEMPORARY_FAILURE", "reason": "INTERNAL_ERROR", "retryable": true}
					""");
	}

	@Test
	void actuatorIsOutsideApiVersioning() {
		assertThat(mvc.get().uri("/actuator/health")).hasStatus(200);
	}

	@Test
	void requestLogContainsOnlyAllowListedFields(CapturedOutput output) {
		assertThat(mvc.get().uri("/test-probe/ok?q=secret-term").header("API-Version", "1")).hasStatus(200);

		String line = output.getOut()
			.lines()
			.filter((candidate) -> candidate.contains("\"message\":\"request completed\"")
					&& candidate.contains("GET /test-probe/ok"))
			.findFirst()
			.orElseThrow();
		assertThat(line).contains("\"trace_id\":\"", "\"http_status\":200", "\"duration_ms\":",
				"\"action\":\"GET /test-probe/ok\"");
		assertThat(line).doesNotContain("secret-term", "thread_name", "@version");
	}

}
