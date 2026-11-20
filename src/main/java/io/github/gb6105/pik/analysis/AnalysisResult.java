package io.github.gb6105.pik.analysis;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A photo's stored analysis result (design 4.1, plan 2.4), as other modules read it through {@link AnalysisResults}.
 * The content is the analysis of a photo and must not be logged.
 *
 * @param resultId the {@code analysis_result} row
 * @param photoId the server photo ID ({@code photo.id})
 * @param jobId the job that ran, also for an inherited result (ADR-0024 #6-3)
 * @param photoVersion the photo version the result is for, from the photo's own intake item
 * @param basisChangeSeq the photo's {@code sourceChangeSeq} the result is based on
 * @param manifestId the call manifest the job ran with
 * @param schemaVersion the result schema version
 * @param outputLocale the language of generated labels
 * @param content the normalized result: the known keys of schema v1, {@code validation} on each item,
 * {@code truncatedFields}, and {@code search} null when discarded
 * @param epoch the current epoch, raised by each manual regeneration
 * @param changeSeq the owner's change number of the last change of the row
 */
public record AnalysisResult(UUID resultId, UUID ownerId, UUID photoId, UUID jobId, Verification verification,
		String photoVersion, long basisChangeSeq, String manifestId, int schemaVersion, String outputLocale,
		SearchState searchState, JsonNode content, int epoch, long changeSeq) {

	// The content must not reach logs.
	@Override
	public String toString() {
		return "AnalysisResult[resultId=" + resultId + ", verification=" + verification + ", searchState="
				+ searchState + ", epoch=" + epoch + "]";
	}

	/** How the result was obtained for the photo. */
	public enum Verification {

		/** The photo was the representative that was analyzed. */
		DIRECT,

		/** The photo was a member of the representative's group and takes over its result. */
		INHERITED

	}

	/** What is left of the {@code search} block (design 4.1). */
	public enum SearchState {

		/** Valid values remain. */
		AVAILABLE,

		/** A well-formed block without values. */
		EMPTY,

		/** The block was missing or malformed, or every value in it was dropped. */
		DISCARDED

	}

}
