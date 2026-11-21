package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.IntakeRecords.ProjectionRows;
import io.github.gb6105.pik.analysis.IntakeResponse.Counts;
import io.github.gb6105.pik.analysis.IntakeResponse.GroupView;
import io.github.gb6105.pik.analysis.IntakeResponse.ItemView;
import io.github.gb6105.pik.analysis.IntakeResponse.JobView;
import io.github.gb6105.pik.shared.error.CommandResult;
import io.github.gb6105.pik.shared.idempotency.ReplayProjection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the intake response from the database: the decisions stored with the request and the current status of
 * the jobs they point at (design 1.5). The first response, every replayed success and the status query go through
 * it, so a retransmission reports progress with the first status code (consistency-rules.md). The rows come from one
 * SQL statement ({@link IntakeRecords#projection}), one snapshot whatever transaction it runs in (plan 4.3).
 */
@Component
@RequiredArgsConstructor
class IntakeProjection implements ReplayProjection {

	private static final TypeReference<List<ItemView>> ITEMS = new TypeReference<>() {
	};

	private static final TypeReference<List<GroupView>> GROUPS = new TypeReference<>() {
	};

	private static final TypeReference<List<JobView>> JOBS = new TypeReference<>() {
	};

	private final IntakeRecords records;

	private final JsonMapper jsonMapper;

	/**
	 * @return the current response of the stored request; the stored body itself when the request row is gone
	 */
	@Override
	public JsonNode project(int status, JsonNode storedBody) {
		JsonNode jobId = storedBody.get("jobId");
		UUID requestId = (jobId != null && jobId.isString()) ? parse(jobId.asString()) : null;
		IntakeResponse response = (requestId != null) ? build(requestId) : null;
		return (response != null) ? jsonMapper.valueToTree(response) : storedBody;
	}

	/**
	 * @return the response, {@code null} when the request does not exist
	 */
	@Nullable IntakeResponse build(UUID requestId) {
		return records.projection(requestId, null).map((rows) -> response(requestId, rows)).orElse(null);
	}

	/**
	 * @return the response of the owner's request, {@code null} when the owner has no such request
	 */
	@Nullable IntakeResponse find(UUID ownerId, UUID requestId) {
		return records.projection(requestId, ownerId).map((rows) -> response(requestId, rows)).orElse(null);
	}

	private IntakeResponse response(UUID requestId, ProjectionRows rows) {
		List<ItemView> items = jsonMapper.readValue(rows.items(), ITEMS);
		Map<AdmissionResult, Integer> decisions = new HashMap<>();
		for (ItemView item : items) {
			decisions.merge(item.admissionResult(), 1, Integer::sum);
		}
		// The jobs of the response are the scope of the counts and the phase.
		List<JobView> jobs = jsonMapper.readValue(rows.jobs(), JOBS);
		int waiting = (int) jobs.stream().filter((job) -> job.status().waiting()).count();
		int done = (int) jobs.stream().filter((job) -> job.status().over()).count();
		int running = jobs.size() - waiting - done;
		Counts counts = new Counts(items.size(), decisions.getOrDefault(AdmissionResult.ACCEPTED, 0),
				decisions.getOrDefault(AdmissionResult.EXISTING_RESULT, 0),
				decisions.getOrDefault(AdmissionResult.IN_PROGRESS, 0),
				decisions.getOrDefault(AdmissionResult.NOT_ACCEPTED, 0), waiting, running, done);
		return new IntakeResponse(requestId, CommandResult.ACCEPTED, false, phase(jobs.size(), waiting, done), counts,
				items, jsonMapper.readValue(rows.groups(), GROUPS), jobs);
	}

	private static Phase phase(int jobs, int waiting, int done) {
		if (done == jobs) {
			return Phase.DONE;
		}
		return (waiting == jobs) ? Phase.WAITING : Phase.RUNNING;
	}

	private static @Nullable UUID parse(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
