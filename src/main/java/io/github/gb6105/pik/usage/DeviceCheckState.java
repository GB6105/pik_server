package io.github.gb6105.pik.usage;

/**
 * What the DeviceCheck bits of an iOS device say. Only the start is recorded: once set, a reinstall gets no trial,
 * whatever was left (ADR-0029 #5).
 */
enum DeviceCheckState {

	TRIAL_STARTED

}
