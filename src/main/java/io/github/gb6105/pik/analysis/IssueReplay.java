package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.account.Principals;
import io.github.gb6105.pik.analysis.UploadAttemptResponse.Item;
import io.github.gb6105.pik.analysis.UploadRecords.AttemptRow;
import io.github.gb6105.pik.analysis.UploadRecords.RequestBasis;
import io.github.gb6105.pik.consent.AiConsents;
import io.github.gb6105.pik.shared.idempotency.ReplayProjection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays a stored upload attempt response with the items whose attempts can no longer be uploaded to turned into
 * {@code REJECTED} / {@code ATTEMPT_NOT_CURRENT} (design 2.3, plan 4.1): an {@code ISSUED} item stays only while its
 * attempt is the job's current {@code ISSUED} one, of the owner, the request's consent basis holds, the principal is
 * active and the attempt's fixed URL expiry is ahead. The issue count is not checked again. Runs in the executor's
 * transaction, which holds the consent lock from {@link AiConsents#basisHolds} on.
 */
@Component
@RequiredArgsConstructor
class IssueReplay {

	private final UploadRecords attempts;

	private final AiConsents consents;

	private final Principals principals;

	private final JsonMapper jsonMapper;

	ReplayProjection of(UUID ownerId, UUID requestId) {
		return (status, storedBody) -> project(ownerId, requestId, storedBody);
	}

	private JsonNode project(UUID ownerId, UUID requestId, JsonNode storedBody) {
		UploadAttemptResponse stored = jsonMapper.treeToValue(storedBody, UploadAttemptResponse.class);
		List<UUID> issued = stored.items()
			.stream()
			.filter((item) -> item.result() == UploadAttemptResponse.Result.ISSUED)
			.map(Item::attemptId)
			.map(Objects::requireNonNull)
			.toList();
		if (issued.isEmpty()) {
			return storedBody;
		}
		RequestBasis request = attempts.findRequest(ownerId, requestId).orElse(null);
		boolean usable = request != null && consents.basisHolds(ownerId, request.consentVersion())
				&& principals.isActive(ownerId);
		Map<UUID, AttemptRow> current = attempts.find(issued);
		Instant now = Instant.now();
		List<Item> items = new ArrayList<>();
		for (Item item : stored.items()) {
			if (item.result() == UploadAttemptResponse.Result.ISSUED) {
				AttemptRow attempt = current.get(item.attemptId());
				boolean stillCurrent = usable && attempt != null && attempt.ownerId().equals(ownerId)
						&& attempt.status() == AttemptStatus.ISSUED
						&& attempt.urlExpiresAt().isAfter(now.plus(UploadSigning.MIN_URL_VALIDITY));
				items.add(stillCurrent ? item
						: Item.rejected(item.analysisJobId(), item.attemptId(), UploadRejection.ATTEMPT_NOT_CURRENT));
			}
			else {
				items.add(item);
			}
		}
		return jsonMapper.valueToTree(
				new UploadAttemptResponse(stored.jobId(), stored.result(), stored.retryable(), items));
	}

}
