package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.error.CommandResult;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The response of {@code POST /analysis-jobs}: the stored decisions with the current state of the jobs (design 1.5).
 * It holds no upload URL. Built from the database for the first response and for every retransmission
 * ({@link IntakeProjection}).
 *
 * @param jobId the request ID
 * @param result always {@link CommandResult#ACCEPTED}: the request was taken, the analysis is not done
 * @param retryable always {@code false}; items say whether they may be sent again
 * @param items one per photo, in {@code photoId} order
 * @param groups the groups this request formed, in the {@code photoId} order of their representatives
 * @param jobs the jobs the items point at, each once: the ones this request created and the ones its
 * {@code IN_PROGRESS} photos wait for, in the {@code photoId} order of their representatives, then by job ID; the
 * scope of {@link Counts} and {@code phase}
 */
record IntakeResponse(UUID jobId, CommandResult result, boolean retryable, Phase phase, Counts counts,
		List<ItemView> items, List<GroupView> groups, List<JobView> jobs) {

	/**
	 * Photos by decision, {@code requested = accepted + existingResult + inProgress + notAccepted}, and the jobs the
	 * request points at by state: its own and those its {@code IN_PROGRESS} photos wait for, each once.
	 */
	record Counts(int requested, int accepted, int existingResult, int inProgress, int notAccepted, int waiting,
			int running, int done) {

	}

	/**
	 * @param photoId the client's photo ID
	 * @param code the error class of a photo that is not analyzed, as {@code ErrorCode}
	 * @param reason the detailed reason, such as {@code SUBSCRIPTION_REQUIRED} or {@code TRIAL_UNAVAILABLE}
	 * @param retryable whether the photo may be sent again with a new key
	 * @param retryAfter seconds to wait before sending the photo again; set from stage 5-2 on
	 * @param analysisJobId the job that analyzes the photo, possibly of another request
	 * @param groupId the group of this request the photo is in
	 * @param execution the current status of that job
	 * @param observedVersion the {@code contentVersion} sent in this request
	 * @param analyzedVersion the version that job analyzes the photo at
	 * @param vectorIgnored why the photo's vector was ignored
	 */
	record ItemView(UUID photoId, AdmissionResult admissionResult, @Nullable String code, @Nullable String reason,
			boolean retryable, @Nullable Long retryAfter, @Nullable UUID analysisJobId, @Nullable UUID groupId,
			@Nullable GroupRole role, @Nullable JobStatus execution, String observedVersion,
			@Nullable String analyzedVersion, @Nullable VectorIgnoredReason vectorIgnored) {

		// Photo IDs and contentVersion values must not reach logs.
		@Override
		public String toString() {
			return "ItemView[admissionResult=" + admissionResult + ", reason=" + reason + ", analysisJobId="
					+ analysisJobId + ", groupId=" + groupId + "]";
		}

	}

	/**
	 * A group of FR-I-02.
	 *
	 * @param representativePhotoId the client's ID of the photo analyzed for the group
	 * @param photoCount the photos of the group, the representative included
	 * @param analysisJobId its job, {@code null} when the group was not accepted
	 */
	record GroupView(UUID groupId, UUID representativePhotoId, int photoCount, @Nullable UUID analysisJobId) {

		@Override
		public String toString() {
			return "GroupView[groupId=" + groupId + ", photoCount=" + photoCount + ", analysisJobId=" + analysisJobId
					+ "]";
		}

	}

	/**
	 * A job an item of the request points at: one the request created or one its photos in progress wait for.
	 *
	 * @param requestId the request that created the job, the path of its uploads
	 * @param uploadRequired whether the device must upload the representative image
	 * @param uploadPolicy what the upload must satisfy, from the job's call manifest
	 */
	record JobView(UUID analysisJobId, UUID requestId, UUID groupId, JobStatus status, boolean uploadRequired,
			UploadPolicy uploadPolicy) {

	}

	record UploadPolicy(String contentType, long maxBytes, int maxLongEdgePx) {

	}

}
