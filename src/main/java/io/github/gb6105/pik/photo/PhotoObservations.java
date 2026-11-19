package io.github.gb6105.pik.photo;

import io.github.gb6105.pik.shared.change.ChangeSequence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Server rows of client photos and what the clients observed about them (FR-I-01, FR-I-02). Observations take the
 * {@code photo (id order)} place of the lock order (stage5-analysis-pipeline.md 0.3) and never raise the Pik data
 * version {@code photo.version}. Created photos and raised {@code sourceChangeSeq} values get their sync change
 * number from {@link #issueChangeSeqs} at the end of the business transaction, which takes the counter last.
 */
@Service
@RequiredArgsConstructor
public class PhotoObservations {

	private static final Object SCOPE = new Object();

	private final PhotoRecords records;

	private final ChangeSequence changes;

	private final ApplicationEventPublisher events;

	/**
	 * Creates the owner's missing photo rows, locks all observed rows in {@code id} order until the transaction ends
	 * and records each {@code contentVersion} that differs from the stored one: {@code sourceChangeSeq} grows by one
	 * and {@link PhotoSourceChanged} is published. The change number counter is not touched; the transaction fails to
	 * commit unless {@link #issueChangeSeqs} is called with the returned batch. A transaction observes once, with all
	 * its photos, and not inside a transaction nested in one that observed.
	 * @return the photos in input order and the photos that need a change number
	 * @throws IllegalArgumentException when a client photo ID repeats
	 * @throws IllegalStateException when the transaction or one it is nested in has already observed photos
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public ObservationBatch observe(UUID ownerId, List<Observation> observations) {
		Map<UUID, String> observed = new HashMap<>();
		for (Observation observation : observations) {
			Assert.isTrue(observed.put(observation.clientRef(), observation.contentVersion()) == null,
					"Client photo IDs must not repeat");
		}
		Locked scope = claimScope(ownerId);
		if (observations.isEmpty()) {
			return new ObservationBatch(ownerId, List.of(), List.of());
		}
		Set<UUID> created = new HashSet<>(records.insertMissing(ownerId, observations));
		List<ObservedPhoto> locked = records.lock(ownerId, observed.keySet());
		Assert.state(locked.size() == observed.size(), "Every observed photo must have a row");

		Map<UUID, ObservedPhoto> byClientRef = new HashMap<>();
		List<ObservedPhoto> raised = new ArrayList<>();
		List<UUID> changed = new ArrayList<>();
		for (ObservedPhoto row : locked) {
			ObservedPhoto photo = row;
			String version = observed.get(row.clientRef());
			if (!row.observedVersion().equals(version)) {
				photo = new ObservedPhoto(row.photoId(), row.clientRef(), version, row.sourceChangeSeq() + 1);
				raised.add(photo);
			}
			if (photo != row || created.contains(row.photoId())) {
				changed.add(row.photoId());
			}
			byClientRef.put(row.clientRef(), photo);
		}
		if (!raised.isEmpty()) {
			records.raise(raised);
			raised.forEach((photo) -> events
				.publishEvent(new PhotoSourceChanged(ownerId, photo.photoId(), photo.sourceChangeSeq())));
		}

		for (ObservedPhoto row : locked) {
			scope.photoIds.add(row.photoId());
		}
		scope.unissued.addAll(changed);
		List<ObservedPhoto> photos = observations.stream()
			.map((observation) -> byClientRef.get(observation.clientRef()))
			.toList();
		return new ObservationBatch(ownerId, photos, changed);
	}

	/**
	 * Issues the change numbers of a batch observed in this transaction, with one counter update and in photo
	 * {@code id} order. Call it after every other domain write of the transaction: the counter row is the last lock
	 * of the lock order. An empty change set does not touch the counter.
	 * @throws IllegalStateException when the batch was not observed in this transaction or its numbers were issued
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void issueChangeSeqs(ObservationBatch batch) {
		List<UUID> photoIds = batch.changedPhotoIds();
		if (photoIds.isEmpty()) {
			return;
		}
		Locked scope = currentScope();
		Assert.state(scope != null && photoIds.stream()
			.allMatch((photoId) -> scope.unissued.contains(photoId) && scope.holds(batch.ownerId(), photoId)),
				"Change numbers are issued once, for photos observed in this transaction");
		long first = changes.nextBlock(batch.ownerId(), photoIds.size());
		Assert.state(records.assignChangeSeqs(batch.ownerId(), photoIds, first) == photoIds.size(),
				"Every changed photo must get a change number");
		scope.unissued.removeAll(photoIds);
	}

	/**
	 * Server photo IDs of the owner's photos, without locks, for preparation before the business transaction.
	 * @return client photo ID to server photo ID; photos without a row are missing
	 */
	@Transactional(readOnly = true)
	public Map<UUID, UUID> findIds(UUID ownerId, Collection<UUID> clientRefs) {
		return clientRefs.isEmpty() ? Map.of() : records.findIds(ownerId, clientRefs);
	}

	/**
	 * Keeps each vector as an immutable version of its photo: the same model and bytes reuse the stored version,
	 * other bytes become a new one (SHA-256 of the raw float32 bytes).
	 * @return photo ID to vector version ID
	 * @throws IllegalStateException when a photo was not locked by {@link #observe} in this transaction for the owner
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<UUID, UUID> keepVectors(UUID ownerId, List<VectorToKeep> vectors) {
		if (vectors.isEmpty()) {
			return Map.of();
		}
		Locked scope = currentScope();
		Set<UUID> photoIds = new HashSet<>();
		for (VectorToKeep vector : vectors) {
			Assert.state(scope != null && scope.holds(ownerId, vector.photoId()),
					"Vectors are kept only for photos observed in this transaction");
			Assert.isTrue(photoIds.add(vector.photoId()), "A photo must not have two vectors");
		}
		Map<UUID, UUID> vectorIds = records.keepVectors(ownerId, vectors);
		Assert.state(vectorIds.size() == vectors.size(), "Every kept vector must have a row");
		return vectorIds;
	}

	private static Locked claimScope(UUID ownerId) {
		Assert.state(currentScope() == null, "A transaction observes photos once");
		Locked scope = new Locked(ownerId);
		TransactionSynchronizationManager.registerSynchronization(scope);
		TransactionSynchronizationManager.bindResource(SCOPE, scope);
		return scope;
	}

	// A scope that is bound but not among the current synchronizations belongs to a suspended outer transaction,
	// whose row locks a nested one would wait for on the same thread (as in TrialUsage, ADR-0029 #16).
	private static @Nullable Locked currentScope() {
		Assert.state(TransactionSynchronizationManager.isSynchronizationActive(),
				"Photo observations need transaction synchronization");
		Locked scope = (Locked) TransactionSynchronizationManager.getResource(SCOPE);
		Assert.state(scope == null || TransactionSynchronizationManager.getSynchronizations().contains(scope),
				"Photos must not be observed in a transaction nested in one that observed them");
		return scope;
	}

	/**
	 * The photos a transaction has locked for its owner, and those whose change number is still a placeholder. A
	 * commit with placeholders left fails and rolls back. Released before the transaction completes, so that
	 * after-commit callbacks may observe again in a new transaction.
	 */
	private static final class Locked implements TransactionSynchronization {

		private final UUID ownerId;

		private final Set<UUID> photoIds = new HashSet<>();

		private final Set<UUID> unissued = new HashSet<>();

		private Locked(UUID ownerId) {
			this.ownerId = ownerId;
		}

		private boolean holds(UUID ownerId, UUID photoId) {
			return this.ownerId.equals(ownerId) && photoIds.contains(photoId);
		}

		@Override
		public void beforeCommit(boolean readOnly) {
			Assert.state(unissued.isEmpty(), "Observed photos need change numbers before commit (issueChangeSeqs)");
		}

		@Override
		public void beforeCompletion() {
			TransactionSynchronizationManager.unbindResourceIfPossible(SCOPE);
		}

		@Override
		public void afterCompletion(int status) {
			TransactionSynchronizationManager.unbindResourceIfPossible(SCOPE);
		}

	}

}
