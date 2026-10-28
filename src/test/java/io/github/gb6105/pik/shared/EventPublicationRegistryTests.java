package io.github.gb6105.pik.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gb6105.pik.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that the Flyway DDL matches what Spring Modulith 2.1.2 writes and that completed publications are deleted
 * (ADR-0026).
 */
@Import({ TestcontainersConfiguration.class, EventPublicationRegistryTests.ListenerConfig.class })
@SpringBootTest
class EventPublicationRegistryTests {

	@Autowired
	ApplicationEventPublisher events;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ProbeListener listener;

	@Test
	void publicationIsStoredUntilListenerCompletesThenDeleted() throws Exception {
		UUID id = UUID.randomUUID();

		transactions.executeWithoutResult((status) -> events.publishEvent(new ProbeEvent(id)));

		assertThat(listener.awaitEntered()).isTrue();
		assertThat(publications()).isEqualTo(1L);
		listener.release();
		await().atMost(Duration.ofSeconds(5)).until(() -> publications() == 0L);
	}

	private long publications() {
		return jdbc.sql("SELECT count(*) FROM event_publication").query(Long.class).single();
	}

	record ProbeEvent(UUID id) {
	}

	// Proxied for @Async and @Transactional, so state is reached through methods, not fields.
	static class ProbeListener {

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		@ApplicationModuleListener
		public void on(ProbeEvent event) throws InterruptedException {
			entered.countDown();
			released.await(5, TimeUnit.SECONDS);
		}

		public boolean awaitEntered() throws InterruptedException {
			return entered.await(5, TimeUnit.SECONDS);
		}

		public void release() {
			released.countDown();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ListenerConfig {

		@Bean
		ProbeListener probeListener() {
			return new ProbeListener();
		}

	}

}
