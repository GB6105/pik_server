package io.github.gb6105.pik.usage;

/**
 * What the DeviceCheck bits of an iOS device say. Only the start is recorded: once set, a reinstall gets no trial,
 * whatever was left (ADR-0029 #5).
 */
enum DeviceCheckState {

	/**
	 * The start is about to be written to Apple. If the write cannot be confirmed, a retry writes again without
	 * reading the bits first, which this install may have set itself (ADR-0029 #18).
	 */
	STARTING,

	TRIAL_STARTED

}
