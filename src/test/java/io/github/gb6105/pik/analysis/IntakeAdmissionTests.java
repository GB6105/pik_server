package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.gb6105.pik.analysis.IntakeTransaction.Admission;
import io.github.gb6105.pik.analysis.IntakeTransaction.AdmittedGroup;
import io.github.gb6105.pik.analysis.IntakeTransaction.StartRequiredSignal;
import io.github.gb6105.pik.photo.ObservedPhoto;
import io.github.gb6105.pik.usage.TrialUsage;
import io.github.gb6105.pik.usage.TrialUsage.Reservation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * O-1 and A-1: the reservation order of groups and the regrouping after {@code AlreadyReserved} (plan 5d), with a
 * stand-in {@link TrialUsage}.
 */
class IntakeAdmissionTests {

	static final AnalysisProperties.Grouping GROUPING = new AnalysisProperties.Grouping(true, Duration.ofSeconds(120),
			20, 1e-6, List.of(new AnalysisProperties.Model("pik-embed", "1", 2, 0.9)));

	static final GroupingRuleV1 RULE = new GroupingRuleV1(GROUPING);

	static final VectorValidator VALIDATOR = new VectorValidator(GROUPING);

	static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

	static final UUID OWNER = UUID.randomUUID();

	static final UUID MARK = UUID.randomUUID();

	final TrialUsage trialUsage = mock(TrialUsage.class);

	// Only the reservation step runs; the other collaborators belong to the transaction around it.
	final IntakeTransaction transaction = new IntakeTransaction(null, null, null, null, trialUsage, null, null, null,
			null);

	final Map<UUID, GroupingCandidate> candidates = new LinkedHashMap<>();

	final Map<UUID, ObservedPhoto> observed = new HashMap<>();

	@Test
	void groupsAreReservedLatestTakenAtFirstThenByUnsignedRepresentativeId() {
		// Same latest takenAt: the representative with the high bit set comes after, although it is negative signed.
		UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
		UUID high = UUID.fromString("80000000-0000-7000-8000-000000000001");
		UUID highMember = UUID.fromString("00000000-0000-7000-8000-000000000009");
		UUID earlier = UUID.fromString("40000000-0000-7000-8000-000000000001");
		UUID untimedHigh = UUID.fromString("f0000000-0000-7000-8000-000000000001");
		UUID untimedLow = UUID.fromString("10000000-0000-7000-8000-000000000001");
		FormedGroup lowGroup = single(low, T0.plusSeconds(60));
		FormedGroup highGroup = group(high, List.of(highMember, high), T0, T0.plusSeconds(60));
		FormedGroup earlierGroup = single(earlier, T0.plusSeconds(10));
		FormedGroup untimedHighGroup = single(untimedHigh, null);
		FormedGroup untimedLowGroup = single(untimedLow, null);
		when(trialUsage.reserve(eq(OWNER), eq(MARK), any(), anyList()))
			.thenAnswer((invocation) -> new Reservation.Reserved(invocation.<List<?>>getArgument(3).size()));

		Admission admission = admit(List.of(untimedHighGroup, earlierGroup, highGroup, untimedLowGroup, lowGroup));

		assertThat(reservedClientRefs(5)).containsExactly(List.of(low), List.of(highMember, high), List.of(earlier),
				List.of(untimedLow), List.of(untimedHigh));
		assertThat(admission.groups()).extracting(AdmittedGroup::group)
			.containsExactly(lowGroup, highGroup, earlierGroup, untimedLowGroup, untimedHighGroup);
		assertThat(admission.groups()).allSatisfy((admitted) -> {
			assertThat(admitted.jobId()).isNotNull();
			assertThat(admitted.reason()).isNull();
		});
		assertThat(admission.inProgress()).isEmpty();
	}

	@Test
	void notCoveredGroupIsRefusedAndTheNextOneIsStillReserved() {
		UUID big = UUID.randomUUID();
		UUID bigMember = UUID.randomUUID();
		UUID small = UUID.randomUUID();
		FormedGroup bigGroup = group(big, List.of(big, bigMember), T0.plusSeconds(100), T0.plusSeconds(110));
		FormedGroup smallGroup = single(small, T0);
		when(trialUsage.reserve(eq(OWNER), eq(MARK), any(), anyList())).thenReturn(new Reservation.NotCovered(true))
			.thenReturn(new Reservation.Reserved(1));

		Admission admission = admit(List.of(smallGroup, bigGroup));

		assertThat(admission.groups()).containsExactly(new AdmittedGroup(bigGroup, null, "SUBSCRIPTION_REQUIRED", true),
				admission.groups().get(1));
		assertThat(admission.groups().get(1).group()).isEqualTo(smallGroup);
		assertThat(admission.groups().get(1).jobId()).isNotNull();
	}

