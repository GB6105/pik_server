package io.github.gb6105.pik.usage;

/**
 * State of a per-photo reservation. Only {@link #RESERVED} changes: a result consumes it for good (ADR-0024 #1), a
 * failed, cancelled or ineligible job releases it (ADR-0021 #3).
 */
enum ReservationStatus {

	RESERVED, CONSUMED, RELEASED

}
