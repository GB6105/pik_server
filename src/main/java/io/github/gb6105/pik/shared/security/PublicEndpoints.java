package io.github.gb6105.pik.shared.security;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Endpoints a module serves without an access token; every other path requires one (ADR-0027). Implemented by
 * modules so that {@code shared} does not depend on them (ADR-0025).
 */
public interface PublicEndpoints {

	List<Endpoint> publicEndpoints();

	record Endpoint(HttpMethod method, String path) {
	}

}
