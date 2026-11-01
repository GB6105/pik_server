package io.github.gb6105.pik.account;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param trialMarkId trial mark of the physical device in the usage module (ADR-0029); empty on devices registered
 * without one
 */
public record RequestingDevice(UUID deviceId, UUID installId, Platform platform, @Nullable UUID trialMarkId) {

}
