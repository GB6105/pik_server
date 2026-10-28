package io.github.gb6105.pik.account;

import jakarta.persistence.EntityManager;
import java.util.List;
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
		return entityManager
			.createQuery("SELECT i FROM AnonymousInstall i WHERE i.tokenHash = :tokenHash", AnonymousInstall.class)
			.setParameter("tokenHash", tokenHash)
			.getResultStream()
			.findFirst();
	}

	Optional<Principal> findPrincipal(UUID id) {
		return Optional.ofNullable(entityManager.find(Principal.class, id));
	}

	List<Device> findDevices(UUID principalId) {
		return entityManager.createQuery("SELECT d FROM Device d WHERE d.principalId = :principalId", Device.class)
			.setParameter("principalId", principalId)
			.getResultList();
	}

}
