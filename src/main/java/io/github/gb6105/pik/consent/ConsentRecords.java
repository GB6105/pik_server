package io.github.gb6105.pik.consent;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ConsentRecords {

	private final EntityManager entityManager;

	Optional<AiConsent> findLatest(UUID principalId, ConsentPurpose purpose) {
		return Optional.ofNullable(entityManager.createQuery("""
				SELECT c FROM AiConsent c WHERE c.principalId = :principalId AND c.purpose = :purpose
				ORDER BY c.version DESC
				""", AiConsent.class)
			.setParameter("principalId", principalId)
			.setParameter("purpose", purpose)
			.setMaxResults(1)
			.getSingleResultOrNull());
	}

	/**
	 * Flushes right away so that a decision made concurrently on the same version fails here, as a unique violation
	 * the caller can answer, instead of at commit.
	 */
	void insert(AiConsent consent) {
		entityManager.persist(consent);
		entityManager.flush();
	}

}
