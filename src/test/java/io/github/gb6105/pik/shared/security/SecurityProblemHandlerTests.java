package io.github.gb6105.pik.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;

class SecurityProblemHandlerTests {

	@Test
	void unrenderedFailureFallsBackToTheErrorController() throws Exception {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("mapping", (HandlerMapping) (request) -> null);
		beans.addBean("resolver", (HandlerExceptionResolver) (request, response, handler, ex) -> null);
		SecurityProblemHandler problemHandler = new SecurityProblemHandler(
				beans.getBeanProvider(HandlerMapping.class), beans.getBeanProvider(HandlerExceptionResolver.class));
		MockHttpServletResponse response = new MockHttpServletResponse();

		problemHandler.commence(new MockHttpServletRequest("GET", "/x"), response,
				new InsufficientAuthenticationException("no token"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.isCommitted()).isTrue();
		assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
	}

}
