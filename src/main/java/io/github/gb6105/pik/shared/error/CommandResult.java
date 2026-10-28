package io.github.gb6105.pik.shared.error;

/**
 * {@code result} of a change request response and its {@code items[]} (ADR-0023 #12).
 */
public enum CommandResult {

	SUCCEEDED, FAILED, CANCELLED, NEEDS_CHECK, ACCEPTED

}
