package io.github.gb6105.pik.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDv7 (RFC 9562) for primary keys and other server-issued IDs (ERD). New rows append to the end of
 * B-tree indexes instead of landing at random pages.
 */
public final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	public static UUID generate() {
		long msb = (System.currentTimeMillis() << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
		long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
		return new UUID(msb, lsb);
	}

}
