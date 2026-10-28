package io.github.gb6105.pik;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void modulesRespectBoundaries() {
		ApplicationModules.of(PikServerApplication.class).verify();
	}

}
