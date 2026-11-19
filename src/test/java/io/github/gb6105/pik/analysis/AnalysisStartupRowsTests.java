package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The default settings start and insert the current rule and call manifest rows; starting again inserts nothing, and
 * changed settings add a row of their own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AnalysisStartupRowsTests {

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
			.sql("SELECT content = CAST(:content AS jsonb) AS same_content, content_type, max_bytes, max_long_edge_px"
					+ " FROM analysis_call_manifest WHERE id = :id")
			.param("content", AnalysisManifestsTests.DEFAULT_MANIFEST_JSON)
			.param("id", AnalysisManifestsTests.DEFAULT_MANIFEST_ID)
			.query()
			.singleRow();

		assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of("same_content", true, "content_type", "image/jpeg",
				"max_bytes", 2_097_152, "max_long_edge_px", 1568));
	}

	@Test
	void startingAgainInsertsNothing() {
		long rules = count("analysis_grouping_rule");
		long manifests = count("analysis_call_manifest");
		OffsetDateTime ruleCreatedAt = createdAt("analysis_grouping_rule", groupingRules.currentId());
		OffsetDateTime manifestCreatedAt = createdAt("analysis_call_manifest", callManifests.currentId());

		new GroupingRules(properties, jdbc).afterSingletonsInstantiated();
		new CallManifests(properties, jdbc).afterSingletonsInstantiated();

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
				properties.manifest());
		GroupingRules rules = new GroupingRules(changed, jdbc);

		rules.afterSingletonsInstantiated();

		assertThat(rules.currentId()).isNotEqualTo(groupingRules.currentId());
		assertThat(jdbc.sql("SELECT count(*) FROM analysis_grouping_rule WHERE id IN (:ids)")
			.param("ids", List.of(rules.currentId(), groupingRules.currentId()))
			.query(Long.class)
			.single()).isEqualTo(2);
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
