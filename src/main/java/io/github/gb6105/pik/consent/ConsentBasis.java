package io.github.gb6105.pik.consent;

/**
 * The granted consent a check relied on, recorded with the work it allowed (consistency-rules.md).
 *
 * @param version the consent version of the grant; later checks look for refusals after it
 */
public record ConsentBasis(long version) {

}
