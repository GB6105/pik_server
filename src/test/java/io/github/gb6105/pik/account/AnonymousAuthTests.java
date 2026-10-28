package io.github.gb6105.pik.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
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

	@Test
	void installStoresPrincipalInstallAndDeviceWithHashedToken() {
		MvcTestResult result = install("""
				{"platform": "IOS", "appVersion": "1.0.0", "attestation": {"token": "%s", "keyId": "k"}}
				""".formatted(FakeAttestationVerifier.VALID_TOKEN));

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
	}

	@Test
	void failedAttestationCreatesNothing() {
		long principals = count("principal");

		assertThat(install("""
				{"platform": "ANDROID", "attestation": {"token": "forged"}}
				""")).hasStatus(403).bodyJson().isLenientlyEqualTo("""
				{"code": "FORBIDDEN", "reason": "ATTESTATION_FAILED", "retryable": false}
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
	void lastSeenMovesAtMostOncePerInterval() {
		Installed installed = installAndroid();
		OffsetDateTime stale = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).truncatedTo(ChronoUnit.MICROS);
		jdbc.sql("UPDATE anonymous_install SET last_seen_at = :at WHERE principal_id = :id")
			.param("at", stale)
			.param("id", installed.principalId())
			.update();
		jdbc.sql("UPDATE device SET last_seen_at = :at WHERE principal_id = :id")
			.param("at", stale)
			.param("id", installed.principalId())
			.update();

		assertThat(token(installed.token())).hasStatusOk();
		Instant installSeen = lastSeen("anonymous_install", installed.principalId());
		Instant deviceSeen = lastSeen("device", installed.principalId());
		assertThat(installSeen).isAfter(stale.toInstant());
		assertThat(deviceSeen).isAfter(stale.toInstant());

		assertThat(token(installed.token())).hasStatusOk();
		assertThat(lastSeen("anonymous_install", installed.principalId())).isEqualTo(installSeen);
		assertThat(lastSeen("device", installed.principalId())).isEqualTo(deviceSeen);
	}

	@Test
	void logsCarryThePrincipalButNoTokens(CapturedOutput output) {
		Installed installed = installAndroid();
		String accessToken = (String) json(token(installed.token())).get("accessToken");

		assertThat(output.getOut().lines().filter((line) -> line.contains("\"request completed\""))
			.filter((line) -> line.contains(installed.principalId().toString()))).hasSize(2);
		assertThat(output.getAll()).doesNotContain(installed.token(), accessToken);
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
		MvcTestResult result = install("""
				{"platform": "ANDROID", "attestation": {"token": "%s"}}
				""".formatted(FakeAttestationVerifier.VALID_TOKEN));
		assertThat(result).hasStatus(201);
		Map<String, Object> body = json(result);
		UUID principalId = jdbc.sql("SELECT principal_id FROM device WHERE install_id = :id")
			.param("id", UUID.fromString((String) body.get("installId")))
			.query(UUID.class)
			.single();
		return new Installed(principalId, (String) body.get("installToken"));
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
		assertThat(result).hasStatus(401)
			.hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "INSTALL_TOKEN_INVALID", "retryable": false}
					""");
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
