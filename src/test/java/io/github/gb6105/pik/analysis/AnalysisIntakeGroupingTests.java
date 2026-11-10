package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.AnalysisIntakeTests.item;
import static io.github.gb6105.pik.analysis.IntakeFixtures.T0;
import static io.github.gb6105.pik.analysis.IntakeFixtures.body;
import static io.github.gb6105.pik.analysis.IntakeFixtures.json;
import static io.github.gb6105.pik.analysis.IntakeFixtures.key;
import static io.github.gb6105.pik.analysis.IntakeFixtures.photo;
import static io.github.gb6105.pik.analysis.IntakeFixtures.vector;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.analysis.IntakeFixtures.Installed;
import io.github.gb6105.pik.analysis.IntakeFixtures.Photo;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * I-3, I-4, I-5, I-6 and I-17 of plan 8.2 with grouping on: one registered model of 2 dimensions, θ = 0.9.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "pik.analysis.intake-enabled=true", "pik.usage.trial.limit=5",
		"pik.analysis.grouping.enabled=true", "pik.analysis.grouping.models[0].model=pik-embed",
		"pik.analysis.grouping.models[0].model-version=1", "pik.analysis.grouping.models[0].dims=2",
		"pik.analysis.grouping.models[0].threshold=0.9" })
@AutoConfigureMockMvc
class AnalysisIntakeGroupingTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	GroupingRules groupingRules;

	@Autowired
	VectorValidator vectorValidator;

	IntakeFixtures fixtures;

	@BeforeEach
	void fixtures() {
		fixtures = new IntakeFixtures(mvc, jdbc);
	}

	// I-5
	@Test
	void similarPhotosShareOneJobAndKeepTheirVectors() {
		Installed device = fixtures.android();
		Photo a = photo("v1").groupable(T0, vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(30), vector(1, 0.01f));
		Photo c = photo("v1").groupable(T0.plusSeconds(60), vector(1, 0.02f));
		Photo other = photo("v1").groupable(T0.plusSeconds(10), vector(0, 1));

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", a, b, c, other));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		assertThat(response.get("jobs")).hasSize(2);
		JsonNode representative = item(response, b);
		assertThat(representative.get("role").asString()).isEqualTo("REPRESENTATIVE");
		for (Photo member : List.of(a, c)) {
			JsonNode item = item(response, member);
			assertThat(item.get("admissionResult").asString()).isEqualTo("ACCEPTED");
			assertThat(item.get("role").asString()).isEqualTo("MEMBER");
			assertThat(item.get("groupId")).isEqualTo(representative.get("groupId"));
			assertThat(item.get("analysisJobId")).isEqualTo(representative.get("analysisJobId"));
		}
		assertThat(item(response, other).get("role").asString()).isEqualTo("REPRESENTATIVE");
		assertThat(item(response, other).get("groupId")).isNotEqualTo(representative.get("groupId"));
		JsonNode group = group(response, representative.get("groupId").asString());
		assertThat(group.get("photoCount").asInt()).isEqualTo(3);
		assertThat(group.get("representativePhotoId").asString()).isEqualTo(b.photoId().toString());
		assertThat(group.get("analysisJobId")).isEqualTo(representative.get("analysisJobId"));

		// The trial counts every photo of the group, representative and members.
		assertThat(jdbc.sql("SELECT count(*) FROM usage_reservation WHERE job_id = :job")
			.param("job", UUID.fromString(representative.get("analysisJobId").asString()))
			.query(Long.class)
			.single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT model_id, model_version FROM analysis_group WHERE id = :id")
			.param("id", UUID.fromString(representative.get("groupId").asString()))
			.query()
			.singleRow()).containsEntry("model_id", "pik-embed").containsEntry("model_version", "1");
		assertThat(jdbc.sql("SELECT count(*) FROM photo_vector WHERE owner_id = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single()).isEqualTo(4);
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_request_item WHERE request_id = :id AND vector_id IS NULL")
			.param("id", requestId)
			.query(Long.class)
			.single()).isZero();
		assertRegroupedFromStoredVectors(requestId);
	}

	// I-6 with grouping on
	@Test
	void ignoredVectorsLeaveTheirPhotoSingleAndTheRestGroups() {
		Installed device = fixtures.android();
		// The newest, so that the trial covers the group before the singles.
		Photo a = photo("v1").groupable(T0.plusSeconds(10), vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(11), vector(1, 0.01f));
		Photo unknownModel = photo("v1").groupable(T0.plusSeconds(2), vector(1, 0)).vector("other-model", vector(1, 0));
		Photo threeDims = photo("v1").groupable(T0.plusSeconds(3), vector(1, 0, 0));
		Photo zero = photo("v1").groupable(T0.plusSeconds(4), vector(0, 0));
		Photo notFinite = photo("v1").groupable(T0.plusSeconds(5), vector(Float.NaN, 1));

		MvcTestResult result = fixtures.intake(device, key(),
				body("NEW_PHOTOS", a, b, unknownModel, threeDims, zero, notFinite));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(item(response, unknownModel).get("vectorIgnored").asString()).isEqualTo("UNKNOWN_MODEL");
		assertThat(item(response, threeDims).get("vectorIgnored").asString()).isEqualTo("BAD_DIMENSION");
		assertThat(item(response, zero).get("vectorIgnored").asString()).isEqualTo("ZERO_NORM");
		assertThat(item(response, notFinite).get("vectorIgnored").asString()).isEqualTo("NOT_FINITE");
		assertThat(item(response, a).get("groupId")).isEqualTo(item(response, b).get("groupId"));
		assertThat(item(response, a).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		for (Photo ignored : List.of(unknownModel, threeDims, zero, notFinite)) {
			String groupId = item(response, ignored).get("groupId").asString();
			assertThat(group(response, groupId).get("photoCount").asInt()).isEqualTo(1);
		}
		// Only the valid vectors are kept.
		assertThat(jdbc.sql("SELECT count(*) FROM photo_vector WHERE owner_id = :owner")
			.param("owner", device.principalId())
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	// I-3
	@Test
	void memberSentAloneIsInProgressOnTheGroupsJob() {
		Installed device = fixtures.android();
		Photo a = photo("v1").groupable(T0, vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(30), vector(1, 0.01f));
		JsonNode accepted = json(fixtures.intake(device, key(), body("NEW_PHOTOS", a, b)));
		JsonNode member = item(accepted, a).get("role").asString().equals("MEMBER") ? item(accepted, a)
				: item(accepted, b);
		Photo memberPhoto = member.get("photoId").asString().equals(a.photoId().toString()) ? a : b;

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", memberPhoto));

		assertThat(result).hasStatus(200);
		JsonNode item = json(result).get("items").get(0);
		assertThat(item.get("admissionResult").asString()).isEqualTo("IN_PROGRESS");
		assertThat(item.get("analysisJobId")).isEqualTo(member.get("analysisJobId"));
		assertThat(item.get("analyzedVersion").asString()).isEqualTo("v1");
		// The group's job, with the request that created it.
		JsonNode jobs = json(result).get("jobs");
		assertThat(jobs).hasSize(1);
		assertThat(jobs.get(0).get("analysisJobId")).isEqualTo(member.get("analysisJobId"));
		assertThat(jobs.get(0).get("requestId")).isEqualTo(accepted.get("jobId"));
		assertThat(jobs.get(0).get("groupId")).isEqualTo(member.get("groupId"));
		assertThat(jobs.get(0).get("status").asString()).isEqualTo("AWAITING_UPLOAD");
		assertThat(jobs.get(0).get("uploadRequired").asBoolean()).isTrue();
		assertThat(json(result).get("counts").get("waiting").asInt()).isEqualTo(1);
		assertThat(json(result).get("phase").asString()).isEqualTo("WAITING");
	}

	// takenAt is cut to microseconds, the stored precision: 120 s and 900 ns apart is inside the 120 s window.
	@Test
	void takenAtBelowAMicrosecondDoesNotSplitAGroup() {
		Installed device = fixtures.android();
		Photo first = photo("v1").groupable(T0, vector(1, 0));
		Photo last = photo("v1").groupable(T0, vector(1, 0.01f)).takenAt("2026-11-01T10:02:00.0000009Z");

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", first, last));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		assertThat(item(response, first).get("groupId")).isEqualTo(item(response, last).get("groupId"));
		UUID requestId = UUID.fromString(response.get("jobId").asString());
		assertThat(jdbc.sql("SELECT taken_at FROM analysis_request_item WHERE request_id = :id AND client_ref = :ref")
			.param("id", requestId)
			.param("ref", last.photoId())
			.query(Timestamp.class)
			.single()
			.toInstant()).isEqualTo(T0.plusSeconds(120));
		assertRegroupedFromStoredVectors(requestId);
	}

	// I-4 with a group
	@Test
	void groupLargerThanTheTrialLeftIsRefusedAndASmallerOneIsReserved() {
		Installed device = fixtures.android();
		fixtures.intake(device, key(), body("NEW_PHOTOS", photo("v1"), photo("v1"), photo("v1")));
		Photo a = photo("v1").groupable(T0.plusSeconds(100), vector(1, 0));
		Photo b = photo("v1").groupable(T0.plusSeconds(110), vector(1, 0.01f));
		Photo c = photo("v1").groupable(T0.plusSeconds(120), vector(1, 0.02f));
		Photo single = photo("v1").takenAt(T0);

		MvcTestResult result = fixtures.intake(device, key(), body("NEW_PHOTOS", a, b, c, single));

		assertThat(result).hasStatus(202);
		JsonNode response = json(result);
		for (Photo grouped : List.of(a, b, c)) {
			JsonNode item = item(response, grouped);
			assertThat(item.get("admissionResult").asString()).isEqualTo("NOT_ACCEPTED");
			assertThat(item.get("code").asString()).isEqualTo("FORBIDDEN");
			assertThat(item.get("reason").asString()).isEqualTo("SUBSCRIPTION_REQUIRED");
			assertThat(item.get("retryable").asBoolean()).isTrue();
			assertThat(item.get("groupId").isNull()).isFalse();
		}
		JsonNode refusedGroup = group(response, item(response, a).get("groupId").asString());
		assertThat(refusedGroup.get("photoCount").asInt()).isEqualTo(3);
		assertThat(refusedGroup.get("analysisJobId").isNull()).isTrue();
		assertThat(item(response, single).get("admissionResult").asString()).isEqualTo("ACCEPTED");
		assertThat(response.get("jobs")).hasSize(1);
	}

	// I-17
	@Test
	void vectorVersionsStayWithTheRequestsThatUsedThem() {
		Installed device = fixtures.android();
		// Uses the whole trial, so the next requests are refused but still group and keep their vectors.
		fixtures.intake(device, key(),
				body("NEW_PHOTOS", photo("v1"), photo("v1"), photo("v1"), photo("v1"), photo("v1")));
		UUID p = UUID.randomUUID();
		Photo q = photo("v1").groupable(T0.plusSeconds(10), vector(1, 0.01f));

		JsonNode first = json(fixtures.intake(device, key(),
				body("NEW_PHOTOS", photo(p, "v1").groupable(T0, vector(1, 0)), q)));
		UUID firstRequest = UUID.fromString(first.get("jobId").asString());
		assertThat(item(first, p).get("admissionResult").asString()).isEqualTo("NOT_ACCEPTED");
		assertThat(item(first, p).get("groupId")).isEqualTo(item(first, q).get("groupId"));
		UUID vectorA = vectorId(firstRequest, p);
		assertThat(vectorA).isNotNull();
		assertRegroupedFromStoredVectors(firstRequest);

		JsonNode second = json(fixtures.intake(device, key(),
				body("NEW_PHOTOS", photo(p, "v1").groupable(T0, vector(0.5f, 0.5f)))));
		UUID vectorB = vectorId(UUID.fromString(second.get("jobId").asString()), p);
		assertThat(vectorB).isNotNull().isNotEqualTo(vectorA);
		assertThat(vectorId(firstRequest, p)).isEqualTo(vectorA);

		JsonNode third = json(fixtures.intake(device, key(),
				body("NEW_PHOTOS", photo(p, "v1").groupable(T0, vector(0.5f, 0.5f)))));
		assertThat(vectorId(UUID.fromString(third.get("jobId").asString()), p)).isEqualTo(vectorB);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM photo_vector v JOIN photo ph ON ph.id = v.photo_id
				WHERE ph.owner_id = :owner AND ph.client_ref = :ref
				""").param("owner", device.principalId()).param("ref", p).query(Long.class).single()).isEqualTo(2);
	}

	private UUID vectorId(UUID requestId, UUID clientRef) {
		return jdbc.sql("SELECT vector_id FROM analysis_request_item WHERE request_id = :id AND client_ref = :ref")
			.param("id", requestId)
			.param("ref", clientRef)
			.query(UUID.class)
			.single();
	}

	// Grouping the request again from its stored float32 vectors and item snapshots gives the stored groups.
	private void assertRegroupedFromStoredVectors(UUID requestId) {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT i.client_ref, i.taken_at, i.text_status, i.group_id, v.model_id, v.model_version, v.vector
				FROM analysis_request_item i LEFT JOIN photo_vector v ON v.id = i.vector_id
				WHERE i.request_id = :id AND i.group_id IS NOT NULL
				""").param("id", requestId).query().listOfRows();
		List<GroupingCandidate> candidates = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			VectorValidator.Valid vector = null;
			if (row.get("vector") != null) {
				vector = (VectorValidator.Valid) vectorValidator.check((String) row.get("model_id"),
						(String) row.get("model_version"), Base64.getEncoder().encodeToString((byte[]) row.get("vector")));
			}
			Timestamp takenAt = (Timestamp) row.get("taken_at");
			candidates.add(new GroupingCandidate((UUID) row.get("client_ref"),
					(takenAt != null) ? takenAt.toInstant() : null, TextStatus.valueOf((String) row.get("text_status")),
					vector));
		}
		List<List<UUID>> regrouped = groupingRules.current()
			.group(candidates, SimilarityMatrix.of(candidates))
			.stream()
			.map((group) -> sorted(group.photoIds()))
			.sorted(Comparator.comparing((List<UUID> group) -> group.getFirst(), GroupingRuleV1.PHOTO_ID_ORDER))
			.toList();
		List<List<UUID>> stored = jdbc.sql("""
				SELECT array_agg(client_ref ORDER BY client_ref) AS refs FROM analysis_request_item
				WHERE request_id = :id AND group_id IS NOT NULL GROUP BY group_id
				""").param("id", requestId).query((rs, rowNum) -> sorted(List.of((UUID[]) rs.getArray("refs").getArray())))
			.list()
			.stream()
			.sorted(Comparator.comparing((List<UUID> group) -> group.getFirst(), GroupingRuleV1.PHOTO_ID_ORDER))
			.toList();
		assertThat(regrouped).isEqualTo(stored);
	}

	private static List<UUID> sorted(List<UUID> photoIds) {
		return photoIds.stream().sorted(GroupingRuleV1.PHOTO_ID_ORDER).toList();
	}

	private static JsonNode group(JsonNode response, String groupId) {
		for (JsonNode group : response.get("groups")) {
			if (group.get("groupId").asString().equals(groupId)) {
				return group;
			}
		}
		throw new AssertionError("no such group");
	}

}
