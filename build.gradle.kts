plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.gb6105"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.1.2"
extra["shedlockVersion"] = "7.10.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
	implementation("net.javacrumbs.shedlock:shedlock-spring")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template")
	implementation("org.flywaydb:flyway-database-postgresql")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
		mavenBom("net.javacrumbs.shedlock:shedlock-bom:${property("shedlockVersion")}")
	}
}

// Fakes (device attestation) and ephemeral JWT keys are allowed only in the local and test profiles (ADR-0027).
tasks.withType<Test> {
	useJUnitPlatform()
	systemProperty("spring.profiles.active", "test")
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
	systemProperty("spring.profiles.active", "local")
}

// Lombok 1.18.48 ignores flagUsage for @SuperBuilder, so lombok.config alone cannot block it (ADR-0012).
val forbidLombokSuperBuilder by tasks.registering {
	group = "verification"
	description = "Fails when @SuperBuilder is used."
	val sources = fileTree("src") { include("**/*.java") }
	inputs.files(sources)
	doLast {
		val offenders = sources.filter { it.readText().contains("SuperBuilder") }.files
		if (offenders.isNotEmpty()) {
			throw GradleException("@SuperBuilder is forbidden (ADR-0012): ${offenders.joinToString()}")
		}
	}
}

tasks.named("check") {
	dependsOn(forbidLombokSuperBuilder)
}
