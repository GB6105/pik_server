package io.github.gb6105.pik.shared.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as a change request: {@code Idempotency-Key} is required and error responses carry
 * {@code jobId} and {@code result} (ADR-0023 #12, ADR-0026). The handler receives the key as an
 * {@link IdempotencyKey} parameter and passes it to {@link IdempotentExecutor}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentCommand {

}
