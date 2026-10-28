package io.github.gb6105.pik.shared.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChangeSequenceTests {

	@Autowired
	ChangeSequence changes;

	@Autowired
	TransactionTemplate transactions;

	@Test
	void numbersIncreasePerOwner() {
		UUID owner = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		List<Long> issued = transactions
			.execute((status) -> List.of(changes.next(owner), changes.next(owner), changes.next(other)));
		long afterCommit = transactions.execute((status) -> changes.next(owner));

		assertThat(issued).containsExactly(1L, 2L, 1L);
		assertThat(afterCommit).isEqualTo(3L);
	}

	@Test
	void requiresCallerTransaction() {
		assertThatThrownBy(() -> changes.next(UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
	}

}
