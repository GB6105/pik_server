package io.github.gb6105.pik.shared.security;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every endpoint requires an access token except those listed by {@link PublicEndpoints}, error dispatches and the
 * health check (ADR-0027). Access tokens are ES256 JWTs verified against the current and previous keys (ADR-0023 #8).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig implements WebMvcConfigurer {

	@Bean
	JwtKeys jwtKeys(JwtProperties properties, Environment environment) {
		return JwtKeys.load(properties.jwkSet(), DevelopmentProfiles.active(environment));
	}

	@Bean
	JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.verificationKeys()))
			.jwsAlgorithm(SignatureAlgorithm.ES256)
			.jwtProcessorCustomizer(
					(processor) -> processor.setJWSKeySelector(requireKeyId(processor.getJWSKeySelector())))
			.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				new JwtTimestampValidator(properties.clockSkew()), new JwtIssuerValidator(properties.issuer()),
				new JwtClaimValidator<>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtClaimValidator<>(JwtClaimNames.SUB, SecurityConfig::isUuid),
				new JwtClaimValidator<>(AccessTokens.KIND_CLAIM, SecurityConfig::isKind)));
		return decoder;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, List<PublicEndpoints> publicEndpoints,
			SecurityProblemHandler problemHandler) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(problemHandler)
				.accessDeniedHandler(problemHandler))
			.oauth2ResourceServer((server) -> server.authenticationEntryPoint(problemHandler)
				.accessDeniedHandler(problemHandler)
				.jwt((jwt) -> {
				}))
			.addFilterAfter(new PrincipalLoggingFilter(), BearerTokenAuthenticationFilter.class)
			.authorizeHttpRequests((requests) -> {
				requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				requests.requestMatchers(EndpointRequest.to("health")).permitAll();
				publicEndpoints.stream()
					.flatMap((module) -> module.publicEndpoints().stream())
					.forEach((endpoint) -> requests.requestMatchers(endpoint.method(), endpoint.path()).permitAll());
				requests.anyRequest().authenticated();
			})
			.build();
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new CurrentPrincipalArgumentResolver());
	}

	// Without kid, Nimbus would try every key in the set; tokens issued here always name their key.
	private static JWSKeySelector<SecurityContext> requireKeyId(JWSKeySelector<SecurityContext> selector) {
		return (header, context) -> header.getKeyID() == null ? List.of() : selector.selectJWSKeys(header, context);
	}

	private static boolean isUuid(@Nullable Object value) {
		if (!(value instanceof String text)) {
			return false;
		}
		try {
			return UUID.fromString(text).toString().equals(text);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean isKind(@Nullable Object value) {
		return value instanceof String text
				&& Stream.of(PrincipalKind.values()).anyMatch((kind) -> kind.name().equals(text));
	}

}
