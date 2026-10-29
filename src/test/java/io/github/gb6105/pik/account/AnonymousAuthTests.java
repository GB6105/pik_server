package io.github.gb6105.pik.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AnonymousAuthTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtDecoder jwtDecoder;

	@MockitoSpyBean
	AttestationVerifier attestationVerifier;

	@Test
	void installStoresPrincipalInstallAndDeviceWithHashedToken() {
		MvcTestResult result = install("""
				{"platform": "IOS", "appVersion": "1.0.0", "attestation": {"token": "%s", "keyId": "k"},
				 "trialDevice": {"deviceCheckToken": "fake-device-check:%s"}}
				""".formatted(FakeAttestationVerifier.VALID_TOKEN, UUID.randomUUID()));

		assertThat(result).hasStatus(201);
		Map<String, Object> body = json(result);
		UUID installId = UUID.fromString((String) body.get("installId"));
		String installToken = (String) body.get("installToken");
		assertThat(installId.version()).isEqualTo(7);
		assertThat(installToken).matches("[A-Za-z0-9_-]{43}");

		Map<String, Object> device = jdbc.sql("SELECT * FROM device WHERE install_id = :id")
			.param("id", installId)
			.query()
			.singleRow();
		UUID principalId = (UUID) device.get("principal_id");
		assertThat(device).containsEntry("platform", "IOS").containsEntry("app_version", "1.0.0");
		assertThat(jdbc.sql("SELECT platform, exhausted_at FROM device_trial_mark WHERE id = :id")
			.param("id", device.get("trial_mark_id"))
			.query()
			.singleRow()).containsEntry("platform", "IOS").containsEntry("exhausted_at", null);
		assertThat(jdbc.sql("SELECT kind, status FROM principal WHERE id = :id")
			.param("id", principalId)
			.query()
			.singleRow()).containsEntry("kind", "ANONYMOUS")
			.containsEntry("status", "ACTIVE");
		assertThat(jdbc.sql("SELECT * FROM anonymous_install WHERE principal_id = :id")
			.param("id", principalId)
			.query()
			.singleRow()).containsEntry("token_hash", InstallTokens.hash(installToken))
			.containsEntry("platform", "IOS")
			.containsEntry("attestation_kind", "APP_ATTEST")
			.doesNotContainValue(installToken);
	}

	@Test
	void installTokenIsExchangedForAccessToken() {
		Installed installed = installAndroid();

		MvcTestResult result = token(installed.token());

		assertThat(result).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"tokenType": "Bearer", "expiresIn": 900}
				""");
		Jwt jwt = jwtDecoder.decode((String) json(result).get("accessToken"));
		assertThat(jwt.getSubject()).isEqualTo(installed.principalId().toString());
		assertThat(jwt.getClaimAsString("kind")).isEqualTo("ANONYMOUS");
		assertThat(jwt.getClaimAsString("did")).isEqualTo(jdbc.sql("SELECT id FROM device WHERE principal_id = :id")
			.param("id", installed.principalId())
			.query(UUID.class)
			.single()
			.toString());
	}

	@Test
	void attestationRunsOutsideTheTransaction() {
		AtomicBoolean inTransaction = new AtomicBoolean(true);
		doAnswer((invocation) -> {
			inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(attestationVerifier).verify(any(), any());

		installAndroid();

		assertThat(inTransaction).isFalse();
	}

	@Test
	void tokenEndpointsIgnoreAnInvalidAccessToken() {
		Installed installed = installAndroid();

		assertThat(mvc.post()
			.uri("/auth/token")
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"grantType": "install_token", "installToken": "%s"}
					""".formatted(installed.token()))).hasStatusOk();
		assertThat(mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"platform": "IOS", "attestation": {"token": "%s"},
					 "trialDevice": {"deviceCheckToken": "fake-device-check:%s"}}
					""".formatted(FakeAttestationVerifier.VALID_TOKEN, UUID.randomUUID()))).hasStatus(201);
	}

	@Test
	void failedAttestationCreatesNothing() {
		long principals = count("principal");

		assertThat(install("""
				{"platform": "ANDROID", "attestation": {"token": "forged"}, "trialDevice": {"androidId": "%s"}}
				""".formatted(androidId()))).hasStatus(403).bodyJson().isLenientlyEqualTo("""
				{"code": "FORBIDDEN", "reason": "ATTESTATION_FAILED", "retryable": false}
				""");
		assertThat(count("principal")).isEqualTo(principals);
	}

	@Test
	void trialDeviceMustBeThePlatformsValueOnly() {
		long principals = count("principal");
		String android = """
				{"platform": "ANDROID", "attestation": {"token": "%s"}, "trialDevice": %s}
				""";
		String ios = """
				{"platform": "IOS", "attestation": {"token": "%s"}, "trialDevice": %s}
				""";

		for (String body : List.of(android.formatted(FakeAttestationVerifier.VALID_TOKEN, "{}"),
				android.formatted(FakeAttestationVerifier.VALID_TOKEN, "{\"androidId\": \" \"}"),
				android.formatted(FakeAttestationVerifier.VALID_TOKEN, "{\"androidId\": \"not-hex\"}"),
				android.formatted(FakeAttestationVerifier.VALID_TOKEN, "{\"deviceCheckToken\": \"fake-device-check:a\"}"),
				android.formatted(FakeAttestationVerifier.VALID_TOKEN,
						"{\"androidId\": \"%s\", \"deviceCheckToken\": \"fake-device-check:a\"}".formatted(androidId())),
				ios.formatted(FakeAttestationVerifier.VALID_TOKEN, "{\"androidId\": \"%s\"}".formatted(androidId())))) {
			assertThat(install(body)).hasStatus(400).bodyJson().isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "TRIAL_DEVICE_INVALID", "retryable": false}
					""");
		}
		assertThat(install(android.formatted(FakeAttestationVerifier.VALID_TOKEN, "null"))).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED"}
					""");
		assertThat(count("principal")).isEqualTo(principals);
	}

	@Test
	void malformedInstallRequestIsInvalidInput() {
		assertThat(install("""
				{"platform": "IOS"}
				""")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED"}
				""");
		assertThat(install("""
				{"platform": "WINDOWS", "attestation": {"token": "fake-attestation"}}
				""")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "MALFORMED_BODY"}
				""");
	}

	@Test
	void unknownRevokedOrDeletedInstallTokenIsUnauthenticated() {
		assertInstallTokenInvalid(token(InstallTokens.generate()));

		Installed revoked = installAndroid();
		jdbc.sql("UPDATE anonymous_install SET revoked_at = now() WHERE principal_id = :id")
			.param("id", revoked.principalId())
			.update();
		assertInstallTokenInvalid(token(revoked.token()));

		Installed deleted = installAndroid();
		jdbc.sql("UPDATE principal SET status = 'DELETED' WHERE id = :id").param("id", deleted.principalId()).update();
		assertInstallTokenInvalid(token(deleted.token()));
	}

	@Test
	void tokenRequestNeedsKnownGrantTypeAndInstallToken() {
		assertThat(exchange("""
				{"grantType": "password", "installToken": "x"}
				""")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "UNSUPPORTED_GRANT_TYPE"}
				""");
		assertThat(exchange("""
				{"grantType": "install_token"}
				""")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "INVALID_INPUT", "reason": "VALIDATION_FAILED"}
				""");
	}

	@Test
	void lastSeenMovesAtMostOncePerIntervalWithoutDeviceVersion() {
		Installed installed = installAndroid();
		OffsetDateTime stale = makeStale(installed.principalId());
		long deviceVersion = deviceVersion(installed.principalId());

		assertThat(token(installed.token())).hasStatusOk();
		Instant installSeen = lastSeen("anonymous_install", installed.principalId());
		Instant deviceSeen = lastSeen("device", installed.principalId());
		assertThat(installSeen).isAfter(stale.toInstant());
		assertThat(deviceSeen).isAfter(stale.toInstant());

		assertThat(token(installed.token())).hasStatusOk();
		assertThat(lastSeen("anonymous_install", installed.principalId())).isEqualTo(installSeen);
		assertThat(lastSeen("device", installed.principalId())).isEqualTo(deviceSeen);
		assertThat(deviceVersion(installed.principalId())).isEqualTo(deviceVersion);
	}

	@Test
	void concurrentExchangesOfAStaleInstallAllSucceed() throws Exception {
		Installed installed = installAndroid();

		for (int round = 0; round < 5; round++) {
			makeStale(installed.principalId());
			List<Integer> statuses = parallel(8, () -> token(installed.token()).getResponse().getStatus());
			assertThat(statuses).containsOnly(200);
		}
	}

	@Test
	void logsCarryThePrincipalButNoTokens(CapturedOutput output) {
		String androidId = androidId();
		Installed installed = installAndroid(androidId);
		String accessToken = (String) json(token(installed.token())).get("accessToken");

		assertThat(output.getOut().lines().filter((line) -> line.contains("\"request completed\""))
			.filter((line) -> line.contains(installed.principalId().toString()))).hasSize(2);
		assertThat(output.getAll()).doesNotContain(installed.token(), accessToken, androidId);
	}

	@Test
	void fakeAttestationIsRefusedOutsideDevelopmentProfiles() {
		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		assertThatIllegalStateException().isThrownBy(() -> new AccountConfig().attestationVerifier(production));
		assertThat(new AccountConfig().attestationVerifier(local)).isInstanceOf(FakeAttestationVerifier.class);
	}

	private Installed installAndroid() {
		return installAndroid(androidId());
	}

	private Installed installAndroid(String androidId) {
		MvcTestResult result = install("""
				{"platform": "ANDROID", "attestation": {"token": "%s"}, "trialDevice": {"androidId": "%s"}}
				""".formatted(FakeAttestationVerifier.VALID_TOKEN, androidId));
		assertThat(result).hasStatus(201);
		Map<String, Object> body = json(result);
		UUID principalId = jdbc.sql("SELECT principal_id FROM device WHERE install_id = :id")
			.param("id", UUID.fromString((String) body.get("installId")))
			.query(UUID.class)
			.single();
		return new Installed(principalId, (String) body.get("installToken"));
	}

	private static String androidId() {
		byte[] id = new byte[8];
		ThreadLocalRandom.current().nextBytes(id);
		return HexFormat.of().formatHex(id);
	}

	private MvcTestResult install(String body) {
		return mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private MvcTestResult token(String installToken) {
		return exchange("""
				{"grantType": "install_token", "installToken": "%s"}
				""".formatted(installToken));
	}

	private MvcTestResult exchange(String body) {
		return mvc.post()
			.uri("/auth/token")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private static void assertInstallTokenInvalid(MvcTestResult result) {
		assertThat(result.getResponse().getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
		assertThat(result).hasStatus(401)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "INSTALL_TOKEN_INVALID", "retryable": false}
					""");
	}

	private OffsetDateTime makeStale(UUID principalId) {
		OffsetDateTime stale = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).truncatedTo(ChronoUnit.MICROS);
		for (String table : List.of("anonymous_install", "device")) {
			jdbc.sql("UPDATE " + table + " SET last_seen_at = :at WHERE principal_id = :id")
				.param("at", stale)
				.param("id", principalId)
				.update();
		}
		return stale;
	}

	private long deviceVersion(UUID principalId) {
		return jdbc.sql("SELECT version FROM device WHERE principal_id = :id")
			.param("id", principalId)
			.query(Long.class)
			.single();
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

	private Instant lastSeen(String table, UUID principalId) {
		return jdbc.sql("SELECT last_seen_at FROM " + table + " WHERE principal_id = :id")
			.param("id", principalId)
			.query(OffsetDateTime.class)
			.single()
			.toInstant();
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> json(MvcTestResult result) {
		return JSON.readValue(result.getResponse().getContentAsByteArray(), Map.class);
	}

	record Installed(UUID principalId, String token) {
	}

}
