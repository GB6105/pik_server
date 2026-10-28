package io.github.gb6105.pik;

import io.github.gb6105.pik.shared.security.AccessTokens;
import io.github.gb6105.pik.shared.security.PrincipalKind;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Sends MockMvc requests with a fresh access token unless a request sets {@code Authorization} itself, for tests
 * whose subject is not authentication.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMockMvc {

	public static final UUID PRINCIPAL_ID = UUID.fromString("019a2b3c-4d5e-7f60-8a1b-000000000001");

	@Bean
	MockMvcBuilderCustomizer authenticatedRequests(AccessTokens accessTokens) {
		return (builder) -> builder.defaultRequest(MockMvcRequestBuilders.get("/").with((request) -> {
			if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
				request.addHeader(HttpHeaders.AUTHORIZATION, bearer(accessTokens));
			}
			return request;
		}));
	}

	public static String bearer(AccessTokens accessTokens) {
		return "Bearer " + accessTokens.issue(PRINCIPAL_ID, PrincipalKind.ANONYMOUS).value();
	}

}
