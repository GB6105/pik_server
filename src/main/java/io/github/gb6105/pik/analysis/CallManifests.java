package io.github.gb6105.pik.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The call manifests jobs can run with: the current one of the settings, fixed on new jobs, and the earlier ones the
 * settings list (design 2.5, plan 6.1). The ID is the SHA-256 of the canonical content: the settings, the SHA-256 of
 * the prompt and schema resources, the instruction text and the maximum cost, so that any change gives a new ID. At
 * startup, before the web server accepts requests, the current row is inserted with its call limits when missing,
 * and each earlier ID must name a stored row with call limits whose content the resources still rebuild; otherwise
 * startup fails. Rows never change. Stage 5-1 rows have no call limits and cannot run (K-12).
 */
@Component
class CallManifests implements SmartInitializingSingleton {

	/** The instruction text sent after the image; part of the content. */
	static final String USER_TEXT = "Extract the information in this photo as the system prompt describes.";

	/** Where the output locale goes in a system prompt resource. */
	static final String LOCALE_PLACEHOLDER = "{{outputLocale}}";

	private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	private final CallManifest current;

	private final List<String> previousIds;

	private volatile Map<String, CallManifest> runnable;

	CallManifests(AnalysisProperties properties, JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
		this.current = resolve(properties.manifest(), USER_TEXT, jsonMapper);
		this.previousIds = properties.manifest().previousIds();
		this.runnable = Map.of(current.id(), current);
		Assert.isTrue(!previousIds.contains(current.id()), "manifest.previous-ids must not name the current manifest");
	}

	/**
	 * @return the {@code analysis_call_manifest} ID of the current settings
	 */
	String currentId() {
		return current.id();
	}

	CallManifest current() {
		return current;
	}

	/**
	 * @param id the manifest ID of a job
	 * @return the manifest, empty when jobs of that manifest cannot run on this instance
	 */
	Optional<CallManifest> runnable(String id) {
		return Optional.ofNullable(runnable.get(id));
	}

