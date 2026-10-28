package io.github.gb6105.pik.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Import({ TestcontainersConfiguration.class, ApiFoundationTests.ProbeController.class,
		ApiFoundationTests.FailingFilterConfig.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiFoundationTests {

	static final String SECRET = "secret-term";

	// Must match logging.structured.json.include in application.yml.
	static final Set<String> LOG_FIELDS = Set.of("@timestamp", "level", "logger_name", "message", "stack_trace",
			"trace_id", "principal_id", "action", "target_id", "status", "reason", "http_status", "duration_ms");

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvcTester mvc;

	@LocalServerPort
	int port;

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
			.doesNotHavePath("$.result")
			.isLenientlyEqualTo("""
					{"status": 409, "code": "CONFLICT", "reason": "PROBE_CONFLICT", "retryable": true}
					""");
	}

	@Test
	void rateLimitedSendsRetryAfter() {
		assertThat(mvc.get().uri("/test-probe/rate-limited").header("API-Version", "1")).hasStatus(429)
			.hasHeader("Retry-After", "30")
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "RATE_LIMITED", "reason": "PROBE_LIMIT", "retryable": true}
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
	void unreadableBodyIsMalformedBody() {
		assertThat(mvc.post()
			.uri("/test-probe/validated")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": ")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "MALFORMED_BODY"}
					""");
	}

	@Test
	void unsupportedContentTypeIsInvalidInput() {
		assertThat(mvc.post()
			.uri("/test-probe/validated")
			.header("API-Version", "1")
			.contentType(MediaType.TEXT_PLAIN)
			.content("name")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "MEDIA_TYPE_NOT_SUPPORTED"}
					""");
	}

	@Test
	void otherClientErrorFallsBackToBadRequest() {
		assertThat(mvc.get().uri("/test-probe/param").header("API-Version", "1")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "BAD_REQUEST", "retryable": false}
					""");
	}

	@ParameterizedTest
	@CsvSource({ "401, 401, UNAUTHENTICATED, AUTHENTICATION_REQUIRED, false",
			"404, 404, NOT_FOUND, RESOURCE_NOT_FOUND, false",
			"502, 503, TEMPORARY_FAILURE, SERVER_UNAVAILABLE, true" })
	void responseStatusExceptionKeepsStatusMapping(int thrown, int expected, String code, String reason,
			boolean retryable) {
		assertThat(mvc.get().uri("/test-probe/status/{code}", thrown).header("API-Version", "1")).hasStatus(expected)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "%s", "reason": "%s", "retryable": %s}
					""".formatted(code, reason, retryable));
	}

	@Test
	void serverErrorLogKeepsResponseReason(CapturedOutput output) {
		assertThat(mvc.get().uri("/test-probe/status/502").header("API-Version", "1")).hasStatus(503);

		assertThat(logEvents(output)).anySatisfy((event) -> assertThat(event).containsEntry("level", "ERROR")
			.containsEntry("message", "server error")
			.containsEntry("reason", "SERVER_UNAVAILABLE"));
	}

	@Test
	void directErrorPathRequestIsNotFound() {
		assertThat(mvc.get().uri("/error").header("API-Version", "1")).hasStatus(404)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "NOT_FOUND", "reason": "RESOURCE_NOT_FOUND", "retryable": false}
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
	void unsupportedMethodMapsToUnsupportedOperationAndKeepsAllow() {
		assertThat(mvc.delete().uri("/test-probe/ok").header("API-Version", "1")).hasStatus(422)
			.containsHeader("Allow")
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNSUPPORTED_OPERATION", "reason": "METHOD_NOT_ALLOWED", "retryable": false}
					""");
	}

	@Test
	void unexpectedFailureIsRetryableTemporaryFailureWithoutDetails(CapturedOutput output) {
		assertThat(mvc.get().uri("/test-probe/unexpected").header("API-Version", "1")).hasStatus(503)
			.bodyJson()
			.doesNotHavePath("$.detail")
			.isLenientlyEqualTo("""
					{"code": "TEMPORARY_FAILURE", "reason": "INTERNAL_ERROR", "retryable": true}
					""");

		assertThat(logEvents(output)).anySatisfy((event) -> assertThat(event).containsEntry("level", "ERROR")
			.containsEntry("message", "server error")
			.containsEntry("reason", "INTERNAL_ERROR")
			.containsKey("trace_id")
			.hasEntrySatisfying("stack_trace", (trace) -> assertThat((String) trace)
				.startsWith("java.lang.IllegalStateException\n")
				.contains("Caused by: java.lang.IllegalArgumentException\n")));
		assertThat(output.getAll()).doesNotContain(SECRET);
	}

	@Test
	void sendErrorOutsideDispatcherIsProblemResponse() throws Exception {
		HttpResponse<String> response = send("/test-filter/denied", true);

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Content-Type")).hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(json(response.body())).containsEntry("code", "FORBIDDEN")
			.containsEntry("reason", "ACCESS_DENIED")
			.containsEntry("retryable", false);
	}

	@Test
	void sendErrorMethodNotAllowedKeepsAllow() throws Exception {
		HttpResponse<String> response = send("/test-filter/method", true);

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.headers().firstValue("Allow")).hasValue("GET");
		assertThat(json(response.body())).containsEntry("code", "UNSUPPORTED_OPERATION")
			.containsEntry("reason", "METHOD_NOT_ALLOWED");
	}

	@Test
	void sendErrorWithoutApiVersionKeepsStatus() throws Exception {
		HttpResponse<String> response = send("/test-filter/denied", false);

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(json(response.body())).containsEntry("code", "FORBIDDEN");
	}

	@Test
	void filterExceptionIsLoggedWithTraceIdAndRenderedAsTemporaryFailure(CapturedOutput output) throws Exception {
		HttpResponse<String> response = send("/test-filter/boom", true);

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(json(response.body())).containsEntry("code", "TEMPORARY_FAILURE")
			.containsEntry("reason", "INTERNAL_ERROR")
			.containsEntry("retryable", true)
			.doesNotContainKey("detail");
		assertThat(logEvents(output)).anySatisfy((event) -> assertThat(event).containsEntry("level", "ERROR")
			.containsEntry("message", "request failed")
			.containsEntry("http_status", 503)
			.containsKey("trace_id")
			.hasEntrySatisfying("stack_trace",
					(trace) -> assertThat((String) trace).startsWith("java.lang.IllegalStateException\n")));
		assertThat(output.getAll()).doesNotContain(SECRET);
	}

	@Test
	void servletExceptionMessageIsNotLogged(CapturedOutput output) throws Exception {
		HttpResponse<String> response = send("/test-filter/servlet-boom", true);

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(logEvents(output)).anySatisfy(
				(event) -> assertThat(event).containsEntry("message", "request failed").containsKey("trace_id"));
		assertThat(logEvents(output)).noneSatisfy((event) -> assertThat(event).containsEntry("logger_name",
				"org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]"));
		assertThat(output.getAll()).doesNotContain(SECRET);
	}

	@Test
	void actuatorIsOutsideApiVersioningAndRequestLog(CapturedOutput output) {
		assertThat(mvc.get().uri("/actuator/health")).hasStatus(200);
		assertThat(mvc.get().uri("/actuator")).hasStatus(200);

		assertThat(logEvents(output)).noneSatisfy(
				(event) -> assertThat(event).containsEntry("message", "request completed"));
	}

	@Test
	void logsKeepOnlyAllowListedFieldsAndNoRequestData(CapturedOutput output) {
		assertThat(mvc.get().uri("/test-probe/ok?q={q}", SECRET).header("API-Version", "1")).hasStatus(200);
		assertThat(mvc.post()
			.uri("/test-probe/validated")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"" + SECRET)).hasStatus(400);
		assertThat(mvc.get().uri("/test-probe/unexpected").header("API-Version", "1")).hasStatus(503);

		List<Map<String, Object>> events = logEvents(output);
		assertThat(events).isNotEmpty().allSatisfy((event) -> assertThat(LOG_FIELDS).containsAll(event.keySet()));
		assertThat(events).anySatisfy((event) -> assertThat(event).containsEntry("message", "request completed")
			.containsEntry("action", "GET /test-probe/ok")
			.containsEntry("http_status", 200)
			.containsKeys("trace_id", "duration_ms"));
		assertThat(output.getAll()).doesNotContain(SECRET);
	}

	private HttpResponse<String> send(String path, boolean withApiVersion) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (withApiVersion) {
			request.header("API-Version", "1");
		}
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> json(String text) {
		return JSON.readValue(text, Map.class);
	}

	private static List<Map<String, Object>> logEvents(CapturedOutput output) {
		return output.getOut().lines().filter((line) -> line.startsWith("{")).map(ApiFoundationTests::json).toList();
	}

	@RestController
	@RequestMapping("/test-probe")
	static class ProbeController {

		@GetMapping("/ok")
		String ok() {
			return "ok";
		}

		@GetMapping("/conflict")
		String conflict() {
			throw new PikException(ErrorCode.CONFLICT, "PROBE_CONFLICT", true);
		}

		@GetMapping("/rate-limited")
		String rateLimited() {
			throw new PikException(ErrorCode.RATE_LIMITED, "PROBE_LIMIT", true, Duration.ofMillis(29_500));
		}

		@GetMapping("/unexpected")
		String unexpected() {
			throw new IllegalStateException("probe failure " + SECRET, new IllegalArgumentException("cause " + SECRET));
		}

		@GetMapping("/status/{code}")
		String status(@PathVariable int code) {
			throw new ResponseStatusException(HttpStatus.valueOf(code), "probe status " + SECRET);
		}

		@GetMapping("/param")
		String param(@RequestParam int value) {
			return Integer.toString(value);
		}

		@PostMapping("/validated")
		String validated(@Valid @RequestBody ProbeBody body) {
			return body.name();
		}

		record ProbeBody(@NotBlank String name) {
		}

	}

	// Stands in for filters such as Spring Security that fail or call sendError outside the DispatcherServlet.
	@TestConfiguration(proxyBeanMethods = false)
	static class FailingFilterConfig {

		@Bean
		OncePerRequestFilter failingFilter() {
			return new OncePerRequestFilter() {

				@Override
				protected boolean shouldNotFilter(HttpServletRequest request) {
					return !request.getRequestURI().startsWith("/test-filter/");
				}

				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
						FilterChain chain) throws ServletException, IOException {
					switch (request.getRequestURI()) {
						case "/test-filter/denied" -> response.sendError(HttpServletResponse.SC_FORBIDDEN);
						case "/test-filter/method" -> {
							response.setHeader("Allow", "GET");
							response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
						}
						case "/test-filter/servlet-boom" -> throw new ServletException("servlet failure " + SECRET);
						default -> throw new IllegalStateException("filter failure " + SECRET);
					}
				}

			};
		}

	}

}
