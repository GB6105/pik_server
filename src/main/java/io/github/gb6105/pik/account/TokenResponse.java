package io.github.gb6105.pik.account;

/**
 * @param expiresIn access token lifetime in seconds
 */
record TokenResponse(String accessToken, String tokenType, long expiresIn) {

}
