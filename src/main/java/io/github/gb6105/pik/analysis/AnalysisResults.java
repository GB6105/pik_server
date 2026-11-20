package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.RunRecords.LockedResult;
import io.github.gb6105.pik.shared.change.ChangeSequence;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stored analysis results for other modules (plan 3.4).
 */
@Service
@RequiredArgsConstructor
public class AnalysisResults {

	private final RunRecords records;

	private final ChangeSequence changes;

	private final ApplicationEventPublisher events;

	private final JsonMapper jsonMapper;

	/**
	 * The owner's result of the photo with its current epoch, read without locks.
	 * @param photoId the server photo ID ({@code photo.id}), as {@link AnalysisCompleted} carries it
	 * @return empty when the photo has no result or is of another owner
	 */
	@Transactional(readOnly = true)
	public Optional<AnalysisResult> find(UUID ownerId, UUID photoId) {
		return records.findResult(ownerId, photoId)
			.map((stored) -> new AnalysisResult(stored.id(), ownerId, photoId, stored.jobId(), stored.verification(),
					stored.photoVersion(), stored.basisChangeSeq(), stored.manifestId(), stored.schemaVersion(),
					stored.outputLocale(), stored.searchState(), jsonMapper.readTree(stored.content()),
					stored.epoch(), stored.changeSeq()));
	}

	/**
	 * Regenerates what derives from the owner's result of the photo (design 3.1, plan 3.4): the result's epoch goes
	 * from {@code expectedEpoch} to the next one under the result row's lock, with a new change number, and
	 * {@link AnalysisCompleted} is published again with that epoch in the same transaction. The result itself stays
	 * as stored; nothing is sent to the model and no usage is settled.
	 * @param photoId the server photo ID ({@code photo.id})
	 * @param expectedEpoch the epoch the caller saw, from {@link #find}
	 * @return the new epoch
	 * @throws PikException {@code NOT_FOUND} / {@code RESOURCE_NOT_FOUND} when the owner has no result for the photo,
	 * {@code CONFLICT} / {@code EPOCH_CONFLICT} when the result's epoch is no longer {@code expectedEpoch}
	 */
	@Transactional
	public int regenerate(UUID ownerId, UUID photoId, int expectedEpoch) {
		LockedResult result = records.lockResult(ownerId, photoId)
			.orElseThrow(() -> new PikException(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false));
		if (result.epoch() != expectedEpoch) {
			throw new PikException(ErrorCode.CONFLICT, "EPOCH_CONFLICT", false);
		}
		int epoch = Math.addExact(result.epoch(), 1);
		// The change counter is the last lock.
		records.changeEpoch(result.id(), epoch, changes.next(ownerId), Instant.now());
		events.publishEvent(new AnalysisCompleted(ownerId, photoId, result.id(), epoch));
		return epoch;
	}

}
