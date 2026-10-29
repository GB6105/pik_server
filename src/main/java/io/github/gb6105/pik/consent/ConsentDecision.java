package io.github.gb6105.pik.consent;

/**
 * A recorded decision. Clients only grant or refuse; a refusal right after a grant is recorded as {@link #WITHDRAWN}.
 */
enum ConsentDecision {

	GRANTED, DENIED, WITHDRAWN

}
