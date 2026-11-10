package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

/**
 * Startup checks of {@code pik.analysis.*} (plan 7, design 1.4): wrong values fail the start. The runner binds only
 * the values given here, so each test starts from the defaults of {@code application.yml}. {@code models} is left out
 * of the defaults: an empty {@code models} value in the same property source hides indexed entries.
 */
class AnalysisSettingsTests {

	static final String[] DEFAULTS = { "pik.analysis.intake-enabled=false",
			"pik.analysis.intake.upload-deadline=PT24H", "pik.analysis.intake.processing-deadline=PT48H",
			"pik.analysis.intake.max-body-size=2MB",
			"pik.analysis.grouping.enabled=false", "pik.analysis.grouping.window=PT120S",
			"pik.analysis.grouping.max-group-size=20", "pik.analysis.grouping.zero-norm-epsilon=1e-6",
			"pik.analysis.manifest.model=claude-haiku-5-5", "pik.analysis.manifest.content-type=image/jpeg",
			"pik.analysis.manifest.max-bytes=2097152", "pik.analysis.manifest.max-long-edge-px=1568" };

	static final String MODEL_MESSAGE = "grouping.models[].model must have 1 to 64 visible ASCII characters"
			+ " (0x21 to 0x7E)";

	static final String VERSION_MESSAGE = "grouping.models[].model-version must have 1 to 64 visible ASCII"
			+ " characters (0x21 to 0x7E)";

	// Each has a character outside 0x21 to 0x7E inside; values are trimmed when bound, so none is at either end.
	static final String[] NOT_VISIBLE_ASCII = { "pik embed", "pik\tembed", "pik\u0000embed", "pik\u007fembed",
			"pik\ud800embed", "pik\u00e9mbed", "\ud53d\uc775" };

	static final String ON = "pik.analysis.grouping.enabled=true";

