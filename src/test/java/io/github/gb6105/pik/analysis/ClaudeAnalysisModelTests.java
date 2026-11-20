package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.errors.AnthropicIoException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Claude adapter against MockWebServer (plan 6.2, 6.4, 11.1): the request it sends, one HTTP request per call, and
 * the settlement table row of every reply. No test reaches the Anthropic API; the API key is a dummy.
 */
class ClaudeAnalysisModelTests {

	static final JsonMapper JSON = AnalysisManifestsTests.JSON;

	static final CallManifest MANIFEST = CallManifests.resolve(AnalysisManifestsTests.DEFAULT_MANIFEST,
			CallManifests.USER_TEXT, JSON);

	static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10, 0x7F, (byte) 0xFF, (byte) 0xD9 };

	static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

	static final String OUTPUT = "{\"context\":{\"summary\":\"poster\"}}";

	static final String USAGE = "{\"input_tokens\":1500,\"output_tokens\":300,\"cache_creation_input_tokens\":1200,"
			+ "\"cache_read_input_tokens\":null}";

	static final ModelReply.Usage TOKENS = new ModelReply.Usage(1500, 300, 1200, 0);

	MockWebServer server;

	ClaudeAnalysisModel model;

	@BeforeEach
	void start() throws IOException {
		server = new MockWebServer();
		server.start(InetAddress.getLoopbackAddress(), 0);
		model = model(baseUrl(), Duration.ofSeconds(5));
	}

	@AfterEach
	void stop() throws IOException {
		model.close();
		server.shutdown();
	}

	@Test
	void requestHoldsTheManifestSettingsTheImageAndTheInstructionOnly() throws Exception {
		server.enqueue(message("end_turn", text(OUTPUT)));

		model.call(MANIFEST, JPEG);

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/v1/messages");
		// SDK 3.10.0 sends a static API key as a bearer credential (StaticAuthHeaders), and no key of the environment.
		assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
		assertThat(request.getHeader("X-Api-Key")).isNull();
		assertThat(request.getHeader("anthropic-version")).isNotBlank();
		JsonNode body = JSON.readTree(request.getBody().readUtf8());
		// Nothing else: no temperature, top_p, top_k, metadata, stop sequences, tools, stream or fallbacks.
		assertThat(body.propertyNames())
			.containsExactlyInAnyOrder("model", "max_tokens", "thinking", "output_config", "system", "messages");
		assertThat(body.get("model").stringValue()).isEqualTo("claude-haiku-5-5");
		assertThat(body.get("max_tokens").intValue()).isEqualTo(4096);
		assertThat(body.get("thinking")).isEqualTo(JSON.readTree("{\"type\":\"adaptive\"}"));
		assertThat(body.get("output_config").propertyNames()).containsExactlyInAnyOrder("effort", "format");
		assertThat(body.at("/output_config/effort").stringValue()).isEqualTo("low");
		assertThat(body.at("/output_config/format/type").stringValue()).isEqualTo("json_schema");
		assertThat(body.at("/output_config/format/schema"))
			.isEqualTo(JSON.readTree(getClass().getResourceAsStream("/analysis/schema/v1.json")));
		assertThat(body.get("system")).isEqualTo(JSON.createArrayNode()
			.add(JSON.createObjectNode()
				.put("type", "text")
				.put("text", MANIFEST.systemPrompt())
				.set("cache_control", JSON.createObjectNode().put("type", "ephemeral").put("ttl", "5m"))));
		assertThat(body.get("messages")).isEqualTo(JSON.readTree("""
				[{"role": "user", "content": [
				  {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "%s"}},
				  {"type": "text", "text": "%s"}]}]
				""".formatted(Base64.getEncoder().encodeToString(JPEG), CallManifests.USER_TEXT)));
		assertThat(MANIFEST.systemPrompt()).contains("ko-KR").doesNotContain(CallManifests.LOCALE_PLACEHOLDER);
	}

	@Test
	void cacheOffLeavesCacheControlOutAndOtherSettingsFollowTheManifest() throws Exception {
		CallManifest manifest = CallManifests.resolve(AnalysisManifestsTests.manifest((m) -> {
			m.cacheEnabled = false;
			m.thinking = "disabled";
			m.effort = "medium";
			m.maxTokens = 2048;
		}), CallManifests.USER_TEXT, JSON);
		CallManifest hourCache = CallManifests.resolve(
				AnalysisManifestsTests.manifest((m) -> m.cacheTtl = Duration.ofHours(1)), CallManifests.USER_TEXT,
				JSON);
		server.enqueue(message("end_turn", text(OUTPUT)));
		server.enqueue(message("end_turn", text(OUTPUT)));

		model.call(manifest, JPEG);
		model.call(hourCache, JPEG);

		JsonNode body = JSON.readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
		assertThat(body.get("system").get(0).propertyNames()).containsExactlyInAnyOrder("type", "text");
		assertThat(body.get("thinking")).isEqualTo(JSON.readTree("{\"type\":\"disabled\"}"));
		assertThat(body.at("/output_config/effort").stringValue()).isEqualTo("medium");
		assertThat(body.get("max_tokens").intValue()).isEqualTo(2048);
		assertThat(body.at("/messages/0/content/0").has("cache_control")).isFalse();
		assertThat(body.at("/messages/0/content/1").has("cache_control")).isFalse();
		JsonNode hour = JSON.readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
		assertThat(hour.at("/system/0/cache_control/ttl").stringValue()).isEqualTo("1h");
	}

	@Test
	void endTurnWithJsonTextIsTheOutputRow() {
		server.enqueue(message("end_turn", "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}",
				text("{\"context\":"), text("{\"summary\":\"poster\"}}")).setHeader("request-id", "req_1"));

		ModelReply reply = model.call(MANIFEST, JPEG);

		assertThat(reply.row()).isEqualTo(ModelReply.Row.OUTPUT);
		ModelReply.Answered answered = (ModelReply.Answered) reply;
		assertThat(answered.status()).isEqualTo(200);
		assertThat(answered.requestId()).isEqualTo("req_1");
		assertThat(answered.usage()).isEqualTo(TOKENS);
		assertThat(answered.stopReason()).isEqualTo("end_turn");
		assertThat(answered.output()).isEqualTo(JSON.readTree(OUTPUT));
		assertThat(answered.retryAfter()).isNull();
		assertThat(answered.toString()).doesNotContain("poster").contains("output=(present)");
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "refusal | REFUSAL", "max_tokens | TRUNCATED",
			"stop_sequence | INVALID_OUTPUT", "pause_turn | INVALID_OUTPUT", "tool_use | INVALID_OUTPUT" })
	void stopReasonsOtherThanEndTurnAreTheirRows(String stopReason, ModelReply.Row row) {
		server.enqueue(message(stopReason, text(OUTPUT)));

		ModelReply reply = model.call(MANIFEST, JPEG);

		assertThat(reply.row()).isEqualTo(row);
		assertThat(((ModelReply.Answered) reply).output()).isNull();
		assertThat(((ModelReply.Answered) reply).usage()).isEqualTo(TOKENS);
	}

	@Test
	void refusalKeepsItsUsage() {
		server.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"type\":\"message\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":"
					+ "{\"category\":\"general_harms\"},\"usage\":" + USAGE + "}"));

		ModelReply reply = model.call(MANIFEST, JPEG);

		assertThat(reply).isEqualTo(new ModelReply.Answered(200, null, TOKENS, "refusal", null, null));
		assertThat(reply.row()).isEqualTo(ModelReply.Row.REFUSAL);
	}

	@Test
	void endTurnWithoutTextOrWithTextThatIsNotJsonIsInvalidOutput() {
		List<String> contents = List.of("", "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}",
				text(""), text("{\"context\":"), text("{} trailing"), text("not json"));
		contents.forEach((content) -> server.enqueue(message("end_turn", content)));

		for (int i = 0; i < contents.size(); i++) {
			ModelReply reply = model.call(MANIFEST, JPEG);

			assertThat(reply.row()).as(contents.get(i)).isEqualTo(ModelReply.Row.INVALID_OUTPUT);
			assertThat(reply).isInstanceOf(ModelReply.Answered.class);
			assertThat(((ModelReply.Answered) reply).usage()).isEqualTo(TOKENS);
		}
	}

	// The SDK would reject these bodies; the call is settled at the usage when it can be read (plan 6.4).
	@Test
	void unreadableMessageEnvelopesKeepTheUsageTheyHave() {
		List<String> withUsage = List.of("{\"type\":\"message\",\"stop_reason\":\"end_turn\",\"usage\":" + USAGE + "}",
				"{\"type\":\"message\",\"content\":{},\"stop_reason\":\"end_turn\",\"usage\":" + USAGE + "}",
				"{\"type\":\"message\",\"content\":[{\"text\":\"x\"}],\"stop_reason\":\"end_turn\",\"usage\":"
						+ USAGE + "}",
				"{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":1}],\"stop_reason\":\"end_turn\","
						+ "\"usage\":" + USAGE + "}",
				"{\"type\":\"message\",\"content\":[],\"usage\":" + USAGE + "}",
				"{\"type\":\"message\",\"content\":[],\"stop_reason\":1,\"usage\":" + USAGE + "}",
				"{\"type\":\"error\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":" + USAGE + "}");
		List<String> withoutUsage = List.of("not json", "", "[]",
				"{\"type\":\"message\",\"content\":[],\"stop_reason\":\"end_turn\"}",
				"{\"type\":\"message\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1}}",
				"{\"type\":\"message\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":"
						+ "{\"input_tokens\":-1,\"output_tokens\":1}}",
				"{\"type\":\"message\",\"content\":[],\"stop_reason\":\"end_turn\",\"usage\":"
						+ "{\"input_tokens\":1.5,\"output_tokens\":1}}");
		withUsage.forEach((body) -> server.enqueue(new MockResponse().setResponseCode(200).setBody(body)));
		withoutUsage.forEach((body) -> server.enqueue(
				new MockResponse().setResponseCode(200).setHeader("request-id", "req_2").setBody(body)));

		for (String body : withUsage) {
			assertThat(model.call(MANIFEST, JPEG)).as(body).isEqualTo(new ModelReply.Unreadable(200, null, TOKENS));
		}
		for (String body : withoutUsage) {
			ModelReply reply = model.call(MANIFEST, JPEG);
			assertThat(reply).as(body).isEqualTo(new ModelReply.Unreadable(200, "req_2", null));
			assertThat(reply.row()).isEqualTo(ModelReply.Row.INVALID_OUTPUT);
		}
		assertThat(server.getRequestCount()).isEqualTo(withUsage.size() + withoutUsage.size());
	}

	// A success queued behind each error shows that the SDK sent no second request (maxRetries 0).
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "400 | PROVIDER_REJECTED", "401 | CONFIGURATION_ERROR",
			"402 | PAYMENT_REQUIRED", "403 | CONFIGURATION_ERROR", "404 | CONFIGURATION_ERROR", "408 | UNKNOWN",
			"409 | UNKNOWN", "413 | PROVIDER_REJECTED", "418 | PROVIDER_REJECTED", "422 | PROVIDER_REJECTED",
			"429 | THROTTLED", "500 | UNKNOWN", "502 | UNKNOWN", "503 | UNKNOWN", "504 | UNKNOWN", "507 | UNKNOWN",
			"529 | THROTTLED" })
	void errorStatusesAreTheirRowsAfterOneRequest(int status, ModelReply.Row row) {
		server.enqueue(error(status).setHeader("request-id", "req_" + status));
		server.enqueue(message("end_turn", text(OUTPUT)));

		ModelReply reply = model.call(MANIFEST, JPEG);

		assertThat(reply).isEqualTo(new ModelReply.Answered(status, "req_" + status, null, null, null, null));
		assertThat(reply.row()).isEqualTo(row);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void retryAfterIsKeptInSecondsOrAsAnHttpDate() {
		server.enqueue(error(429).setHeader("retry-after", "17"));
		server.enqueue(error(529).setHeader("Retry-After", "Fri, 20 Nov 2026 00:01:30 GMT"));
		server.enqueue(error(529).setHeader("retry-after", "Thu, 19 Nov 2026 23:00:00 GMT"));
		server.enqueue(error(429).setHeader("retry-after", "soon"));
		server.enqueue(error(429).setHeader("retry-after", "-5"));

		assertThat(((ModelReply.Answered) model.call(MANIFEST, JPEG)).retryAfter()).isEqualTo(Duration.ofSeconds(17));
		assertThat(((ModelReply.Answered) model.call(MANIFEST, JPEG)).retryAfter()).isEqualTo(Duration.ofSeconds(90));
		assertThat(((ModelReply.Answered) model.call(MANIFEST, JPEG)).retryAfter()).isEqualTo(Duration.ZERO);
		assertThat(((ModelReply.Answered) model.call(MANIFEST, JPEG)).retryAfter()).isNull();
		assertThat(((ModelReply.Answered) model.call(MANIFEST, JPEG)).retryAfter()).isNull();
	}

	@Test
	void errorBodyUsageIsKept() {
		server.enqueue(new MockResponse().setResponseCode(400)
			.setHeader("request-id", "req_400")
			.setBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"m\"},"
					+ "\"usage\":" + USAGE + "}"));
		server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream failure, not JSON"));

		assertThat(model.call(MANIFEST, JPEG))
			.isEqualTo(new ModelReply.Answered(400, "req_400", TOKENS, null, null, null));
		assertThat(model.call(MANIFEST, JPEG)).isEqualTo(new ModelReply.Answered(500, null, null, null, null, null));
	}

	@Test
	void connectionRefusedBeforeTheRequestIsNotSent() throws IOException {
		try (ClaudeAnalysisModel closedPort = model("http://127.0.0.1:1", Duration.ofSeconds(5))) {
			ModelReply reply = closedPort.call(MANIFEST, JPEG);

			assertThat(reply).isEqualTo(new ModelReply.NotSent());
			assertThat(reply.row()).isEqualTo(ModelReply.Row.NOT_SENT);
		}
	}

	@Test
	void timeoutAndDisconnectsAfterTheRequestAreUnknown() throws Exception {
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
		server.enqueue(message("end_turn", text(OUTPUT)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
		try (ClaudeAnalysisModel shortTimeout = model(baseUrl(), Duration.ofSeconds(1))) {
			for (int i = 1; i <= 3; i++) {
				ModelReply reply = shortTimeout.call(MANIFEST, JPEG);

				assertThat(reply).isEqualTo(new ModelReply.NoAnswer());
				assertThat(reply.row()).isEqualTo(ModelReply.Row.UNKNOWN);
				assertThat(server.getRequestCount()).isEqualTo(i);
			}
		}
	}

	// K-6: only failures before a connection was made are NOT_SENT; anything else may follow a sent request.
	@Test
	void transportFailuresAreNotSentOnlyBeforeTheConnection() {
		for (IOException cause : List.of(new ConnectException("refused"), new NoRouteToHostException("route"),
				new UnknownHostException("host"), new SSLHandshakeException("handshake"))) {
			assertThat(ClaudeAnalysisModel.transportFailure(new AnthropicIoException("Request failed", cause)))
				.as(cause.getClass().getSimpleName())
				.isEqualTo(new ModelReply.NotSent());
		}
		for (IOException cause : List.of(new SocketTimeoutException("connect timed out"),
				new InterruptedIOException("timeout"), new SSLException("closed"), new IOException("reset"))) {
			assertThat(ClaudeAnalysisModel.transportFailure(new AnthropicIoException("Request failed", cause)))
				.as(cause.getClass().getSimpleName())
				.isEqualTo(new ModelReply.NoAnswer());
		}
		assertThat(ClaudeAnalysisModel.transportFailure(new AnthropicIoException("Request failed")))
			.isEqualTo(new ModelReply.NoAnswer());
	}

	@Test
	void otherSuccessStatusesFollowTheirStopReason() {
		assertThat(new ModelReply.Answered(201, null, TOKENS, "end_turn", JSON.readTree("{}"), null).row())
			.isEqualTo(ModelReply.Row.OUTPUT);
		assertThat(new ModelReply.Answered(299, null, TOKENS, null, null, null).row())
			.isEqualTo(ModelReply.Row.INVALID_OUTPUT);
		assertThat(new ModelReply.Answered(302, null, null, null, null, null).row()).isEqualTo(ModelReply.Row.UNKNOWN);
		assertThat(Set.of(new ModelReply.Unreadable(200, null, null).row(), new ModelReply.NoAnswer().row(),
				new ModelReply.NotSent().row()))
			.containsExactlyInAnyOrder(ModelReply.Row.INVALID_OUTPUT, ModelReply.Row.UNKNOWN, ModelReply.Row.NOT_SENT);
	}

	// An IP address, not localhost: OkHttp would try the other address of localhost after a failed exchange, and a
	// refused connection there would look like a request that was not sent.
	private String baseUrl() {
		return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getPort();
	}

	private ClaudeAnalysisModel model(String baseUrl, Duration timeout) {
		return ClaudeAnalysisModel.create(
				new AnalysisProperties.Claude(timeout, "1", "test-key", URI.create(baseUrl)), JSON,
				InstantSource.fixed(NOW));
	}

	private static MockResponse message(String stopReason, String... blocks) {
		return new MockResponse().setResponseCode(200)
			.setHeader("Content-Type", "application/json")
			.setBody("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
					+ "\"content\":[" + String.join(",", blocks) + "],\"stop_reason\":\"" + stopReason
					+ "\",\"stop_sequence\":null,\"usage\":" + USAGE + "}");
	}

	private static String text(String text) {
		return JSON.writeValueAsString(Map.of("type", "text", "text", text));
	}

	private static MockResponse error(int status) {
		return new MockResponse().setResponseCode(status)
			.setHeader("Content-Type", "application/json")
			.setBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"m\"},"
					+ "\"request_id\":\"req\"}");
	}

}
