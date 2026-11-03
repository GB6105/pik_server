package io.github.gb6105.pik.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Immutable vector versions of photos (FR-I-02, R19-02, decision Q5-b).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PhotoVectorTests {

	@Autowired
	PhotoObservations observations;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	@Test
	void sameContentReusesVersionAndOtherContentAddsOne() throws Exception {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		UUID otherRef = UUID.randomUUID();
		byte[] a = f32le(1f, 2f);
		byte[] b = f32le(1f, 3f);

		Map<UUID, UUID> first = keep(owner, Map.of(ref, a, otherRef, b), "1");
		UUID photoId = photoId(owner, ref);
		UUID otherPhotoId = photoId(owner, otherRef);
		Map<UUID, UUID> same = keep(owner, Map.of(ref, a), "1");
		Map<UUID, UUID> changed = keep(owner, Map.of(ref, b), "1");
		Map<UUID, UUID> otherModelVersion = keep(owner, Map.of(ref, a), "2");

		assertThat(first).containsOnlyKeys(photoId, otherPhotoId);
		assertThat(same).isEqualTo(Map.of(photoId, first.get(photoId)));
		assertThat(changed.get(photoId)).isNotEqualTo(first.get(photoId)).isNotEqualTo(first.get(otherPhotoId));
		assertThat(otherModelVersion.get(photoId)).isNotEqualTo(first.get(photoId));
		assertThat(vectorRows(photoId)).isEqualTo(3L);
		assertThat(vectorRows(otherPhotoId)).isEqualTo(1L);
		StoredVector stored = jdbc.sql("""
				SELECT photo_id, owner_id, model_id, model_version, encoding, dims, vector, content_sha256
				FROM photo_vector WHERE id = :id
				""").param("id", first.get(photoId)).query(StoredVector.class).single();
		assertThat(stored.photoId()).isEqualTo(photoId);
		assertThat(stored.ownerId()).isEqualTo(owner);
		assertThat(stored.modelId()).isEqualTo("model");
		assertThat(stored.modelVersion()).isEqualTo("1");
		assertThat(stored.encoding()).isEqualTo("F32LE");
		assertThat(stored.dims()).isEqualTo(2);
		assertThat(stored.vector()).isEqualTo(a);
		assertThat(stored.contentSha256()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(a));
	}

	@Test
	void vectorsNeedPhotosObservedForTheOwnerInTheTransaction() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();
		transactions.executeWithoutResult((status) -> observations
			.issueChangeSeqs(observations.observe(owner, List.of(new Observation(ref, "v1")))));
		UUID photoId = photoId(owner, ref);
		VectorToKeep vector = new VectorToKeep(photoId, "model", "1", 2, f32le(1f, 2f));

		// No observation in the transaction.
		assertThatIllegalStateException().isThrownBy(() -> transactions
			.executeWithoutResult((status) -> observations.keepVectors(owner, List.of(vector))));
		// The photo is the owner's and committed, but the transaction observed (locked) another one.
		assertThatIllegalStateException().isThrownBy(() -> transactions.executeWithoutResult((status) -> {
			observations.observe(owner, List.of(new Observation(UUID.randomUUID(), "v1")));
			observations.keepVectors(owner, List.of(vector));
		}));
		// The photo was observed for another owner.
		assertThatIllegalStateException().isThrownBy(() -> transactions.executeWithoutResult((status) -> {
			observations.observe(owner, List.of(new Observation(ref, "v1")));
			observations.keepVectors(UUID.randomUUID(), List.of(vector));
		}));
		assertThat(vectorRows(photoId)).isZero();
	}

	@Test
	void photoHasOneVectorPerCall() {
		UUID owner = UUID.randomUUID();
		UUID ref = UUID.randomUUID();

		assertThatIllegalArgumentException().isThrownBy(() -> transactions.executeWithoutResult((status) -> {
			ObservationBatch batch = observations.observe(owner, List.of(new Observation(ref, "v1")));
			UUID photoId = batch.photos().get(0).photoId();
			observations.keepVectors(owner, List.of(new VectorToKeep(photoId, "model", "1", 1, f32le(1f)),
					new VectorToKeep(photoId, "model", "1", 1, f32le(2f))));
		}));
	}

	@Test
	void vectorBytesMatchDimsAndStayOutOfToString() {
		VectorToKeep vector = new VectorToKeep(UUID.randomUUID(), "model", "1", 2, f32le(1f, 2f));

		assertThat(vector.toString()).contains("dims=2", "f32le=(8 bytes)").doesNotContain("[B@");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new VectorToKeep(UUID.randomUUID(), "model", "1", 2, new byte[7]));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new VectorToKeep(UUID.randomUUID(), "model", "1", 0, new byte[0]));
	}

	/**
	 * Observes the photos and keeps their vectors in one transaction, as the intake does.
	 */
	private Map<UUID, UUID> keep(UUID owner, Map<UUID, byte[]> vectorsByRef, String modelVersion) {
		return transactions.execute((status) -> {
			ObservationBatch batch = observations.observe(owner,
					vectorsByRef.keySet().stream().map((ref) -> new Observation(ref, "v1")).toList());
			List<VectorToKeep> vectors = batch.photos()
				.stream()
				.map((photo) -> {
					byte[] bytes = vectorsByRef.get(photo.clientRef());
					return new VectorToKeep(photo.photoId(), "model", modelVersion, bytes.length / 4, bytes);
				})
				.toList();
			Map<UUID, UUID> kept = observations.keepVectors(owner, vectors);
			observations.issueChangeSeqs(batch);
			return kept;
		});
	}

	private UUID photoId(UUID owner, UUID ref) {
		return jdbc.sql("SELECT id FROM photo WHERE owner_id = :owner AND client_ref = :ref")
			.param("owner", owner)
			.param("ref", ref)
			.query(UUID.class)
			.single();
	}

	private long vectorRows(UUID photoId) {
		return jdbc.sql("SELECT count(*) FROM photo_vector WHERE photo_id = :photo")
			.param("photo", photoId)
			.query(Long.class)
			.single();
	}

	private static byte[] f32le(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return buffer.array();
	}

	record StoredVector(UUID photoId, UUID ownerId, String modelId, String modelVersion, String encoding, int dims,
			byte[] vector, byte[] contentSha256) {
	}

}
