package io.github.gb6105.pik.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A call manifest jobs can run with (design 2.5, plan 6.1): its settings and the resources it names, as sent to
 * Claude. {@link CallManifests} builds the current one from the settings and earlier ones from their stored content.
 *
 * @param id the {@code analysis_call_manifest} ID, the SHA-256 of {@code content}
 * @param content the canonical JSON of the manifest ({@link CallManifests#content})
 * @param settings the values of the content; {@code previousIds} is not part of it
 * @param systemPrompt the system prompt sent, with the output locale filled in
 * @param userText the fixed instruction text sent after the image
 * @param schema the output JSON schema, parsed from its resource
 */
record CallManifest(String id, String content, AnalysisProperties.Manifest settings, String systemPrompt,
		String userText, Map<String, Object> schema) {

	// Key order stays as in the resource, so that every instance sends the same request text.
	CallManifest {
		schema = Collections.unmodifiableMap(new LinkedHashMap<>(schema));
	}

	// The prompt and schema are long and not needed to tell manifests apart.
	@Override
	public String toString() {
		return "CallManifest[id=" + id + "]";
	}

}
