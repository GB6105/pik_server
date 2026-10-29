package io.github.gb6105.pik.consent;

/**
 * The latest decision as reported to clients, {@link #NONE} before the first one.
 */
enum ConsentStatus {

	NONE, GRANTED, DENIED, WITHDRAWN

}
