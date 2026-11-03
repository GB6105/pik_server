package io.github.gb6105.pik;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts table rows given as column maps, for tests of schema constraints written in plain SQL.
 */
public final class SchemaRows {

	private final JdbcClient jdbc;

	public SchemaRows(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(String table, Map<String, Object> row) {
		String columns = String.join(", ", row.keySet());
		String values = row.keySet().stream().map((column) -> ":" + column).collect(Collectors.joining(", "));
		jdbc.sql("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")").params(row).update();
	}

	/**
	 * Copies the row with the given column and value pairs replaced; a value may be {@code null}.
	 */
	public static Map<String, Object> with(Map<String, Object> row, Object... changes) {
		Map<String, Object> changed = new LinkedHashMap<>(row);
		for (int i = 0; i < changes.length; i += 2) {
			changed.put((String) changes[i], changes[i + 1]);
		}
		return changed;
	}

	/**
	 * Asserts that the statement fails on the named constraint. The PostgreSQL message names the constraint even with
	 * {@code logServerErrorDetail=false}.
	 */
	public static void assertRejectedBy(String constraint, Runnable statement) {
		assertThatThrownBy(statement::run).isInstanceOfSatisfying(DataIntegrityViolationException.class,
				(ex) -> assertThat(ex.getMostSpecificCause()).hasMessageContaining("\"" + constraint + "\""));
	}

}
