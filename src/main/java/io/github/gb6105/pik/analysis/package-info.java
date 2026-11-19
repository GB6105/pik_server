/**
 * AI analysis of photos: intake, grouping of similar photos and the immutable rule and call manifests that jobs are
 * fixed to (FR-I-01, FR-I-02, design stage5-analysis-pipeline). Client vectors, photo IDs and {@code contentVersion}
 * values pass through this module and must not be logged.
 */
@ApplicationModule(allowedDependencies = { "shared", "account", "consent", "usage", "photo" })
package io.github.gb6105.pik.analysis;

import org.springframework.modulith.ApplicationModule;
