package io.github.gb6105.pik.photo;

import java.util.UUID;
import org.springframework.util.Assert;

/**
 * A client vector of a photo to keep as an immutable version.
 *
 * @param photoId the server photo ID, locked by {@link PhotoObservations#observe} in the same transaction
 * @param dims the number of floats
 * @param f32le the received float32 little-endian bytes, before normalization
 */
public record VectorToKeep(UUID photoId, String modelId, String modelVersion, int dims, byte[] f32le) {

	public VectorToKeep {
		Assert.isTrue(dims > 0 && f32le.length == dims * 4L, "f32le must hold dims float32 values");
		f32le = f32le.clone();
	}

	// Copies, so that the bytes that were checked and are hashed cannot change.
	@Override
	public byte[] f32le() {
		return f32le.clone();
	}

	// The vector is client data and too long for messages.
	@Override
	public String toString() {
		return "VectorToKeep[photoId=" + photoId + ", modelId=" + modelId + ", modelVersion=" + modelVersion
				+ ", dims=" + dims + ", f32le=(" + f32le.length + " bytes)]";
	}

}
