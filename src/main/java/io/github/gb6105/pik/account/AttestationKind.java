package io.github.gb6105.pik.account;

/**
 * Device attestation service, fixed by the platform (ADR-0027).
 */
enum AttestationKind {

	APP_ATTEST, PLAY_INTEGRITY;

	static AttestationKind of(Platform platform) {
		return switch (platform) {
			case IOS -> APP_ATTEST;
			case ANDROID -> PLAY_INTEGRITY;
		};
	}

}
