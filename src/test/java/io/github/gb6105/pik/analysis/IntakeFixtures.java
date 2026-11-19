package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.shared.id.UuidV7;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real installs, tokens and consents through the HTTP API, and intake requests, for the intake integration tests
 * (plan 8: {@code POST /installs}, {@code POST /auth/token}, {@code PUT /consents/ai}).
 */
final class IntakeFixtures {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

	private final MockMvcTester mvc;

	private final JdbcClient jdbc;

	IntakeFixtures(MockMvcTester mvc, JdbcClient jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
	}

	/**
	 * A new Android install with a granted consent.
	 */
	Installed android() {
		Installed installed = androidWithoutConsent();
		grantConsent(installed);
		return installed;
	}

	Installed androidWithoutConsent() {
		byte[] androidId = new byte[8];
		ThreadLocalRandom.current().nextBytes(androidId);
		return install("ANDROID", "{\"androidId\": \"%s\"}".formatted(HexFormat.of().formatHex(androidId)), null);
	}

	/**
	 * A new iOS install with a granted consent; its trial is not started.
	 */
	Installed ios() {
		String deviceCheckToken = "fake-device-check:" + UUID.randomUUID();
		Installed installed = install("IOS", "{\"deviceCheckToken\": \"%s\"}".formatted(deviceCheckToken),
				deviceCheckToken);
		grantConsent(installed);
		return installed;
	}

	private Installed install(String platform, String trialDevice, @Nullable String deviceCheckToken) {
		MvcTestResult installed = mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"platform": "%s", "attestation": {"token": "fake-attestation"}, "trialDevice": %s}
					""".formatted(platform, trialDevice))
			.exchange();
		assertThat(installed).hasStatus(201);
		JsonNode install = json(installed);
		MvcTestResult token = mvc.post()
			.uri("/auth/token")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"grantType\": \"install_token\", \"installToken\": \"%s\"}"
				.formatted(install.get("installToken").asString()))
			.exchange();
		assertThat(token).hasStatusOk();
		Map<String, Object> device = jdbc.sql("SELECT id, principal_id, trial_mark_id FROM device WHERE install_id = :id")
			.param("id", UUID.fromString(install.get("installId").asString()))
			.query()
			.singleRow();
		return new Installed((UUID) device.get("principal_id"), (UUID) device.get("id"),
				(UUID) device.get("trial_mark_id"), "Bearer " + json(token).get("accessToken").asString(),
				deviceCheckToken);
	}

	void grantConsent(Installed installed) {
		long version = jdbc.sql("SELECT count(*) FROM ai_consent WHERE principal_id = :id")
			.param("id", installed.principalId())
			.query(Long.class)
			.single();
		assertThat(decide(installed, UuidV7.generate().toString(), "GRANTED", version)).hasStatusOk();
	}

	/**
	 * {@code PUT /consents/ai} on the current notice.
	 * @param version the consent version the decision replaces
	 */
	MvcTestResult decide(Installed installed, String key, String decision, long version) {
		return mvc.put()
			.uri("/consents/ai")
			.header("API-Version", "1")
			.header("Idempotency-Key", key)
			.header(HttpHeaders.AUTHORIZATION, installed.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\": \"%s\", \"noticeVersion\": 1, \"version\": %d}".formatted(decision, version))
			.exchange();
	}

	MvcTestResult intake(Installed installed, String key, String body) {
		return intake(installed, key, body, Map.of());
	}

	MvcTestResult intake(Installed installed, String key, String body, Map<String, String> headers) {
		var request = mvc.post()
			.uri("/analysis-jobs")
			.header("API-Version", "1")
			.header("Idempotency-Key", key)
			.header(HttpHeaders.AUTHORIZATION, installed.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body.getBytes(StandardCharsets.UTF_8));
		headers.forEach((name, value) -> request.header(name, value));
		return request.exchange();
	}

	/**
	 * The iOS device check token and a valid fake assertion over the challenge of an {@code ATTESTATION_REQUIRED}
	 * response.
	 */
	static Map<String, String> evidence(Installed installed, String assertion, JsonNode challenge) {
		return Map.of("Device-Check-Token", String.valueOf(installed.deviceCheckToken()), "App-Attest-Assertion",
				assertion, "App-Attest-Challenge-Id", challenge.get("challengeId").asString(), "App-Attest-Nonce",
				challenge.get("nonce").asString());
	}

	static Map<String, String> evidence(Installed installed, String assertion) {
		return Map.of("Device-Check-Token", String.valueOf(installed.deviceCheckToken()), "App-Attest-Assertion",
				assertion, "App-Attest-Challenge-Id", UuidV7.generate().toString(), "App-Attest-Nonce", "bm9uY2U");
	}

	static JsonNode json(MvcTestResult result) {
		return JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	static String body(String trigger, Photo... photos) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Photo photo : photos) {
			list.add(photo.json());
		}
		return JSON.writeValueAsString(Map.of("trigger", trigger, "photos", list));
	}

	static String key() {
		return UuidV7.generate().toString();
	}

	static Photo photo(String version) {
		return new Photo(UUID.randomUUID(), version, null, null, null, null);
	}

	static Photo photo(UUID photoId, String version) {
		return new Photo(photoId, version, null, null, null, null);
	}

	static String vector(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return Base64.getEncoder().encodeToString(buffer.array());
	}

	/**
	 * @param takenAt RFC 3339 text, sent as given
	 * @param vectorData base64 of float32 little-endian values with model {@code pik-embed} version {@code 1}
	 * @param textStatus {@code text.status}
	 */
	record Photo(UUID photoId, String contentVersion, @Nullable String takenAt, @Nullable String vectorData,
			@Nullable String textStatus, @Nullable String model) {

		Photo takenAt(Instant instant) {
			return new Photo(photoId, contentVersion, instant.toString(), vectorData, textStatus, model);
		}

		Photo takenAt(String text) {
			return new Photo(photoId, contentVersion, text, vectorData, textStatus, model);
		}

		Photo vector(String data) {
			return new Photo(photoId, contentVersion, takenAt, data, textStatus, model);
		}

		Photo vector(String modelId, String data) {
			return new Photo(photoId, contentVersion, takenAt, data, textStatus, modelId);
		}

		Photo text(String status) {
			return new Photo(photoId, contentVersion, takenAt, vectorData, status, model);
		}

		Photo version(String version) {
			return new Photo(photoId, version, takenAt, vectorData, textStatus, model);
		}

		/**
		 * The photo as grouping takes it: taken at the given time, a vector and no text.
		 */
		Photo groupable(Instant instant, String data) {
			return takenAt(instant).vector(data).text("NO_TEXT");
		}

		private Map<String, Object> json() {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("photoId", photoId.toString());
			json.put("contentVersion", contentVersion);
			if (takenAt != null) {
				json.put("takenAt", takenAt);
			}
			if (vectorData != null) {
				json.put("vector", Map.of("model", (model != null) ? model : "pik-embed", "modelVersion", "1", "data",
						vectorData));
			}
			if (textStatus != null) {
				json.put("text", Map.of("status", textStatus));
			}
			return json;
		}

	}

	/**
	 * @param trialMarkId the device's trial mark
	 * @param deviceCheckToken the token an iOS install registered with, {@code null} for Android
	 */
	record Installed(UUID principalId, UUID deviceId, @Nullable UUID trialMarkId, String bearer,
			@Nullable String deviceCheckToken) {

	}

}
