package io.github.gb6105.pik.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentCommand;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@Import({ TestcontainersConfiguration.class, SecurityTests.ProbeController.class })
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityTests {

	static final ECKey CURRENT = generate("current");

	static final ECKey PREVIOUS = generate("previous");

	static final ECKey UNLISTED = generate("unlisted");

	// Duplicate fields would mean principal_id came from both the MDC and the completion line.
	static final JsonMapper STRICT_JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	@DynamicPropertySource
	static void keys(DynamicPropertyRegistry registry) {
		registry.add("pik.security.jwt.jwk-set",
				() -> new JWKSet(List.<JWK>of(CURRENT, PREVIOUS.toPublicJWK())).toString(false));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	AccessTokens accessTokens;

	@Autowired
	JwtProperties properties;

	final UUID principalId = UuidV7.generate();

	@Test
	void issuedTokenIdentifiesThePrincipal() {
		String token = accessTokens.issue(principalId, PrincipalKind.ANONYMOUS).value();

		assertThat(whoami("Bearer " + token)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"id": "%s", "kind": "ANONYMOUS"}
				""".formatted(principalId));
	}

	@Test
	void issuedTokenCarriesOnlyTheAgreedClaims() throws Exception {
		SignedJWT jwt = SignedJWT.parse(accessTokens.issue(principalId, PrincipalKind.ACCOUNT).value());

		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
		assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
		assertThat(jwt.getHeader().getKeyID()).isEqualTo("current");
		assertThat(jwt.getJWTClaimsSet().getClaims()).containsOnlyKeys("iss", "sub", "kind", "iat", "exp")
			.containsEntry("sub", principalId.toString())
			.containsEntry("kind", "ACCOUNT");
		assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
			.isEqualTo(jwt.getJWTClaimsSet().getIssueTime().toInstant().plus(properties.accessTokenTtl()));
	}

	@Test
	void tokenOfPreviousKeyStillVerifies() {
		assertThat(whoami(bearer(PREVIOUS, "previous", (claims) -> claims))).hasStatusOk();
	}

	@Test
	void tokenOfUnlistedKeyIsInvalid() {
		assertInvalid(whoami(bearer(UNLISTED, "unlisted", (claims) -> claims)));
		assertInvalid(whoami(bearer(UNLISTED, "current", (claims) -> claims)));
	}

	@Test
	void tokenWithoutKeyIdIsInvalid() {
		assertInvalid(whoami(bearer(CURRENT, null, (claims) -> claims)));
	}

	@Test
	void tokenOfOtherTypeIsInvalid() {
		JWTClaimsSet claims = validClaims().build();

		assertInvalid(whoami(bearer(CURRENT, header("current").type(new JOSEObjectType("at+jwt")).build(), claims)));
		assertInvalid(whoami(bearer(CURRENT, header("current").type(null).build(), claims)));
	}

	@Test
	void tokenOfOtherAlgorithmIsInvalid() throws JOSEException {
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).keyID("current").build();
		String hmac = sign(header, new MACSigner(new byte[32]), validClaims().build());
		String unsigned = new PlainJWT(validClaims().build()).serialize();

		assertInvalid(whoami("Bearer " + hmac));
		assertInvalid(whoami("Bearer " + unsigned));
	}

	@Test
	void expiredTokenIsInvalidAfterClockSkew() {
		Instant beyondSkew = Instant.now().minus(properties.clockSkew()).minusSeconds(5);
		Instant withinSkew = Instant.now().minus(properties.clockSkew()).plusSeconds(30);

		assertInvalid(whoami(bearer(CURRENT, "current", expiringAt(beyondSkew))));
		assertThat(whoami(bearer(CURRENT, "current", expiringAt(withinSkew)))).hasStatusOk();
	}

	@Test
	void tokenWithUnexpectedClaimsIsInvalid() {
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.issuer("someone-else"))));
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.expirationTime(null))));
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.subject("not-a-uuid"))));
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.subject(null))));
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.claim("kind", "ADMIN"))));
		assertInvalid(whoami(bearer(CURRENT, "current", (claims) -> claims.claim("kind", null))));
	}

	@Test
	void missingTokenIsUnauthenticatedWithChallenge() {
		MvcTestResult result = mvc.get().uri("/test-secure/whoami").header("API-Version", "1").exchange();

		assertThat(result.getResponse().getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
		assertThat(result).hasStatus(401)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.doesNotHavePath("$.jobId")
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "ACCESS_TOKEN_REQUIRED", "retryable": false}
					""");
	}

	@Test
	void unknownPathWithoutTokenIsUnauthenticated() {
		assertThat(mvc.get().uri("/no-such-path").header("API-Version", "1")).hasStatus(401);
	}

	@Test
	void changeRequestWithoutTokenCarriesJobId() {
		UUID key = UuidV7.generate();

		assertThat(mvc.post()
			.uri("/test-secure/command")
			.header("API-Version", "1")
			.header(IdempotencyKey.HEADER, key.toString())).hasStatus(401)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "ACCESS_TOKEN_REQUIRED", "jobId": "%s", "result": "FAILED"}
					""".formatted(key));
	}

	@Test
	void changeRequestWithInvalidTokenCarriesNullJobIdForInvalidKey() {
		assertThat(mvc.post()
			.uri("/test-secure/command")
			.header("API-Version", "1")
			.header(IdempotencyKey.HEADER, "not-a-key")
			.header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")).hasStatus(401)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"reason": "ACCESS_TOKEN_INVALID", "jobId": null, "result": "FAILED"}
					""");
	}

	@Test
	void failureResponseIgnoresTheHandlersProducibleTypes() {
		assertThat(mvc.get().uri("/test-secure/csv").header("API-Version", "1")).hasStatus(401)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "ACCESS_TOKEN_REQUIRED"}
					""");
	}

	@Test
	void healthIsTheOnlyOpenActuatorEndpoint() {
		String token = accessTokens.issue(principalId, PrincipalKind.ANONYMOUS).value();

		assertThat(mvc.get().uri("/actuator/health")).hasStatus(200);
		assertThat(mvc.get().uri("/actuator")).hasStatus(401);
		assertThat(mvc.get().uri("/actuator").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).hasStatus(403)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "FORBIDDEN", "reason": "ACCESS_DENIED", "retryable": false}
					""");
	}

	@Test
	void publicEndpointIgnoresAnInvalidToken() {
		assertThat(mvc.get().uri("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")).hasStatus(200);
	}

	@Test
	void logsCarryThePrincipalButNeverTheToken(CapturedOutput output) {
		String token = accessTokens.issue(principalId, PrincipalKind.ANONYMOUS).value();
		assertThat(whoami("Bearer " + token)).hasStatusOk();
		assertInvalid(whoami("Bearer " + token + "x"));

		List<Map<String, Object>> events = logEvents(output);
		assertThat(events).anySatisfy((event) -> assertThat(event).containsEntry("message", "probe")
			.containsEntry("principal_id", principalId.toString()));
		assertThat(events).anySatisfy((event) -> assertThat(event).containsEntry("message", "request completed")
			.containsEntry("action", "GET /test-secure/whoami")
			.containsEntry("http_status", 200)
			.containsEntry("principal_id", principalId.toString()));
		assertThat(events).anySatisfy((event) -> assertThat(event).containsEntry("message", "request completed")
			.containsEntry("http_status", 401)
			.doesNotContainKey("principal_id"));
		assertThat(output.getAll()).doesNotContain(token);
	}

	private MvcTestResult whoami(String authorization) {
		return mvc.get()
			.uri("/test-secure/whoami")
			.header("API-Version", "1")
			.header(HttpHeaders.AUTHORIZATION, authorization)
			.exchange();
	}

	private static void assertInvalid(MvcTestResult result) {
		assertThat(result.getResponse().getHeaders(HttpHeaders.WWW_AUTHENTICATE))
			.containsExactly("Bearer error=\"invalid_token\"");
		assertThat(result).hasStatus(401)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code": "UNAUTHENTICATED", "reason": "ACCESS_TOKEN_INVALID", "retryable": false}
					""");
	}

	private String bearer(ECKey key, @Nullable String keyId, UnaryOperator<JWTClaimsSet.Builder> claims) {
		return bearer(key, header(keyId).build(), claims.apply(validClaims()).build());
	}

	private static String bearer(ECKey key, JWSHeader header, JWTClaimsSet claims) {
		try {
			return "Bearer " + sign(header, new ECDSASigner(key), claims);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static JWSHeader.Builder header(@Nullable String keyId) {
		return new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(keyId);
	}

	// iat must stay before exp, or the token is rejected as malformed rather than expired.
	private UnaryOperator<JWTClaimsSet.Builder> expiringAt(Instant expiry) {
		return (claims) -> claims.issueTime(Date.from(expiry.minus(properties.accessTokenTtl())))
			.expirationTime(Date.from(expiry));
	}

	private JWTClaimsSet.Builder validClaims() {
		Instant now = Instant.now();
		return new JWTClaimsSet.Builder().issuer(properties.issuer())
			.subject(principalId.toString())
			.claim("kind", "ANONYMOUS")
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(300)));
	}

	private static String sign(JWSHeader header, JWSSigner signer, JWTClaimsSet claims) throws JOSEException {
		SignedJWT jwt = new SignedJWT(header, claims);
		jwt.sign(signer);
		return jwt.serialize();
	}

	private static ECKey generate(String keyId) {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> logEvents(CapturedOutput output) {
		return output.getOut()
			.lines()
			.filter((line) -> line.startsWith("{"))
			.map((line) -> (Map<String, Object>) STRICT_JSON.readValue(line, Map.class))
			.toList();
	}

	@RestController
	@RequestMapping("/test-secure")
	static class ProbeController {

		private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

		@GetMapping("/whoami")
		CurrentPrincipal whoami(CurrentPrincipal principal) {
			log.info("probe");
			return principal;
		}

		@GetMapping(path = "/csv", produces = "text/csv")
		String csv() {
			return "a,b";
		}

		@IdempotentCommand
		@PostMapping("/command")
		void command(IdempotencyKey key) {
		}

	}

}
