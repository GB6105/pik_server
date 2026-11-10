package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * G-1 to G-7: grouping rule v1 (design 1.3, plan 6.5). Vectors go through {@link VectorValidator}, so similarities
 * come from float32 values as the intake computes them.
 */
class GroupingRuleV1Tests {

	static final Instant T0 = Instant.parse("2026-11-04T09:00:00Z");

	static final String MODEL = "pik-embed";

	static final AnalysisProperties.Model PLANE = new AnalysisProperties.Model(MODEL, "1", 2, 0.85);

	static final AnalysisProperties.Model PLANE_V2 = new AnalysisProperties.Model(MODEL, "2", 2, 0.85);

	static final AnalysisProperties.Model OTHER = new AnalysisProperties.Model("other-embed", "1", 2, 0.85);

	static final AnalysisProperties.Model GOLDEN = new AnalysisProperties.Model("golden-embed", "1", 4, 0.9);

	static final VectorValidator VALIDATOR = new VectorValidator(settings(true, 20, PLANE, PLANE_V2, OTHER, GOLDEN));

	static final VectorValidator UNREGISTERED = new VectorValidator(settings(false, 20));

	@Test
	void g1ShuffledInputGivesTheSameGroupsRepresentativesAndOrder() {
		List<GroupingCandidate> photos = new ArrayList<>(golden());
		photos.add(photo(UUID.fromString("ffffffff-0000-0000-0000-000000000000"), 20, angle(PLANE, 0)));
		photos.add(photo(UUID.fromString("00000000-0000-0000-ffff-000000000000"), 20, angle(PLANE, 0)));
		photos.add(photo(UUID.fromString("7fffffff-0000-0000-0000-000000000000"), 20, angle(PLANE, 1)));
		photos.add(photo(UUID.fromString("80000000-0000-0000-0000-000000000000"), 25, angle(PLANE, 60)));
		photos.add(new GroupingCandidate(id(99), null, TextStatus.UNKNOWN, null));
		GroupingRuleV1 rule = rule(settings(true, 20, PLANE, GOLDEN));
		List<FormedGroup> expected = group(rule, photos);
		Random random = new Random(20261104);

		for (int i = 0; i < 50; i++) {
			List<GroupingCandidate> shuffled = new ArrayList<>(photos);
			Collections.shuffle(shuffled, random);
			assertThat(group(rule, shuffled)).isEqualTo(expected);
		}
		assertThat(expected).anySatisfy((group) -> assertThat(group.photoIds()).hasSize(3));
	}

	@Test
	void g2SimilarityEqualToThresholdJoinsAndTheNextDoubleUpSplits() {
		GroupingCandidate a = photo(id(1), 0, angle(PLANE, 0));
		GroupingCandidate b = photo(id(2), 10, angle(PLANE, 10));
		double similarity = SimilarityMatrix.of(List.of(a, b)).between(a.photoId(), b.photoId());

		assertThat(memberLists(rule(PLANE, similarity), a, b)).containsExactly(List.of(id(1), id(2)));
		assertThat(memberLists(rule(PLANE, Math.nextUp(similarity)), a, b)).containsExactly(List.of(id(1)),
				List.of(id(2)));
	}

	@Test
	void g2SpanOfWindowJoinsAndOneSecondMoreSplits() {
		GroupingRuleV1 rule = rule(settings(true, 20, PLANE));
		GroupingCandidate first = photo(id(1), 0, angle(PLANE, 0));

		assertThat(memberLists(rule, first, photo(id(2), 120, angle(PLANE, 0)))).containsExactly(List.of(id(1), id(2)));
		assertThat(memberLists(rule, first, photo(id(2), 121, angle(PLANE, 0)))).containsExactly(List.of(id(1)),
				List.of(id(2)));
		assertThat(memberLists(rule, first, candidate(id(2), T0.plusSeconds(120).plusNanos(1), angle(PLANE, 0))))
			.containsExactly(List.of(id(1)), List.of(id(2)));
		// The span counts from the earliest member, not from the latest one.
		assertThat(memberLists(rule, first, photo(id(2), 60, angle(PLANE, 0)), photo(id(3), 120, angle(PLANE, 0)),
				photo(id(4), 121, angle(PLANE, 0))))
			.containsExactly(List.of(id(1), id(2), id(3)), List.of(id(4)));
	}

	@Test
	void g2PhotoAfterAFullGroupStartsANewGroup() {
		List<GroupingCandidate> photos = IntStream.rangeClosed(1, 21)
			.mapToObj((n) -> photo(id(n), n, angle(PLANE, 0)))
			.toList();

		List<FormedGroup> groups = group(rule(settings(true, 20, PLANE)), photos);

		assertThat(groups).hasSize(2);
		assertThat(groups.get(0).photoIds()).hasSize(20).doesNotContain(id(21));
		assertThat(groups.get(1).photoIds()).containsExactly(id(21));
	}

