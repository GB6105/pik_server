package io.github.gb6105.pik.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The common pure validator of analysis result schema v1 (design 4.1 and 2.5, plan 6.5): the run checks a model
 * output with it before the result is stored, and the generation of stage 5-3 reuses it. The action and event part
 * and the {@code search} block are checked apart:
 * <ul>
 * <li>Action and event part: a missing required key or a wrong type is a schema violation, and so are items that are
 * all {@code INVALID}: the output is {@code INVALID_OUTPUT}. Lists longer than their limit keep their first items and
 * name the list in {@code truncatedFields}; an item with a value outside its enum is dropped. Display texts over their
 * length are cut and named in {@code truncatedFields}; execution values (phone and account numbers, addresses,
 * dates, times, time zones, country codes, relative date texts) over their length or in a wrong format make their
 * item {@code INVALID}. A date outside capture time −1 year to +3 years makes its item {@code NEEDS_CHECK}.</li>
 * <li>{@code search} block: a missing block or a wrong type discards the block; values outside their enum are dropped,
 * texts and lists are cut. Its problems only set {@link SearchState}, never fail the output.</li>
 * </ul>
 * Lengths count Unicode code points. Unknown keys are left out of the result. The limits are those of schema v1;
 * other limits are a new schema version.
 */
final class ResultValidator {

	/** Validation of an item (design 0.2). */
	enum Validation {

		VALID, NEEDS_CHECK, INVALID

	}

	/** What is left of the {@code search} block (design 4.1). */
	enum SearchState {

		/** Valid values remain. */
		AVAILABLE,

		/** A well-formed block without values. */
		EMPTY,

		/** The block was missing or malformed, or every value in it was dropped. */
		DISCARDED

	}

	/** The outcome of a validation. */
	sealed interface Outcome {

		/**
		 * A result to store.
		 *
		 * @param content the normalized result: the known keys, {@code validation} on each item,
		 * {@code truncatedFields}, and {@code search} null when discarded
		 * @param searchState what is left of the {@code search} block
		 * @param truncatedFields paths of the cut lists and texts in {@code content}, such as {@code phones} or
		 * {@code schedules[0].title.value}
		 */
		record Usable(ObjectNode content, SearchState searchState, List<String> truncatedFields) implements Outcome {

			// The content is the analysis of a photo and must not reach logs.
			@Override
			public String toString() {
				return "Usable[searchState=" + searchState + ", truncatedFields=" + truncatedFields + "]";
			}

		}

		/**
		 * The output is {@code INVALID_OUTPUT}.
		 *
		 * @param reason where the action and event part broke the schema, without values, for logs
		 */
		record InvalidOutput(String reason) implements Outcome {
		}

	}

	private static final int MAX_SCHEDULES = 3;

	private static final int MAX_PHONES = 5;

	private static final int MAX_ACCOUNTS = 3;

	private static final int MAX_COPY_TEXTS = 5;

	private static final int MAX_PLACES = 3;

	private static final int MAX_UNDATED_INTENTS = 3;

	private static final int SUMMARY = 200;

	private static final int TITLE = 100;

	private static final int NAME = 100;

	private static final int ADDRESS = 200;

	private static final int RELATIVE_DATE_TEXT = 50;

	private static final int RAW = 32;

	private static final int LABEL = 50;

	private static final int BANK = 30;

	private static final int NUMBER = 32;

	private static final int HOLDER = 30;

	private static final int COPY_TEXT = 500;

	private static final int TARGET_NAME = 100;

	private static final int MAX_ENTITIES = 8;

	private static final int ENTITY_LABEL = 20;

	private static final int MAX_ENTITY_COLORS = 2;

	private static final int MAX_BACKGROUND_COLORS = 2;

	private static final int MAX_SCENES = 3;

	private static final int MAX_ACTIVITIES = 3;

	private static final int SCENE = 20;

	private static final int MAX_MOODS = 2;

	private static final int MAX_KEYWORDS = 10;

	private static final int KEYWORD = 30;

	// The date range of design 2.5 around validation_reference_at.
	private static final int YEARS_BEFORE = 1;

	private static final int YEARS_AFTER = 3;

	private static final Set<String> EXTRACTIONS = Set.of("READ", "ESTIMATED", "ABSENT");

