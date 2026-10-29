package io.github.gb6105.pik.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.mockito.Mockito.doReturn;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.security.AccessTokens;
import io.github.gb6105.pik.shared.security.PrincipalKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AiConsentTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AccessTokens accessTokens;

	@Autowired
	AiConsents consents;

	@MockitoSpyBean
	ConsentRecords records;

	@Test
	void beforeAnyDecisionOnlyBasicFeaturesAndTheCurrentNotice() {
		UUID principal = UuidV7.generate();

		assertThat(current(principal)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"consent": {"purpose": "AI_ANALYSIS_AND_SEARCH", "status": "NONE", "version": 0,
					"noticeVersion": null, "decidedAt": null, "reconsentRequired": false,
					"features": ["MANUAL_ORGANIZE", "MEMO", "TAG", "ATTRIBUTE_SEARCH"]},
				 "notice": %s}
				""".formatted(servedNotice()));
		assertThat(consents.isGranted(principal)).isFalse();
	}

	@Test
	void grantIsRecordedAndEnablesAnalysis() {
		UUID principal = UuidV7.generate();
		String key = UuidV7.generate().toString();

		MvcTestResult result = decide(principal, key, "GRANTED", 1, 0);

		assertThat(result).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"jobId": "%s", "result": "SUCCEEDED", "retryable": false,
				 "consent": {"purpose": "AI_ANALYSIS_AND_SEARCH", "status": "GRANTED", "version": 1, "noticeVersion": 1,
					"reconsentRequired": false, "features": ["MANUAL_ORGANIZE", "MEMO", "TAG", "ATTRIBUTE_SEARCH",
					"PHOTO_ANALYSIS", "NATURAL_LANGUAGE_SEARCH"]}}
				""".formatted(key));
		assertThat(json(result)).extractingByKey("consent").asInstanceOf(MAP).containsKey("decidedAt");
		assertThat(rows(principal)).singleElement()
			.satisfies((row) -> assertThat(row).containsEntry("decision", "GRANTED")
				.containsEntry("notice_version", 1)
				.containsEntry("version", 1L)
				.containsEntry("purpose", "AI_ANALYSIS_AND_SEARCH")
				.containsEntry("device_id", null));
		assertThat(consents.isGranted(principal)).isTrue();
		assertThat(current(principal)).bodyJson().extractingPath("$.consent.status").isEqualTo("GRANTED");
	}

	@Test
	void refusingAfterAGrantIsAWithdrawal() {
		UUID principal = UuidV7.generate();
		assertThat(decide(principal, "GRANTED", 1, 0)).hasStatusOk();

		assertThat(decide(principal, "DENIED", 1, 1)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"result": "SUCCEEDED", "consent": {"status": "WITHDRAWN", "version": 2,
					"features": ["MANUAL_ORGANIZE", "MEMO", "TAG", "ATTRIBUTE_SEARCH"]}}
				""");
		assertThat(consents.isGranted(principal)).isFalse();
		assertThat(rows(principal)).extracting((row) -> row.get("decision")).containsExactly("GRANTED", "WITHDRAWN");
	}

	@Test
	void firstRefusalIsADenial() {
		UUID principal = UuidV7.generate();

		assertThat(decide(principal, "DENIED", 1, 0)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.consent.status")
			.isEqualTo("DENIED");
		assertThat(consents.isGranted(principal)).isFalse();
	}

	@Test
	void staleVersionIsRejectedWithoutRecording() {
		UUID principal = UuidV7.generate();
		assertThat(decide(principal, "GRANTED", 1, 0)).hasStatusOk();
		String key = UuidV7.generate().toString();

		assertThat(decide(principal, key, "DENIED", 1, 0)).hasStatus(409).bodyJson().isLenientlyEqualTo("""
				{"code": "CONFLICT", "reason": "VERSION_CONFLICT", "retryable": false, "jobId": "%s",
				 "result": "FAILED"}
				""".formatted(key));
		assertThat(rows(principal)).hasSize(1);
		assertThat(consents.isGranted(principal)).isTrue();
	}

	@Test
	void repeatingTheLatestDecisionOnTheSameNoticeAddsNoRecord() {
		UUID granting = UuidV7.generate();
		assertThat(decide(granting, "GRANTED", 1, 0)).hasStatusOk();
		assertThat(decide(granting, "GRANTED", 1, 1)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.consent.version")
			.isEqualTo(1);
		assertThat(rows(granting)).hasSize(1);

		UUID refusing = UuidV7.generate();
		assertThat(decide(refusing, "DENIED", 1, 0)).hasStatusOk();
		assertThat(decide(refusing, "DENIED", 1, 1)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.consent.status")
			.isEqualTo("DENIED");
		assertThat(rows(refusing)).hasSize(1);
	}

	@Test
	void retransmissionGetsTheFirstResponse() throws Exception {
		UUID principal = UuidV7.generate();
		String key = UuidV7.generate().toString();

		String first = decide(principal, key, "GRANTED", 1, 0).getResponse().getContentAsString();
		assertThat(decide(principal, key, "GRANTED", 1, 0)).hasStatusOk().bodyJson().isStrictlyEqualTo(first);
		assertThat(rows(principal)).hasSize(1);
	}

	@Test
	void noticeVersionMustBeOneTheServerServed() {
		UUID principal = UuidV7.generate();

		assertThat(decide(principal, "GRANTED", 2, 0)).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "UNKNOWN_NOTICE_VERSION", "result": "FAILED"}
				""");
		assertThat(decide(principal, "DENIED", 2, 0)).hasStatus(400);
		assertThat(rows(principal)).isEmpty();
	}

	@Test
	void malformedDecisionsAreRejected() {
		UUID principal = UuidV7.generate();

		assertThat(decide(principal, UuidV7.generate().toString(), """
				{"decision": "WITHDRAWN", "noticeVersion": 1, "version": 0}
				""")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "MALFORMED_BODY"}
				""");
		for (String body : List.of("""
				{"decision": "GRANTED", "noticeVersion": 0, "version": 0}
				""", """
				{"decision": "GRANTED", "noticeVersion": 1, "version": -1}
				""", """
				{"decision": "GRANTED", "noticeVersion": 1}
				""", """
				{"noticeVersion": 1, "version": 0}
				""")) {
			assertThat(decide(principal, UuidV7.generate().toString(), body)).hasStatus(400)
				.bodyJson()
				.isLenientlyEqualTo("""
						{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED"}
						""");
		}
		assertThat(mvc.put()
			.uri(AiConsentController.PATH)
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, bearer(principal))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"decision": "GRANTED", "noticeVersion": 1, "version": 0}
					""")
			.exchange()).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"reason": "IDEMPOTENCY_KEY_REQUIRED"}
				""");
		assertThat(rows(principal)).isEmpty();
	}

	@Test
	void concurrentDecisionsOnTheSameVersionRecordOnce() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID principal = UuidV7.generate();

			List<MvcTestResult> results = parallel(8, () -> decide(principal, "GRANTED", 1, 0));

			assertThat(results).filteredOn((result) -> result.getResponse().getStatus() == 200).hasSize(1);
			assertThat(results).filteredOn((result) -> result.getResponse().getStatus() != 200)
				.hasSize(7)
				.allSatisfy((result) -> assertThat(result).hasStatus(409)
					.bodyJson()
					.extractingPath("$.reason")
					.isEqualTo("VERSION_CONFLICT"));
			assertThat(rows(principal)).hasSize(1);
		}
	}

	@Test
	void decisionThatLostTheVersionRaceIsAConflict() {
		UUID principal = UuidV7.generate();
		assertThat(decide(principal, "GRANTED", 1, 0)).hasStatusOk();
		// As if this decision had read the consent before the grant above was committed.
		doReturn(Optional.empty()).when(records).findLatest(principal, ConsentPurpose.AI_ANALYSIS_AND_SEARCH);

		assertThat(decide(principal, "DENIED", 1, 0)).hasStatus(409)
			.bodyJson()
			.extractingPath("$.reason")
			.isEqualTo("VERSION_CONFLICT");
		assertThat(rows(principal)).extracting((row) -> row.get("decision")).containsExactly("GRANTED");
	}

	@Test
	void consentBelongsToTheRequestingPrincipal() {
		UUID granting = UuidV7.generate();
		UUID other = UuidV7.generate();
		assertThat(decide(granting, "GRANTED", 1, 0)).hasStatusOk();

		assertThat(current(other)).bodyJson().extractingPath("$.consent.status").isEqualTo("NONE");
		assertThat(consents.isGranted(other)).isFalse();
	}

	@Test
	void consentNeedsAnAccessToken() {
		assertThat(mvc.get().uri(AiConsentController.PATH).header("API-Version", "1").exchange()).hasStatus(401);
	}

	private MvcTestResult current(UUID principal) {
		return mvc.get()
			.uri(AiConsentController.PATH)
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, bearer(principal))
			.exchange();
	}

	private MvcTestResult decide(UUID principal, String decision, int noticeVersion, long version) {
		return decide(principal, UuidV7.generate().toString(), decision, noticeVersion, version);
	}

	private MvcTestResult decide(UUID principal, String key, String decision, int noticeVersion, long version) {
		return decide(principal, key, """
				{"decision": "%s", "noticeVersion": %d, "version": %d}
				""".formatted(decision, noticeVersion, version));
	}

	private MvcTestResult decide(UUID principal, String key, String body) {
		return mvc.put()
			.uri(AiConsentController.PATH)
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, bearer(principal))
			.header(IdempotencyKey.HEADER, key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private String bearer(UUID principal) {
		return "Bearer " + accessTokens.issue(principal, PrincipalKind.ANONYMOUS).value();
	}

	private List<Map<String, Object>> rows(UUID principal) {
		return jdbc.sql("SELECT * FROM ai_consent WHERE principal_id = :id ORDER BY version")
			.param("id", principal)
			.query()
			.listOfRows();
	}

	// The bundled notice as clients receive it: the draft flag stays on the server.
	private static String servedNotice() {
		@SuppressWarnings("unchecked")
		Map<String, Object> notice = JSON
			.readValue(AiConsentTests.class.getResourceAsStream("/consent/ai-notice-v1.json"), Map.class);
		notice.remove("draft");
		return JSON.writeValueAsString(notice);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> json(MvcTestResult result) {
		return JSON.readValue(result.getResponse().getContentAsByteArray(), Map.class);
	}

	private static <T> List<T> parallel(int count, Callable<T> task) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
	}

}
