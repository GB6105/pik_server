package io.github.gb6105.pik.consent;

/**
 * @param notice the current notice, to show before asking for a decision
 */
record ConsentResponse(ConsentView consent, AiNotice notice) {

}
