package io.github.gb6105.pik.account;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * IDs are assigned before insert, so new entities are persisted explicitly instead of merged.
 */
@Repository
@RequiredArgsConstructor
class AccountRecords {

	private final EntityManager entityManager;

	void persist(Object entity) {
		entityManager.persist(entity);
	}

	Optional<AnonymousInstall> findInstallByTokenHash(String tokenHash) {
		return Optional.ofNullable(entityManager
			.createQuery("SELECT i FROM AnonymousInstall i WHERE i.tokenHash = :tokenHash", AnonymousInstall.class)
			.setParameter("tokenHash", tokenHash)
			.getSingleResultOrNull());
	}

	Optional<Principal> findPrincipal(UUID id) {
		return Optional.ofNullable(entityManager.find(Principal.class, id));
	}

	/**
	 * Moves {@code last_seen_at} of a principal's install and devices to {@code now} when the install's is older than
	 * {@code staleBefore}. Conditional statements instead of entity changes: concurrent exchanges neither fail on the
	 * device version nor bump it, and only the first of them writes (ADR-0027 #15). Install entities already loaded in
	 * the transaction keep the old value, so the transaction must not change them afterwards: Hibernate would write
	 * the old {@code last_seen_at} back.
	 */
	void touchLastSeen(UUID principalId, Instant now, Instant staleBefore) {
		int installs = entityManager.createQuery("""
				UPDATE AnonymousInstall i SET i.lastSeenAt = :now
				WHERE i.principalId = :principalId AND (i.lastSeenAt IS NULL OR i.lastSeenAt <= :staleBefore)
				""")
			.setParameter("now", now)
			.setParameter("principalId", principalId)
			.setParameter("staleBefore", staleBefore)
			.executeUpdate();
		if (installs == 0) {
			return;
		}
		entityManager.createQuery("UPDATE Device d SET d.lastSeenAt = :now WHERE d.principalId = :principalId")
			.setParameter("now", now)
			.setParameter("principalId", principalId)
			.executeUpdate();
	}

}
