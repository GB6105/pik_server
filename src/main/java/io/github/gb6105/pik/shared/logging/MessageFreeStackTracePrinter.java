package io.github.gb6105.pik.shared.logging;

import java.io.IOException;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.StandardStackTracePrinter;

/**
 * Prints stack traces with exception class names only. Exception messages from libraries (JDBC, Jackson) can carry
 * request data, and {@code stack_trace} is on the log allow list (ADR-0023 #9). Registered through
 * {@code logging.structured.json.stacktrace.printer}.
 */
public class MessageFreeStackTracePrinter implements StackTracePrinter {

	private final StackTracePrinter delegate;

	public MessageFreeStackTracePrinter(StandardStackTracePrinter standard) {
		this.delegate = standard.withFormatter((throwable) -> throwable.getClass().getName());
	}

	@Override
	public void printStackTrace(Throwable throwable, Appendable out) throws IOException {
		delegate.printStackTrace(throwable, out);
	}

}
