package io.github.gb6105.pik.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * @param grantType {@value #INSTALL_TOKEN}; login adds its own grant types
 * @param installToken required for {@value #INSTALL_TOKEN}
 */
record TokenRequest(@NotBlank String grantType, @Size(max = 64) @Nullable String installToken) {

	static final String INSTALL_TOKEN = "install_token";

}
