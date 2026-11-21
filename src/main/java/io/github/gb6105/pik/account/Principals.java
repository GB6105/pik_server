package io.github.gb6105.pik.account;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status of principals for other modules.
 */
@Service
@RequiredArgsConstructor
public class Principals {

	private final AccountRecords records;

	/**
	 * Whether the principal may still start or finish work, read without a lock. Callers that store results read it
	 * under the consent lock, which a deletion holds while it records a refusal before the principal stops being active
	 * (plan K-8).
	 * @return {@code false} for a principal being deleted or deleted, and for an unknown one
	 */
	@Transactional(readOnly = true)
	public boolean isActive(UUID principalId) {
		return records.findPrincipal(principalId).map(Principal::isActive).orElse(false);
	}

}
