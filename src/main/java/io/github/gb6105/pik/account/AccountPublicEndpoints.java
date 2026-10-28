package io.github.gb6105.pik.account;

import io.github.gb6105.pik.shared.security.PublicEndpoints;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Obtaining an install token and an access token cannot require an access token.
 */
@Component
class AccountPublicEndpoints implements PublicEndpoints {

	@Override
	public List<Endpoint> publicEndpoints() {
		return List.of(new Endpoint(HttpMethod.POST, AnonymousAuthController.INSTALLS),
				new Endpoint(HttpMethod.POST, AnonymousAuthController.TOKEN));
	}

}
