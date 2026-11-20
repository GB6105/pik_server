package io.github.gb6105.pik.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.LogLevel;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AnalysisModel} on the Anthropic Java SDK (plan 6.2, 6.4). One request per call: SDK retries are off, OkHttp
 * does not retry on connection failures, and the whole request has one time limit. The raw response keeps the HTTP
 * status, {@code request-id} and {@code Retry-After}; a 2xx body is read as a JSON tree, blocks by their
 * {@code type}, without the SDK's strict parsing. The request holds the model settings, the system prompt, the image
 * and the instruction text of the manifest, and nothing else: no metadata, sampling parameters or prefill. SDK logs
 * and tracing are off, so neither the image nor the answer leaves this class; its own log lines carry statuses, stop
 * reasons, token counts and exception types only.
 */
@Slf4j
final class ClaudeAnalysisModel implements AnalysisModel, AutoCloseable {

	private static final String END_TURN = "end_turn";

	private final AnthropicClient client;

	private final JsonMapper jsonMapper;

	private final InstantSource clock;

	ClaudeAnalysisModel(AnthropicClient client, JsonMapper jsonMapper, InstantSource clock) {
		this.client = client;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	/**
	 * Builds the client of the settings. The API key is passed in, not read from the environment: {@code .env.local}
	 * reaches Spring properties only.
	 */
	static ClaudeAnalysisModel create(AnalysisProperties.Claude claude, JsonMapper jsonMapper) {
		return create(claude, jsonMapper, InstantSource.system());
	}

	/**
	 * @param clock the clock a {@code Retry-After} date is read against
	 */
	static ClaudeAnalysisModel create(AnalysisProperties.Claude claude, JsonMapper jsonMapper, InstantSource clock) {
		Assert.state(claude.hasApiKey(),
				"pik.analysis.claude.api-key (ANTHROPIC_API_KEY) must be set when the worker is enabled");
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
			.apiKey(claude.apiKey())
			.maxRetries(0)
			.timeout(claude.requestTimeout())
			.logLevel(LogLevel.OFF)
			.openTelemetry(false);
		if (claude.baseUrl() != null) {
			builder.baseUrl(claude.baseUrl().toString());
		}
		return new ClaudeAnalysisModel(builder.build(), jsonMapper, clock);
	}

	@Override
	public ModelReply call(CallManifest manifest, byte[] jpeg) {
		MessageCreateParams params = params(manifest, jpeg);
		try (HttpResponseFor<Message> response = client.messages().withRawResponse().create(params)) {
			int status = response.statusCode();
			@Nullable String requestId = response.requestId().orElse(null);
			byte[] body;
			try {
				body = response.body().readAllBytes();
			}
			catch (IOException ex) {
				log.warn("Claude call answered HTTP {}, its body broke off: {}", status, ex.getClass().getSimpleName());
				return new ModelReply.NoAnswer();
			}
			return message(status, requestId, body);
		}
		catch (AnthropicServiceException ex) {
			return error(ex);
		}
		catch (AnthropicIoException ex) {
			return transportFailure(ex);
		}
		catch (RuntimeException ex) {
			// Whether the request went out cannot be told (K-6).
			log.warn("Claude call failed: {}", ex.getClass().getSimpleName());
			return new ModelReply.NoAnswer();
		}
	}

	@Override
	public void close() {
		client.close();
	}

	static MessageCreateParams params(CallManifest manifest, byte[] jpeg) {
		AnalysisProperties.Manifest settings = manifest.settings();
		TextBlockParam.Builder system = TextBlockParam.builder().text(manifest.systemPrompt());
		if (settings.cache().enabled()) {
			system.cacheControl(CacheControlEphemeral.builder()
				.ttl(Duration.ofHours(1).equals(settings.cache().ttl()) ? CacheControlEphemeral.Ttl.TTL_1H
						: CacheControlEphemeral.Ttl.TTL_5M)
				.build());
		}
		JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
		for (Map.Entry<String, Object> entry : manifest.schema().entrySet()) {
			schema.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
		}
		ImageBlockParam image = ImageBlockParam.builder()
			.source(Base64ImageSource.builder()
				.mediaType(Base64ImageSource.MediaType.of(settings.contentType()))
				.data(Base64.getEncoder().encodeToString(jpeg))
				.build())
			.build();
		MessageCreateParams.Builder params = MessageCreateParams.builder()
			.model(settings.model())
			.maxTokens(settings.maxTokens())
			.outputConfig(OutputConfig.builder()
				.effort(OutputConfig.Effort.of(settings.effort()))
				.format(JsonOutputFormat.of(schema.build()))
				.build())
			.systemOfTextBlockParams(List.of(system.build()))
			.addUserMessageOfContentBlockParams(List.of(ContentBlockParam.ofImage(image),
					ContentBlockParam.ofText(TextBlockParam.builder().text(manifest.userText()).build())));
		if ("disabled".equals(settings.thinking())) {
			params.thinking(ThinkingConfigDisabled.builder().build());
		}
		else {
			params.thinking(ThinkingConfigAdaptive.builder().build());
		}
		return params.build();
	}

	/**
	 * Classifies a transport failure: only failures before a connection was made are {@link ModelReply.NotSent}
	 * (K-6). Any other, a timeout included, may come after the request was sent.
	 */
	static ModelReply transportFailure(AnthropicIoException ex) {
		Throwable cause = ex.getCause();
		String type = (cause != null) ? cause.getClass().getSimpleName() : ex.getClass().getSimpleName();
		if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
				|| cause instanceof UnknownHostException || cause instanceof SSLHandshakeException) {
			log.warn("Claude call not sent: {}", type);
			return new ModelReply.NotSent();
		}
		log.warn("Claude call got no answer: {}", type);
		return new ModelReply.NoAnswer();
	}

