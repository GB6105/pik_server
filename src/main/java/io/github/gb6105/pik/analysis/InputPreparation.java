package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.analysis.RunRecords.RunBasis;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads and checks the confirmed input of a run outside any transaction (plan 5.3, design 2.4 step 2): a conditional
 * GET on the confirmed ETag, at most one byte past the manifest's limit, then the JPEG frame header against the
 * manifest's pixel and edge limits, then a full decode and the SHA-256 of the confirmed upload (K-5), so that a broken
 * image never reaches Claude as a 400 that spends risky sends.
 */
@Component
@RequiredArgsConstructor
class InputPreparation {

	private final InputStore store;

	/**
	 * @param manifest the job's call manifest, whose limits the image must keep
	 */
	Prepared prepare(RunBasis basis, CallManifest manifest) {
		String objectKey = basis.objectKey();
		String etag = basis.inputEtag();
		byte[] sha256 = basis.inputSha256();
		if (objectKey == null || etag == null || sha256 == null) {
			return new Prepared.Failed(FailureCode.INPUT_MISSING);
		}
		AnalysisProperties.Manifest limits = manifest.settings();
		return switch (store.getIfMatch(objectKey, etag, Math.addExact(limits.maxBytes(), 1))) {
			case InputStore.Read.Bytes bytes -> check(bytes.data(), sha256, limits);
			case InputStore.Read.PreconditionFailed changed -> new Prepared.Failed(FailureCode.INPUT_CHANGED);
			case InputStore.Read.Absent absent -> new Prepared.Failed(FailureCode.INPUT_MISSING);
			case InputStore.Read.Unavailable unavailable -> new Prepared.Unavailable();
		};
	}

	private static Prepared check(byte[] data, byte[] sha256, AnalysisProperties.Manifest limits) {
		if (data.length > limits.maxBytes()) {
			return new Prepared.Failed(FailureCode.INPUT_INVALID);
		}
		int[] size = jpegSize(data);
		if (size == null || size[0] <= 0 || size[1] <= 0 || Math.max(size[0], size[1]) > limits.maxLongEdgePx()
				|| (long) size[0] * size[1] > limits.maxPixels()) {
			return new Prepared.Failed(FailureCode.INPUT_INVALID);
		}
		if (!decodes(data) || !Arrays.equals(sha256(data), sha256)) {
			return new Prepared.Failed(FailureCode.INPUT_INVALID);
		}
		return new Prepared.Ready(data);
	}

	/**
	 * Width and height from the first frame header (SOF0 to SOF15 but DHT, JPG and DAC) of a JPEG, read before
	 * anything is decoded.
	 * @return {@code {width, height}}, {@code null} when the bytes are not a JPEG with a frame header before its scan
	 */
	static int @Nullable [] jpegSize(byte[] data) {
		if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
			return null;
		}
		int at = 2;
		while (at + 3 < data.length) {
			if ((data[at] & 0xFF) != 0xFF) {
				return null;
			}
			int marker = data[at + 1] & 0xFF;
			if (marker == 0xFF) {
				at++;
				continue;
			}
			// Markers without a length: TEM and RST0 to RST7.
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				at += 2;
				continue;
			}
			if (marker == 0xD9 || marker == 0xDA) {
				return null;
			}
			int length = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
			if (length < 2) {
				return null;
			}
			boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
			if (frame) {
				if (length < 7 || at + 8 >= data.length) {
					return null;
				}
				int height = ((data[at + 5] & 0xFF) << 8) | (data[at + 6] & 0xFF);
				int width = ((data[at + 7] & 0xFF) << 8) | (data[at + 8] & 0xFF);
				return new int[] { width, height };
			}
			at += 2 + length;
		}
		return null;
	}

	// The whole image, in memory: no cache file is written.
	private static boolean decodes(byte[] data) {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
		if (!readers.hasNext()) {
			return false;
		}
		ImageReader reader = readers.next();
		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
			reader.setInput(input, true, true);
			return reader.read(0) != null;
		}
		catch (IOException | RuntimeException ex) {
			return false;
		}
		finally {
			reader.dispose();
		}
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the preparation found.
	 */
	sealed interface Prepared {

		/**
		 * @param jpeg the checked image; not copied, since it holds a whole image
		 */
		record Ready(byte[] jpeg) implements Prepared {

			@Override
			public String toString() {
				return "Ready[" + jpeg.length + " bytes]";
			}

		}

		/**
		 * The input cannot be used: {@code INPUT_CHANGED}, {@code INPUT_MISSING} or {@code INPUT_INVALID}.
		 */
		record Failed(FailureCode reason) implements Prepared {
		}

		/** The storage failed or refused the request; the job tries again later. */
		record Unavailable() implements Prepared {
		}

	}

}
