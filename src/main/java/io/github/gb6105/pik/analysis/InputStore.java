package io.github.gb6105.pik.analysis;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Storage of the analysis input images (design 2.2, 2.3, plan 3.2). Devices upload to a presigned URL; the server
 * confirms the upload with {@link #head}, reads it for the call with {@link #getIfMatch} and deletes it. Storage
 * failures come back as {@code Unavailable} instead of exceptions, so callers retry them later. Object keys hold job
 * and attempt IDs only (plan K-3).
 */
interface InputStore {

	/** The content type of every input image (design 1.1). */
	String CONTENT_TYPE = "image/jpeg";

	/**
	 * Signs a PUT that stores the image only under an empty key and only with the given length and SHA-256.
	 * @param key the object key
	 * @param contentLength the image size in bytes
	 * @param sha256 the SHA-256 of the image, 32 bytes
	 * @param expiresAt the latest time the URL may work, 2 seconds to 7 days ahead; it stops up to 2 seconds earlier
	 * @return the URL and the headers the device must send with it
	 * @throws IllegalArgumentException if a value is out of range
	 * @throws RuntimeException if the request cannot be signed, for example without credentials
	 */
	PresignedPut presignPut(String key, long contentLength, byte[] sha256, Instant expiresAt);

	/**
	 * Reads the stored object's metadata, with its SHA-256 checksum.
	 * @param key the object key
	 * @return the metadata, {@link Head.Absent} when nothing is stored under the key, or {@link Head.Unavailable}
	 */
	Head head(String key);

	/**
	 * Reads the stored object if it is still the version of {@code etag}. At most {@code maxBytes} are read: a longer
	 * object gives its first {@code maxBytes} bytes, so callers ask for one byte more than they accept.
	 * @param key the object key
	 * @param etag the ETag the object must still have
	 * @param maxBytes the most bytes to read, at least 1
	 * @return the bytes, {@link Read.PreconditionFailed} when the object has another ETag, {@link Read.Absent} when
	 * nothing is stored under the key, or {@link Read.Unavailable}
	 */
	Read getIfMatch(String key, String etag, int maxBytes);

	/**
	 * Deletes the object. A key without an object is deleted too.
	 * @param key the object key
	 * @return {@code true} when the storage accepted the delete, {@code false} when it was unavailable
	 */
	boolean delete(String key);

	/**
	 * @param url the presigned URL
	 * @param headers the signed headers, lower case, without {@code host}: {@code content-type},
	 * {@code content-length}, {@code x-amz-checksum-sha256} and {@code if-none-match}
	 * @param expiresAt when the URL stops working, not after the requested time
	 */
	record PresignedPut(URI url, Map<String, String> headers, Instant expiresAt) {

		public PresignedPut {
			headers = Map.copyOf(headers);
		}

	}

	sealed interface Head {

		/**
		 * @param size the object size in bytes
		 * @param sha256 the stored SHA-256 checksum, 32 bytes; {@code null} when the object has none
		 * @param etag the ETag, as S3 returns it (quoted)
		 * @param lastModified when the object was stored
		 * @param contentType the stored content type, {@code null} when missing
		 */
		record Present(long size, byte @Nullable [] sha256, String etag, Instant lastModified,
				@Nullable String contentType) implements Head {

			public Present {
				sha256 = (sha256 != null) ? sha256.clone() : null;
			}

			@Override
			public byte @Nullable [] sha256() {
				return (sha256 != null) ? sha256.clone() : null;
			}

			// By content: the generated methods would compare the checksum arrays by identity.
			@Override
			public boolean equals(@Nullable Object other) {
				return this == other || (other instanceof Present present && size == present.size
						&& Arrays.equals(sha256, present.sha256) && etag.equals(present.etag)
						&& lastModified.equals(present.lastModified)
						&& Objects.equals(contentType, present.contentType));
			}

			@Override
			public int hashCode() {
				return Objects.hash(size, Arrays.hashCode(sha256), etag, lastModified, contentType);
			}

			@Override
			public String toString() {
				return "Present[size=" + size + ", sha256=" + ((sha256 != null) ? HexFormat.of().formatHex(sha256) : null)
						+ ", etag=" + etag + ", lastModified=" + lastModified + ", contentType=" + contentType + "]";
			}

		}

		record Absent() implements Head {
		}

		/** The storage failed or refused the request (5xx, 403, I/O); try again later. */
		record Unavailable() implements Head {
		}

	}

	sealed interface Read {

		/**
		 * @param data the bytes read; not copied, since it holds up to a whole image
		 */
		record Bytes(byte[] data) implements Read {
		}

		/** The object has another ETag than the one asked for (412). */
		record PreconditionFailed() implements Read {
		}

		record Absent() implements Read {
		}

		/** The storage failed or refused the request (5xx, 403, I/O); try again later. */
		record Unavailable() implements Read {
		}

	}

}
