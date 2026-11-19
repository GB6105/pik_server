/**
 * Server rows of the photos clients send: the last observed source version, sync change numbers and immutable vector
 * versions (FR-I-01, FR-I-02, ADR-0022 #2, ADR-0031 #4). Photo originals and album contents stay on the device.
 * The records of this module carry photo IDs and {@code contentVersion} values and must not be logged.
 */
@ApplicationModule(allowedDependencies = "shared")
package io.github.gb6105.pik.photo;

import org.springframework.modulith.ApplicationModule;
