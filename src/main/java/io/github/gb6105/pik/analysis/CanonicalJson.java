package io.github.gb6105.pik.analysis;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON of the rule and call manifests, whose SHA-256 is their ID. The text depends on the values only, not
 * on a library version: no whitespace, object keys sorted by UTF-16 code units, integers in decimal, doubles as the
 * shortest decimal that reads back to the same double in plain notation without trailing zeros ({@code 1E-6} is
 * {@code 0.000001}), and strings escaped only where JSON requires it.
 */
final class CanonicalJson {

	private CanonicalJson() {
	}

	/**
	 * @param object values are strings, booleans, integers, longs, finite doubles, lists and maps with string keys
	 */
	static String write(Map<String, ?> object) {
		StringBuilder json = new StringBuilder();
		writeValue(json, object);
		return json.toString();
	}

	static String sha256Hex(String json) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static void writeValue(StringBuilder json, Object value) {
		switch (value) {
			case String string -> writeString(json, string);
			case Boolean bool -> json.append(bool);
			case Integer number -> json.append(number);
			case Long number -> json.append(number);
			case Double number -> {
				if (!Double.isFinite(number)) {
					throw new IllegalArgumentException("JSON has no NaN or infinite numbers");
				}
				json.append(BigDecimal.valueOf(number).stripTrailingZeros().toPlainString());
			}
			case List<?> list -> {
				json.append('[');
				for (int i = 0; i < list.size(); i++) {
					json.append((i > 0) ? "," : "");
					writeValue(json, list.get(i));
				}
				json.append(']');
			}
			case Map<?, ?> map -> {
				json.append('{');
				boolean first = true;
				for (Map.Entry<?, ?> entry : new TreeMap<>(map).entrySet()) {
					json.append(first ? "" : ",");
					first = false;
					writeString(json, (String) entry.getKey());
					json.append(':');
					writeValue(json, entry.getValue());
				}
				json.append('}');
			}
			default -> throw new IllegalArgumentException("Unsupported JSON value " + value.getClass().getName());
		}
	}

	private static void writeString(StringBuilder json, String string) {
		json.append('"');
		for (int i = 0; i < string.length(); i++) {
			char c = string.charAt(i);
			switch (c) {
				case '"' -> json.append("\\\"");
				case '\\' -> json.append("\\\\");
				case '\b' -> json.append("\\b");
				case '\f' -> json.append("\\f");
				case '\n' -> json.append("\\n");
				case '\r' -> json.append("\\r");
				case '\t' -> json.append("\\t");
				default -> {
					if (c < 0x20) {
						json.append(String.format("\\u%04x", (int) c));
					}
					else {
						json.append(c);
					}
				}
			}
		}
		json.append('"');
	}

}