	final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AnalysisConfig.class)
		.withPropertyValues(DEFAULTS);

	@Test
	void defaultsStartWithGroupingOffAndNoModels() {
		runner.run((context) -> {
			assertThat(context).hasNotFailed().hasSingleBean(VectorValidator.class);
			AnalysisProperties properties = context.getBean(AnalysisProperties.class);
			assertThat(properties.intakeEnabled()).isFalse();
			assertThat(properties.intake())
				.isEqualTo(new AnalysisProperties.Intake(Duration.ofHours(24), Duration.ofHours(48),
						DataSize.ofBytes(2_097_152)));
			assertThat(properties.grouping())
				.isEqualTo(new AnalysisProperties.Grouping(false, Duration.ofSeconds(120), 20, 1e-6, List.of()));
			assertThat(properties.manifest())
				.isEqualTo(new AnalysisProperties.Manifest("claude-haiku-5-5", "image/jpeg", 2_097_152, 1568));
		});
	}

	@Test
	void groupingOnWithRegisteredModelsStarts() {
		runner
			.withPropertyValues(properties(ON, model(0, "pik-embed", "1", "1", "0.999999"),
					model(1, "pik-embed", "2", "2250", "0.85"), model(2, "!~", "1", "512", "4.9e-324")))
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AnalysisProperties.class).grouping().models()).containsExactly(
						new AnalysisProperties.Model("pik-embed", "1", 1, 0.999999),
						new AnalysisProperties.Model("pik-embed", "2", 2250, 0.85),
						new AnalysisProperties.Model("!~", "1", 512, Double.MIN_VALUE));
			});
	}

	@Test
	void groupingOnWithoutModelsFailsStartup() {
		assertFails("grouping.models must not be empty when grouping is enabled", ON);
		assertFails("grouping.models must not be empty when grouping is enabled", ON, "pik.analysis.grouping.models=");
	}

	@Test
	void groupingOnWithADuplicateModelAndVersionFailsStartup() {
		assertFails("grouping.models registers model pik-embed version 1 more than once",
				properties(ON, model(0, "pik-embed", "1", "512", "0.9"), model(1, "pik-embed", "1", "256", "0.8")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "2251" })
	void groupingOnWithDimsOutOfRangeFailsStartup(String dims) {
		assertFails("grouping.models[].dims must be between 1 and 2250",
				properties(ON, model(0, "pik-embed", "1", dims, "0.9")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-0.1", "1", "1.0", "1.0000000000000002", "NaN", "Infinity" })
	void groupingOnWithThresholdOutsideZeroToOneFailsStartup(String threshold) {
		assertFails("grouping.models[].threshold must be in (0, 1)",
				properties(ON, model(0, "pik-embed", "1", "512", threshold)));
	}

	@Test
	void groupingOnWithoutModelNameOrVersionFailsStartup() {
		assertFails(MODEL_MESSAGE, properties(ON, model(0, " ", "1", "512", "0.9")));
		assertFails(MODEL_MESSAGE, properties(ON, model(0, "m".repeat(65), "1", "512", "0.9")));
		assertFails(VERSION_MESSAGE, properties(ON, model(0, "pik-embed", "", "512", "0.9")));
		assertFails(VERSION_MESSAGE, properties(ON, model(0, "pik-embed", "v".repeat(65), "512", "0.9")));
	}

	@Test
	void settingsThatGoIntoManifestsMustBePrintableAscii() {
		for (String value : NOT_VISIBLE_ASCII) {
			assertFails(MODEL_MESSAGE, properties(ON, model(0, value, "1", "512", "0.9")));
			assertFails(VERSION_MESSAGE, properties(ON, model(0, "pik-embed", value, "512", "0.9")));
			assertFails("manifest.model must have 1 or more visible ASCII characters (0x21 to 0x7E)",
					"pik.analysis.manifest.model=" + value);
			assertFails("manifest.content-type must have 1 or more visible ASCII characters (0x21 to 0x7E)",
					"pik.analysis.manifest.content-type=" + value);
		}
		assertFails("manifest.content-type must have 1 or more visible ASCII characters (0x21 to 0x7E)",
				"pik.analysis.manifest.content-type=image/jpeg; q=1");
	}

	// A wrong list fails even while grouping is off, so that turning it on cannot be the first time it is checked.
	@Test
	void groupingOffStillChecksTheModelsItIsGiven() {
		assertFails("grouping.models[].dims must be between 1 and 2250", model(0, "pik-embed", "1", "0", "0.9"));
		assertFails("grouping.models registers model pik-embed version 1 more than once",
				properties(model(0, "pik-embed", "1", "512", "0.9"), model(1, "pik-embed", "1", "512", "0.9")));
	}

	@Test
	void otherRangesFailStartup() {
		assertFails("intake.upload-deadline must be positive", "pik.analysis.intake.upload-deadline=PT0S");
		assertFails("intake.processing-deadline must be positive", "pik.analysis.intake.processing-deadline=-PT1H");
		assertFails("intake.max-body-size must be between 1 byte and 2 GB", "pik.analysis.intake.max-body-size=0B");
		assertFails("intake.max-body-size must be between 1 byte and 2 GB", "pik.analysis.intake.max-body-size=2GB");
		assertFails("grouping.window must be positive", "pik.analysis.grouping.window=PT0S");
		assertFails("grouping.max-group-size must be at least 1", "pik.analysis.grouping.max-group-size=0");
		assertFails("grouping.zero-norm-epsilon must be positive", "pik.analysis.grouping.zero-norm-epsilon=0");
		assertFails("grouping.zero-norm-epsilon must be positive", "pik.analysis.grouping.zero-norm-epsilon=NaN");
		assertFails("manifest.model must have 1 or more visible ASCII characters (0x21 to 0x7E)",
				"pik.analysis.manifest.model=");
		assertFails("manifest.content-type must have 1 or more visible ASCII characters (0x21 to 0x7E)",
				"pik.analysis.manifest.content-type= ");
		assertFails("manifest.max-bytes must be positive", "pik.analysis.manifest.max-bytes=0");
		assertFails("manifest.max-long-edge-px must be positive", "pik.analysis.manifest.max-long-edge-px=0");
	}

	private void assertFails(String message, String... properties) {
		runner.withPropertyValues(properties).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(message);
		});
	}

	// Flattens property values and arrays of them.
	private static String[] properties(Object... parts) {
		return Arrays.stream(parts)
			.flatMap((part) -> (part instanceof String[] values) ? Arrays.stream(values) : Stream.of((String) part))
			.toArray(String[]::new);
	}

	private static String[] model(int index, String model, String version, String dims, String threshold) {
		String prefix = "pik.analysis.grouping.models[" + index + "].";
		return new String[] { prefix + "model=" + model, prefix + "model-version=" + version, prefix + "dims=" + dims,
				prefix + "threshold=" + threshold };
	}

}
