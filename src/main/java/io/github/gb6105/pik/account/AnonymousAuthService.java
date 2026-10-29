package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.security.AccessTokens;
import io.github.gb6105.pik.shared.security.AccessTokens.AccessToken;
import io.github.gb6105.pik.shared.security.PrincipalKind;
import io.github.gb6105.pik.usage.TrialDevices;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Anonymous installs: an attested install gets an install token, which is exchanged for access tokens (ADR-0020,
 * ADR-0023, ADR-0027).
 */
@Service
@RequiredArgsConstructor
class AnonymousAuthService {

	private final AttestationVerifier attestationVerifier;

	private final TrialDevices trialDevices;

	private final AccountRecords records;

	private final AccessTokens accessTokens;

	private final AccountProperties properties;

	private final TransactionTemplate transaction;

	/**
	 * Verifies the device and reads its trial evidence first, outside the transaction, then stores the principal,
	 * install, device and the device's trial mark together.
	 */
	RegisteredInstall register(InstallRequest request) {
		TrialDevices.Evidence evidence = request.trialEvidence();
		attestationVerifier.verify(request.platform(), request.attestation());
		TrialDevices.IdentifiedDevice trialDevice = trialDevices.identify(evidence);
		String token = InstallTokens.generate();
		Instant now = Instant.now();
		Principal principal = Principal.anonymous(now);
		AnonymousInstall install = new AnonymousInstall(principal.getId(), InstallTokens.hash(token),
				request.platform(), AttestationKind.of(request.platform()), now);
		Device device = Objects.requireNonNull(transaction.execute((status) -> {
			records.persist(principal);
			records.persist(install);
			Device created = Device.ofNewInstall(principal.getId(), request.platform(), request.appVersion(),
					trialDevices.register(trialDevice), now);
			records.persist(created);
			return created;
		}));
		return new RegisteredInstall(principal.getId(), device.getInstallId(), token);
	}

	/**
	 * A missing, revoked or deleted install gets the same answer, so the response does not tell them apart.
	 */
	@Transactional
	IssuedToken exchange(String installToken) {
		AnonymousInstall install = records.findInstallByTokenHash(InstallTokens.hash(installToken))
			.filter((candidate) -> !candidate.isRevoked())
			.orElseThrow(AnonymousAuthService::invalidInstallToken);
		Principal principal = records.findPrincipal(install.getPrincipalId())
			.filter(Principal::isActive)
			.orElseThrow(AnonymousAuthService::invalidInstallToken);
		Instant now = Instant.now();
		records.touchLastSeen(principal.getId(), now, now.minus(properties.lastSeenInterval()));
		Device device = records.findDeviceOfAnonymous(principal.getId());
		return new IssuedToken(principal.getId(),
				accessTokens.issue(principal.getId(), PrincipalKind.ANONYMOUS, device.getId()));
	}

	private static PikException invalidInstallToken() {
		return new PikException(ErrorCode.UNAUTHENTICATED, "INSTALL_TOKEN_INVALID", false);
	}

	record RegisteredInstall(UUID principalId, UUID installId, String installToken) {
	}

	record IssuedToken(UUID principalId, AccessToken accessToken) {
	}

}
