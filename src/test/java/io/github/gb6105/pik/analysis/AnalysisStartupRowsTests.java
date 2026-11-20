package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The default settings start and insert the current rule and call manifest rows; starting again inserts nothing, and
 * changed settings add a row of their own. Earlier call manifests run only when they are stored with call limits and
 * their resources still rebuild their ID.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisStartupRowsTests {

	static final JsonMapper JSON = AnalysisManifestsTests.JSON;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	GroupingRules groupingRules;

	@Autowired
	CallManifests callManifests;

	@Autowired
	AnalysisProperties properties;

	@Test
	void applicationYmlKeepsIntakeAndGroupingOff() {
		assertThat(properties.intakeEnabled()).isFalse();
		assertThat(properties.intake().uploadDeadline()).isEqualTo(Duration.ofHours(24));
		assertThat(properties.intake().processingDeadline()).isEqualTo(Duration.ofHours(48));
		assertThat(properties.grouping().enabled()).isFalse();
	}

	@Test
	void startupInsertsTheCurrentRuleOfTheDefaultSettings() {
		assertThat(groupingRules.currentId()).isEqualTo(AnalysisManifestsTests.DEFAULT_RULE_ID);
		assertThat(groupingRules.current().settings()).isEqualTo(AnalysisManifestsTests.DEFAULT_GROUPING);
		assertThat(jdbc.sql("SELECT content = CAST(:content AS jsonb) FROM analysis_grouping_rule WHERE id = :id")
			.param("content", AnalysisManifestsTests.DEFAULT_RULE_JSON)
			.param("id", AnalysisManifestsTests.DEFAULT_RULE_ID)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void startupInsertsTheCurrentCallManifestOfTheDefaultSettings() {
		assertThat(callManifests.currentId()).isEqualTo(AnalysisManifestsTests.DEFAULT_MANIFEST_ID);
		Map<String, Object> row = jdbc
			.sql("SELECT content = CAST(:content AS jsonb) AS same_content, content_type, max_bytes, max_long_edge_px,"
					+ " max_pixels, max_tokens, max_cost_micros FROM analysis_call_manifest WHERE id = :id")
			.param("content", AnalysisManifestsTests.DEFAULT_MANIFEST_JSON)
			.param("id", AnalysisManifestsTests.DEFAULT_MANIFEST_ID)
			.query()
			.singleRow();

		assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of("same_content", true, "content_type", "image/jpeg",
				"max_bytes", 2_097_152, "max_long_edge_px", 1568, "max_pixels", 2_458_624L, "max_tokens", 4096,
				"max_cost_micros", 3527L));
		assertThat(callManifests.runnable(AnalysisManifestsTests.DEFAULT_MANIFEST_ID)).contains(callManifests.current());
	}

	@Test
	void previousIdsMakeStoredManifestsRunnable() {
		CallManifests earlier = new CallManifests(withManifest(AnalysisManifestsTests.manifest((m) -> {
			m.maxTokens = 2048;
			m.thinking = "disabled";
		})), jdbc, JSON);
		earlier.afterSingletonsInstantiated();
		CallManifests manifests = new CallManifests(
				withManifest(AnalysisManifestsTests.manifest((m) -> m.previousIds = List.of(earlier.currentId()))),
				jdbc, JSON);

		manifests.afterSingletonsInstantiated();

		assertThat(manifests.currentId()).isEqualTo(AnalysisManifestsTests.DEFAULT_MANIFEST_ID);
		CallManifest previous = manifests.runnable(earlier.currentId()).orElseThrow();
		assertThat(previous.content()).isEqualTo(earlier.current().content());
		assertThat(previous.settings().maxTokens()).isEqualTo(2048);
		assertThat(previous.settings().thinking()).isEqualTo("disabled");
		assertThat(previous.systemPrompt()).isEqualTo(earlier.current().systemPrompt());
		assertThat(previous.userText()).isEqualTo(CallManifests.USER_TEXT);
		assertThat(manifests.runnable(AnalysisManifestsTests.DEFAULT_MANIFEST_ID)).isPresent();
		assertThat(manifests.runnable("0".repeat(64))).isEmpty();
	}

	@Test
	void previousIdsThatCannotRunFailStartup() {
		String missing = "1".repeat(64);
		String stage51 = "2".repeat(64);
		String changed = "3".repeat(64);
		insertManifest(stage51, "{\"model\":\"claude-haiku-5-5\"}", null, null, null);
		// The content of the default manifest under another ID: the resources rebuild a different ID.
		insertManifest(changed, AnalysisManifestsTests.DEFAULT_MANIFEST_JSON, 2_458_624L, 4096, 3527L);

		assertPreviousFails(missing, "manifest.previous-ids names " + missing + ", which is not stored");
		assertPreviousFails(stage51,
				"manifest.previous-ids names " + stage51 + ", which has no call limits and cannot run");
		assertPreviousFails(changed, "manifest.previous-ids names " + changed
				+ ", whose content the prompt and schema resources no longer rebuild");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CallManifests(withManifest(AnalysisManifestsTests
				.manifest((m) -> m.previousIds = List.of(AnalysisManifestsTests.DEFAULT_MANIFEST_ID))), jdbc, JSON))
			.withMessage("manifest.previous-ids must not name the current manifest");
	}

	@Test
	void previousIdWhoseContentCannotBeReadFailsStartup() {
		String unreadable = "4".repeat(64);
		insertManifest(unreadable, "{\"model\":\"claude-haiku-5-5\",\"prompt\":\"v9\"}", 2_458_624L, 4096, 3527L);

		CallManifests manifests = new CallManifests(
				withManifest(AnalysisManifestsTests.manifest((m) -> m.previousIds = List.of(unreadable))), jdbc, JSON);

		assertThatIllegalStateException().isThrownBy(manifests::afterSingletonsInstantiated)
			.withMessageStartingWith("manifest.previous-ids names " + unreadable + ", whose content cannot be rebuilt");
	}

	@Test
	void startingAgainInsertsNothing() {
		long rules = count("analysis_grouping_rule");
		long manifests = count("analysis_call_manifest");
		OffsetDateTime ruleCreatedAt = createdAt("analysis_grouping_rule", groupingRules.currentId());
		OffsetDateTime manifestCreatedAt = createdAt("analysis_call_manifest", callManifests.currentId());

		new GroupingRules(properties, jdbc).afterSingletonsInstantiated();
		new CallManifests(properties, jdbc, JSON).afterSingletonsInstantiated();

		assertThat(count("analysis_grouping_rule")).isEqualTo(rules);
		assertThat(count("analysis_call_manifest")).isEqualTo(manifests);
		assertThat(createdAt("analysis_grouping_rule", groupingRules.currentId())).isEqualTo(ruleCreatedAt);
		assertThat(createdAt("analysis_call_manifest", callManifests.currentId())).isEqualTo(manifestCreatedAt);
	}

	@Test
	void changedSettingsAddANewRowAndKeepTheOldOne() {
		AnalysisProperties.Grouping defaults = properties.grouping();
		AnalysisProperties changed = new AnalysisProperties(properties.intakeEnabled(), properties.intake(),
				new AnalysisProperties.Grouping(defaults.enabled(), defaults.window().plusSeconds(1),
						defaults.maxGroupSize(), defaults.zeroNormEpsilon(), defaults.models()),
				properties.manifest(), properties.upload(), properties.worker(), properties.claude(),
				properties.sendRate(), properties.budget(), properties.input(), properties.inputRate(),
				properties.circuit(), properties.cleanup(), properties.poll());
		GroupingRules rules = new GroupingRules(changed, jdbc);

		rules.afterSingletonsInstantiated();

		assertThat(rules.currentId()).isNotEqualTo(groupingRules.currentId());
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_grouping_rule WHERE id IN (:ids)")
			.param("ids", List.of(rules.currentId(), groupingRules.currentId()))
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	private void assertPreviousFails(String id, String message) {
		CallManifests manifests = new CallManifests(
				withManifest(AnalysisManifestsTests.manifest((m) -> m.previousIds = List.of(id))), jdbc, JSON);

		assertThatIllegalStateException().isThrownBy(manifests::afterSingletonsInstantiated).withMessage(message);
	}

	private AnalysisProperties withManifest(AnalysisProperties.Manifest manifest) {
		return new AnalysisProperties(properties.intakeEnabled(), properties.intake(), properties.grouping(), manifest,
				properties.upload(), properties.worker(), properties.claude(), properties.sendRate(),
				properties.budget(), properties.input(), properties.inputRate(), properties.circuit(),
				properties.cleanup(), properties.poll());
	}

	private void insertManifest(String id, String content, @Nullable Long maxPixels, @Nullable Integer maxTokens,
			@Nullable Long maxCostMicros) {
		jdbc.sql("""
				INSERT INTO analysis_call_manifest (id, content, content_type, max_bytes, max_long_edge_px, max_pixels,
				                                    max_tokens, max_cost_micros, created_at)
				VALUES (:id, CAST(:content AS jsonb), 'image/jpeg', 2097152, 1568, :maxPixels, :maxTokens, :maxCostMicros,
				        now())
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", id)
			.param("content", content)
			.param("maxPixels", maxPixels)
			.param("maxTokens", maxTokens)
			.param("maxCostMicros", maxCostMicros)
			.update();
	}

	private long count(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

	private OffsetDateTime createdAt(String table, String id) {
		return jdbc.sql("SELECT created_at FROM " + table + " WHERE id = :id")
			.param("id", id)
			.query(OffsetDateTime.class)
			.single();
	}

}
