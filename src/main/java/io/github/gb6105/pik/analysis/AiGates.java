package io.github.gb6105.pik.analysis;

import static io.github.gb6105.pik.analysis.JobRecords.timestamp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * The gates in front of every Claude call, one {@code ai_gate} row per {@link GateCause} (design 2.5, 2.7, plan 5.10).
 * Every state transition compares the generation it observed, raises it by 1 and clears the probe; naming a probe
 * does not raise it, so a probe that still matches a row was named in the generation the row has. Probe decisions
 * are taken only for the job and run token named on the row, and every path that discards a run token clears its
 * probe in the same transaction: a stale response or observation can neither close nor open a newer generation.
 * Responses of calls permitted before the row last closed are no evidence for the closed row either. Rows are
 * locked in cause order, after every other lock of the transaction but the generated rows and the change counter.
 * Claims, permits and responses drive the transitions here; the monitor batch drives its own through
 * {@link #transition}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AiGates {

	private static final String GATE = """
			SELECT cause, state, generation, blocked_until, probe_job_id, probe_run_token, config_version, reset_at,
			       failure_streak, opened_at
			FROM ai_gate
			""";

	private static final List<GateCause> CIRCUITS = List.of(GateCause.CONFIG_CIRCUIT, GateCause.TRANSIENT_CIRCUIT);

	// 2^20 times the base backoff is beyond any maximum the settings allow.
	private static final int MAX_DOUBLINGS = 20;

	private final JdbcClient jdbc;

	private final CallManifests manifests;

	private final AnalysisProperties properties;

	/**
	 * The rows as last committed, without locks, in cause order.
	 */
	Map<GateCause, Gate> read() {
		Map<GateCause, Gate> gates = new EnumMap<>(GateCause.class);
		jdbc.sql(GATE + "ORDER BY cause").query(AiGates::gate).list().forEach((gate) -> gates.put(gate.cause(), gate));
		return gates;
	}

	/**
	 * Locks the rows of the causes in cause order until the transaction ends.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Map<GateCause, Gate> lock(Collection<GateCause> causes) {
		Map<GateCause, Gate> gates = new EnumMap<>(GateCause.class);
		if (causes.isEmpty()) {
			return gates;
		}
		jdbc.sql(GATE + "WHERE cause IN (:causes) ORDER BY cause FOR UPDATE")
			.param("causes", causes.stream().map(GateCause::name).toList())
			.query(AiGates::gate)
			.list()
			.forEach((gate) -> gates.put(gate.cause(), gate));
		return gates;
	}

	/**
	 * The claim's check before its lock (plan 5.2 step 1), read without locks: no claim while any cause is open and not
	 * due to half-open, or while a probe is out; a probe claim while a circuit is half-open or due to half-open; a
	 * normal claim otherwise. A budget whose deadline passed does not hold claims back: the permit closes it after it
	 * checked the cost again.
	 */
	Admission admission(Instant now) {
		List<Gate> probe = new ArrayList<>();
		for (Gate gate : read().values()) {
			switch (gate.state()) {
				case CLOSED -> {
				}
				case OPEN -> {
					// The configuration circuit stays open until the monitor batch sees a new fingerprint.
					if (gate.cause() == GateCause.CONFIG_CIRCUIT || !gate.due(now)) {
						return new Admission.Blocked();
					}
					if (gate.cause() == GateCause.TRANSIENT_CIRCUIT) {
						probe.add(gate);
					}
				}
				case HALF_OPEN -> {
					if (gate.hasProbe()) {
						return new Admission.Blocked();
					}
					probe.add(gate);
				}
			}
		}
		return probe.isEmpty() ? new Admission.Normal() : new Admission.Probe(List.copyOf(probe));
	}

	/**
	 * Names the claimed job and run as the probe of every circuit the admission found half-open or due to half-open,
	 * under the claim lock and the job's lock (plan 5.2 step 3). The rows are locked in cause order and must still have
	 * the generation the admission read and no probe; a transient circuit due to half-open turns {@code HALF_OPEN}
	 * first.
	 * @return {@code false}, with nothing changed, when a row changed since the admission
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean assignProbe(Admission.Probe probe, UUID jobId, UUID runToken, Instant now) {
		Map<GateCause, Gate> locked = lock(probe.gates().stream().map(Gate::cause).toList());
		for (Gate seen : probe.gates()) {
			Gate current = locked.get(seen.cause());
			if (current.generation() != seen.generation() || current.hasProbe()) {
				return false;
			}
		}
		for (Gate seen : probe.gates()) {
			long generation = seen.generation();
			if (seen.state() == GateState.OPEN) {
				Assert.state(transition(seen, GateState.HALF_OPEN, seen.blockedUntil(), null, seen.failureStreak(), now),
						"The gate row is locked");
				generation++;
			}
			int named = jdbc.sql("""
					UPDATE ai_gate SET probe_job_id = :job, probe_run_token = :token, updated_at = :now
					WHERE cause = :cause AND generation = :generation AND state = 'HALF_OPEN' AND probe_job_id IS NULL
					""")
				.param("cause", seen.cause().name())
				.param("generation", generation)
				.param("job", jobId)
				.param("token", runToken)
				.param("now", timestamp(now))
				.update();
			Assert.state(named == 1, "The gate row is locked");
		}
		return true;
	}

	/**
	 * Locks all rows in cause order, for the gate decision of a permit (plan 5.4).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Map<GateCause, Gate> lockAll() {
		return lock(List.of(GateCause.values()));
	}

	/**
	 * The gate decision of a permit on rows locked by {@link #lockAll}, without changes: the budget is closed or its
	 * deadline passed, and each circuit is closed or half-open with this job and run as its probe.
	 */
	static Passage passage(Map<GateCause, Gate> gates, UUID jobId, UUID runToken, Instant now) {
		boolean held = false;
		Instant until = null;
		Gate dueBudget = null;
		for (Gate gate : gates.values()) {
			boolean passes = switch (gate.state()) {
				case CLOSED -> true;
				case OPEN -> gate.cause() == GateCause.BUDGET && gate.due(now);
				case HALF_OPEN -> gate.probeIs(jobId, runToken);
			};
			if (!passes) {
				held = true;
				Instant blockedUntil = gate.blockedUntil();
				if (blockedUntil != null && blockedUntil.isAfter(now) && (until == null || blockedUntil.isAfter(until))) {
					until = blockedUntil;
				}
			}
			else if (gate.state() == GateState.OPEN) {
				dueBudget = gate;
			}
		}
		return held ? new Passage.Held(until) : new Passage.Pass(dueBudget);
	}

	/**
	 * Closes a budget gate whose deadline passed, once a permit found the cost within the limit (plan 5.10).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void closeBudget(Gate budget, Instant now) {
		Assert.state(transition(budget, GateState.CLOSED, null, null, 0, now), "The gate row is locked");
	}

	/**
	 * Opens the budget gate, locked by {@link #lockAll}, until {@code until}, or moves its deadline to {@code until}
	 * when that is later (plan 5.4): a deadline is never brought forward.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void holdBudget(Gate budget, Instant until, Instant now) {
		if (budget.state() == GateState.CLOSED) {
			Assert.state(transition(budget, GateState.OPEN, until, null, 0, now), "The gate row is locked");
			log.warn("Daily AI cost budget is spent; calls are held until {}", until);
			return;
		}
		jdbc.sql("""
				UPDATE ai_gate SET blocked_until = GREATEST(blocked_until, :until), updated_at = :now
				WHERE cause = 'BUDGET' AND generation = :generation
				""")
			.param("until", timestamp(until))
			.param("generation", budget.generation())
			.param("now", timestamp(now))
			.update();
	}

	/**
	 * Whether the job and run are the probe of a circuit, read without locks. Only the claim that made the run token
	 * names it, and it has committed, so a run that finds no row naming it is no probe.
	 */
	boolean isProbe(UUID jobId, UUID runToken) {
		return !probeCauses(jobId, runToken).isEmpty();
	}

	/**
	 * Clears the probe of the job and run wherever it is still named, for a path that discards the run token
	 * (plan 5.10). The circuits keep their state, so the next claim names another probe.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void releaseProbe(UUID jobId, UUID runToken, Instant now) {
		List<GateCause> causes = probeCauses(jobId, runToken);
		if (causes.isEmpty()) {
			return;
		}
		lock(causes);
		clearProbe(causes, jobId, runToken, now);
	}

	/**
	 * Takes a response, or a call that was not sent, as evidence for the circuits (plan 5.10). Responses of runs that
	 * lost their job count as well; probe decisions need the job and run named on the row. Rows that cannot change are
	 * not locked.
	 * @param permittedAt when the call was permitted: evidence from before a circuit last closed does not count for
	 * the closed circuit
	 * @param retryAfter the provider's {@code Retry-After}, which an opening transient circuit waits for at least
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void observe(Signal signal, UUID jobId, UUID runToken, Instant permittedAt, @Nullable Duration retryAfter,
			Instant now) {
		List<GateCause> affected = new ArrayList<>();
		for (Gate gate : read().values()) {
			if (gate.cause().circuit() && affects(gate, signal, jobId, runToken, permittedAt)) {
				affected.add(gate.cause());
			}
		}
		for (Gate gate : lock(affected).values()) {
			boolean probe = gate.state() == GateState.HALF_OPEN && gate.probeIs(jobId, runToken);
			boolean evidence = gate.state() == GateState.CLOSED && !permittedAt.isBefore(gate.resetAt());
			if (gate.cause() == GateCause.CONFIG_CIRCUIT) {
				observeConfig(gate, signal, probe, evidence, now);
			}
			else {
				observeTransient(gate, signal, probe, evidence, retryAfter, now);
			}
		}
	}

	/**
	 * The one state transition of a row: under its lock, only from the generation observed, which it raises by 1, and
	 * with its probe cleared. {@code reset_at} moves on every transition to {@code CLOSED}, {@code opened_at} on every
	 * transition to {@code OPEN}.
	 * @param blockedUntil the deadline of the new state, {@code null} for none
	 * @param configVersion the fingerprint to record, {@code null} to keep the observed one
	 * @return {@code false}, with nothing changed, when the row is no longer in the observed generation
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean transition(Gate observed, GateState to, @Nullable Instant blockedUntil, @Nullable String configVersion,
			int failureStreak, Instant now) {
		Assert.isTrue(observed.cause().circuit() || to != GateState.HALF_OPEN, "The budget gate is CLOSED or OPEN only");
		Instant resetAt = (to == GateState.CLOSED) ? now : observed.resetAt();
		Instant openedAt = (to == GateState.OPEN) ? now : observed.openedAt();
		return jdbc.sql("""
				UPDATE ai_gate SET state = :state, generation = generation + 1, blocked_until = :blockedUntil,
				                   probe_job_id = NULL, probe_run_token = NULL, config_version = :configVersion,
				                   reset_at = :resetAt, failure_streak = :streak, opened_at = :openedAt,
				                   updated_at = :now
				WHERE cause = :cause AND generation = :generation
				""")
			.param("cause", observed.cause().name())
			.param("generation", observed.generation())
			.param("state", to.name())
			.param("blockedUntil", (blockedUntil != null) ? timestamp(blockedUntil) : null,
					Types.TIMESTAMP_WITH_TIMEZONE)
			.param("configVersion", (configVersion != null) ? configVersion : observed.configVersion(), Types.VARCHAR)
			.param("resetAt", timestamp(resetAt))
			.param("streak", failureStreak)
			.param("openedAt", (openedAt != null) ? timestamp(openedAt) : null, Types.TIMESTAMP_WITH_TIMEZONE)
			.param("now", timestamp(now))
			.update() == 1;
	}

	// Plan 5.10, configuration circuit. A 400 in CLOSED is left to the share of the monitor batch (K-11).
	private void observeConfig(Gate gate, Signal signal, boolean probe, boolean evidence, Instant now) {
		if (probe) {
			switch (signal) {
				case ANSWERED -> Assert.state(transition(gate, GateState.CLOSED, null, null, 0, now),
						"The gate row is locked");
				// A probe 400 may be the request defect that is still there (R3-02): no evidence of recovery.
				case REJECTED, CONFIG_ERROR -> openConfig(gate, signal, now);
				case TRANSIENT, NOT_SENT -> clearProbe(List.of(gate.cause()), gate.probeJobId(), gate.probeRunToken(),
						now);
			}
		}
		else if (evidence && signal == Signal.CONFIG_ERROR) {
			openConfig(gate, signal, now);
		}
	}

	private void openConfig(Gate gate, Signal signal, Instant now) {
		Assert.state(transition(gate, GateState.OPEN, null, manifests.configFingerprint(), 0, now),
				"The gate row is locked");
		log.warn("Configuration circuit opened on a {} response; calls wait for a new key version or manifest", signal);
	}

	// Plan 5.10, transient circuit. A single 400 is an answer of a working provider.
	private void observeTransient(Gate gate, Signal signal, boolean probe, boolean evidence,
			@Nullable Duration retryAfter, Instant now) {
		boolean answered = signal == Signal.ANSWERED || signal == Signal.REJECTED;
		if (probe) {
			if (answered) {
				Assert.state(transition(gate, GateState.CLOSED, null, null, 0, now), "The gate row is locked");
			}
			else if (signal == Signal.TRANSIENT) {
				openTransient(gate, gate.failureStreak() + 1, retryAfter, now);
			}
			else {
				clearProbe(List.of(gate.cause()), gate.probeJobId(), gate.probeRunToken(), now);
			}
		}
		else if (evidence && answered && gate.failureStreak() > 0) {
			setStreak(gate, 0, now);
		}
		else if (evidence && signal == Signal.TRANSIENT) {
			int streak = gate.failureStreak() + 1;
			if (streak >= properties.circuit().transientThreshold()) {
				openTransient(gate, streak, retryAfter, now);
			}
			else {
				setStreak(gate, streak, now);
			}
		}
	}

	private void openTransient(Gate gate, int streak, @Nullable Duration retryAfter, Instant now) {
		Instant until = now.plus(backoff(streak));
		if (retryAfter != null && now.plus(retryAfter).isAfter(until)) {
			until = now.plus(retryAfter);
		}
		Instant blockedUntil = gate.blockedUntil();
		if (blockedUntil != null && blockedUntil.isAfter(until)) {
			until = blockedUntil;
		}
		Assert.state(transition(gate, GateState.OPEN, until, null, streak, now), "The gate row is locked");
		log.warn("Transient circuit opened after {} failures in a row; calls are held until {}", streak, until);
	}

	// Doubles from the base for each failure past the threshold up to the maximum, then grows by the jitter share.
	private Duration backoff(int streak) {
		AnalysisProperties.Circuit circuit = properties.circuit();
		int doublings = Math.min(MAX_DOUBLINGS, Math.max(0, streak - circuit.transientThreshold()));
		Duration delay = circuit.transientBaseBackoff().multipliedBy(1L << doublings);
		if (delay.compareTo(circuit.transientMaxBackoff()) > 0) {
			delay = circuit.transientMaxBackoff();
		}
		double jitter = properties.worker().retryJitter() * ThreadLocalRandom.current().nextDouble();
		return delay.plusMillis((long) (delay.toMillis() * jitter));
	}

	private void setStreak(Gate gate, int streak, Instant now) {
		jdbc.sql("""
				UPDATE ai_gate SET failure_streak = :streak, updated_at = :now
				WHERE cause = :cause AND generation = :generation
				""")
			.param("cause", gate.cause().name())
			.param("generation", gate.generation())
			.param("streak", streak)
			.param("now", timestamp(now))
			.update();
	}

	private void clearProbe(List<GateCause> causes, @Nullable UUID jobId, @Nullable UUID runToken, Instant now) {
		jdbc.sql("""
				UPDATE ai_gate SET probe_job_id = NULL, probe_run_token = NULL, updated_at = :now
				WHERE cause IN (:causes) AND probe_job_id = :job AND probe_run_token = :token
				""")
			.param("causes", causes.stream().map(GateCause::name).toList())
			.param("job", jobId)
			.param("token", runToken)
			.param("now", timestamp(now))
			.update();
	}

	private List<GateCause> probeCauses(UUID jobId, UUID runToken) {
		return jdbc.sql("""
				SELECT cause FROM ai_gate WHERE probe_job_id = :job AND probe_run_token = :token ORDER BY cause
				""")
			.param("job", jobId)
			.param("token", runToken)
			.query((rs, rowNum) -> GateCause.valueOf(rs.getString("cause")))
			.list();
	}

	// Whether the evidence may change the row as read without its lock; the lock decides again.
	private static boolean affects(Gate gate, Signal signal, UUID jobId, UUID runToken, Instant permittedAt) {
		if (gate.state() == GateState.HALF_OPEN) {
			return gate.probeIs(jobId, runToken);
		}
		if (gate.state() == GateState.OPEN || permittedAt.isBefore(gate.resetAt())) {
			return false;
		}
		return (gate.cause() == GateCause.CONFIG_CIRCUIT) ? signal == Signal.CONFIG_ERROR
				: signal == Signal.TRANSIENT || ((signal == Signal.ANSWERED || signal == Signal.REJECTED)
						&& gate.failureStreak() > 0);
	}

	private static Gate gate(ResultSet rs, int rowNum) throws SQLException {
		return new Gate(GateCause.valueOf(rs.getString("cause")), GateState.valueOf(rs.getString("state")),
				rs.getLong("generation"), instant(rs.getObject("blocked_until", OffsetDateTime.class)),
				rs.getObject("probe_job_id", UUID.class), rs.getObject("probe_run_token", UUID.class),
				rs.getString("config_version"), rs.getObject("reset_at", OffsetDateTime.class).toInstant(),
				rs.getInt("failure_streak"), instant(rs.getObject("opened_at", OffsetDateTime.class)));
	}

	private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
		return (value != null) ? value.toInstant() : null;
	}

	/**
	 * An {@code ai_gate} row.
	 *
	 * @param blockedUntil when an open budget or transient circuit may pass or half-open again; on a half-open
	 * circuit, when it became due to half-open, from which the half-open alarm counts
	 * @param probeJobId the job of the probe, {@code null} without one
	 * @param probeRunToken the run of the probe, {@code null} without one
	 * @param configVersion the setting fingerprint the configuration circuit opened under
	 * @param resetAt the last transition to {@code CLOSED}
	 * @param failureStreak transient failures in a row
	 */
	record Gate(GateCause cause, GateState state, long generation, @Nullable Instant blockedUntil,
			@Nullable UUID probeJobId, @Nullable UUID probeRunToken, @Nullable String configVersion, Instant resetAt,
			int failureStreak, @Nullable Instant openedAt) {

		boolean hasProbe() {
			return probeJobId != null;
		}

		boolean probeIs(UUID jobId, UUID runToken) {
			return jobId.equals(probeJobId) && runToken.equals(probeRunToken);
		}

		/**
		 * Whether an open row's deadline passed.
		 */
		boolean due(Instant now) {
			return blockedUntil != null && !blockedUntil.isAfter(now);
		}

	}

	/**
	 * What a response, or a call that was not sent, tells the circuits (plan 5.10).
	 */
	enum Signal {

		/** A 2xx, whatever the stop reason. */
		ANSWERED,

		/** A 400, 413, 422 or another 4xx the settlement table settles as 400: one request was refused. */
		REJECTED,

		/** 401, 403, 404 or 402: our key, model or account. */
		CONFIG_ERROR,

		/** 429, 529, 5xx, 408, 409 or no answer. */
		TRANSIENT,

		/** The call was not sent. */
		NOT_SENT

	}

	/**
	 * How a claim may go (plan 5.2 step 1).
	 */
	sealed interface Admission {

		/** No claim now. */
		record Blocked() implements Admission {
		}

		/** Claim as many jobs as there are slots. */
		record Normal() implements Admission {
		}

		/**
		 * Claim one job as the probe of these rows, as read.
		 */
		record Probe(List<Gate> gates) implements Admission {
		}

	}

	/**
	 * The gate decision of a permit.
	 */
	sealed interface Passage {

		/**
		 * @param dueBudget the budget row to close, open with its deadline passed; {@code null} when closed
		 */
		record Pass(@Nullable Gate dueBudget) implements Passage {
		}

		/**
		 * @param until the latest deadline of the rows that hold the call, {@code null} when none has one
		 */
		record Held(@Nullable Instant until) implements Passage {
		}

	}

}
