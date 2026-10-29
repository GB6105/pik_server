package io.github.gb6105.pik.usage;

/**
 * Apple DeviceCheck: two bits per iOS device, kept by Apple across reinstalls. Requests carry a token the app has just
 * generated; tokens are never stored (data-retention.md). Implementations call Apple, so they run outside database
 * transactions.
 */
interface DeviceCheck {

	/**
	 * Whether the device of the token started a trial before.
	 * @throws io.github.gb6105.pik.shared.error.PikException {@code FORBIDDEN} / {@code DEVICE_CHECK_FAILED} for a
	 * token Apple does not accept, {@code TEMPORARY_FAILURE} / {@code DEVICE_CHECK_UNAVAILABLE} when Apple cannot be
	 * reached
	 */
	boolean trialStarted(String deviceToken);

	/**
	 * Records that the device of the token started a trial.
	 * @throws io.github.gb6105.pik.shared.error.PikException as {@link #trialStarted}; {@code FORBIDDEN} only when
	 * nothing was written, so malformed and reused tokens must be refused with it, while any other failure may follow
	 * a write that set the bits (ADR-0029 #18)
	 */
	void recordTrialStarted(String deviceToken);

}