	@Override
	public void afterSingletonsInstantiated() {
		AnalysisProperties.Manifest settings = current.settings();
		jdbc.sql("""
				INSERT INTO analysis_call_manifest (id, content, content_type, max_bytes, max_long_edge_px, max_pixels,
				                                    max_tokens, max_cost_micros, created_at)
				VALUES (:id, CAST(:content AS jsonb), :contentType, :maxBytes, :maxLongEdgePx, :maxPixels, :maxTokens,
				        :maxCostMicros, now())
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", current.id())
			.param("content", current.content())
			.param("contentType", settings.contentType())
			.param("maxBytes", settings.maxBytes())
			.param("maxLongEdgePx", settings.maxLongEdgePx())
			.param("maxPixels", settings.maxPixels())
			.param("maxTokens", settings.maxTokens())
			.param("maxCostMicros", settings.maxCostMicros())
			.update();
		Map<String, CallManifest> manifests = new HashMap<>();
		manifests.put(current.id(), current);
		for (String id : previousIds) {
			manifests.put(id, stored(id));
		}
		runnable = Map.copyOf(manifests);
	}

	/**
	 * Builds a manifest from its settings and the resources they name.
	 *
	 * @param userText the instruction text sent after the image
	 * @throws IllegalArgumentException when a resource is missing or malformed
	 */
	static CallManifest resolve(AnalysisProperties.Manifest settings, String userText, JsonMapper jsonMapper) {
		String promptPath = "analysis/prompt/" + settings.prompt() + "/system.md";
		String schemaPath = "analysis/schema/v" + settings.schemaVersion() + ".json";
		String prompt = resource(promptPath);
		String schemaText = resource(schemaPath);
		Assert.isTrue(prompt.contains(LOCALE_PLACEHOLDER),
				() -> promptPath + " must hold the placeholder " + LOCALE_PLACEHOLDER);
		Map<String, Object> schema;
		try {
			schema = jsonMapper.readValue(schemaText, OBJECT);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException(schemaPath + " must hold one JSON object", ex);
		}
		String content = content(settings, CanonicalJson.sha256Hex(prompt), CanonicalJson.sha256Hex(schemaText),
				userText);
		return new CallManifest(CanonicalJson.sha256Hex(content), content, settings,
				prompt.replace(LOCALE_PLACEHOLDER, settings.outputLocale()), userText, schema);
	}

	/**
	 * The canonical JSON whose SHA-256 is the manifest ID. {@code uploadPolicy} keeps its stage 5-1 shape.
	 *
	 * @param promptSha256 SHA-256 of the UTF-8 system prompt resource, {@code promptVersion}
	 * @param schemaSha256 SHA-256 of the UTF-8 schema resource
	 */
	static String content(AnalysisProperties.Manifest settings, String promptSha256, String schemaSha256,
			String userText) {
		AnalysisProperties.Price price = settings.price();
		Map<String, Object> content = new HashMap<>();
		content.put("model", settings.model());
		content.put("uploadPolicy", Map.of("contentType", settings.contentType(), "maxBytes", settings.maxBytes(),
				"maxLongEdgePx", settings.maxLongEdgePx()));
		content.put("maxPixels", settings.maxPixels());
		content.put("prompt", settings.prompt());
		content.put("promptVersion", promptSha256);
		content.put("userText", userText);
		content.put("schemaVersion", settings.schemaVersion());
		content.put("outputFormat", Map.of("type", "json_schema", "schemaSha256", schemaSha256));
		content.put("thinking", settings.thinking());
		content.put("effort", settings.effort());
		content.put("maxTokens", settings.maxTokens());
		content.put("imageTokenCap", settings.imageTokenCap());
		content.put("promptTokenCap", settings.promptTokenCap());
		content.put("cache", Map.of("enabled", settings.cache().enabled(), "ttl", settings.cache().ttl().toString()));
		content.put("outputLocale", settings.outputLocale());
		content.put("price", Map.of("version", price.version(), "inputMicrosPerMtok", price.inputMicrosPerMtok(),
				"cacheWriteMicrosPerMtok", price.cacheWriteMicrosPerMtok(), "cacheReadMicrosPerMtok",
				price.cacheReadMicrosPerMtok(), "outputMicrosPerMtok", price.outputMicrosPerMtok()));
		content.put("maxCostMicros", settings.maxCostMicros());
		return CanonicalJson.write(content);
	}

	// An earlier manifest rebuilt from its row: the same ID proves that its resources are unchanged.
	private CallManifest stored(String id) {
		List<StoredRow> rows = jdbc
			.sql("SELECT content::text AS content, max_pixels, max_tokens, max_cost_micros"
					+ " FROM analysis_call_manifest WHERE id = :id")
			.param("id", id)
			.query((rs, rowNum) -> new StoredRow(rs.getString("content"), rs.getObject("max_pixels", Long.class),
					rs.getObject("max_tokens", Integer.class), rs.getObject("max_cost_micros", Long.class)))
			.list();
		Assert.state(!rows.isEmpty(), () -> "manifest.previous-ids names " + id + ", which is not stored");
		StoredRow row = rows.get(0);
		Assert.state(row.maxPixels() != null,
				() -> "manifest.previous-ids names " + id + ", which has no call limits and cannot run");
		CallManifest manifest;
		try {
			JsonNode content = jsonMapper.readTree(row.content());
			manifest = resolve(settings(content), content.path("userText").stringValue(), jsonMapper);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException(
					"manifest.previous-ids names " + id + ", whose content cannot be rebuilt: " + ex.getMessage(), ex);
		}
		Assert.state(manifest.id().equals(id), () -> "manifest.previous-ids names " + id
				+ ", whose content the prompt and schema resources no longer rebuild");
		AnalysisProperties.Manifest settings = manifest.settings();
		Assert.state(row.maxPixels() == settings.maxPixels() && row.maxTokens() != null
				&& row.maxTokens() == settings.maxTokens() && row.maxCostMicros() != null
				&& row.maxCostMicros() == settings.maxCostMicros(),
				() -> "manifest.previous-ids names " + id + ", whose call limits differ from its content");
		return manifest;
	}

	// The stored values are checked as settings are, so a value out of range fails as it would in the settings.
	private static AnalysisProperties.Manifest settings(JsonNode content) {
		JsonNode upload = content.path("uploadPolicy");
		JsonNode cache = content.path("cache");
		JsonNode price = content.path("price");
		return new AnalysisProperties.Manifest(content.path("model").stringValue(),
				upload.path("contentType").stringValue(), upload.path("maxBytes").intValue(),
				upload.path("maxLongEdgePx").intValue(), content.path("maxPixels").longValue(),
				content.path("prompt").stringValue(), content.path("schemaVersion").intValue(),
				content.path("thinking").stringValue(), content.path("effort").stringValue(),
				content.path("maxTokens").intValue(), content.path("imageTokenCap").intValue(),
				content.path("promptTokenCap").intValue(),
				new AnalysisProperties.Cache(cache.path("enabled").booleanValue(),
						Duration.parse(cache.path("ttl").stringValue())),
				content.path("outputLocale").stringValue(),
				new AnalysisProperties.Price(price.path("version").stringValue(),
						price.path("inputMicrosPerMtok").longValue(), price.path("cacheWriteMicrosPerMtok").longValue(),
						price.path("cacheReadMicrosPerMtok").longValue(), price.path("outputMicrosPerMtok").longValue()),
				List.of());
	}

	// Strict UTF-8, so that the SHA-256 of the text is the SHA-256 of the resource bytes.
	private static String resource(String path) {
		ClassPathResource resource = new ClassPathResource(path);
		Assert.isTrue(resource.exists(), () -> "The manifest names " + path + ", which is not on the class path");
		try (InputStream in = resource.getInputStream()) {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(in.readAllBytes())).toString();
		}
		catch (CharacterCodingException ex) {
			throw new IllegalArgumentException(path + " must be UTF-8", ex);
		}
		catch (IOException ex) {
			throw new IllegalStateException(path + " cannot be read", ex);
		}
	}

	private record StoredRow(String content, @Nullable Long maxPixels, @Nullable Integer maxTokens,
			@Nullable Long maxCostMicros) {
	}

}