	private static final Set<String> TIMEZONE_EVIDENCE = Set.of("EXPLICIT_OFFSET", "PLACE_LINKED", "NONE");

	private static final Set<String> COUNTRY_EVIDENCE = Set.of("EXPLICIT_CODE", "LINKED_ADDRESS", "NONE");

	private static final Set<String> COPY_TEXT_KINDS = Set.of("URL", "COUPON", "ADDRESS", "OTHER");

	private static final Set<String> INTENT_KINDS = Set.of("VISIT", "BUY", "WATCH", "OTHER");

	private static final Set<String> COLORS = Set.of("RED", "ORANGE", "YELLOW", "GREEN", "BLUE", "PURPLE", "PINK",
			"BROWN", "BLACK", "WHITE", "GRAY", "BEIGE");

	private static final Set<String> MOODS = Set.of("CHEERFUL", "CALM", "ROMANTIC", "FESTIVE", "GLOOMY", "TENSE",
			"NOSTALGIC", "NEUTRAL");

	private static final Set<String> PEOPLE_COUNTS = Set.of("NONE", "ONE", "FEW", "MANY");

	private static final Set<String> CONTENT_TYPES = Set.of("PHOTO", "DOCUMENT", "RECEIPT", "POSTER", "TICKET", "MENU",
			"BUSINESS_CARD", "OTHER");

	private static final Set<String> SAVE_INTENTS = Set.of("REFERENCE", "TODO", "SHOPPING", "MEMORY", "OTHER");

	private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

	private static final Set<String> ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

	private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

	private static final Pattern OFFSET = Pattern.compile("[+-](0[0-9]|1[0-4]):[0-5][0-9]");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private ResultValidator() {
	}

	/**
	 * @param output the model output, a raw JSON tree
	 * @param reference {@code validation_reference_at}: the capture time of the photo, else the intake time. The date
	 * range is taken on UTC dates; the day the zone can move is below its precision
	 */
	static Outcome validate(JsonNode output, Instant reference) {
		return new Run(LocalDate.ofInstant(reference, ZoneOffset.UTC)).validate(output);
	}

	private static final class Run {

		private final LocalDate earliest;

		private final LocalDate latest;

		private final List<String> truncated = new ArrayList<>();

		private int presented;

		private int invalid;

		Run(LocalDate reference) {
			this.earliest = reference.minusYears(YEARS_BEFORE);
			this.latest = reference.plusYears(YEARS_AFTER);
		}

		Outcome validate(JsonNode output) {
			ObjectNode content = NODES.objectNode();
			try {
				object(output, "output");
				content.set("context", context(object(output.get("context"), "context")));
				content.set("schedules", items(output, "schedules", MAX_SCHEDULES, this::schedule));
				content.set("phones", items(output, "phones", MAX_PHONES, this::phone));
				content.set("accounts", items(output, "accounts", MAX_ACCOUNTS, this::account));
				content.set("copyTexts", items(output, "copyTexts", MAX_COPY_TEXTS, this::copyText));
				content.set("places", items(output, "places", MAX_PLACES, this::place));
				content.set("undatedIntents", items(output, "undatedIntents", MAX_UNDATED_INTENTS, this::undatedIntent));
			}
			catch (SchemaViolation ex) {
				return new Outcome.InvalidOutput(ex.getMessage());
			}
			if (presented > 0 && invalid == presented) {
				return new Outcome.InvalidOutput("every item is INVALID");
			}
			Search search = new Search();
			ObjectNode searchContent = search.read(output.get("search"));
			content.set("search", searchContent);
			truncated.addAll(search.truncated);
			ArrayNode truncatedFields = content.putArray("truncatedFields");
			truncated.forEach(truncatedFields::add);
			return new Outcome.Usable(content, search.state(searchContent), List.copyOf(truncated));
		}

		private ObjectNode context(JsonNode context) {
			ObjectNode node = NODES.objectNode();
			Item item = new Item();
			node.put("summary", item.display(string(context, "summary", "context"), SUMMARY, "summary"));
			// Suggestions only: a malformed one is left out.
			String language = nullableString(context, "primaryLanguage", "context");
			Locale locale = (language != null) ? Locale.forLanguageTag(language) : Locale.ROOT;
			node.put("primaryLanguage", (!locale.getLanguage().isEmpty()
					&& locale.toLanguageTag().equalsIgnoreCase(language)) ? locale.toLanguageTag() : null);
			String country = nullableString(context, "suggestedCountry", "context");
			node.put("suggestedCountry", (country != null && COUNTRIES.contains(country)) ? country : null);
			item.truncated.forEach((path) -> truncated.add("context." + path));
			return node;
		}

