package io.github.gb6105.pik.analysis;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code POST /analysis-jobs/{requestId}/upload-attempts} (plan 4.1). Values are checked after the
 * idempotency lookup, so every field may be missing here.
 *
 * @param items the jobs to issue an attempt for, 1 to 100, each job once
 */
record UploadAttemptRequest(@Nullable List<Item> items) {

	/**
	 * @param contentLength the image size in bytes, 1 to the job's {@code maxBytes}
	 * @param sha256 the image's SHA-256, standard base64 of 32 bytes
	 */
	record Item(@Nullable UUID analysisJobId, @Nullable Long contentLength, @Nullable String sha256) {

	}

}
