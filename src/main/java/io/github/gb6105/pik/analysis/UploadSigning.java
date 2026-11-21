package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.IssueTransaction.CheckedItem;
import io.github.gb6105.pik.analysis.UploadAttemptResponse.Item;
import io.github.gb6105.pik.analysis.UploadAttemptResponse.Upload;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.idempotency.IdempotentResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds the presigned upload of each {@code ISSUED} item to an upload attempt response, for the first response and for
 * every replay alike, after the executor has returned and outside any transaction (plan K-1). The URL keeps the
 * attempt's fixed expiry. Signing may fetch credentials; when it fails the record is already stored, and the same key
 * replays and signs again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class UploadSigning {

	/**
	 * The URL validity an attempt must have left to be issued or replayed: {@link InputStore#presignPut} needs 2
	 * seconds, and signing follows the commit.
	 */
	static final Duration MIN_URL_VALIDITY = Duration.ofSeconds(10);

	private final InputStore store;

	private final JsonMapper jsonMapper;

	/**
	 * @param items the checked items of the request; a replay has the same ones, since its request hash matched
	 * @throws PikException {@code TEMPORARY_FAILURE} / {@code STORAGE_UNAVAILABLE} when a URL cannot be signed
	 */
	IdempotentResponse sign(IdempotentResponse response, List<CheckedItem> items) {
		Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
				"Upload URLs are signed outside transactions");
		if (response.status() != 200) {
			return response;
		}
		Map<UUID, CheckedItem> requested = new HashMap<>();
		items.forEach((item) -> requested.put(item.analysisJobId(), item));
		UploadAttemptResponse body = jsonMapper.treeToValue(response.body(), UploadAttemptResponse.class);
		List<Item> signed = new ArrayList<>();
		for (Item item : body.items()) {
			signed.add((item.result() == UploadAttemptResponse.Result.ISSUED)
					? item.withUpload(presign(item, requested.get(item.analysisJobId()))) : item);
		}
		return new IdempotentResponse(response.status(), jsonMapper.valueToTree(
				new UploadAttemptResponse(body.jobId(), body.result(), body.retryable(), signed)));
	}

	private Upload presign(Item item, CheckedItem requested) {
		Assert.state(requested != null && item.attemptId() != null && item.expiresAt() != null,
				"An issued item names its attempt, its expiry and a requested job");
		InputStore.PresignedPut put;
		try {
			put = store.presignPut(UploadRecords.objectKey(item.analysisJobId(), item.attemptId()),
					requested.contentLength(), requested.sha256(), item.expiresAt());
		}
		catch (RuntimeException ex) {
			// The exception type only: messages may hold the URL or the key.
			log.warn("Upload URL signing failed: {}", ex.getClass().getSimpleName());
			throw PikException.unstored(ErrorCode.TEMPORARY_FAILURE, "STORAGE_UNAVAILABLE", true);
		}
		return new Upload(put.url(), "PUT", put.headers(), put.expiresAt());
	}

}