	@Test
	void alreadyReservedPhotosAreInProgressAndTheRestIsGroupedAgainInOrder() {
		// Newest single first, then the group of three, whose rest (a, c) still forms a group and must come before
		// the single taken at +80 s.
		UUID newest = UUID.randomUUID();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		UUID older = UUID.randomUUID();
		FormedGroup newestGroup = single(newest, T0.plusSeconds(120));
		groupable(a, T0, 1, 0);
		groupable(b, T0.plusSeconds(50), 1, 0.01f);
		groupable(c, T0.plusSeconds(100), 1, 0.02f);
		FormedGroup abc = new FormedGroup(b, List.of(a, b, c), "pik-embed", "1");
		FormedGroup olderGroup = single(older, T0.plusSeconds(80));
		UUID holder = UUID.randomUUID();
		when(trialUsage.reserve(eq(OWNER), eq(MARK), any(), anyList())).thenAnswer((invocation) -> {
			List<TrialUsage.Photo> photos = invocation.getArgument(3);
			if (photos.size() == 3) {
				return new Reservation.AlreadyReserved(Map.of(observed.get(b).photoId(), holder));
			}
			return new Reservation.Reserved(photos.size());
		});

		Admission admission = admit(List.of(olderGroup, abc, newestGroup));

		assertThat(reservedClientRefs(4)).containsExactly(List.of(newest), List.of(a, b, c), List.of(a, c),
				List.of(older));
		assertThat(admission.inProgress()).containsExactly(Map.entry(b, holder));
		assertThat(admission.groups()).extracting(AdmittedGroup::group)
			.containsExactly(newestGroup, new FormedGroup(a, List.of(a, c), "pik-embed", "1"), olderGroup);
		assertThat(admission.groups()).allSatisfy((admitted) -> assertThat(admitted.jobId()).isNotNull());
	}

	@Test
	void restThatNoLongerGroupsBecomesSingles() {
		// Without b in the middle, a and c are 130 s apart, beyond the window.
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		groupable(a, T0, 1, 0);
		groupable(b, T0.plusSeconds(65), 1, 0.01f);
		groupable(c, T0.plusSeconds(130), 1, 0.02f);
		UUID holder = UUID.randomUUID();
		when(trialUsage.reserve(eq(OWNER), eq(MARK), any(), anyList())).thenAnswer((invocation) -> {
			List<TrialUsage.Photo> photos = invocation.getArgument(3);
			return (photos.size() == 3) ? new Reservation.AlreadyReserved(Map.of(observed.get(b).photoId(), holder))
					: new Reservation.Reserved(photos.size());
		});

		Admission admission = admit(List.of(new FormedGroup(b, List.of(a, b, c), "pik-embed", "1")));

		assertThat(reservedClientRefs(3)).containsExactly(List.of(a, b, c), List.of(c), List.of(a));
		assertThat(admission.groups()).extracting(AdmittedGroup::group)
			.containsExactly(new FormedGroup(c, List.of(c), null, null), new FormedGroup(a, List.of(a), null, null));
	}

	@Test
	void startRequiredStopsWithTheBlockedGroupSize() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		groupable(a, T0, 1, 0);
		groupable(b, T0.plusSeconds(1), 1, 0.01f);
		when(trialUsage.reserve(eq(OWNER), eq(MARK), any(), anyList())).thenReturn(new Reservation.StartRequired());

		assertThatThrownBy(() -> admit(List.of(new FormedGroup(a, List.of(a, b), "pik-embed", "1"))))
			.isInstanceOfSatisfying(StartRequiredSignal.class, (signal) -> assertThat(signal.photos()).isEqualTo(2));
	}

	@Test
	void deviceWithoutTrialMarkReservesNothing() {
		UUID a = UUID.randomUUID();
		FormedGroup group = single(a, T0);

		Admission admission = transaction.admit(OWNER, null, List.of(group), candidates, observed, RULE,
				SimilarityMatrix.of(List.copyOf(candidates.values())));

		assertThat(admission.groups()).containsExactly(new AdmittedGroup(group, null, "TRIAL_UNAVAILABLE", false));
		verifyNoInteractions(trialUsage);
	}

	private Admission admit(List<FormedGroup> groups) {
		return transaction.admit(OWNER, MARK, groups, candidates, observed, RULE,
				SimilarityMatrix.of(List.copyOf(candidates.values())));
	}

	// The client photo IDs of each reserve call, in call order.
	private List<List<UUID>> reservedClientRefs(int calls) {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<TrialUsage.Photo>> photos = ArgumentCaptor.forClass(List.class);
		verify(trialUsage, times(calls)).reserve(eq(OWNER), eq(MARK), any(), photos.capture());
		Map<UUID, UUID> clientRefs = new HashMap<>();
		observed.values().forEach((photo) -> clientRefs.put(photo.photoId(), photo.clientRef()));
		List<List<UUID>> calledWith = new ArrayList<>();
		for (List<TrialUsage.Photo> call : photos.getAllValues()) {
			calledWith.add(call.stream().map((photo) -> clientRefs.get(photo.id())).toList());
		}
		return calledWith;
	}

	private FormedGroup single(UUID clientRef, @Nullable Instant takenAt) {
		add(new GroupingCandidate(clientRef, takenAt, TextStatus.UNKNOWN, null));
		return new FormedGroup(clientRef, List.of(clientRef), null, null);
	}

	private FormedGroup group(UUID representative, List<UUID> clientRefs, Instant first, Instant last) {
		add(new GroupingCandidate(clientRefs.get(0), first, TextStatus.NO_TEXT, null));
		add(new GroupingCandidate(clientRefs.get(1), last, TextStatus.NO_TEXT, null));
		return new FormedGroup(representative, clientRefs, "pik-embed", "1");
	}

	private void groupable(UUID clientRef, Instant takenAt, float x, float y) {
		VectorValidator.Valid vector = (VectorValidator.Valid) VALIDATOR.check("pik-embed", "1", vector(x, y));
		add(new GroupingCandidate(clientRef, takenAt, TextStatus.NO_TEXT, vector));
	}

	private void add(GroupingCandidate candidate) {
		candidates.put(candidate.photoId(), candidate);
		observed.put(candidate.photoId(), new ObservedPhoto(UUID.randomUUID(), candidate.photoId(), "v1", 0));
	}

	private static String vector(float... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return Base64.getEncoder().encodeToString(buffer.array());
	}

}
