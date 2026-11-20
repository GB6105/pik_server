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
import tools.jackson.databind.json.JsonMapper;

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
			"pik.analysis.manifest.max-bytes=2097152", "pik.analysis.manifest.max-long-edge-px=1568",
			"pik.analysis.manifest.max-pixels=2458624", "pik.analysis.manifest.prompt=v1",
			"pik.analysis.manifest.schema-version=1", "pik.analysis.manifest.thinking=adaptive",
			"pik.analysis.manifest.effort=low", "pik.analysis.manifest.max-tokens=4096",
			"pik.analysis.manifest.image-token-cap=4784", "pik.analysis.manifest.prompt-token-cap=8000",
			"pik.analysis.manifest.cache.enabled=true", "pik.analysis.manifest.cache.ttl=PT5M",
			"pik.analysis.manifest.output-locale=ko-KR", "pik.analysis.manifest.price.version=claude-haiku-5-5-2026-11",
			"pik.analysis.manifest.price.input-micros-per-mtok=100000",
			"pik.analysis.manifest.price.cache-write-micros-per-mtok=125000",
			"pik.analysis.manifest.price.cache-read-micros-per-mtok=10000",
			"pik.analysis.manifest.price.output-micros-per-mtok=500000",
			"pik.analysis.upload.url-ttl=PT15M", "pik.analysis.upload.confirm-grace=PT15M",
			"pik.analysis.upload.max-attempts=5", "pik.analysis.upload.in-flight-margin=PT30M",
			"pik.analysis.upload.not-found-retry-after=PT5S", "pik.analysis.upload.storage-call-timeout=PT10S",
			"pik.analysis.upload.storage-attempt-timeout=PT3S",
			"pik.analysis.upload.bucket=pik-analysis-test", "pik.analysis.upload.region=us-east-1",
			"pik.analysis.worker.enabled=false", "pik.analysis.worker.poll-interval=PT1S",
			"pik.analysis.worker.global-slots=2", "pik.analysis.worker.principal-slots=4",
			"pik.analysis.worker.claim-oversample=4", "pik.analysis.worker.claim-rounds=3",
			"pik.analysis.worker.lease=PT120S", "pik.analysis.worker.send-margin=PT70S",
			"pik.analysis.claude.request-timeout=PT60S", "pik.analysis.claude.key-version=1",
			"pik.analysis.send-rate.global-per-second=1", "pik.analysis.send-rate.max-instances=2",
			"pik.analysis.budget.daily-cost-limit-micros=1000000", "pik.analysis.budget.principal-daily-sends=300",
			"pik.analysis.budget.photo-lifetime-sends=3", "pik.analysis.budget.device-send-multiplier=2",
			"pik.analysis.input.invalid-threshold=3", "pik.analysis.input.block-duration=PT24H",
			"pik.analysis.input-rate.principal-items-per-hour=300",
			"pik.analysis.input-rate.principal-bytes-per-hour=600MB",
			"pik.analysis.input-rate.trial-items-per-hour=100", "pik.analysis.input-rate.trial-bytes-per-hour=200MB",
			"pik.analysis.circuit.bad-request-ratio=0.2", "pik.analysis.circuit.window=PT10M",
			"pik.analysis.circuit.min-samples=20", "pik.analysis.circuit.transient-threshold=5",
			"pik.analysis.circuit.transient-base-backoff=PT30S", "pik.analysis.circuit.transient-max-backoff=PT10M",
			"pik.analysis.circuit.probe-exclusion=PT10M", "pik.analysis.circuit.half-open-alarm=PT30M",
			"pik.analysis.cleanup.initial-delay=PT1M", "pik.analysis.cleanup.recovery-interval=PT5M",
			"pik.analysis.cleanup.delete-interval=PT1M", "pik.analysis.cleanup.reconcile-interval=PT6H",
			"pik.analysis.cleanup.overdue-alarm=PT6H", "pik.analysis.cleanup.rate-window-interval=PT1H",
			"pik.analysis.cleanup.batch-size=500",
			"pik.analysis.poll.after-running=PT3S", "pik.analysis.poll.jitter=0.2" };

	static final String MODEL_MESSAGE = "grouping.models[].model must have 1 to 64 visible ASCII characters"
			+ " (0x21 to 0x7E)";

	static final String VERSION_MESSAGE = "grouping.models[].model-version must have 1 to 64 visible ASCII"
			+ " characters (0x21 to 0x7E)";

	// Each has a character outside 0x21 to 0x7E inside; values are trimmed when bound, so none is at either end.
	static final String[] NOT_VISIBLE_ASCII = { "pik embed", "pik\tembed", "pik\u0000embed", "pik\u007fembed",
			"pik\ud800embed", "pik\u00e9mbed", "\ud53d\uc775" };

	static final String ON = "pik.analysis.grouping.enabled=true";

	// The stage 5-2 records of DEFAULTS.
	static final AnalysisProperties.Upload UPLOAD = new AnalysisProperties.Upload(Duration.ofMinutes(15),
			Duration.ofMinutes(15), 5, Duration.ofMinutes(30), Duration.ofSeconds(5), Duration.ofSeconds(10),
			Duration.ofSeconds(3), "pik-analysis-test", "us-east-1", null);

	static final AnalysisProperties.Worker WORKER = new AnalysisProperties.Worker(false, Duration.ofSeconds(1), 2, 4,
			4, 3, Duration.ofSeconds(120), Duration.ofSeconds(70));

	static final AnalysisProperties.Claude CLAUDE = new AnalysisProperties.Claude(Duration.ofSeconds(60), "1", null,
			null);

	static final AnalysisProperties.SendRate SEND_RATE = new AnalysisProperties.SendRate(1, 2);

	static final AnalysisProperties.Budget BUDGET = new AnalysisProperties.Budget(1_000_000, 300, 3, 2);

	static final AnalysisProperties.Input INPUT = new AnalysisProperties.Input(3, Duration.ofHours(24));

	static final AnalysisProperties.InputRate INPUT_RATE = new AnalysisProperties.InputRate(300,
			DataSize.ofMegabytes(600), 100, DataSize.ofMegabytes(200));

	static final AnalysisProperties.Circuit CIRCUIT = new AnalysisProperties.Circuit(0.2, Duration.ofMinutes(10), 20,
			5, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(30));

	static final AnalysisProperties.Cleanup CLEANUP = new AnalysisProperties.Cleanup(Duration.ofMinutes(1),
			Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(6), Duration.ofHours(6), Duration.ofHours(1),
			500);

	static final AnalysisProperties.Poll POLL = new AnalysisProperties.Poll(Duration.ofSeconds(3), 0.2);

	final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(AnalysisConfig.class)
		.withPropertyValues(DEFAULTS);

	@Test
	void defaultsStartWithGroupingOffAndNoModels() {
		runner.run((context) -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(VectorValidator.class)
				.hasSingleBean(InputStore.class)
				.hasSingleBean(SendRateLimiter.class)
				.doesNotHaveBean(AnalysisModel.class);
			AnalysisProperties properties = context.getBean(AnalysisProperties.class);
			assertThat(properties.intakeEnabled()).isFalse();
			assertThat(properties.intake())
				.isEqualTo(new AnalysisProperties.Intake(Duration.ofHours(24), Duration.ofHours(48),
						DataSize.ofBytes(2_097_152)));
			assertThat(properties.grouping())
				.isEqualTo(new AnalysisProperties.Grouping(false, Duration.ofSeconds(120), 20, 1e-6, List.of()));
			assertThat(properties.manifest()).isEqualTo(AnalysisManifestsTests.DEFAULT_MANIFEST);
			assertThat(properties).isEqualTo(withStage52Defaults(false, properties.intake(), properties.grouping(),
					properties.manifest()));
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
	void settingsThatGoIntoManifestsMustBeVisibleAscii() {
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

	@Test
	void callManifestRangesFailStartup() {
		String maxPixels = "manifest.max-pixels must be between 1 and max-long-edge-px squared";
		assertFails(maxPixels, "pik.analysis.manifest.max-pixels=0");
		assertFails(maxPixels, "pik.analysis.manifest.max-pixels=2458625");
		assertFails(maxPixels, "pik.analysis.manifest.max-long-edge-px=1567");
		String prompt = "manifest.prompt must name a prompt resource directory: v followed by a version number";
		for (String value : List.of("", "1", "v0", "v01", "V1", "v1/../v2", "v1a")) {
			assertFails(prompt, "pik.analysis.manifest.prompt=" + value);
		}
		assertFails("manifest.schema-version must be at least 1", "pik.analysis.manifest.schema-version=0");
		assertFails("manifest.thinking must be adaptive or disabled", "pik.analysis.manifest.thinking=enabled");
		assertFails("manifest.effort must be low, medium, high, xhigh or max", "pik.analysis.manifest.effort=LOW");
		assertFails("manifest.thinking disabled needs manifest.effort low, medium or high",
				"pik.analysis.manifest.thinking=disabled", "pik.analysis.manifest.effort=xhigh");
		assertFails("manifest.max-tokens must be between 1 and 128000", "pik.analysis.manifest.max-tokens=0");
		assertFails("manifest.max-tokens must be between 1 and 128000", "pik.analysis.manifest.max-tokens=128001");
		assertFails("manifest.image-token-cap must be positive", "pik.analysis.manifest.image-token-cap=0");
		assertFails("manifest.prompt-token-cap must be positive", "pik.analysis.manifest.prompt-token-cap=0");
		assertFails("manifest.cache.ttl must be PT5M or PT1H", "pik.analysis.manifest.cache.ttl=PT10M");
		String locale = "manifest.output-locale must be a well-formed BCP 47 language tag, such as ko-KR";
		for (String value : List.of("ko_KR", "ko--KR", "ko-kr", "\ud55c\uad6d\uc5b4")) {
			assertFails(locale, "pik.analysis.manifest.output-locale=" + value);
		}
		assertFails("manifest.price.version must have 1 or more visible ASCII characters (0x21 to 0x7E)",
				"pik.analysis.manifest.price.version=price 1");
		assertFails("manifest.price.input-micros-per-mtok must be positive",
				"pik.analysis.manifest.price.input-micros-per-mtok=0");
		assertFails("manifest.price.cache-write-micros-per-mtok must be positive",
				"pik.analysis.manifest.price.cache-write-micros-per-mtok=0");
		assertFails("manifest.price.cache-read-micros-per-mtok must be positive",
				"pik.analysis.manifest.price.cache-read-micros-per-mtok=0");
		assertFails("manifest.price.output-micros-per-mtok must be positive",
				"pik.analysis.manifest.price.output-micros-per-mtok=0");
		assertFails("manifest token caps and prices give a maximum cost beyond 2^63",
				"pik.analysis.manifest.price.output-micros-per-mtok=" + Long.MAX_VALUE);
		assertFails("manifest.previous-ids must be manifest IDs: 64 lowercase hexadecimal characters",
				"pik.analysis.manifest.previous-ids=" + "A".repeat(64));
		assertFails("manifest.previous-ids must not name a manifest more than once",
				"pik.analysis.manifest.previous-ids=" + "a".repeat(64) + "," + "a".repeat(64));
	}

	@Test
	void callManifestLimitsStart() {
		runner
			.withPropertyValues("pik.analysis.manifest.max-pixels=1", "pik.analysis.manifest.max-tokens=128000",
					"pik.analysis.manifest.thinking=disabled", "pik.analysis.manifest.effort=high",
					"pik.analysis.manifest.cache.ttl=PT1H", "pik.analysis.manifest.output-locale=en",
					"pik.analysis.manifest.previous-ids=" + "a".repeat(64) + "," + "b".repeat(64))
			.run((context) -> {
				assertThat(context).hasNotFailed();
				AnalysisProperties.Manifest manifest = context.getBean(AnalysisProperties.class).manifest();
				assertThat(manifest.cache().ttl()).isEqualTo(Duration.ofHours(1));
				assertThat(manifest.previousIds()).containsExactly("a".repeat(64), "b".repeat(64));
			});
	}

	// Tests and bootRun run without a key: only the worker calls Claude.
	@Test
	void workerEnabledWithAnApiKeyRegistersTheClaudeAdapter() {
		runner.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withPropertyValues("pik.analysis.worker.enabled=true", "pik.analysis.claude.api-key=test-key")
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(AnalysisModel.class));
	}

	@Test
	void workerEnabledWithoutAnApiKeyFailsStartup() {
		// Empty is what application.yml binds when ANTHROPIC_API_KEY is not set; the last one binds no key at all.
		for (String key : List.of("pik.analysis.claude.api-key=", "pik.analysis.claude.api-key= ",
				"pik.analysis.claude.key-version=1")) {
			runner.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
				.withPropertyValues("pik.analysis.worker.enabled=true", key)
				.run((context) -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.isInstanceOf(IllegalStateException.class)
						.hasMessage("pik.analysis.claude.api-key (ANTHROPIC_API_KEY) must be set when the worker is"
								+ " enabled");
				});
		}
	}

	@Test
	void baseUrlOutsideTheLocalAndTestProfilesFailsStartup() {
		runner.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withInitializer((context) -> context.getEnvironment().setActiveProfiles("prod"))
			.withPropertyValues("pik.analysis.worker.enabled=true", "pik.analysis.claude.api-key=test-key",
					"pik.analysis.claude.base-url=http://127.0.0.1:1")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("pik.analysis.claude.base-url is for a fake server; only the local and test profiles"
							+ " may set it");
			});
		assertFails("claude.base-url must be an http or https URL", "pik.analysis.claude.base-url=ftp://localhost");
	}

	@Test
	void apiKeyIsLeftOutOfTheSettingsText() {
		runner.withPropertyValues("pik.analysis.claude.api-key=test-key").run((context) -> {
			AnalysisProperties.Claude claude = context.getBean(AnalysisProperties.class).claude();
			assertThat(claude.apiKey()).isEqualTo("test-key");
			assertThat(claude).hasToString(
					"Claude[requestTimeout=PT1M, keyVersion=1, apiKey=(set), baseUrl=null]");
			assertThat(context.getBean(AnalysisProperties.class).toString()).doesNotContain("test-key");
		});
	}

	@Test
	void shortestUrlTtlAndFewestAttemptsStart() {
		runner.withPropertyValues("pik.analysis.upload.url-ttl=PT1M", "pik.analysis.upload.max-attempts=1")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void stage52RangesFailStartup() {
		String urlTtl = "upload.url-ttl must be between 1 minute (10 s replay minimum + 50 s signing margin) and 7 days";
		assertFails(urlTtl, "pik.analysis.upload.url-ttl=PT0S");
		assertFails(urlTtl, "pik.analysis.upload.url-ttl=PT59S");
		assertFails(urlTtl, "pik.analysis.upload.url-ttl=P7DT1S");
		assertFails("upload.confirm-grace must not be negative", "pik.analysis.upload.confirm-grace=-PT1S");
		assertFails("upload.max-attempts must be between 1 and 5", "pik.analysis.upload.max-attempts=0");
		assertFails("upload.max-attempts must be between 1 and 5", "pik.analysis.upload.max-attempts=6");
		assertFails("upload.in-flight-margin must not be negative", "pik.analysis.upload.in-flight-margin=-PT1S");
		assertFails("upload.not-found-retry-after must be positive", "pik.analysis.upload.not-found-retry-after=PT0S");
		assertFails("upload.storage-attempt-timeout must be positive",
				"pik.analysis.upload.storage-attempt-timeout=PT0S");
		assertFails("upload.storage-call-timeout must not be shorter than upload.storage-attempt-timeout",
				"pik.analysis.upload.storage-call-timeout=PT2S");
		assertFails("upload.endpoint must be an http or https URL", "pik.analysis.upload.endpoint=ftp://localhost");
		assertFails("worker.global-slots must be at least 1", "pik.analysis.worker.global-slots=0");
		assertFails("worker.principal-slots must be at least 1", "pik.analysis.worker.principal-slots=0");
		assertFails("worker.claim-oversample must be at least 1", "pik.analysis.worker.claim-oversample=0");
		assertFails("worker.claim-rounds must be at least 1", "pik.analysis.worker.claim-rounds=0");
		assertFails("worker.poll-interval must be positive", "pik.analysis.worker.poll-interval=PT0S");
		assertFails("worker.lease must be longer than worker.send-margin", "pik.analysis.worker.lease=PT70S");
		assertFails("worker.send-margin must be longer than claude.request-timeout",
				"pik.analysis.worker.send-margin=PT60S");
		assertFails("worker.send-margin must be longer than claude.request-timeout",
				"pik.analysis.claude.request-timeout=PT70S");
		assertFails("claude.request-timeout must be positive", "pik.analysis.claude.request-timeout=PT0S");
		assertFails("claude.key-version must have 1 or more visible ASCII characters (0x21 to 0x7E)",
				"pik.analysis.claude.key-version=key 2");
		assertFails("send-rate.global-per-second must be positive", "pik.analysis.send-rate.global-per-second=0");
		assertFails("send-rate.global-per-second must be positive",
				"pik.analysis.send-rate.global-per-second=Infinity");
		assertFails("send-rate.max-instances must be at least 1", "pik.analysis.send-rate.max-instances=0");
		assertFails("budget.daily-cost-limit-micros must not be negative",
				"pik.analysis.budget.daily-cost-limit-micros=-1");
		assertFails("budget.principal-daily-sends must be at least 1", "pik.analysis.budget.principal-daily-sends=0");
		assertFails("budget.photo-lifetime-sends must be at least 1", "pik.analysis.budget.photo-lifetime-sends=0");
		assertFails("budget.device-send-multiplier must be at least 1",
				"pik.analysis.budget.device-send-multiplier=0");
		assertFails("input.invalid-threshold must be at least 1", "pik.analysis.input.invalid-threshold=0");
		assertFails("input.block-duration must be positive", "pik.analysis.input.block-duration=PT0S");
		assertFails("input-rate.principal-items-per-hour must be at least 1",
				"pik.analysis.input-rate.principal-items-per-hour=0");
		assertFails("input-rate.principal-bytes-per-hour must be positive",
				"pik.analysis.input-rate.principal-bytes-per-hour=0B");
		assertFails("input-rate.trial-items-per-hour must be at least 1",
				"pik.analysis.input-rate.trial-items-per-hour=0");
		assertFails("input-rate.trial-bytes-per-hour must be positive",
				"pik.analysis.input-rate.trial-bytes-per-hour=0B");
		assertFails("circuit.bad-request-ratio must be in (0, 1]", "pik.analysis.circuit.bad-request-ratio=0");
		assertFails("circuit.bad-request-ratio must be in (0, 1]", "pik.analysis.circuit.bad-request-ratio=1.1");
		assertFails("circuit.bad-request-ratio must be in (0, 1]", "pik.analysis.circuit.bad-request-ratio=NaN");
		assertFails("circuit.window must be positive", "pik.analysis.circuit.window=PT0S");
		assertFails("circuit.min-samples must be at least 1", "pik.analysis.circuit.min-samples=0");
		assertFails("circuit.transient-threshold must be at least 1", "pik.analysis.circuit.transient-threshold=0");
		assertFails("circuit.transient-base-backoff must be positive",
				"pik.analysis.circuit.transient-base-backoff=PT0S");
		assertFails("circuit.transient-max-backoff must not be shorter than circuit.transient-base-backoff",
				"pik.analysis.circuit.transient-max-backoff=PT29S");
		assertFails("circuit.probe-exclusion must be positive", "pik.analysis.circuit.probe-exclusion=PT0S");
		assertFails("circuit.half-open-alarm must be positive", "pik.analysis.circuit.half-open-alarm=PT0S");
		assertFails("cleanup.initial-delay must not be negative", "pik.analysis.cleanup.initial-delay=-PT1S");
		assertFails("cleanup.recovery-interval must be positive", "pik.analysis.cleanup.recovery-interval=PT0S");
		assertFails("cleanup.delete-interval must be positive", "pik.analysis.cleanup.delete-interval=PT0S");
		assertFails("cleanup.reconcile-interval must be positive", "pik.analysis.cleanup.reconcile-interval=PT0S");
		assertFails("cleanup.overdue-alarm must be positive", "pik.analysis.cleanup.overdue-alarm=PT0S");
		assertFails("cleanup.rate-window-interval must be positive",
				"pik.analysis.cleanup.rate-window-interval=PT0S");
		assertFails("cleanup.batch-size must be at least 1", "pik.analysis.cleanup.batch-size=0");
		assertFails("poll.after-running must be positive", "pik.analysis.poll.after-running=PT0S");
		assertFails("poll.jitter must be in [0, 1)", "pik.analysis.poll.jitter=1");
		assertFails("poll.jitter must be in [0, 1)", "pik.analysis.poll.jitter=-0.1");
	}

	// Empty is what application.yml binds when PIK_ANALYSIS_BUCKET or AWS_REGION is not set.
	@ParameterizedTest
	@ValueSource(strings = { "", "ab", "Pik-analysis", "pik_analysis", "-pik-analysis", "pik-analysis-" })
	void bucketThatIsNoS3BucketNameFailsStartup(String bucket) {
		assertFails("upload.bucket must be an S3 bucket name (PIK_ANALYSIS_BUCKET)",
				"pik.analysis.upload.bucket=" + bucket);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "US-EAST-1", "us east 1", "us-east-1-" })
	void regionThatIsNoAwsRegionNameFailsStartup(String region) {
		assertFails("upload.region must be an AWS region name (AWS_REGION)", "pik.analysis.upload.region=" + region);
	}

	/**
	 * Settings with the given stage 5-1 values and the stage 5-2 values of DEFAULTS, for tests that build them by
	 * hand.
	 */
	static AnalysisProperties withStage52Defaults(boolean intakeEnabled, AnalysisProperties.Intake intake,
			AnalysisProperties.Grouping grouping, AnalysisProperties.Manifest manifest) {
		return new AnalysisProperties(intakeEnabled, intake, grouping, manifest, UPLOAD, WORKER, CLAUDE, SEND_RATE,
				BUDGET, INPUT, INPUT_RATE, CIRCUIT, CLEANUP, POLL);
	}

	private void assertFails(String message, String... properties) {
		runner.withPropertyValues(properties).run((context) -> {
			assertThat(context).as(String.join(", ", properties)).hasFailed();
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
