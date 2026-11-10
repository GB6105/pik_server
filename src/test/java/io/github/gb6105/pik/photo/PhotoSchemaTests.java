package io.github.gb6105.pik.photo;

import static io.github.gb6105.pik.SchemaRows.assertRejectedBy;
import static io.github.gb6105.pik.SchemaRows.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.SchemaRows;
import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Constraints of the V5 photo tables, checked with plain SQL (FR-I-01, FR-I-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PhotoSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	SchemaRows rows;

	@BeforeEach
	void setUp() {
		rows = new SchemaRows(jdbc);
	}

	@Test
	void photoVersionStartsAtZero() {
		Map<String, Object> photo = photo(UUID.randomUUID());
		rows.insert("photo", photo);

		assertThat(jdbc.sql("SELECT version FROM photo WHERE id = :id").param("id", photo.get("id"))
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void observedVersionHasOneTo128Characters() {
		UUID owner = UUID.randomUUID();
		rows.insert("photo", with(photo(owner), "observed_version", "v"));
		rows.insert("photo", with(photo(owner), "observed_version", "v".repeat(128)));

		assertRejectedBy("ck_photo_observed_version",
				() -> rows.insert("photo", with(photo(owner), "observed_version", "")));
		assertRejectedBy("ck_photo_observed_version",
				() -> rows.insert("photo", with(photo(owner), "observed_version", "v".repeat(129))));
	}

	@Test
	void clientRefIsUniquePerOwner() {
		UUID owner = UUID.randomUUID();
		UUID clientRef = UUID.randomUUID();
		rows.insert("photo", with(photo(owner), "client_ref", clientRef));
		rows.insert("photo", with(photo(UUID.randomUUID()), "client_ref", clientRef));

		assertRejectedBy("uk_photo_owner_client_ref",
				() -> rows.insert("photo", with(photo(owner), "client_ref", clientRef)));
	}

	@Test
	void changeNumbersAreNotNegative() {
		UUID owner = UUID.randomUUID();

		assertRejectedBy("ck_photo_source_change_seq",
				() -> rows.insert("photo", with(photo(owner), "source_change_seq", -1L)));
		assertRejectedBy("ck_photo_change_seq", () -> rows.insert("photo", with(photo(owner), "change_seq", -1L)));
	}

	@Test
	void changeSeqPlaceholderZeroIsAccepted() {
		// 0 stands for "not issued yet" and is replaced before commit; the transaction is rolled back here.
		Map<String, Object> photo = with(photo(UUID.randomUUID()), "change_seq", 0L);
		Long stored = transactions.execute((status) -> {
			status.setRollbackOnly();
			rows.insert("photo", photo);
			return changeSeq(photo);
		});

		assertThat(stored).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM photo WHERE id = :id").param("id", photo.get("id"))
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void vectorBytesAreFourPerDimension() {
		UUID photo = insertPhoto();
		rows.insert("photo_vector", vector(photo, 3));

		assertRejectedBy("ck_photo_vector_length",
				() -> rows.insert("photo_vector", with(vector(photo, 3), "vector", bytes(11))));
		assertRejectedBy("ck_photo_vector_length",
				() -> rows.insert("photo_vector", with(vector(photo, 3), "vector", bytes(13))));
		assertRejectedBy("ck_photo_vector_dims",
				() -> rows.insert("photo_vector", with(vector(photo, 3), "dims", 0, "vector", bytes(0))));
	}

	@Test
	void vectorEncodingIsF32le() {
		UUID photo = insertPhoto();

		assertRejectedBy("ck_photo_vector_encoding",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "encoding", "F16LE")));
	}

	@Test
	void contentHashHas32Bytes() {
		UUID photo = insertPhoto();

		assertRejectedBy("ck_photo_vector_content_sha256",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "content_sha256", bytes(31))));
		assertRejectedBy("ck_photo_vector_content_sha256",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "content_sha256", bytes(33))));
	}

	@Test
	void modelNamesHaveOneTo64Characters() {
		UUID photo = insertPhoto();
		rows.insert("photo_vector", with(vector(photo, 2), "model_id", "m".repeat(64), "model_version", "1"));

		assertRejectedBy("ck_photo_vector_model_id",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "model_id", "")));
		assertRejectedBy("ck_photo_vector_model_id",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "model_id", "m".repeat(65))));
		assertRejectedBy("ck_photo_vector_model_version",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "model_version", "")));
		assertRejectedBy("ck_photo_vector_model_version",
				() -> rows.insert("photo_vector", with(vector(photo, 2), "model_version", "1".repeat(65))));
	}

	@Test
	void sameVectorContentIsOneRow() {
		UUID photo = insertPhoto();
		Map<String, Object> first = vector(photo, 2);
		rows.insert("photo_vector", first);
		// Other content, another model version or another photo is a new version.
		rows.insert("photo_vector", vector(photo, 2));
		rows.insert("photo_vector", with(vector(photo, 2), "content_sha256", first.get("content_sha256"),
				"model_version", "2"));
		rows.insert("photo_vector", with(vector(insertPhoto(), 2), "content_sha256", first.get("content_sha256")));

		assertRejectedBy("uk_photo_vector_content", () -> rows.insert("photo_vector",
				with(vector(photo, 2), "content_sha256", first.get("content_sha256"))));
		int inserted = jdbc
			.sql("INSERT INTO photo_vector (id, photo_id, model_id, model_version, owner_id, encoding, dims, vector,"
					+ " content_sha256, created_at) SELECT :id, photo_id, model_id, model_version, owner_id, encoding,"
					+ " dims, vector, content_sha256, created_at FROM photo_vector WHERE id = :existing"
					+ " ON CONFLICT DO NOTHING")
			.param("id", UuidV7.generate())
			.param("existing", first.get("id"))
			.update();
		assertThat(inserted).isZero();
	}

	@Test
	void vectorBelongsToAPhoto() {
		assertRejectedBy("fk_photo_vector_photo", () -> rows.insert("photo_vector", vector(UuidV7.generate(), 2)));
	}

	private Long changeSeq(Map<String, Object> photo) {
		return jdbc.sql("SELECT change_seq FROM photo WHERE id = :id")
			.param("id", photo.get("id"))
			.query(Long.class)
			.single();
	}

	private UUID insertPhoto() {
		Map<String, Object> photo = photo(UUID.randomUUID());
		rows.insert("photo", photo);
		return (UUID) photo.get("id");
	}

	private static Map<String, Object> photo(UUID owner) {
		OffsetDateTime now = OffsetDateTime.now();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("owner_id", owner);
		row.put("client_ref", UUID.randomUUID());
		row.put("observed_version", "content-1");
		row.put("source_change_seq", 0L);
		row.put("change_seq", 1L);
		row.put("created_at", now);
		row.put("updated_at", now);
		return row;
	}

	private static Map<String, Object> vector(UUID photo, int dims) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", UuidV7.generate());
		row.put("photo_id", photo);
		row.put("model_id", "clip-vit-b32");
		row.put("model_version", "1");
		row.put("owner_id", UUID.randomUUID());
		row.put("encoding", "F32LE");
		row.put("dims", dims);
		row.put("vector", bytes(dims * 4));
		row.put("content_sha256", bytes(32));
		row.put("created_at", OffsetDateTime.now());
		return row;
	}

	private static byte[] bytes(int length) {
		byte[] bytes = new byte[length];
		ThreadLocalRandom.current().nextBytes(bytes);
		return bytes;
	}

}
