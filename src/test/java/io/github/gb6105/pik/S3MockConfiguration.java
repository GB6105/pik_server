package io.github.gb6105.pik;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * S3Mock for the analysis input bucket, with {@link TestcontainersConfiguration}. It creates the bucket when it starts
 * and accepts presigned URLs without checking their signature or expiry, so those are left to the real S3 contract
 * tests (plan 11.4). Import it only where S3 is used: every context that imports it starts its own container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class S3MockConfiguration {

	public static final String BUCKET = "pik-analysis-test";

	private static final int HTTP_PORT = 9090;

	@Bean
	GenericContainer<?> s3MockContainer() {
		return new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.1.0")).withExposedPorts(HTTP_PORT)
			.withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
			.waitingFor(Wait.forHttp("/favicon.ico").forPort(HTTP_PORT));
	}

	@Bean
	DynamicPropertyRegistrar s3MockProperties(GenericContainer<?> s3MockContainer) {
		return (registry) -> {
			registry.add("pik.analysis.upload.endpoint",
					() -> "http://" + s3MockContainer.getHost() + ":" + s3MockContainer.getMappedPort(HTTP_PORT));
			registry.add("pik.analysis.upload.bucket", () -> BUCKET);
		};
	}

}
