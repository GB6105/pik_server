package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link S3InputStore} against S3Mock (plan 3.2, 11.2). S3Mock checks neither signatures nor expiry, so these tests do
 * not either; the real S3 contract is plan 11.4.
 */
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class })
@SpringBootTest
class S3InputStoreTests {

	// java.net.http sets these itself, from the URL and the body.
	private static final Set<String> CLIENT_HEADERS = Set.of("host", "content-length");

	@Autowired
	InputStore store;

	@Autowired
	AnalysisProperties properties;

	final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	final Random random = new Random(42);

	@Test
	void presignedPutWithTheSignedHeadersStoresTheImage() throws Exception {
		byte[] image = image(1000);
		byte[] sha256 = sha256(image);
		String key = key();
		Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));

		InputStore.PresignedPut put = store.presignPut(key, image.length, sha256, expiresAt);

		assertThat(put.headers()).isEqualTo(Map.of("content-type", "image/jpeg", "content-length", "1000",
				"x-amz-checksum-sha256", Base64.getEncoder().encodeToString(sha256), "if-none-match", "*"));
		assertThat(put.url().getPath()).isEqualTo("/" + S3MockConfiguration.BUCKET + "/" + key);
		assertThat(put.expiresAt()).isBeforeOrEqualTo(expiresAt).isAfter(expiresAt.minusSeconds(3));
		assertThat(upload(put, image).statusCode()).isEqualTo(200);

		assertThat(store.head(key)).isInstanceOfSatisfying(InputStore.Head.Present.class, (present) -> {
			assertThat(present.size()).isEqualTo(1000);
			assertThat(present.sha256()).isEqualTo(sha256);
			assertThat(present.etag()).isNotBlank();
			assertThat(present.contentType()).isEqualTo("image/jpeg");
			assertThat(present.lastModified()).isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));
		});
	}

	@Test
	void headOfAKeyWithoutObjectIsAbsent() {
		assertThat(store.head(key())).isEqualTo(new InputStore.Head.Absent());
	}

	@Test
	void getIfMatchReadsOnlyTheConfirmedVersionUpToMaxBytes() throws Exception {
		byte[] image = image(64);
		String key = stored(image);
		String etag = ((InputStore.Head.Present) store.head(key)).etag();

		assertThat(bytes(store.getIfMatch(key, etag, 65))).isEqualTo(image);
		assertThat(bytes(store.getIfMatch(key, etag, 64))).isEqualTo(image);
		assertThat(bytes(store.getIfMatch(key, etag, 10))).isEqualTo(Arrays.copyOf(image, 10));
		assertThat(store.getIfMatch(key, "\"0123456789abcdef0123456789abcdef\"", 65))
			.isEqualTo(new InputStore.Read.PreconditionFailed());
		assertThat(store.getIfMatch(key(), etag, 65)).isEqualTo(new InputStore.Read.Absent());
	}

	@Test
	void deleteRemovesTheObjectAndSucceedsForKeysWithoutObject() throws Exception {
		String key = stored(image(16));

		assertThat(store.delete(key)).isTrue();
		assertThat(store.head(key)).isEqualTo(new InputStore.Head.Absent());
		assertThat(store.delete(key)).isTrue();
		assertThat(store.delete(key())).isTrue();
	}

	@Test
	void unreachableStorageIsUnavailable() {
		AnalysisProperties.Upload upload = properties.upload();
		AnalysisProperties.Upload closedPort = new AnalysisProperties.Upload(upload.urlTtl(), upload.confirmGrace(),
				upload.maxAttempts(), upload.inFlightMargin(), upload.bucket(), upload.region(),
				URI.create("http://127.0.0.1:1"));

		try (S3InputStore unreachable = S3InputStore.create(closedPort)) {
			assertThat(unreachable.head(key())).isEqualTo(new InputStore.Head.Unavailable());
			assertThat(unreachable.getIfMatch(key(), "\"etag\"", 10)).isEqualTo(new InputStore.Read.Unavailable());
			assertThat(unreachable.delete(key())).isFalse();
		}
	}

	@Test
	void endpointIsRefusedOutsideDevelopmentProfiles() {
		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		assertThatIllegalStateException().isThrownBy(() -> new AnalysisConfig().inputStore(properties, production))
			.withMessage("pik.analysis.upload.endpoint is for S3Mock; only the local and test profiles may set it");
		try (S3InputStore localStore = (S3InputStore) new AnalysisConfig().inputStore(properties, local)) {
			assertThat(localStore).isNotNull();
		}
	}

	private String stored(byte[] image) throws Exception {
		String key = key();
		InputStore.PresignedPut put = store.presignPut(key, image.length, sha256(image),
				Instant.now().plus(Duration.ofMinutes(15)));
		assertThat(upload(put, image).statusCode()).isEqualTo(200);
		return key;
	}

	private HttpResponse<String> upload(InputStore.PresignedPut put, byte[] image) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(put.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(image));
		put.headers().forEach((name, value) -> {
			if (!CLIENT_HEADERS.contains(name)) {
				request.header(name, value);
			}
		});
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private byte[] image(int size) {
		byte[] image = new byte[size];
		random.nextBytes(image);
		return image;
	}

	private static byte[] bytes(InputStore.Read read) {
		assertThat(read).isInstanceOf(InputStore.Read.Bytes.class);
		return ((InputStore.Read.Bytes) read).data();
	}

	private static String key() {
		return "analysis-input/" + UUID.randomUUID() + "/" + UUID.randomUUID();
	}

	private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256").digest(bytes);
	}

}
