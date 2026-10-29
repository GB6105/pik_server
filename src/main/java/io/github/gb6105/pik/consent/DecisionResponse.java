package io.github.gb6105.pik.consent;

import io.github.gb6105.pik.shared.error.CommandResult;
import java.util.UUID;

record DecisionResponse(UUID jobId, CommandResult result, boolean retryable, ConsentView consent) {

}