	@Test
	void g3PhotoMustReachTheThresholdWithEveryMember() {
		GroupingCandidate a = photo(id(1), 0, angle(PLANE, 0));
		GroupingCandidate b = photo(id(2), 10, angle(PLANE, 20));
		GroupingCandidate c = photo(id(3), 20, angle(PLANE, 40));
		double threshold = Math.cos(Math.toRadians(30));
		SimilarityMatrix similarities = SimilarityMatrix.of(List.of(a, b, c));
		assertThat(similarities.between(id(1), id(2))).isGreaterThanOrEqualTo(threshold);
		assertThat(similarities.between(id(2), id(3))).isGreaterThanOrEqualTo(threshold);
		assertThat(similarities.between(id(1), id(3))).isLessThan(threshold);

		assertThat(memberLists(rule(PLANE, threshold), a, b, c)).containsExactly(List.of(id(1), id(2)),
				List.of(id(3)));
	}

	@Test
	void g4PhotoJoinsTheGroupWithTheLargestMinimumSimilarity() {
		// a1 is closest to p, but a2 makes the minimum of their group lower than b's.
		GroupingCandidate a1 = photo(id(1), 0, angle(PLANE, -5));
		GroupingCandidate a2 = photo(id(2), 10, angle(PLANE, -28));
		GroupingCandidate b = photo(id(3), 20, angle(PLANE, 20));
		GroupingCandidate p = photo(id(4), 30, angle(PLANE, 0));

		assertThat(memberLists(rule(PLANE, 0.85), a1, a2, b, p)).containsExactly(List.of(id(1), id(2)),
				List.of(id(3), id(4)));
	}

	@Test
	void g4EqualMinimumSimilarityGoesToTheGroupMadeFirst() {
		// Mirrored vectors give p exactly the same similarity to a and b.
		float x = (float) Math.cos(Math.toRadians(25));
		float y = (float) Math.sin(Math.toRadians(25));
		VectorValidator.Valid a = valid(PLANE, x, -y);
		VectorValidator.Valid b = valid(PLANE, x, y);
		GroupingRuleV1 rule = rule(PLANE, 0.85);

		assertThat(memberLists(rule, photo(id(9), 0, a), photo(id(1), 10, b), photo(id(5), 20, angle(PLANE, 0))))
			.containsExactly(List.of(id(9), id(5)), List.of(id(1)));
		assertThat(memberLists(rule, photo(id(9), 10, a), photo(id(1), 0, b), photo(id(5), 20, angle(PLANE, 0))))
			.containsExactly(List.of(id(1), id(5)), List.of(id(9)));
	}

	@Test
	void g5RepresentativeHasTheLargestMeanSimilarity() {
		GroupingRuleV1 rule = rule(PLANE, 0.9);

		List<FormedGroup> groups = group(rule, List.of(photo(id(1), 0, angle(PLANE, -10)),
				photo(id(9), 10, angle(PLANE, 0)), photo(id(2), 20, angle(PLANE, 10))));

		assertThat(groups).singleElement().satisfies((group) -> {
			assertThat(group.representativeId()).isEqualTo(id(9));
			assertThat(group.photoIds()).containsExactly(id(1), id(9), id(2));
		});
	}

	@Test
	void g5EqualMeansGoToTheEarliestTakenAt() {
		List<FormedGroup> groups = group(rule(PLANE, 0.9),
				List.of(photo(id(1), 5, angle(PLANE, 0)), photo(id(2), 0, angle(PLANE, 0))));

		assertThat(groups).singleElement().satisfies((group) -> {
			assertThat(group.representativeId()).isEqualTo(id(2));
			assertThat(group.photoIds()).containsExactly(id(2), id(1));
		});
	}

	@Test
	void g5EqualMeansAndTakenAtGoToTheSmallestUnsignedPhotoId() {
		UUID highMost = UUID.fromString("80000000-0000-0000-0000-000000000000");
		UUID lowMost = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
		UUID highLeast = UUID.fromString("00000000-0000-0000-8000-000000000000");
		UUID lowLeast = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
		// The signed order of UUID.compareTo is the other way round.
		assertThat(highMost.compareTo(lowMost)).isNegative();
		assertThat(highLeast.compareTo(lowLeast)).isNegative();
		GroupingRuleV1 rule = rule(PLANE, 0.9);

		assertThat(group(rule, List.of(photo(highMost, 0, angle(PLANE, 0)), photo(lowMost, 0, angle(PLANE, 0)))))
			.singleElement()
			.satisfies((group) -> {
				assertThat(group.representativeId()).isEqualTo(lowMost);
				assertThat(group.photoIds()).containsExactly(lowMost, highMost);
			});
		assertThat(group(rule, List.of(photo(highLeast, 0, angle(PLANE, 0)), photo(lowLeast, 0, angle(PLANE, 0)))))
			.singleElement()
			.satisfies((group) -> assertThat(group.representativeId()).isEqualTo(lowLeast));
		assertThat(GroupingRuleV1.PHOTO_ID_ORDER.compare(lowMost, highMost)).isNegative();
		assertThat(GroupingRuleV1.PHOTO_ID_ORDER.compare(highLeast, lowMost)).isNegative();
	}