	private ModelReply error(AnthropicServiceException ex) {
		int status = ex.statusCode();
		log.warn("Claude call answered HTTP {}", status);
		return new ModelReply.Answered(status, ex.requestId().orElse(null), errorUsage(ex.body()), null, null,
				retryAfter(ex.headers().values("retry-after")));
	}

	// Error bodies carry no usage today; one that does is settled at it (design 2.5, 400 row).
	private ModelReply.@Nullable Usage errorUsage(JsonValue body) {
		try {
			return usage(jsonMapper.valueToTree(body.convert(Object.class)).get("usage"));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private ModelReply message(int status, @Nullable String requestId, byte[] body) {
		JsonNode envelope = readTree(body);
		if (envelope == null || !envelope.isObject()) {
			log.warn("Claude call answered HTTP {} with a body that is not a JSON object", status);
			return new ModelReply.Unreadable(status, requestId, null);
		}
		ModelReply.Usage usage = usage(envelope.get("usage"));
		JsonNode stopReason = envelope.get("stop_reason");
		JsonNode content = envelope.get("content");
		String text = (content != null && content.isArray()) ? text(content) : null;
		if (usage == null || !"message".equals(string(envelope.get("type"))) || stopReason == null
				|| !(stopReason.isString() || stopReason.isNull()) || text == null) {
			log.warn("Claude call answered HTTP {} with an unreadable message", status);
			return new ModelReply.Unreadable(status, requestId, usage);
		}
		String reason = stopReason.isNull() ? null : stopReason.stringValue();
		JsonNode output = null;
		if (END_TURN.equals(reason)) {
			output = text.isEmpty() ? null : readTree(text.getBytes(StandardCharsets.UTF_8));
			if (output == null) {
				log.warn("Claude call ended without JSON text: {} output tokens", usage.outputTokens());
			}
		}
		else {
			log.warn("Claude call stopped with {}: {} output tokens", reason, usage.outputTokens());
		}
		return new ModelReply.Answered(status, requestId, usage, reason, output, null);
	}

	/**
	 * The text blocks of {@code content} joined in order, empty when there is none; {@code null} when a block is not
	 * an object with a {@code type}, or a text block has no text.
	 */
	private static @Nullable String text(JsonNode content) {
		StringBuilder text = new StringBuilder();
		for (JsonNode block : content) {
			String type = block.isObject() ? string(block.get("type")) : null;
			if (type == null) {
				return null;
			}
			if ("text".equals(type)) {
				String value = string(block.get("text"));
				if (value == null) {
					return null;
				}
				text.append(value);
			}
		}
		return text.toString();
	}

	// Input and output tokens are required; cache tokens are 0 when missing or null.
	private static ModelReply.@Nullable Usage usage(@Nullable JsonNode usage) {
		if (usage == null || !usage.isObject()) {
			return null;
		}
		long input = tokens(usage.get("input_tokens"), -1);
		long output = tokens(usage.get("output_tokens"), -1);
		long cacheCreation = tokens(usage.get("cache_creation_input_tokens"), 0);
		long cacheRead = tokens(usage.get("cache_read_input_tokens"), 0);
		if (input < 0 || output < 0 || cacheCreation < 0 || cacheRead < 0) {
			return null;
		}
		return new ModelReply.Usage(input, output, cacheCreation, cacheRead);
	}

	// -1 when the value is not a count; whenMissing when it is missing or null.
	private static long tokens(@Nullable JsonNode value, long whenMissing) {
		if (value == null || value.isNull()) {
			return whenMissing;
		}
		return (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) ? value.longValue()
				: -1;
	}

	private static @Nullable String string(@Nullable JsonNode value) {
		return (value != null && value.isString()) ? value.stringValue() : null;
	}

	// One JSON value; null when the bytes are empty or not JSON.
	private @Nullable JsonNode readTree(byte[] json) {
		try {
			JsonNode tree = jsonMapper.readTree(json);
			return (tree == null || tree.isMissingNode()) ? null : tree;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	// Delay seconds or an HTTP date (RFC 9110 10.2.3); null when missing or unreadable.
	private @Nullable Duration retryAfter(List<String> values) {
		if (values.isEmpty()) {
			return null;
		}
		String value = values.get(0).trim();
		try {
			long seconds = Long.parseLong(value);
			return (seconds >= 0) ? Duration.ofSeconds(seconds) : null;
		}
		catch (NumberFormatException ex) {
			try {
				Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				Duration delay = Duration.between(clock.instant(), at);
				return delay.isNegative() ? Duration.ZERO : delay;
			}
			catch (DateTimeParseException notADate) {
				return null;
			}
		}
	}

}
