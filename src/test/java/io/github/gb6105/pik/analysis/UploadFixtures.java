package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * Intake requests with jobs to upload for, upload attempts, uploads to the presigned URLs on S3Mock, confirmations
 * and status queries through the HTTP API, for the stage 5-2 upload tests (plan 11.2).
 */
final class UploadFixtures {

	// java.net.http sets these itself, from the URL and the body.
	private static final Set<String> CLIENT_HEADERS = Set.of("host", "content-length");

	private final MockMvcTester mvc;

	private final JdbcClient jdbc;

	private final IntakeFixtures intake;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final Random random = new Random();

	UploadFixtures(MockMvcTester mvc, JdbcClient jdbc) {
		this.mvc = mvc;
		this.jdbc = jdbc;
		this.intake = new IntakeFixtures(mvc, jdbc);
	}

	IntakeFixtures intake() {
		return intake;
	}

	/**
	 * An intake request of single photos, each accepted with a job of its own.
	 */
	Accepted accepted(Installed device, int photos) {
		List<Photo> sent = new ArrayList<>();
		for (int i = 0; i < photos; i++) {
			sent.add(photo("v1"));
		}
		MvcTestResult result = intake.intake(device, key(), body("NEW_PHOTOS", sent.toArray(Photo[]::new)));
		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		List<UUID> jobIds = new ArrayList<>();
		response.get("jobs").forEach((job) -> jobIds.add(UUID.fromString(job.get("analysisJobId").asString())));
		assertThat(jobIds).hasSize(photos);
		return new Accepted(UUID.fromString(response.get("jobId").asString()), jobIds);
	}

	MvcTestResult issue(Installed device, String key, UUID requestId, String body) {
		return mvc.post()
			.uri("/analysis-jobs/{requestId}/upload-attempts", requestId)
			.header("API-Version", "1")
			.header("Idempotency-Key", key)
			.header(HttpHeaders.AUTHORIZATION, device.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	/**
	 * Issues an attempt for one job and returns its item.
	 */
	JsonNode issued(Installed device, UUID requestId, UUID jobId, Image image) {
		MvcTestResult result = issue(device, key(), requestId, items(Map.of(jobId, image)));
		assertThat(result).hasStatusOk();
		JsonNode item = json(result).get("items").get(0);
		assertThat(item.get("result").asString()).isEqualTo("ISSUED");
		return item;
	}

	MvcTestResult complete(Installed device, String key, UUID requestId, UUID attemptId) {
		return mvc.post()
			.uri("/analysis-jobs/{requestId}/upload-attempts/{attemptId}/complete", requestId, attemptId)
			.header("API-Version", "1")
			.header("Idempotency-Key", key)
			.header(HttpHeaders.AUTHORIZATION, device.bearer())
			.exchange();
	}

	MvcTestResult status(Installed device, UUID requestId, @Nullable String ifNoneMatch) {
		var request = mvc.get()
			.uri("/analysis-jobs/{requestId}", requestId)
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, device.bearer());
		if (ifNoneMatch != null) {
			request.header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
		}
		return request.exchange();
	}

	/**
	 * Uploads the image as a device does: to the item's URL with the signed headers.
	 * @return the HTTP status of the PUT
	 */
	int put(JsonNode item, byte[] bytes) throws Exception {
		JsonNode upload = item.get("upload");
		assertThat(upload.get("method").asString()).isEqualTo("PUT");
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asString()))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
		upload.get("headers").properties().forEach((header) -> {
			if (!CLIENT_HEADERS.contains(header.getKey())) {
				request.header(header.getKey(), header.getValue().asString());
			}
		});
		return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	Image image(int size) {
		byte[] bytes = new byte[size];
		random.nextBytes(bytes);
		return new Image(bytes);
	}

	static String items(Map<UUID, Image> images) {
		List<Map<String, Object>> items = new ArrayList<>();
		images.forEach((jobId, image) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("analysisJobId", jobId.toString());
			item.put("contentLength", image.bytes().length);
			item.put("sha256", image.sha256Base64());
			items.add(item);
		});
		return IntakeFixtures.JSON.writeValueAsString(Map.of("items", items));
	}

	Map<String, Object> attempt(UUID attemptId) {
		return jdbc.sql("SELECT * FROM analysis_upload_attempt WHERE id = :id").param("id", attemptId).query().singleRow();
	}

	Map<String, Object> job(UUID jobId) {
		return jdbc.sql("SELECT * FROM analysis_job WHERE id = :id").param("id", jobId).query().singleRow();
	}

	List<String> attemptStatuses(UUID jobId) {
		return jdbc.sql("SELECT status FROM analysis_upload_attempt WHERE job_id = :job ORDER BY id")
			.param("job", jobId)
			.query(String.class)
			.list();
	}

	Optional<Integer> storedStatus(Installed device, String key) {
		return jdbc.sql("SELECT response_code FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", device.principalId())
			.param("key", UUID.fromString(key))
			.query(Integer.class)
			.optional();
	}

	String storedBody(Installed device, String key) {
		return jdbc.sql("""
				SELECT response_body::text FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key
				""")
			.param("owner", device.principalId())
			.param("key", UUID.fromString(key))
			.query(String.class)
			.single();
	}

	List<String> reservations(UUID jobId) {
		return jdbc.sql("SELECT status FROM usage_reservation WHERE job_id = :job")
			.param("job", jobId)
			.query(String.class)
			.list();
	}

	/**
	 * Withdraws the device's consent with a refusal on the current notice.
	 */
	void withdrawConsent(Installed device) {
		long version = jdbc.sql("SELECT count(*) FROM ai_consent WHERE principal_id = :id")
			.param("id", device.principalId())
			.query(Long.class)
			.single();
		assertThat(intake.decide(device, key(), "DENIED", version)).hasStatusOk();
	}

	static void assertError(JsonNode problem, String key, String code, String reason, boolean retryable) {
		assertThat(problem.get("code").asString()).isEqualTo(code);
		assertThat(problem.get("reason").asString()).isEqualTo(reason);
		assertThat(problem.get("retryable").asBoolean()).isEqualTo(retryable);
		assertThat(problem.get("jobId").asString()).isEqualTo(key);
		assertThat(problem.get("result").asString()).isEqualTo("FAILED");
	}

	static void assertRejected(JsonNode item, String code, String reason, boolean retryable) {
		assertThat(item.get("result").asString()).isEqualTo("REJECTED");
		assertThat(item.get("code").asString()).isEqualTo(code);
		assertThat(item.get("reason").asString()).isEqualTo(reason);
		assertThat(item.get("retryable").asBoolean()).isEqualTo(retryable);
		assertThat(item.get("upload").isNull()).isTrue();
	}

	/**
	 * @param jobIds the jobs of the request, in the order of the response
	 */
	record Accepted(UUID requestId, List<UUID> jobIds) {

		UUID job() {
			return jobIds.get(0);
		}

	}

	record Image(byte[] bytes) {

		byte[] sha256() {
			try {
				return MessageDigest.getInstance("SHA-256").digest(bytes);
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

		String sha256Base64() {
			return Base64.getEncoder().encodeToString(sha256());
		}

	}

}
