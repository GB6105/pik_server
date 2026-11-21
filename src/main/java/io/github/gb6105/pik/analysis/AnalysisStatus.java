package io.github.gb6105.pik.analysis;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the status response of an intake request: the intake projection with a polling hint (design 2.3, plan 4.3,
 * K-2). The projection is one SQL statement, so it reads one snapshot without a transaction. The hint and the strong
 * {@code ETag} (SHA-256 of the body) only change when the projection does: the hint's jitter is fixed per request.
 */
@Service
@RequiredArgsConstructor
class AnalysisStatus {

	private final IntakeProjection projection;

	private final AnalysisProperties properties;

	private final JsonMapper jsonMapper;

	/**
	 * @return the response of the owner's request; empty when the owner has no such request
	 */
	Optional<Snapshot> find(UUID ownerId, UUID requestId) {
		IntakeResponse response = projection.find(ownerId, requestId);
		if (response == null) {
			return Optional.empty();
		}
		ObjectNode body = jsonMapper.valueToTree(response);
		body.put("pollAfterSeconds", pollAfterSeconds(response, properties.poll()));
		byte[] bytes = jsonMapper.writeValueAsBytes(body);
		return Optional.of(new Snapshot(bytes, "\"" + HexFormat.of().formatHex(sha256(bytes)) + "\""));
	}

	/**
	 * The polling hint: the base delay with the request's jitter while a job is {@code READY} or {@code RUNNING};
	 * none while the jobs only wait for uploads, which the device sends before it asks again, or are all over.
	 */
	static @Nullable Long pollAfterSeconds(IntakeResponse response, AnalysisProperties.Poll poll) {
		boolean queued = response.jobs()
			.stream()
			.anyMatch((job) -> job.status() == JobStatus.READY || job.status() == JobStatus.RUNNING);
		if (!queued) {
			return null;
		}
		Duration base = poll.afterRunning();
		double delay = base.toMillis() * (1 + poll.jitter() * jitterUnit(response.jobId())) / 1000.0;
		return Math.max(1, Math.round(delay));
	}

	/**
	 * A value in [-1, 1] fixed by the request ID, from its random bits.
	 */
	static double jitterUnit(UUID requestId) {
		byte[] digest = sha256(ByteBuffer.allocate(16)
			.putLong(requestId.getMostSignificantBits())
			.putLong(requestId.getLeastSignificantBits())
			.array());
		int value = ((digest[0] & 0xFF) << 8) | (digest[1] & 0xFF);
		return value / 32767.5 - 1;
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * @param body the JSON response body
	 * @param etag the quoted strong entity tag of the body
	 */
	record Snapshot(byte[] body, String etag) {

	}

}