		private ArrayNode items(JsonNode output, String key, int max, ItemReader reader) {
			JsonNode items = array(output.get(key), key);
			ArrayNode kept = NODES.arrayNode();
			if (items.size() > max) {
				truncated.add(key);
			}
			for (int i = 0; i < Math.min(items.size(), max); i++) {
				Item item = new Item();
				ObjectNode node;
				try {
					node = reader.read(object(items.get(i), key + "[" + i + "]"), key + "[" + i + "]", item);
				}
				catch (DropItem ex) {
					continue;
				}
				node.put("validation", item.validation.name());
				String path = key + "[" + kept.size() + "].";
				item.truncated.forEach((field) -> truncated.add(path + field));
				kept.add(node);
				presented++;
				invalid += (item.validation == Validation.INVALID) ? 1 : 0;
			}
			return kept;
		}

		private ObjectNode schedule(JsonNode schedule, String path, Item item) {
			ObjectNode node = NODES.objectNode();
			JsonNode title = object(schedule.get("title"), path + ".title");
			node.set("title", valued(item.display(nullableString(title, "value", path + ".title"), TITLE, "title.value"),
					extraction(title, path + ".title")));
			for (String key : List.of("date", "startTime", "endTime")) {
				JsonNode field = object(schedule.get(key), path + "." + key);
				String value = nullableString(field, "value", path + "." + key);
				item.check(("date".equals(key)) ? date(value) : time(value));
				node.set(key, valued(value, extraction(field, path + "." + key)));
			}
			node.put("allDay", bool(schedule, "allDay", path));
			JsonNode timezone = object(schedule.get("timezone"), path + ".timezone");
			String zone = nullableString(timezone, "value", path + ".timezone");
			item.check((zone == null || OFFSET.matcher(zone).matches() || ZONES.contains(zone))
					? Validation.VALID : Validation.INVALID);
			ObjectNode timezoneNode = node.putObject("timezone");
			timezoneNode.put("value", zone);
			timezoneNode.put("evidence", member(string(timezone, "evidence", path + ".timezone"), TIMEZONE_EVIDENCE));
			JsonNode place = present(schedule, "place", path);
			node.set("place", place.isNull() ? NODES.nullNode()
					: placeFields(object(place, path + ".place"), path + ".place", item, "place."));
			String relative = nullableString(schedule, "relativeDateText", path);
			item.execution(relative, RELATIVE_DATE_TEXT);
			node.put("relativeDateText", relative);
			return node;
		}

		private ObjectNode phone(JsonNode phone, String path, Item item) {
			ObjectNode node = NODES.objectNode();
			String raw = string(phone, "raw", path);
			item.execution(raw, RAW);
			node.put("raw", raw);
			node.put("label", item.display(nullableString(phone, "label", path), LABEL, "label"));
			node.put("countryCode", country(nullableString(phone, "countryCode", path), item));
			node.put("countryEvidence", member(string(phone, "countryEvidence", path), COUNTRY_EVIDENCE));
			node.put("extraction", extraction(phone, path));
			return node;
		}

		private ObjectNode account(JsonNode account, String path, Item item) {
			ObjectNode node = NODES.objectNode();
			node.put("bank", item.display(nullableString(account, "bank", path), BANK, "bank"));
			String number = string(account, "number", path);
			item.execution(number, NUMBER);
			node.put("number", number);
			node.put("holder", item.display(nullableString(account, "holder", path), HOLDER, "holder"));
			node.put("extraction", extraction(account, path));
			return node;
		}

		private ObjectNode copyText(JsonNode copyText, String path, Item item) {
			ObjectNode node = NODES.objectNode();
			node.put("text", item.display(string(copyText, "text", path), COPY_TEXT, "text"));
			node.put("kind", member(string(copyText, "kind", path), COPY_TEXT_KINDS));
			node.put("extraction", extraction(copyText, path));
			return node;
		}

		private ObjectNode place(JsonNode place, String path, Item item) {
			return placeFields(place, path, item, "");
		}

