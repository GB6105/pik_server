package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The JPEG frame header read before a decode (plan 5.3).
 */
class InputPreparationTests {

	@Test
	void frameHeaderGivesWidthAndHeight() {
		assertThat(InputPreparation.jpegSize(RunFixtures.jpeg(64, 48))).containsExactly(64, 48);
		assertThat(InputPreparation.jpegSize(RunFixtures.jpeg(3, 1601))).containsExactly(3, 1601);
	}

	@Test
	void bytesWithoutAFrameHeaderBeforeTheScanHaveNoSize() {
		byte[] jpeg = RunFixtures.jpeg(16, 16);
		assertThat(InputPreparation.jpegSize(new byte[] { (byte) 0x89, 'P', 'N', 'G' })).isNull();
		assertThat(InputPreparation.jpegSize(Arrays.copyOf(jpeg, 4))).isNull();
		// A start of image followed by the start of scan.
		assertThat(InputPreparation.jpegSize(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2 }))
			.isNull();
		// A segment length that runs past the end.
		assertThat(InputPreparation.jpegSize(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x7F,
				(byte) 0xFF })).isNull();
	}

}
