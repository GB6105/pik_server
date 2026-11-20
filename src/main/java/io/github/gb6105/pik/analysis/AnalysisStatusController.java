package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.shared.error.ErrorCode;
import io.github.gb6105.pik.shared.error.PikException;
import io.github.gb6105.pik.shared.security.CurrentPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The current state of an intake request, its items, groups and jobs (design 2.3, plan 4.3). The body has no upload
 * URL and is stable while nothing changes, so a matching {@code If-None-Match} answers 304 (Spring compares the
 * {@code ETag} of the response).
 */
@RestController
@RequiredArgsConstructor
class AnalysisStatusController {

	private final AnalysisStatus status;

	@GetMapping(AnalysisIntakeController.PATH + "/{requestId}")
	ResponseEntity<byte[]> status(CurrentPrincipal principal, @PathVariable UUID requestId) {
		AnalysisStatus.Snapshot snapshot = status.find(principal.id(), requestId)
			.orElseThrow(() -> new PikException(ErrorCode.NOT_FOUND, "RESOURCE_NOT_FOUND", false));
		return ResponseEntity.ok().eTag(snapshot.etag()).contentType(MediaType.APPLICATION_JSON).body(snapshot.body());
	}

}