	@Test
	void g6PhotosThatAreNotEligibleStaySingle() {
		GroupingRuleV1 rule = rule(settings(true, 20, PLANE, PLANE_V2, OTHER));
		GroupingCandidate first = photo(id(1), 0, angle(PLANE, 0));
		GroupingCandidate last = photo(id(3), 2, angle(PLANE, 0));
		List<GroupingCandidate> variants = List.of(candidate(id(2), null, angle(PLANE, 0)),
				new GroupingCandidate(id(2), T0.plusSeconds(1), TextStatus.UNKNOWN, angle(PLANE, 0)),
				new GroupingCandidate(id(2), T0.plusSeconds(1), TextStatus.TEXT, angle(PLANE, 0)),
				new GroupingCandidate(id(2), T0.plusSeconds(1), TextStatus.NO_TEXT, null),
				new GroupingCandidate(id(2), T0.plusSeconds(1), TextStatus.NO_TEXT, unchecked("unregistered", 1, 0)),
				photo(id(2), 1, angle(PLANE_V2, 0)), photo(id(2), 1, angle(OTHER, 0)));

		for (GroupingCandidate single : variants) {
			List<FormedGroup> groups = group(rule, List.of(first, single, last));

			assertThat(groups).extracting(FormedGroup::photoIds)
				.containsExactlyInAnyOrder(List.of(id(1), id(3)), List.of(id(2)));
			assertThat(groups).filteredOn((group) -> group.photoIds().equals(List.of(id(2))))
				.singleElement()
				.satisfies((group) -> {
					assertThat(group.representativeId()).isEqualTo(id(2));
					assertThat(group.modelId()).isNull();
					assertThat(group.modelVersion()).isNull();
				});
		}
	}

	/**
	 * A vector checked with grouping off keeps any length. Under a registered model of other dims the photo is single,
	 * and the similarities skip the pair: it comes first, so a dot product over its 3 values would fail.
	 */
	@Test
	void g6VectorOfARegisteredModelWithOtherDimsStaysSingle() {
		GroupingCandidate threeDims = new GroupingCandidate(id(2), T0.plusSeconds(1), TextStatus.NO_TEXT,
				unchecked(MODEL, 1, 0, 0));
		List<GroupingCandidate> photos = List.of(threeDims, photo(id(1), 0, angle(PLANE, 0)),
				photo(id(3), 2, angle(PLANE, 0)));

		List<FormedGroup> groups = group(rule(settings(true, 20, PLANE)), photos);

		assertThat(groups).extracting(FormedGroup::photoIds).containsExactly(List.of(id(1), id(3)), List.of(id(2)));
	}

	@Test
	void g6PhotosOfAnotherModelGroupOnlyAmongThemselves() {
		List<FormedGroup> groups = group(rule(settings(true, 20, PLANE, PLANE_V2)),
				List.of(photo(id(1), 0, angle(PLANE, 0)), photo(id(2), 1, angle(PLANE_V2, 0)),
						photo(id(3), 2, angle(PLANE, 0)), photo(id(4), 3, angle(PLANE_V2, 0))));

		assertThat(groups).extracting(FormedGroup::photoIds)
			.containsExactly(List.of(id(1), id(3)), List.of(id(2), id(4)));
		assertThat(groups).extracting(FormedGroup::modelVersion).containsExactly("1", "2");
	}

	@Test
	void g6GroupingOffLeavesEveryPhotoSingle() {
		GroupingRuleV1 off = rule(settings(false, 20, PLANE));

		List<FormedGroup> groups = group(off, List.of(photo(id(1), 0, angle(PLANE, 0)),
				photo(id(2), 1, angle(PLANE, 0)), photo(id(3), 2, angle(PLANE, 0))));

		assertThat(groups).extracting(FormedGroup::photoIds)
			.containsExactly(List.of(id(1)), List.of(id(2)), List.of(id(3)));
		assertThat(groups).extracting(FormedGroup::modelId).containsOnlyNulls();
	}