		private ObjectNode placeFields(JsonNode place, String path, Item item, String prefix) {
			ObjectNode node = NODES.objectNode();
			node.put("name", item.display(nullableString(place, "name", path), NAME, prefix + "name"));
			String address = nullableString(place, "address", path);
			item.execution(address, ADDRESS);
			node.put("address", address);
			node.put("countryCode", country(nullableString(place, "countryCode", path), item));
			node.put("extraction", extraction(place, path));
			return node;
		}

		private ObjectNode undatedIntent(JsonNode intent, String path, Item item) {
			ObjectNode node = NODES.objectNode();
			node.put("targetName", item.display(string(intent, "targetName", path), TARGET_NAME, "targetName"));
			node.put("kind", member(string(intent, "kind", path), INTENT_KINDS));
			return node;
		}

		private Validation date(@Nullable String value) {
			if (value == null) {
				return Validation.VALID;
			}
			LocalDate date;
			try {
				date = LocalDate.parse(value);
			}
			catch (DateTimeParseException ex) {
				return Validation.INVALID;
			}
			return (date.isBefore(earliest) || date.isAfter(latest)) ? Validation.NEEDS_CHECK : Validation.VALID;
		}

		private static Validation time(@Nullable String value) {
			return (value == null || TIME.matcher(value).matches()) ? Validation.VALID : Validation.INVALID;
		}

		private static @Nullable String country(@Nullable String value, Item item) {
			item.check((value == null || COUNTRIES.contains(value)) ? Validation.VALID : Validation.INVALID);
			return value;
		}

		private static ObjectNode valued(@Nullable String value, String extraction) {
			ObjectNode node = NODES.objectNode();
			node.put("value", value);
			node.put("extraction", extraction);
			return node;
		}

		private static String extraction(JsonNode parent, String path) {
			return member(string(parent, "extraction", path), EXTRACTIONS);
		}

		// A value outside its enum drops its item.
		private static String member(String value, Set<String> values) {
			if (!values.contains(value)) {
				throw new DropItem();
			}
			return value;
		}

	}

	/** The {@code search} block: its problems only set the {@link SearchState}. */
	private static final class Search {

		private final List<String> truncated = new ArrayList<>();

		private boolean dropped;

		@Nullable ObjectNode read(@Nullable JsonNode search) {
			if (search == null || !search.isObject()) {
				return null;
			}
			try {
				ObjectNode node = NODES.objectNode();
				ArrayNode entities = node.putArray("entities");
				JsonNode given = array(search.get("entities"), "search.entities");
				for (int i = 0; i < limit(given, MAX_ENTITIES, "entities"); i++) {
					JsonNode entity = object(given.get(i), "search.entities[" + i + "]");
					ObjectNode entityNode = entities.addObject();
					entityNode.put("label", text(string(entity, "label", "search.entities"), ENTITY_LABEL,
							"entities[" + i + "].label"));
					entityNode.set("colors", members(array(entity.get("colors"), "search.entities.colors"),
							MAX_ENTITY_COLORS, COLORS, "entities[" + i + "].colors"));
				}
				node.set("backgroundColors", members(array(search.get("backgroundColors"), "search"),
						MAX_BACKGROUND_COLORS, COLORS, "backgroundColors"));
				node.set("scenes", texts(array(search.get("scenes"), "search"), MAX_SCENES, SCENE, "scenes"));
				node.set("activities",
						texts(array(search.get("activities"), "search"), MAX_ACTIVITIES, SCENE, "activities"));
				node.set("moods", members(array(search.get("moods"), "search"), MAX_MOODS, MOODS, "moods"));
				node.put("peopleCount", nullableMember(search, "peopleCount", PEOPLE_COUNTS));
				node.put("contentType", nullableMember(search, "contentType", CONTENT_TYPES));
				node.put("saveIntent", nullableMember(search, "saveIntent", SAVE_INTENTS));
				node.set("observedKeywords",
						texts(array(search.get("observedKeywords"), "search"), MAX_KEYWORDS, KEYWORD, "observedKeywords"));
				return node;
			}
			catch (SchemaViolation ex) {
				truncated.clear();
				return null;
			}
		}

