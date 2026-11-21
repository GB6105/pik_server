package io.github.gb6105.pik.analysis;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The worker's model replaced by scripted replies, so that no test reaches the Anthropic API, and the committed
 * {@link AnalysisCompleted} events collected. Import it with the worker enabled.
 */
@TestConfiguration(proxyBeanMethods = false)
class RunTestConfiguration {

	@Bean
	@Primary
	ScriptedModel scriptedModel() {
		return new ScriptedModel();
	}

	@Bean
	CompletedEvents completedEvents() {
		return new CompletedEvents();
	}

	/**
	 * Answers each call with the next scripted reply; a call without one fails the test.
	 */
	static class ScriptedModel implements AnalysisModel {

		private final ConcurrentLinkedDeque<Function<byte[], ModelReply>> replies = new ConcurrentLinkedDeque<>();

		private final AtomicInteger calls = new AtomicInteger();

		void reply(ModelReply reply) {
			replies.add((jpeg) -> reply);
		}

		/**
		 * A reply made while the call is out, after the side effects of the function.
		 */
		void reply(Function<byte[], ModelReply> reply) {
			replies.add(reply);
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			replies.clear();
			calls.set(0);
		}

		@Override
		public ModelReply call(CallManifest manifest, byte[] jpeg) {
			calls.incrementAndGet();
			Function<byte[], ModelReply> reply = replies.poll();
			if (reply == null) {
				throw new AssertionError("No reply is scripted for this call");
			}
			return reply.apply(jpeg);
		}

	}

	/**
	 * The events of committed transactions.
	 */
	static class CompletedEvents {

		private final List<AnalysisCompleted> events = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		void on(AnalysisCompleted event) {
			events.add(event);
		}

		List<AnalysisCompleted> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}

	}

}
