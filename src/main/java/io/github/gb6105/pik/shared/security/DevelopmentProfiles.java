package io.github.gb6105.pik.shared.security;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Profiles in which fakes (device attestation) and ephemeral JWT keys may run. Every other environment fails at
 * startup instead, so a deployment that forgets to set a profile cannot run them (ADR-0027).
 */
public final class DevelopmentProfiles {

	private static final Profiles PROFILES = Profiles.of("local", "test");

	private DevelopmentProfiles() {
	}

	public static boolean active(Environment environment) {
		return environment.acceptsProfiles(PROFILES);
	}

}
