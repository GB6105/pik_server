package io.github.gb6105.pik.analysis;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * {@link InputStore} on S3 with the AWS SDK. Credentials come from the SDK default chain: the ECS task role when
 * deployed. Log lines carry the operation and the HTTP status or exception type only, never keys or messages.
 */
@Slf4j
final class S3InputStore implements InputStore, AutoCloseable {

	private static final int SHA256_BYTES = 32;

	// The signings of one URL before it fails: each starts from what is left at a new reading of the clock.
	private static final int MAX_SIGNINGS = 3;

	private final S3Client client;

	private final S3Presigner presigner;

	private final String bucket;

	private final InstantSource clock;

	S3InputStore(S3Client client, S3Presigner presigner, String bucket, InstantSource clock) {
		this.client = client;
		this.presigner = presigner;
		this.bucket = bucket;
		this.clock = clock;
	}

	/**
	 * Builds the clients of the settings. Calls keep the SDK standard retries, bounded per attempt and per call; they
	 * all run outside database transactions (plan 1). An endpoint is S3Mock, which serves path-style URLs only.
	 */
	static S3InputStore create(AnalysisProperties.Upload upload) {
		return create(upload, InstantSource.system());
	}

	/**
	 * @param clock the clock the URL validity is computed with
	 */
	static S3InputStore create(AnalysisProperties.Upload upload, InstantSource clock) {
		Region region = Region.of(upload.region());
		S3ClientBuilder client = S3Client.builder()
			.region(region)
			.overrideConfiguration((config) -> config.retryStrategy(RetryMode.STANDARD)
				.apiCallTimeout(upload.storageCallTimeout())
				.apiCallAttemptTimeout(upload.storageAttemptTimeout()));
		S3Presigner.Builder presigner = S3Presigner.builder().region(region);
		URI endpoint = upload.endpoint();
		if (endpoint != null) {
			client.endpointOverride(endpoint).forcePathStyle(true);
			presigner.endpointOverride(endpoint)
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
		}
		return new S3InputStore(client.build(), presigner.build(), upload.bucket(), clock);
	}

	@Override
	public PresignedPut presignPut(String key, long contentLength, byte[] sha256, Instant expiresAt) {
		Assert.isTrue(contentLength >= 1, "contentLength must be positive");
		Assert.isTrue(sha256.length == SHA256_BYTES, "sha256 must have 32 bytes");
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.contentType(CONTENT_TYPE)
			.contentLength(contentLength)
			.checksumSHA256(Base64.getEncoder().encodeToString(sha256))
			.ifNoneMatch("*")
			.build();
		for (int signing = 1; signing <= MAX_SIGNINGS; signing++) {
			// The URL lives whole seconds from the signing time cut to the second. One second less than what is left
			// keeps it from outliving expiresAt, which deletes rely on (plan 7.4).
			Duration validity = Duration.ofSeconds(Duration.between(clock.instant(), expiresAt).toSeconds() - 1);
			Assert.isTrue(validity.isPositive(), "expiresAt must be at least 2 seconds ahead");
			PresignedPutObjectRequest presigned = presigner
				.presignPutObject((request) -> request.signatureDuration(validity).putObjectRequest(put));
			// The SDK adds the validity to its own, later reading of the clock; after a pause between the two the URL
			// would outlive expiresAt, so it is signed again with what is left then.
			if (!presigned.expiration().isAfter(expiresAt)) {
				return presignedPut(presigned);
			}
		}
		throw new IllegalStateException("The URL could not be signed to expire by expiresAt");
	}

	private static PresignedPut presignedPut(PresignedPutObjectRequest presigned) {
		// Devices cannot set Host; their HTTP client sends it from the URL.
		Map<String, String> headers = new HashMap<>();
		presigned.signedHeaders().forEach((name, values) -> {
			if (!"host".equalsIgnoreCase(name)) {
				headers.put(name, String.join(",", values));
			}
		});
		return new PresignedPut(URI.create(presigned.url().toString()), headers, presigned.expiration());
	}

	@Override
	public Head head(String key) {
		try {
			HeadObjectResponse response = client
				.headObject((request) -> request.bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED));
			return new Head.Present(response.contentLength(), sha256(response.checksumSHA256()), response.eTag(),
					response.lastModified(), response.contentType());
		}
		catch (SdkException ex) {
			if (status(ex) == 404) {
				return new Head.Absent();
			}
			logUnavailable("HEAD", ex);
			return new Head.Unavailable();
		}
	}

	@Override
	public Read getIfMatch(String key, String etag, int maxBytes) {
		Assert.isTrue(maxBytes >= 1, "maxBytes must be positive");
		try (ResponseInputStream<GetObjectResponse> stream = client
			.getObject((request) -> request.bucket(bucket).key(key).ifMatch(etag))) {
			byte[] data = stream.readNBytes(maxBytes);
			if (data.length == maxBytes) {
				// Closing would otherwise read the rest of a longer object to reuse the connection.
				stream.abort();
			}
			return new Read.Bytes(data);
		}
		catch (SdkException ex) {
			return switch (status(ex)) {
				case 404 -> new Read.Absent();
				case 412 -> new Read.PreconditionFailed();
				default -> {
					logUnavailable("GET", ex);
					yield new Read.Unavailable();
				}
			};
		}
		catch (IOException ex) {
			logUnavailable("GET", ex);
			return new Read.Unavailable();
		}
	}

	// S3 answers 204 for a key without an object; a 404 means the bucket is missing.
	@Override
	public boolean delete(String key) {
		try {
			client.deleteObject((request) -> request.bucket(bucket).key(key));
			return true;
		}
		catch (SdkException ex) {
			logUnavailable("DELETE", ex);
			return false;
		}
	}

	@Override
	public void close() {
		presigner.close();
		client.close();
	}

	// Base64 of 32 bytes; anything else (a multipart checksum such as "...-3") counts as no checksum.
	private static byte @Nullable [] sha256(@Nullable String checksum) {
		if (checksum == null) {
			return null;
		}
		try {
			byte[] sha256 = Base64.getDecoder().decode(checksum);
			return (sha256.length == SHA256_BYTES) ? sha256 : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	// 0 when no HTTP status was received.
	private static int status(SdkException ex) {
		return (ex instanceof AwsServiceException service) ? service.statusCode() : 0;
	}

	private static void logUnavailable(String operation, Exception ex) {
		int status = (ex instanceof SdkException sdk) ? status(sdk) : 0;
		log.warn("Input store {} unavailable: {}", operation,
				(status != 0) ? "HTTP " + status : ex.getClass().getSimpleName());
	}

}
