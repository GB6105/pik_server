package io.github.gb6105.pik.account;

import io.github.gb6105.pik.account.AnonymousAuthService.IssuedToken;
import io.github.gb6105.pik.account.AnonymousAuthService.RegisteredInstall;
import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.logging.PrincipalLogging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints ({@link AccountPublicEndpoints}); neither is a change request with {@code Idempotency-Key}
 * (ADR-0027).
 */
@RestController
@RequiredArgsConstructor
class AnonymousAuthController {

	static final String INSTALLS = "/installs";

	static final String TOKEN = "/auth/token";

	private final AnonymousAuthService service;

	@PostMapping(INSTALLS)
	@ResponseStatus(HttpStatus.CREATED)
	InstallResponse install(@Valid @RequestBody InstallRequest body, HttpServletRequest request) {
		RegisteredInstall install = service.register(body);
		PrincipalLogging.bind(request, install.principalId());
		return new InstallResponse(install.installId(), install.installToken());
	}

	@PostMapping(TOKEN)
	TokenResponse token(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
		if (!TokenRequest.INSTALL_TOKEN.equals(body.grantType())) {
			throw new PikException(ErrorCode.INVALID_INPUT, "UNSUPPORTED_GRANT_TYPE", false);
		}
		if (!StringUtils.hasText(body.installToken())) {
			throw new PikException(ErrorCode.INVALID_INPUT, "VALIDATION_FAILED", false);
		}
		IssuedToken issued = service.exchange(body.installToken());
		PrincipalLogging.bind(request, issued.principalId());
		return new TokenResponse(issued.accessToken().value(), "Bearer",
				issued.accessToken().expiresIn().toSeconds());
	}

}
