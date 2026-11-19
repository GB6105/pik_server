package io.github.gb6105.pik.photo;

import io.github.gb6105.pik.shared.id.UuidV7;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL instead of entities, so that observations never raise the Pik data version {@code photo.version} (the
 * same reason as ADR-0027 #15). {@code ORDER BY} on PostgreSQL {@code uuid} values compares them as unsigned bytes,
 * so every transaction inserts and locks in the same order.
 */
@Repository
@RequiredArgsConstructor
class PhotoRecords {

	private static final RowMapper<Map.Entry<UUID, UUID>> UUID_PAIR = (rs, rowNum) -> Map
		.entry(rs.getObject("key", UUID.class), rs.getObject("value", UUID.class));

	private final JdbcClient jdbc;

	/**
	 * Inserts the photos that have no row yet in {@code client_ref} order, so that two transactions creating the same
	 * photos wait for each other instead of deadlocking. {@code change_seq} is a placeholder until it is issued.
	 * @return IDs of the rows this statement created
	 */
	List<UUID> insertMissing(UUID ownerId, List<Observation> observations) {
		List<Object[]> rows = observations.stream()
			.map((observation) -> new Object[] { UuidV7.generate(), observation.clientRef(),
					observation.contentVersion() })
			.toList();
		return jdbc.sql("""
				INSERT INTO photo (id, owner_id, client_ref, observed_version, source_change_seq, change_seq,
				                   created_at, updated_at)
				SELECT v.id, :owner, v.client_ref, v.observed_version, 0, 0, now(), now()
				FROM (VALUES :rows) AS v (id, client_ref, observed_version)
				ORDER BY v.client_ref
				ON CONFLICT (owner_id, client_ref) DO NOTHING
				RETURNING id
				""").param("owner", ownerId).param("rows", rows).query(UUID.class).list();
	}

	/**
	 * Locks the owner's rows of the given client photo IDs in {@code id} order until the transaction ends.
	 */
	List<ObservedPhoto> lock(UUID ownerId, Collection<UUID> clientRefs) {
		return jdbc.sql("""
				SELECT id AS photo_id, client_ref, observed_version, source_change_seq
				FROM photo
				WHERE owner_id = :owner AND client_ref IN (:refs)
				ORDER BY id
				FOR UPDATE
				""").param("owner", ownerId).param("refs", clientRefs).query(ObservedPhoto.class).list();
	}

	/**
	 * Records new observed versions of locked rows and raises their {@code source_change_seq} by one, leaving
	 * {@code version} and {@code change_seq} as they are.
	 */
	void raise(Collection<ObservedPhoto> changed) {
		List<Object[]> rows = changed.stream()
			.map((photo) -> new Object[] { photo.photoId(), photo.observedVersion() })
			.toList();
		jdbc.sql("""
				UPDATE photo
				SET observed_version = v.observed_version, source_change_seq = photo.source_change_seq + 1,
				    updated_at = now()
				FROM (VALUES :rows) AS v (id, observed_version)
				WHERE photo.id = v.id
				""").param("rows", rows).update();
	}

	/**
	 * Gives the rows {@code first}, {@code first + 1}, … in {@code id} order.
	 * @return the number of rows changed
	 */
	int assignChangeSeqs(UUID ownerId, Collection<UUID> photoIds, long first) {
		return jdbc.sql("""
				UPDATE photo
				SET change_seq = :first + o.rank - 1
				FROM (SELECT id, row_number() OVER (ORDER BY id) AS rank
				      FROM photo WHERE owner_id = :owner AND id IN (:ids)) AS o
				WHERE photo.id = o.id
				""").param("first", first).param("owner", ownerId).param("ids", photoIds).update();
	}

	/**
	 * @return client photo ID to server photo ID, for the photos that have a row
	 */
	Map<UUID, UUID> findIds(UUID ownerId, Collection<UUID> clientRefs) {
		return jdbc.sql("SELECT client_ref AS key, id AS value FROM photo WHERE owner_id = :owner AND client_ref IN (:refs)")
			.param("owner", ownerId)
			.param("refs", clientRefs)
			.query(UUID_PAIR)
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * Stores the vector versions that are new, in photo {@code id} order, and finds the stored version of each. The
	 * caller holds the photo rows' locks, so the unique constraint only conflicts with committed rows.
	 * @return photo ID to the ID of the version with the same model and the same bytes
	 */
	Map<UUID, UUID> keepVectors(UUID ownerId, List<VectorToKeep> vectors) {
		List<Object[]> rows = new ArrayList<>(vectors.size());
		List<Object[]> keys = new ArrayList<>(vectors.size());
		for (VectorToKeep vector : vectors) {
			byte[] bytes = vector.f32le();
			byte[] sha256 = sha256(bytes);
			rows.add(new Object[] { UuidV7.generate(), vector.photoId(), vector.modelId(), vector.modelVersion(),
					vector.dims(), bytes, sha256 });
			keys.add(new Object[] { vector.photoId(), vector.modelId(), vector.modelVersion(), sha256 });
		}
		jdbc.sql("""
				INSERT INTO photo_vector (id, photo_id, model_id, model_version, owner_id, encoding, dims, vector,
				                          content_sha256, created_at)
				SELECT v.id, v.photo_id, v.model_id, v.model_version, :owner, 'F32LE', v.dims, v.vector,
				       v.content_sha256, now()
				FROM (VALUES :rows) AS v (id, photo_id, model_id, model_version, dims, vector, content_sha256)
				ORDER BY v.photo_id
				ON CONFLICT (photo_id, model_id, model_version, content_sha256) DO NOTHING
				""").param("owner", ownerId).param("rows", rows).update();
		return jdbc.sql("""
				SELECT photo_id AS key, id AS value FROM photo_vector
				WHERE (photo_id, model_id, model_version, content_sha256) IN (:keys)
				""")
			.param("keys", keys)
			.query(UUID_PAIR)
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	// The SHA-256 of the raw float32 bytes identifies a vector version (uk_photo_vector_content).
	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
