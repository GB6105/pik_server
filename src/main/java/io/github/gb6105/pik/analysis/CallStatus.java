package io.github.gb6105.pik.analysis;

/**
 * {@code external_call.status} (design 2.1): a permitted call is {@link #SENDING} until it is settled once.
 */
enum CallStatus {

	SENDING, SUCCEEDED, RESPONDED_FAILURE, UNKNOWN, NOT_SENT

}
