package io.github.gb6105.pik.analysis;

/**
 * {@code ai_gate.cause}: why Claude calls may be held back (design 2.5, 2.7, plan 5.10). Declared in lock order, which
 * is also the text order of the key, so {@code ORDER BY cause} locks rows in this order.
 */
enum GateCause {

	/** The global daily cost; {@code CLOSED} or {@code OPEN} only. */
	BUDGET,

	/** Our own configuration: key, model or request (401, 403, 404, 402, or a high share of 400). */
	CONFIG_CIRCUIT,

	/** The provider's own trouble (429, 529, 5xx, unknown outcomes in a row). */
	TRANSIENT_CIRCUIT;

	/**
	 * Whether the cause is a circuit, which a probe closes again.
	 */
	boolean circuit() {
		return this != BUDGET;
	}

}
