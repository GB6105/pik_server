package io.github.gb6105.pik.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestingDevicesTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	RequestingDevices requestingDevices;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void deviceOfThePrincipalIsFound() {
		Map<String, Object> android = install("ANDROID", "{\"androidId\": \"%s\"}".formatted(androidId()));
		Map<String, Object> ios = install("IOS",
				"{\"deviceCheckToken\": \"fake-device-check:%s\"}".formatted(UUID.randomUUID()));

		assertThat(find(android)).hasValue(new RequestingDevice((UUID) android.get("id"),
				(UUID) android.get("install_id"), Platform.ANDROID, (UUID) android.get("trial_mark_id")));
		assertThat(find(ios)).hasValueSatisfying((device) -> {
			assertThat(device.platform()).isEqualTo(Platform.IOS);
			assertThat(device.trialMarkId()).isEqualTo(ios.get("trial_mark_id")).isNotNull();
		});
	}

	@Test
	void deviceOfAnotherPrincipalIsNotFound() {
		Map<String, Object> device = install("ANDROID", "{\"androidId\": \"%s\"}".formatted(androidId()));
		Map<String, Object> other = install("ANDROID", "{\"androidId\": \"%s\"}".formatted(androidId()));

		assertThat(requestingDevices.find((UUID) other.get("principal_id"), (UUID) device.get("id"))).isEmpty();
		assertThat(requestingDevices.find((UUID) device.get("principal_id"), UuidV7.generate())).isEmpty();
	}

	@Test
	void deviceWithoutTrialMarkIsFoundWithoutIt() {
		Map<String, Object> device = install("ANDROID", "{\"androidId\": \"%s\"}".formatted(androidId()));
		jdbc.sql("UPDATE device SET trial_mark_id = NULL WHERE id = :id").param("id", device.get("id")).update();

		assertThat(find(device)).hasValueSatisfying((found) -> assertThat(found.trialMarkId()).isNull());
	}

	private Optional<RequestingDevice> find(Map<String, Object> device) {
		return requestingDevices.find((UUID) device.get("principal_id"), (UUID) device.get("id"));
	}

	// The device row of a new install.
	private Map<String, Object> install(String platform, String trialDevice) {
		MvcTestResult result = mvc.post()
			.uri("/installs")
			.header("API-Version", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"platform": "%s", "attestation": {"token": "%s"}, "trialDevice": %s}
					""".formatted(platform, FakeAttestationVerifier.VALID_TOKEN, trialDevice))
			.exchange();
		assertThat(result).hasStatus(201);
		String installId = (String) JSON.readValue(result.getResponse().getContentAsByteArray(), Map.class)
			.get("installId");
		return jdbc.sql("SELECT id, principal_id, install_id, trial_mark_id FROM device WHERE install_id = :id")
			.param("id", UUID.fromString(installId))
			.query()
			.singleRow();
	}

	private static String androidId() {
		byte[] id = new byte[8];
		ThreadLocalRandom.current().nextBytes(id);
		return HexFormat.of().formatHex(id);
	}

}
