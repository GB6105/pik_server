package io.github.gb6105.pik.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.TestcontainersConfiguration;
import io.github.gb6105.pik.shared.id.UuidV7;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PrincipalsTests {

	@Autowired
	Principals principals;

	@Autowired
	JdbcClient jdbc;

	@Test
	void onlyAnActivePrincipalIsActive() {
		assertThat(principals.isActive(principal(PrincipalStatus.ACTIVE))).isTrue();
		assertThat(principals.isActive(principal(PrincipalStatus.DELETING))).isFalse();
		assertThat(principals.isActive(principal(PrincipalStatus.DELETED))).isFalse();
	}

	@Test
	void unknownPrincipalIsNotActive() {
		assertThat(principals.isActive(UuidV7.generate())).isFalse();
	}

	private UUID principal(PrincipalStatus status) {
		UUID id = UuidV7.generate();
		jdbc.sql("""
				INSERT INTO principal (id, kind, status, created_at, updated_at, version)
				VALUES (:id, 'ANONYMOUS', :status, now(), now(), 0)
				""")
			.param("id", id)
			.param("status", status.name())
			.update();
		return id;
	}

}
