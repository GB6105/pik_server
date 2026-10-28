package io.github.gb6105.pik.account;

/**
 * {@code DELETING} blocks new analysis and sync; {@code DELETED} remains as a minimal row (FR-A-05).
 */
enum PrincipalStatus {

	ACTIVE, DELETING, DELETED

}