	@Test
	void g7GoldenVectorsFormTheExpectedGroups() {
		List<GroupingCandidate> photos = golden();
		SimilarityMatrix similarities = SimilarityMatrix.of(photos);

		List<FormedGroup> groups = rule(settings(true, 20, GOLDEN)).group(photos, similarities);

		assertThat(similarities.between(golden(1), golden(2))).isEqualTo(0.9599999979972839);
		assertThat(similarities.between(golden(3), golden(4))).isEqualTo(0.9600000066757196);
		assertThat(similarities.between(golden(3), golden(5))).isEqualTo(0.9899494953470402);
		assertThat(similarities.between(golden(4), golden(5))).isEqualTo(0.9899494953470402);
		assertThat(similarities.between(golden(6), golden(8))).isEqualTo(0.990009404957529);
		assertThat(groups).containsExactly(
				new FormedGroup(golden(1), List.of(golden(1), golden(2)), "golden-embed", "1"),
				new FormedGroup(golden(5), List.of(golden(3), golden(4), golden(5)), "golden-embed", "1"),
				new FormedGroup(golden(6), List.of(golden(6), golden(8)), "golden-embed", "1"),
				new FormedGroup(golden(7), List.of(golden(7)), null, null),
				new FormedGroup(golden(9), List.of(golden(9)), null, null),
				new FormedGroup(golden(10), List.of(golden(10)), null, null));
	}

	/**
	 * g9 has text and g10 no {@code takenAt}; the others are photos without text taken within the first 220 s.
	 */
	static List<GroupingCandidate> golden() {
		return List.of(photo(golden(1), 0, valid(GOLDEN, 1, 0, 0, 0)),
				photo(golden(2), 10, valid(GOLDEN, 0.96f, 0.28f, 0, 0)),
				photo(golden(3), 20, valid(GOLDEN, 0.6f, 0.8f, 0, 0)),
				photo(golden(4), 30, valid(GOLDEN, 0.8f, 0.6f, 0, 0)),
				photo(golden(5), 40, valid(GOLDEN, 0.7071f, 0.7071f, 0, 0)),
				photo(golden(6), 200, valid(GOLDEN, 1, 0, 0, 0)),
				photo(golden(7), 210, valid(GOLDEN, 0, 0, 1, 0)),
				photo(golden(8), 215, valid(GOLDEN, 0.99f, 0.141f, 0, 0)),
				new GroupingCandidate(golden(9), T0.plusSeconds(220), TextStatus.TEXT, valid(GOLDEN, 1, 0, 0, 0)),
				new GroupingCandidate(golden(10), null, TextStatus.NO_TEXT, valid(GOLDEN, 1, 0, 0, 0)));
	}

	static UUID golden(int n) {
		return UUID.fromString("0190f1a2-0000-7000-8000-%012d".formatted(n));
	}

	static UUID id(int n) {
		return new UUID(0, n);
	}

	static GroupingCandidate photo(UUID photoId, long seconds, VectorValidator.Valid vector) {
		return candidate(photoId, T0.plusSeconds(seconds), vector);
	}

	static GroupingCandidate candidate(UUID photoId, @Nullable Instant takenAt, VectorValidator.Valid vector) {
		return new GroupingCandidate(photoId, takenAt, TextStatus.NO_TEXT, vector);
	}

	/**
	 * A vector of the plane at the given angle, as float32 values.
	 */
	static VectorValidator.Valid angle(AnalysisProperties.Model model, double degrees) {
		double radians = Math.toRadians(degrees);
		return valid(model, (float) Math.cos(radians), (float) Math.sin(radians));
	}

	static VectorValidator.Valid valid(AnalysisProperties.Model model, float... values) {
		return (VectorValidator.Valid) VALIDATOR.check(model.model(), model.modelVersion(),
				VectorValidatorTests.base64(values));
	}

	/**
	 * A vector checked with grouping off, so neither its model nor its dims are checked against the registered models.
	 */
	static VectorValidator.Valid unchecked(String model, float... values) {
		return (VectorValidator.Valid) UNREGISTERED.check(model, "1", VectorValidatorTests.base64(values));
	}

	static List<FormedGroup> group(GroupingRuleV1 rule, List<GroupingCandidate> photos) {
		return rule.group(photos, SimilarityMatrix.of(photos));
	}

	static List<List<UUID>> memberLists(GroupingRuleV1 rule, GroupingCandidate... photos) {
		return group(rule, List.of(photos)).stream().map(FormedGroup::photoIds).toList();
	}

	static GroupingRuleV1 rule(AnalysisProperties.Grouping settings) {
		return new GroupingRuleV1(settings);
	}

	static GroupingRuleV1 rule(AnalysisProperties.Model model, double threshold) {
		return rule(settings(true, 20, new AnalysisProperties.Model(model.model(), model.modelVersion(), model.dims(),
				threshold)));
	}

	static AnalysisProperties.Grouping settings(boolean enabled, int maxGroupSize, AnalysisProperties.Model... models) {
		return new AnalysisProperties.Grouping(enabled, Duration.ofSeconds(120), maxGroupSize, 1e-6, List.of(models));
	}

}
