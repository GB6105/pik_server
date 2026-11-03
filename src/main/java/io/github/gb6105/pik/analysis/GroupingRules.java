package io.github.gb6105.pik.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The grouping rule of the current settings and its version: the SHA-256 of the canonical rule manifest (design 1.3,
 * decision Q2). The manifest holds every grouping setting and the fixed parts of v1, so that a request's
 * {@code grouping_rule_id} names everything that formed its groups. Its row is inserted at startup when missing,
 * before the web server accepts requests; rows never change.
 */
@Component
class GroupingRules implements SmartInitializingSingleton {

	private final JdbcClient jdbc;

	private final GroupingRuleV1 current;

	private final String manifest;

	private final String currentId;

	GroupingRules(AnalysisProperties properties, JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.current = new GroupingRuleV1(properties.grouping());
		this.manifest = manifest(properties.grouping());
		this.currentId = CanonicalJson.sha256Hex(this.manifest);
	}

	GroupingRuleV1 current() {
		return current;
	}

	/**
	 * @return the {@code analysis_grouping_rule} ID of {@link #current()}
	 */
	String currentId() {
		return currentId;
	}

	@Override
	public void afterSingletonsInstantiated() {
		jdbc.sql("""
				INSERT INTO analysis_grouping_rule (id, content, created_at)
				VALUES (:id, CAST(:content AS jsonb), now())
				ON CONFLICT (id) DO NOTHING
				""").param("id", currentId).param("content", manifest).update();
	}

	/**
	 * Models are sorted by (model, modelVersion), so the order of the settings list does not change the version.
	 */
	static String manifest(AnalysisProperties.Grouping settings) {
		List<Map<String, Object>> models = settings.models()
			.stream()
			.sorted(Comparator.comparing(AnalysisProperties.Model::model)
				.thenComparing(AnalysisProperties.Model::modelVersion))
			.map((model) -> Map.<String, Object>of("model", model.model(), "modelVersion", model.modelVersion(),
					"dims", model.dims(), "threshold", model.threshold()))
			.toList();
		return CanonicalJson.write(Map.ofEntries(Map.entry("algorithmVersion", "grouping-v1"),
				Map.entry("enabled", settings.enabled()), Map.entry("window", settings.window().toString()),
				Map.entry("maxGroupSize", settings.maxGroupSize()),
				Map.entry("zeroNormEpsilon", settings.zeroNormEpsilon()), Map.entry("models", models),
				Map.entry("eligibility", "valid vector of a registered model, takenAt present, text status NO_TEXT"),
				Map.entry("ordering", "takenAt ascending, then photoId as unsigned 128-bit ascending"),
				Map.entry("joining",
						"cosine to every member >= threshold, time span <= window, size < maxGroupSize;"
								+ " largest minimum similarity; tie: group made first"),
				Map.entry("representative", "largest mean similarity to the other members; tie: earliest takenAt,"
						+ " then photoId as unsigned 128-bit"),
				Map.entry("normalization", "float32 values as double divided by the double L2 norm;"
						+ " sums in index order")));
	}

}
