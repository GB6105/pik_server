package io.github.gb6105.pik.shared.security;

import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Profiles in which fakes (device attestation) and ephemeral JWT keys may run. Every other environment fails at
 * startup instead, so a deployment that forgets to set a profile cannot run them (ADR-0027).
 */
public final class DevelopmentProfiles {

	private static final Set<String> PROFILES = Set.of("local", "test");

	private DevelopmentProfiles() {
	}

	// Active profiles only: a default profile (spring.profiles.default) left on a server must not enable them.
	public static boolean active(Environment environment) {
		return Arrays.stream(environment.getActiveProfiles()).anyMatch(PROFILES::contains);
	}

}
