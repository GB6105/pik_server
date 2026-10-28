package io.github.gb6105.pik.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-probe")
class ProbeController {

	@GetMapping("/ok")
	String ok() {
		return "ok";
	}

	@GetMapping("/conflict")
	String conflict() {
		throw new PikException(ErrorCode.CONFLICT, "PROBE_CONFLICT", true);
	}

	@GetMapping("/forbidden")
	String forbidden() {
		throw new PikException(ErrorCode.FORBIDDEN, "PROBE_FORBIDDEN", false);
	}

	@GetMapping("/unexpected")
	String unexpected() {
		throw new IllegalStateException("probe failure");
	}

	@PostMapping("/validated")
	String validated(@Valid @RequestBody ProbeBody body) {
		return body.name();
	}

	record ProbeBody(@NotBlank String name) {
	}

}
