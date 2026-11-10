package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * G-7 (rule version) and the call manifest of decision Q3: canonical JSON and its SHA-256. The defaults are those of
 * {@code application.yml}.
 */
class AnalysisManifestsTests {

	static final AnalysisProperties.Grouping DEFAULT_GROUPING = new AnalysisProperties.Grouping(false,
			Duration.ofSeconds(120), 20, 1e-6, List.of());

	static final AnalysisProperties.Manifest DEFAULT_MANIFEST = new AnalysisProperties.Manifest("claude-haiku-5-5",
			"image/jpeg", 2_097_152, 1568);

	static final String DEFAULT_RULE_JSON = "{\"algorithmVersion\":\"grouping-v1\","
			+ "\"eligibility\":\"valid vector of a registered model, takenAt present, text status NO_TEXT\","
			+ "\"enabled\":false,"
			+ "\"joining\":\"cosine to every member >= threshold, time span <= window, size < maxGroupSize;"
			+ " largest minimum similarity; tie: group made first\","
			+ "\"maxGroupSize\":20,\"models\":[],"
			+ "\"normalization\":\"float32 values as double divided by the double L2 norm; sums in index order\","
			+ "\"ordering\":\"takenAt ascending, then photoId as unsigned 128-bit ascending\","
			+ "\"representative\":\"largest mean similarity to the other members; tie: earliest takenAt,"
			+ " then photoId as unsigned 128-bit\","
			+ "\"window\":\"PT2M\",\"zeroNormEpsilon\":0.000001}";

	// SHA-256 of DEFAULT_RULE_JSON, computed outside the code under test (shasum -a 256).
	static final String DEFAULT_RULE_ID = "d2ad4a00ad0dfcb1df2e101b9cd1d638f69b8bb8e77aba4453783cea624fc9ad";

	static final String DEFAULT_MANIFEST_JSON = "{\"model\":\"claude-haiku-5-5\","
			+ "\"uploadPolicy\":{\"contentType\":\"image/jpeg\",\"maxBytes\":2097152,\"maxLongEdgePx\":1568}}";

	static final String DEFAULT_MANIFEST_ID = "20e8511f279f3dae369d39241dea3978ab2d42efab8bb75403f6b5b774f4ac09";

	static final AnalysisProperties.Model MODEL = new AnalysisProperties.Model("pik-embed", "1", 512, 0.9);

	@Test
	void g7DefaultRuleManifestHasAPinnedSha256() {
		String manifest = GroupingRules.manifest(DEFAULT_GROUPING);

		assertThat(manifest).isEqualTo(DEFAULT_RULE_JSON);
		assertThat(CanonicalJson.sha256Hex(manifest)).isEqualTo(DEFAULT_RULE_ID);
	}

	@Test
	void g7ChangingAnyOneGroupingSettingChangesTheRuleVersion() {
		AnalysisProperties.Grouping on = grouping(true, Duration.ofSeconds(120), 20, 1e-6, MODEL);
		List<AnalysisProperties.Grouping> variants = List.of(DEFAULT_GROUPING,
				grouping(false, Duration.ofSeconds(121), 20, 1e-6),
				grouping(false, Duration.ofMillis(120_001), 20, 1e-6),
				grouping(false, Duration.ofSeconds(120), 19, 1e-6), grouping(false, Duration.ofSeconds(120), 20, 1e-7),
				grouping(false, Duration.ofSeconds(120), 20, 1e-6, MODEL), on,
				grouping(true, Duration.ofSeconds(120), 20, 1e-6, new AnalysisProperties.Model("pik-embed2", "1", 512,
						0.9)),
				grouping(true, Duration.ofSeconds(120), 20, 1e-6, new AnalysisProperties.Model("pik-embed", "2", 512,
						0.9)),
				grouping(true, Duration.ofSeconds(120), 20, 1e-6, new AnalysisProperties.Model("pik-embed", "1", 511,
						0.9)),
				grouping(true, Duration.ofSeconds(120), 20, 1e-6, new AnalysisProperties.Model("pik-embed", "1", 512,
						Math.nextDown(0.9))),
				grouping(true, Duration.ofSeconds(120), 20, 1e-6, MODEL,
						new AnalysisProperties.Model("other", "1", 8, 0.5)));

		Set<String> ids = new HashSet<>();
		variants.forEach((variant) -> ids.add(CanonicalJson.sha256Hex(GroupingRules.manifest(variant))));

		assertThat(ids).hasSize(variants.size());
	}

