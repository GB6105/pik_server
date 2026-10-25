package io.github.gb6105.pik;

import org.springframework.boot.SpringApplication;

public class TestPikServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(PikServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
