package io.github.gb6105.pik.analysis;

import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The call manifest of the current settings, fixed on new jobs. Its ID is the SHA-256 of the canonical content. In
 * stage 5-1 the content is the model and the upload policy only (decision Q3); stage 5-2 adds prompt, schema and
 * prices, which gives a new ID. The row is inserted at startup when missing, before the web server accepts requests;
 * rows never change.
 */
@Component
class CallManifests implements SmartInitializingSingleton {

	private final JdbcClient jdbc;

	private final AnalysisProperties.Manifest current;

	private final String content;

	private final String currentId;

	CallManifests(AnalysisProperties properties, JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.current = properties.manifest();
		this.content = content(properties.manifest());
		this.currentId = CanonicalJson.sha256Hex(this.content);
	}

	/**
	 * @return the {@code analysis_call_manifest} ID of the current settings
	 */
	String currentId() {
		return currentId;
	}

	@Override
	public void afterSingletonsInstantiated() {
		jdbc.sql("""
				INSERT INTO analysis_call_manifest (id, content, content_type, max_bytes, max_long_edge_px, created_at)
				VALUES (:id, CAST(:content AS jsonb), :contentType, :maxBytes, :maxLongEdgePx, now())
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", currentId)
			.param("content", content)
			.param("contentType", current.contentType())
			.param("maxBytes", current.maxBytes())
			.param("maxLongEdgePx", current.maxLongEdgePx())
			.update();
	}

	static String content(AnalysisProperties.Manifest settings) {
		return CanonicalJson.write(Map.of("model", settings.model(), "uploadPolicy",
				Map.of("contentType", settings.contentType(), "maxBytes", settings.maxBytes(), "maxLongEdgePx",
						settings.maxLongEdgePx())));
	}

}
