package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.JobTermination.JobEnding;
import io.github.gb6105.pik.analysis.UploadFixtures.Accepted;
import io.github.gb6105.pik.analysis.UploadFixtures.Image;
import io.github.gb6105.pik.shared.error.CommandResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /analysis-jobs/{requestId}} and the common termination against PostgreSQL and S3Mock (plan 3.3 "공통
 * 종료", 4.3, 11.2 "투영"): the projection of the intake response with its polling hint and strong {@code ETag}.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest(properties = "pik.analysis.intake-enabled=true")
@AutoConfigureMockMvc
class AnalysisStatusTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JobTermination termination;

	UploadFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new UploadFixtures(mvc, jdbc);
	}

	@Test
	void statusIsTheIntakeProjectionWithAnETagAndNoHintWhileUploadsAreAwaited() {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);

		MvcTestResult result = fixtures.status(device, accepted.requestId(), null);

		assertThat(result).hasStatusOk();
		String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
		assertThat(etag).matches("\"[0-9a-f]{64}\"");
		JsonNode body = json(result);
		assertThat(body.get("jobId").asString()).isEqualTo(accepted.requestId().toString());
		assertThat(body.get("phase").asString()).isEqualTo("WAITING");
		assertThat(body.get("counts").get("waiting").asInt()).isEqualTo(2);
		assertThat(body.has("pollAfterSeconds")).isTrue();
		assertThat(body.get("pollAfterSeconds").isNull()).isTrue();
		assertThat(body.get("jobs")).hasSize(2).allSatisfy((job) -> {
			assertThat(job.get("status").asString()).isEqualTo("AWAITING_UPLOAD");
			assertThat(job.get("reason").isNull()).isTrue();
		});
		assertThat(body.get("items")).hasSize(2);

		MvcTestResult unchanged = fixtures.status(device, accepted.requestId(), etag);
		assertThat(unchanged).hasStatus(304);
		assertThat(unchanged.getResponse().getContentAsByteArray()).isEmpty();
		assertThat(fixtures.status(device, accepted.requestId(), "\"other\"")).hasStatusOk();
	}

	@Test
	void queuedJobGivesAHintAndANewETag() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 2);
		String waiting = fixtures.status(device, accepted.requestId(), null).getResponse().getHeader(HttpHeaders.ETAG);
		Image image = fixtures.image(100);
		JsonNode item = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		assertThat(fixtures.put(item, image.bytes())).isEqualTo(200);
		assertThat(fixtures.complete(device, key(), accepted.requestId(),
				UUID.fromString(item.get("attemptId").asString())))
			.hasStatusOk();

		MvcTestResult result = fixtures.status(device, accepted.requestId(), waiting);

		assertThat(result).hasStatusOk();
		assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNotEqualTo(waiting);
		JsonNode body = json(result);
		// 3 s with the request's jitter of at most 20 %.
		long hint = body.get("pollAfterSeconds").asLong();
		assertThat(hint).isBetween(2L, 4L);
		assertThat(json(fixtures.status(device, accepted.requestId(), null)).get("pollAfterSeconds").asLong())
			.isEqualTo(hint);
		assertThat(body.get("jobs"))
			.anySatisfy((job) -> assertThat(job.get("status").asString()).isEqualTo("READY"))
			.anySatisfy((job) -> assertThat(job.get("status").asString()).isEqualTo("AWAITING_UPLOAD"));
	}

	@Test
	void commonTerminationEndsTheJobOnceAndTheStatusShowsItsReason() throws Exception {
		Installed device = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(device, 1);
		Image image = fixtures.image(100);
		JsonNode superseded = fixtures.issued(device, accepted.requestId(), accepted.job(), image);
		JsonNode current = fixtures.issued(device, accepted.requestId(), accepted.job(), image);

		Boolean ended = transaction.execute(
				(status) -> termination.end(accepted.job(), new JobEnding(FailureCode.UPLOAD_EXPIRED)));
		Boolean again = transaction.execute(
				(status) -> termination.end(accepted.job(), new JobEnding(FailureCode.UPLOAD_ATTEMPTS_EXHAUSTED)));

		assertThat(ended).isTrue();
		assertThat(again).isFalse();
		var job = fixtures.job(accepted.job());
		assertThat(job.get("status")).isEqualTo("CANCELLED");
		assertThat(job.get("failure_code")).isEqualTo("UPLOAD_EXPIRED");
		assertThat(job.get("ended_at")).isNotNull();
		assertThat(job.get("run_token")).isNull();
		assertThat(job.get("locked_until")).isNull();
		var abandoned = fixtures.attempt(UUID.fromString(current.get("attemptId").asString()));
		assertThat(abandoned.get("status")).isEqualTo("ABANDONED");
		assertThat(abandoned.get("object_state")).isEqualTo("PENDING");
		var old = fixtures.attempt(UUID.fromString(superseded.get("attemptId").asString()));
		assertThat(old.get("status")).isEqualTo("SUPERSEDED");
		assertThat(old.get("object_state")).isEqualTo("PENDING");
		assertThat(fixtures.reservations(accepted.job())).containsOnly("RELEASED");

		JsonNode body = json(fixtures.status(device, accepted.requestId(), null));
		assertThat(body.get("phase").asString()).isEqualTo("DONE");
		assertThat(body.get("pollAfterSeconds").isNull()).isTrue();
		JsonNode view = body.get("jobs").get(0);
		assertThat(view.get("status").asString()).isEqualTo("CANCELLED");
		assertThat(view.get("reason").asString()).isEqualTo("UPLOAD_EXPIRED");
		assertThat(view.get("uploadRequired").asBoolean()).isFalse();
		assertThat(body.get("items").get(0).get("execution").asString()).isEqualTo("CANCELLED");
	}

	@Test
	void requestOfAnotherPrincipalOrUnknownIsNotFound() {
		Installed owner = fixtures.intake().android();
		Installed stranger = fixtures.intake().android();
		Accepted accepted = fixtures.accepted(owner, 1);

		MvcTestResult foreign = fixtures.status(stranger, accepted.requestId(), null);
		MvcTestResult unknown = fixtures.status(owner, UUID.randomUUID(), null);

		assertThat(foreign).hasStatus(404);
		assertThat(json(foreign).get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(json(foreign).get("reason").asString()).isEqualTo("RESOURCE_NOT_FOUND");
		assertThat(unknown).hasStatus(404);
	}

	@Test
	void jitterIsFixedPerRequestAndBounded() {
		AnalysisProperties.Poll poll = new AnalysisProperties.Poll(Duration.ofSeconds(10), 0.2);
		for (int i = 0; i < 200; i++) {
			UUID requestId = UUID.randomUUID();
			double unit = AnalysisStatus.jitterUnit(requestId);
			assertThat(unit).isBetween(-1.0, 1.0).isEqualTo(AnalysisStatus.jitterUnit(requestId));
			IntakeResponse response = queued(requestId);
			assertThat(AnalysisStatus.pollAfterSeconds(response, poll)).isBetween(8L, 12L)
				.isEqualTo(AnalysisStatus.pollAfterSeconds(response, poll));
		}
		UUID requestId = UUID.randomUUID();
		AnalysisProperties.Poll fixed = new AnalysisProperties.Poll(Duration.ofSeconds(3), 0);
		assertThat(AnalysisStatus.pollAfterSeconds(queued(requestId), fixed)).isEqualTo(3L);
	}

	private static IntakeResponse queued(UUID requestId) {
		IntakeResponse.JobView job = new IntakeResponse.JobView(UUID.randomUUID(), requestId, UUID.randomUUID(),
				UUID.randomUUID(), JobStatus.RUNNING, null, false,
				new IntakeResponse.UploadPolicy("image/jpeg", 2_097_152, 1568));
		return new IntakeResponse(requestId, CommandResult.ACCEPTED, false, Phase.RUNNING,
				new IntakeResponse.Counts(1, 1, 0, 0, 0, 0, 1, 0), List.of(), List.of(), List.of(job));
	}

}
