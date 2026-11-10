package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.shared.idempotency.IdempotencyKey;
import io.github.gb6105.pik.shared.idempotency.IdempotentExecutor;
import io.github.gb6105.pik.shared.idempotency.IdempotentRequest;
import io.github.gb6105.pik.shared.idempotency.Outcome;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-14 of plan 8.2: with the default {@code pik.analysis.intake-enabled=false} new requests get 503
 * {@code INTAKE_DISABLED} and nothing is stored, while records stored before are replayed (decision Q7).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisIntakeDisabledTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	JsonMapper jsonMapper;

	IntakeFixtures fixtures;

	@BeforeEach
	void fixtures() {
		fixtures = new IntakeFixtures(mvc, jdbc);
	}

	@Test
	void newRequestIsATemporaryFailureThatStoresNothing() {
		Installed device = fixtures.android();
		String key = key();

		MvcTestResult result = fixtures.intake(device, key, body("NEW_PHOTOS", photo("v1")));

		assertThat(result).hasStatus(503);
		assertDisabled(json(result));
		assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE owner_id = :owner AND idempotency_key = :key")
			.param("owner", device.principalId())
			.param("key", UUID.fromString(key))
			.query(Long.class)
			.single()).isZero();
		assertThat(count("photo", device)).isZero();
		assertThat(count("analysis_request", device)).isZero();
		assertThat(count("usage_reservation", device)).isZero();
	}

	// The switch comes before the value checks (plan 4.3 #9).
	@Test
	void switchIsCheckedBeforeTheValues() {
		Installed device = fixtures.android();

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS"));

		assertThat(result).hasStatus(503);
		assertDisabled(json(result));
	}

	@Test
	void storedRecordIsStillReplayed() {
		Installed device = fixtures.android();
		String key = key();
		String body = body("NEW_PHOTOS", photo("v1"));
		IntakeCommand command = jsonMapper.readValue(body, IntakeCommand.class);
		UUID jobId = UUID.randomUUID();
		// Stored while intake was on; the request row is gone, so the stored body is replayed as it is.
		executor.commit(new IdempotentRequest(device.principalId(), new IdempotencyKey(UUID.fromString(key)),
				AnalysisIntake.OPERATION, command, true),
				() -> new Outcome(HttpStatus.ACCEPTED, Map.of("jobId", jobId, "result", "ACCEPTED")), (status,
						stored) -> stored);

		MvcTestResult replay = fixtures.intake(device, key, body);

		assertThat(replay).hasStatus(202);
		assertThat(json(replay).get("jobId").asString()).isEqualTo(jobId.toString());
		assertThat(json(replay).get("result").asString()).isEqualTo("ACCEPTED");
	}

	private static void assertDisabled(JsonNode problem) {
		assertThat(problem.get("code").asString()).isEqualTo("TEMPORARY_FAILURE");
		assertThat(problem.get("reason").asString()).isEqualTo("INTAKE_DISABLED");
		assertThat(problem.get("retryable").asBoolean()).isTrue();
		assertThat(problem.get("jobId").isNull()).isTrue();
		assertThat(problem.get("result").asString()).isEqualTo("FAILED");
	}

	private long count(String table, Installed device) {
		String owner = table.equals("usage_reservation") ? "principal_id" : "owner_id";
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + owner + " = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single();
	}

}
