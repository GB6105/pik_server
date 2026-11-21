package io.github.gb6105.pik.analysis;

import io.github.gb6105.pik.S3MockConfiguration;
import io.github.gb6105.pik.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * The context of the run tests, shared by every class that declares the same spies: PostgreSQL, S3Mock, intake and
 * grouping on (one model of 2 dimensions, θ = 0.9), the worker enabled with the scripted model of
 * {@link RunTestConfiguration}, no wait for the send rate, and a transient circuit that opens after 2 failures.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({ TestcontainersConfiguration.class, S3MockConfiguration.class, RunTestConfiguration.class })
@SpringBootTest(properties = { "pik.analysis.intake-enabled=true", "pik.usage.trial.limit=50",
		"pik.analysis.grouping.enabled=true", "pik.analysis.grouping.models[0].model=pik-embed",
		"pik.analysis.grouping.models[0].model-version=1", "pik.analysis.grouping.models[0].dims=2",
		"pik.analysis.grouping.models[0].threshold=0.9", "pik.analysis.worker.enabled=true",
		"pik.analysis.worker.global-slots=8", "pik.analysis.worker.principal-slots=2",
		"pik.analysis.claude.api-key=test-key", "pik.analysis.send-rate.global-per-second=1000",
		"pik.analysis.circuit.transient-threshold=2" })
@AutoConfigureMockMvc
@interface AnalysisRunTest {

}
