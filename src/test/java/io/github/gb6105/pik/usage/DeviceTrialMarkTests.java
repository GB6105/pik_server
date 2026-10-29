package io.github.gb6105.pik.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeviceTrialMarkTests {

	static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

	@Test
	void markThatIsStartingKeepsItsTrialWhenTheBitsAreFoundSet() {
		DeviceTrialMark mark = DeviceTrialMark.ios(false, 5, NOW);
		mark.startDeviceCheck(NOW);

		mark.startedElsewhere(NOW);

		assertThat(mark.getDeviceCheckState()).isEqualTo(DeviceCheckState.STARTING);
		assertThat(mark.getExhaustedAt()).isNull();
		assertThat(mark.covers(5)).isTrue();
	}

	@Test
	void startedMarkStaysStarted() {
		DeviceTrialMark mark = DeviceTrialMark.ios(false, 5, NOW);
		mark.startDeviceCheck(NOW);
		mark.recordDeviceCheck(NOW);

		mark.cancelDeviceCheck(NOW);
		mark.startDeviceCheck(NOW);

		assertThat(mark.getDeviceCheckState()).isEqualTo(DeviceCheckState.TRIAL_STARTED);
		assertThat(mark.needsDeviceCheck()).isFalse();
	}

	@Test
	void deviceStartedBeforeTheInstallHasNoTrial() {
		DeviceTrialMark mark = DeviceTrialMark.ios(true, 5, NOW);

		assertThat(mark.getDeviceCheckState()).isEqualTo(DeviceCheckState.TRIAL_STARTED);
		assertThat(mark.getExhaustedAt()).isEqualTo(NOW);
		assertThat(mark.covers(1)).isFalse();
	}

}