		SearchState state(@Nullable ObjectNode search) {
			if (search == null) {
				return SearchState.DISCARDED;
			}
			boolean values = false;
			for (String key : List.of("entities", "backgroundColors", "scenes", "activities", "moods",
					"observedKeywords")) {
				values |= !search.get(key).isEmpty();
			}
			for (String key : List.of("peopleCount", "contentType", "saveIntent")) {
				values |= !search.get(key).isNull();
			}
			return values ? SearchState.AVAILABLE : (dropped ? SearchState.DISCARDED : SearchState.EMPTY);
		}

		private int limit(JsonNode array, int max, String path) {
			if (array.size() > max) {
				truncated.add("search." + path);
			}
			return Math.min(array.size(), max);
		}

		private ArrayNode members(JsonNode array, int max, Set<String> values, String path) {
			ArrayNode node = NODES.arrayNode();
			for (int i = 0; i < limit(array, max, path); i++) {
				String value = string(array.get(i), "search." + path);
				if (values.contains(value)) {
					node.add(value);
				}
				else {
					dropped = true;
				}
			}
			return node;
		}

		private ArrayNode texts(JsonNode array, int max, int length, String path) {
			ArrayNode node = NODES.arrayNode();
			for (int i = 0; i < limit(array, max, path); i++) {
				node.add(text(string(array.get(i), "search." + path), length, path + "[" + i + "]"));
			}
			return node;
		}

		private @Nullable String nullableMember(JsonNode search, String key, Set<String> values) {
			String value = nullableString(search, key, "search");
			if (value != null && !values.contains(value)) {
				dropped = true;
				return null;
			}
			return value;
		}

		private String text(String value, int length, String path) {
			if (value.codePointCount(0, value.length()) <= length) {
				return value;
			}
			truncated.add("search." + path);
			return value.substring(0, value.offsetByCodePoints(0, length));
		}

	}

	/** The validation and cut texts of one item, collected while it is read. */
	private static final class Item {

		private Validation validation = Validation.VALID;

		private final List<String> truncated = new ArrayList<>();

		void check(Validation found) {
			if (found.compareTo(validation) > 0) {
				validation = found;
			}
		}

		// Execution values are never cut: one over its length makes the item INVALID.
		void execution(@Nullable String value, int length) {
			if (value != null && value.codePointCount(0, value.length()) > length) {
				check(Validation.INVALID);
			}
		}

		@Nullable String display(@Nullable String value, int length, String path) {
			if (value == null || value.codePointCount(0, value.length()) <= length) {
				return value;
			}
			truncated.add(path);
			return value.substring(0, value.offsetByCodePoints(0, length));
		}

	}

	@FunctionalInterface
	private interface ItemReader {

		ObjectNode read(JsonNode item, String path, Item collected);

	}

	private static JsonNode present(JsonNode parent, String key, String path) {
		JsonNode value = parent.get(key);
		if (value == null) {
			throw new SchemaViolation(path + "." + key + " is missing");
		}
		return value;
	}

	private static JsonNode object(@Nullable JsonNode value, String path) {
		if (value == null || !value.isObject()) {
			throw new SchemaViolation(path + " is not an object");
		}
		return value;
	}

	private static JsonNode array(@Nullable JsonNode value, String path) {
		if (value == null || !value.isArray()) {
			throw new SchemaViolation(path + " is not an array");
		}
		return value;
	}

	private static String string(JsonNode parent, String key, String path) {
		return string(parent.get(key), path + "." + key);
	}

	private static String string(@Nullable JsonNode value, String path) {
		if (value == null || !value.isString()) {
			throw new SchemaViolation(path + " is not a string");
		}
		return value.stringValue();
	}

	private static @Nullable String nullableString(JsonNode parent, String key, String path) {
		JsonNode value = present(parent, key, path);
		return value.isNull() ? null : string(value, path + "." + key);
	}

	private static boolean bool(JsonNode parent, String key, String path) {
		JsonNode value = parent.get(key);
		if (value == null || !value.isBoolean()) {
			throw new SchemaViolation(path + "." + key + " is not a boolean");
		}
		return value.booleanValue();
	}

	/** A missing key or a wrong type; its message names the path, never a value. */
	private static final class SchemaViolation extends RuntimeException {

		SchemaViolation(String message) {
			super(message, null, false, false);
		}

	}

	/** A value outside its enum: the item is dropped. */
	private static final class DropItem extends RuntimeException {

		DropItem() {
			super(null, null, false, false);
		}

	}

}
