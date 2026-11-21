package io.github.gb6105.pik.analysis;

/**
 * {@code ai_gate.state} (plan 5.10).
 */
enum GateState {

	/** Calls pass. */
	CLOSED,

	/** Calls are held back. */
	OPEN,

	/** Only the probe, one job and run token named on the row, may be sent. */
	HALF_OPEN

}
