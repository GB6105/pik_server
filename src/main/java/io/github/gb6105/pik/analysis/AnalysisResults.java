package io.github.gb6105.pik.analysis;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

}
