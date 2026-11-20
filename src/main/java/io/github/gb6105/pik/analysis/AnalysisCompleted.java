package io.github.gb6105.pik.analysis;

import java.util.UUID;

/**
 * A photo's analysis result was stored (design 2.4 step 6, plan 3.4), published in the transaction that stored it,
 * once per stored result: the representative's and each member's. A manual regeneration publishes it again with the
 * next epoch. Listeners that derive data from a result keep the epoch they derived from and ignore older ones.
 *
 * @param ownerId the photo's owner
 * @param photoId the server photo ID ({@code photo.id}), not the API {@code photoId}
 * @param resultId the {@code analysis_result} row
 * @param epoch the result's epoch, 1 for a first result
 */
public record AnalysisCompleted(UUID ownerId, UUID photoId, UUID resultId, int epoch) {

}