	@Test
	void ruleVersionDoesNotDependOnTheOrderOfTheModelList() {
		AnalysisProperties.Model other = new AnalysisProperties.Model("other", "1", 8, 0.5);
		AnalysisProperties.Model older = new AnalysisProperties.Model("pik-embed", "0", 512, 0.9);

		assertThat(GroupingRules.manifest(grouping(true, Duration.ofSeconds(120), 20, 1e-6, MODEL, other, older)))
			.isEqualTo(GroupingRules.manifest(grouping(true, Duration.ofSeconds(120), 20, 1e-6, other, older, MODEL)))
			.contains("\"models\":[{\"dims\":8,\"model\":\"other\",\"modelVersion\":\"1\",\"threshold\":0.5},"
					+ "{\"dims\":512,\"model\":\"pik-embed\",\"modelVersion\":\"0\",\"threshold\":0.9},"
					+ "{\"dims\":512,\"model\":\"pik-embed\",\"modelVersion\":\"1\",\"threshold\":0.9}]");
	}

	@Test
	void defaultCallManifestHoldsTheModelAndTheUploadPolicy() {
		String content = CallManifests.content(DEFAULT_MANIFEST);

		assertThat(content).isEqualTo(DEFAULT_MANIFEST_JSON);
		assertThat(CanonicalJson.sha256Hex(content)).isEqualTo(DEFAULT_MANIFEST_ID);
		assertThat(List.of(new AnalysisProperties.Manifest("claude-haiku-5-6", "image/jpeg", 2_097_152, 1568),
				new AnalysisProperties.Manifest("claude-haiku-5-5", "image/png", 2_097_152, 1568),
				new AnalysisProperties.Manifest("claude-haiku-5-5", "image/jpeg", 2_097_151, 1568),
				new AnalysisProperties.Manifest("claude-haiku-5-5", "image/jpeg", 2_097_152, 1092)))
			.extracting((manifest) -> CanonicalJson.sha256Hex(CallManifests.content(manifest)))
			.doesNotHaveDuplicates()
			.doesNotContain(DEFAULT_MANIFEST_ID);
	}

	@Test
	void canonicalJsonSortsKeysAndFormatsNumbersAndStringsOneWay() {
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("z", List.of(1, 2L, 0.5, 1.0, 1e-7, 1e21, 123.456, true));
		nested.put("B", "upper");
		nested.put("a", Map.of("y", "x", "x", "y"));
		nested.put("text", "quote\" backslash\\ newline\n tab\t nul\u0000 unit\u001f del\u007f 한글 é");

		assertThat(CanonicalJson.write(nested)).isEqualTo("{\"B\":\"upper\",\"a\":{\"x\":\"y\",\"y\":\"x\"},"
				+ "\"text\":\"quote\\\" backslash\\\\ newline\\n tab\\t nul\\u0000 unit\\u001f del\u007f 한글 é\","
				+ "\"z\":[1,2,0.5,1,0.0000001,1000000000000000000000,123.456,true]}");
		assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJson.write(Map.of("nan", Double.NaN)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> CanonicalJson.write(Map.of("infinite", Double.POSITIVE_INFINITY)));
		assertThatIllegalArgumentException().isThrownBy(() -> CanonicalJson.write(Map.of("duration", Duration.ZERO)));
	}

	static AnalysisProperties.Grouping grouping(boolean enabled, Duration window, int maxGroupSize,
			double zeroNormEpsilon, AnalysisProperties.Model... models) {
		return new AnalysisProperties.Grouping(enabled, window, maxGroupSize, zeroNormEpsilon, List.of(models));
	}

}
