package io.github.gb6105.pik.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gb6105.pik.analysis.ResultValidator.Outcome;
import io.github.gb6105.pik.analysis.ResultValidator.SearchState;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every rule of the common result validator (design 4.1 and 2.5, plan 6.5, 11.1).
 */
class ResultValidatorTests {

	static final JsonMapper JSON = AnalysisManifestsTests.JSON;

	// validation_reference_at: the accepted dates are 2025-11-20 to 2029-11-20.
	static final Instant REFERENCE = Instant.parse("2026-11-20T10:00:00Z");

	static final String VALID = """
			{"context": {"summary": "A concert poster with a date, a hall and a ticket phone number.",
			             "primaryLanguage": "ko-KR", "suggestedCountry": "KR"},
			 "schedules": [{"title": {"value": "Jazz Night", "extraction": "READ"},
			                "date": {"value": "2026-12-24", "extraction": "READ"},
			                "startTime": {"value": "19:30", "extraction": "READ"},
			                "endTime": {"value": null, "extraction": "ABSENT"},
			                "allDay": false,
			                "timezone": {"value": "Asia/Seoul", "evidence": "PLACE_LINKED"},
			                "place": {"name": "Blue Hall", "address": "서울 마포구 1", "countryCode": "KR",
			                          "extraction": "READ"},
			                "relativeDateText": null}],
			 "phones": [{"raw": "02-123-4567", "label": "Ticket office", "countryCode": "KR",
			             "countryEvidence": "LINKED_ADDRESS", "extraction": "READ"}],
			 "accounts": [{"bank": "Pik Bank", "number": "123-456-789", "holder": "Blue Hall", "extraction": "READ"}],
			 "copyTexts": [{"text": "https://example.com/tickets", "kind": "URL", "extraction": "READ"}],
			 "places": [{"name": "Cafe", "address": null, "countryCode": null, "extraction": "ESTIMATED"}],
			 "undatedIntents": [{"targetName": "Jazz album", "kind": "BUY"}],
			 "search": {"entities": [{"label": "poster", "colors": ["BLUE", "WHITE"]}], "backgroundColors": ["WHITE"],
			            "scenes": ["wall"], "activities": [], "moods": ["FESTIVE"], "peopleCount": "NONE",
			            "contentType": "POSTER", "saveIntent": "REFERENCE", "observedKeywords": ["Jazz Night"]}}
			""";

	static final String ITEMS = "/schedules,/phones,/accounts,/copyTexts,/places,/undatedIntents";

	@Test
	void validOutputKeepsItsValuesAndMarksEachItemValid() {
		Outcome.Usable usable = usable(output((o) -> {
		}));

		ObjectNode expected = (ObjectNode) JSON.readTree(VALID);
		for (String items : ITEMS.split(",")) {
			((ObjectNode) expected.at(items + "/0")).put("validation", "VALID");
		}
		expected.putArray("truncatedFields");
		assertThat(usable.content()).isEqualTo(expected);
		assertThat(usable.searchState()).isEqualTo(SearchState.AVAILABLE);
		assertThat(usable.truncatedFields()).isEmpty();
		assertThat(usable.toString()).doesNotContain("Jazz");
	}

	@Test
	void unknownKeysAreLeftOut() {
		Outcome.Usable usable = usable(output((o) -> {
			o.put("extra", 1);
			((ObjectNode) o.at("/phones/0")).put("extra", "x");
			((ObjectNode) o.at("/schedules/0/title")).put("extra", "x");
			((ObjectNode) o.get("search")).put("extra", "x");
		}));

		assertThat(usable.content().has("extra")).isFalse();
		assertThat(usable.content().at("/phones/0").has("extra")).isFalse();
		assertThat(usable.content().at("/schedules/0/title").has("extra")).isFalse();
		assertThat(usable.content().get("search").has("extra")).isFalse();
	}

	@Test
	void listsOverTheirLimitKeepTheirFirstItems() {
		Outcome.Usable usable = usable(output((o) -> {
			repeat(o, "/schedules", 4, (item, i) -> ((ObjectNode) item.get("title")).put("value", "event " + i));
			repeat(o, "/phones", 6, (item, i) -> item.put("raw", "010-0000-000" + i));
			repeat(o, "/accounts", 4, (item, i) -> item.put("number", "100-" + i));
			repeat(o, "/copyTexts", 6, (item, i) -> item.put("text", "code " + i));
			repeat(o, "/places", 4, (item, i) -> item.put("name", "place " + i));
			repeat(o, "/undatedIntents", 4, (item, i) -> item.put("targetName", "thing " + i));
		}));

		JsonNode content = usable.content();
		assertThat(content.get("schedules")).hasSize(3);
		assertThat(content.at("/schedules/2/title/value").stringValue()).isEqualTo("event 2");
		assertThat(content.get("phones")).hasSize(5);
		assertThat(content.at("/phones/4/raw").stringValue()).isEqualTo("010-0000-0004");
		assertThat(content.get("accounts")).hasSize(3);
		assertThat(content.get("copyTexts")).hasSize(5);
		assertThat(content.get("places")).hasSize(3);
		assertThat(content.get("undatedIntents")).hasSize(3);
		assertThat(content.at("/undatedIntents/0/targetName").stringValue()).isEqualTo("thing 0");
		assertThat(usable.truncatedFields()).containsExactly("schedules", "phones", "accounts", "copyTexts", "places",
				"undatedIntents");
		assertThat(content.get("truncatedFields")).isEqualTo(JSON.valueToTree(usable.truncatedFields()));
	}

	// Lengths count code points: a cut never splits a surrogate pair.
	@Test
	void displayTextsOverTheirLengthAreCut() {
		String emoji = "🎷";
		Outcome.Usable usable = usable(output((o) -> {
			((ObjectNode) o.get("context")).put("summary", emoji.repeat(201));
			((ObjectNode) o.at("/schedules/0/title")).put("value", "t".repeat(101));
			((ObjectNode) o.at("/schedules/0/place")).put("name", "n".repeat(101));
			((ObjectNode) o.at("/phones/0")).put("label", "l".repeat(51));
			((ObjectNode) o.at("/accounts/0")).put("bank", "b".repeat(31)).put("holder", "h".repeat(31));
			((ObjectNode) o.at("/copyTexts/0")).put("text", "x".repeat(501));
			((ObjectNode) o.at("/places/0")).put("name", "p".repeat(101));
			((ObjectNode) o.at("/undatedIntents/0")).put("targetName", "g".repeat(101));
		}));

		JsonNode content = usable.content();
		assertThat(content.at("/context/summary").stringValue()).isEqualTo(emoji.repeat(200));
		assertThat(content.at("/schedules/0/title/value").stringValue()).hasSize(100);
		assertThat(content.at("/schedules/0/place/name").stringValue()).hasSize(100);
		assertThat(content.at("/phones/0/label").stringValue()).hasSize(50);
		assertThat(content.at("/accounts/0/bank").stringValue()).hasSize(30);
		assertThat(content.at("/accounts/0/holder").stringValue()).hasSize(30);
		assertThat(content.at("/copyTexts/0/text").stringValue()).hasSize(500);
		assertThat(content.at("/places/0/name").stringValue()).hasSize(100);
		assertThat(content.at("/undatedIntents/0/targetName").stringValue()).hasSize(100);
		assertThat(usable.truncatedFields()).containsExactly("context.summary", "schedules[0].title.value",
				"schedules[0].place.name", "phones[0].label", "accounts[0].bank", "accounts[0].holder",
				"copyTexts[0].text", "places[0].name", "undatedIntents[0].targetName");
		for (String items : ITEMS.split(",")) {
			assertThat(content.at(items + "/0/validation").stringValue()).isEqualTo("VALID");
		}
	}

	@Test
	void textsAtTheirLengthAreKept() {
		Outcome.Usable usable = usable(output((o) -> {
			((ObjectNode) o.get("context")).put("summary", "s".repeat(200));
			((ObjectNode) o.at("/phones/0")).put("raw", "1".repeat(32)).put("label", "l".repeat(50));
			((ObjectNode) o.at("/schedules/0")).put("relativeDateText", "r".repeat(50));
			((ObjectNode) o.at("/places/0")).put("address", "a".repeat(200));
		}));

		assertThat(usable.truncatedFields()).isEmpty();
		assertThat(usable.content().at("/phones/0/validation").stringValue()).isEqualTo("VALID");
		assertThat(usable.content().at("/schedules/0/validation").stringValue()).isEqualTo("VALID");
		assertThat(usable.content().at("/places/0/validation").stringValue()).isEqualTo("VALID");
	}

	@Test
	void executionValuesOverTheirLengthMakeTheirItemInvalidWithoutACut() {
		Outcome.Usable usable = usable(output((o) -> {
			repeat(o, "/phones", 2, (item, i) -> {
			});
			((ObjectNode) o.at("/phones/0")).put("raw", "1".repeat(33));
			repeat(o, "/accounts", 2, (item, i) -> {
			});
			((ObjectNode) o.at("/accounts/1")).put("number", "2".repeat(33));
			repeat(o, "/places", 2, (item, i) -> {
			});
			((ObjectNode) o.at("/places/0")).put("address", "a".repeat(201));
			repeat(o, "/schedules", 3, (item, i) -> {
			});
			((ObjectNode) o.at("/schedules/0/place")).put("address", "a".repeat(201));
			((ObjectNode) o.at("/schedules/1")).put("relativeDateText", "r".repeat(51));
		}));

		JsonNode content = usable.content();
		assertThat(validations(content, "phones")).containsExactly("INVALID", "VALID");
		assertThat(content.at("/phones/0/raw").stringValue()).hasSize(33);
		assertThat(validations(content, "accounts")).containsExactly("VALID", "INVALID");
		assertThat(content.at("/accounts/1/number").stringValue()).hasSize(33);
		assertThat(validations(content, "places")).containsExactly("INVALID", "VALID");
		assertThat(validations(content, "schedules")).containsExactly("INVALID", "INVALID", "VALID");
		assertThat(content.at("/schedules/0/place/address").stringValue()).hasSize(201);
		assertThat(usable.truncatedFields()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/schedules/0/date:2026-02-30", "/schedules/0/date:2026/12/24",
			"/schedules/0/date:24-12-2026", "/schedules/0/startTime:24:00", "/schedules/0/startTime:7:30",
			"/schedules/0/endTime:19:60", "/schedules/0/endTime:19:30:00", "/schedules/0/timezone:Mars/Olympus",
			"/schedules/0/timezone:+15:00", "/schedules/0/timezone:GMT+9", "/schedules/0/place:countryCode=XX",
			"/phones/0:countryCode=kr", "/places/0:countryCode=KOR" })
	void malformedExecutionValuesMakeTheirItemInvalid(String change) {
		String[] parts = change.split(":", 2);
		Outcome.Usable usable = usable(output((o) -> {
			ObjectNode target = (ObjectNode) o.at(parts[0]);
			if (parts[1].startsWith("countryCode=")) {
				target.put("countryCode", parts[1].substring("countryCode=".length()));
			}
			else {
				target.put("value", parts[1]);
			}
		}));

		String items = parts[0].substring(1, parts[0].indexOf('/', 1));
		assertThat(validations(usable.content(), items)).containsExactly("INVALID");
	}

	@ParameterizedTest
	@ValueSource(strings = { "+09:00", "-03:30", "+14:00", "UTC", "America/New_York" })
	void timeZonesAreIanaNamesOrUtcOffsets(String zone) {
		Outcome.Usable usable = usable(output((o) -> ((ObjectNode) o.at("/schedules/0/timezone")).put("value", zone)));

		assertThat(validations(usable.content(), "schedules")).containsExactly("VALID");
	}

	@Test
	void datesOutsideCaptureTimeMinusOneToPlusThreeYearsNeedACheck() {
		Outcome.Usable usable = usable(output((o) -> {
			repeat(o, "/schedules", 3, (item, i) -> {
			});
			((ObjectNode) o.at("/schedules/0/date")).put("value", "2025-11-20");
			((ObjectNode) o.at("/schedules/1/date")).put("value", "2025-11-19");
			((ObjectNode) o.at("/schedules/2/date")).put("value", "2029-11-21");
		}));
		Outcome.Usable latest = usable(output((o) -> ((ObjectNode) o.at("/schedules/0/date")).put("value",
				"2029-11-20")));
		// A malformed value outranks the date check.
		Outcome.Usable both = usable(output((o) -> {
			((ObjectNode) o.at("/schedules/0/date")).put("value", "2031-01-01");
			((ObjectNode) o.at("/schedules/0/startTime")).put("value", "25:00");
		}));

		assertThat(validations(usable.content(), "schedules")).containsExactly("VALID", "NEEDS_CHECK", "NEEDS_CHECK");
		assertThat(validations(latest.content(), "schedules")).containsExactly("VALID");
		assertThat(validations(both.content(), "schedules")).containsExactly("INVALID");
		assertThat(usable.content().at("/schedules/1/date/value").stringValue()).isEqualTo("2025-11-19");
	}

	@Test
	void itemsWithAValueOutsideItsEnumAreDropped() {
		Outcome.Usable usable = usable(output((o) -> {
			repeat(o, "/phones", 3, (item, i) -> item.put("label", "l".repeat(51)));
			((ObjectNode) o.at("/phones/0")).put("extraction", "GUESSED");
			((ObjectNode) o.at("/phones/1")).put("countryEvidence", "AREA_CODE");
			((ObjectNode) o.at("/schedules/0/timezone")).put("evidence", "CITY");
			((ObjectNode) o.at("/copyTexts/0")).put("kind", "MAP");
			((ObjectNode) o.at("/undatedIntents/0")).put("kind", "READ");
			((ObjectNode) o.at("/places/0")).put("extraction", "read");
			((ObjectNode) o.at("/accounts/0")).put("extraction", "");
		}));

		JsonNode content = usable.content();
		assertThat(content.get("phones")).hasSize(1);
		assertThat(content.at("/phones/0/label").stringValue()).hasSize(50);
		for (String items : List.of("schedules", "copyTexts", "undatedIntents", "places", "accounts")) {
			assertThat(content.get(items)).as(items).isEmpty();
		}
		// Paths name the stored item.
		assertThat(usable.truncatedFields()).containsExactly("phones[0].label");
	}

	@ParameterizedTest
	@ValueSource(strings = { "root", "context", "context.summary", "schedules", "schedules[0]", "schedules[0].title",
			"schedules[0].date.value", "schedules[0].allDay", "schedules[0].timezone", "schedules[0].place",
			"schedules[0].relativeDateText", "phones[0].raw", "phones[0].label", "accounts[0].number",
			"copyTexts[0].kind", "places[0].extraction", "undatedIntents", "undatedIntents[0].targetName" })
	void schemaViolationsOfTheActionAndEventPartAreInvalidOutput(String broken) {
		JsonNode output = switch (broken) {
			case "root" -> JSON.readTree("[]");
			case "context" -> output((o) -> o.remove("context"));
			case "context.summary" -> output((o) -> ((ObjectNode) o.get("context")).putNull("summary"));
			case "schedules" -> output((o) -> o.put("schedules", "none"));
			case "schedules[0]" -> output((o) -> ((ArrayNode) o.get("schedules")).set(0, JSON.readTree("1")));
			case "schedules[0].title" -> output((o) -> ((ObjectNode) o.at("/schedules/0/title")).remove("extraction"));
			case "schedules[0].date.value" -> output((o) -> ((ObjectNode) o.at("/schedules/0/date")).put("value", 20261224));
			case "schedules[0].allDay" -> output((o) -> ((ObjectNode) o.at("/schedules/0")).put("allDay", "no"));
			case "schedules[0].timezone" -> output((o) -> ((ObjectNode) o.at("/schedules/0")).putNull("timezone"));
			case "schedules[0].place" -> output((o) -> ((ObjectNode) o.at("/schedules/0")).put("place", "Blue Hall"));
			case "schedules[0].relativeDateText" -> output((o) -> ((ObjectNode) o.at("/schedules/0")).remove("relativeDateText"));
			case "phones[0].raw" -> output((o) -> ((ObjectNode) o.at("/phones/0")).putNull("raw"));
			case "phones[0].label" -> output((o) -> ((ObjectNode) o.at("/phones/0")).remove("label"));
			case "accounts[0].number" -> output((o) -> ((ObjectNode) o.at("/accounts/0")).put("number", 123));
			case "copyTexts[0].kind" -> output((o) -> ((ObjectNode) o.at("/copyTexts/0")).putNull("kind"));
			case "places[0].extraction" -> output((o) -> ((ObjectNode) o.at("/places/0")).remove("extraction"));
			case "undatedIntents" -> output((o) -> o.remove("undatedIntents"));
			case "undatedIntents[0].targetName" -> output((o) -> ((ObjectNode) o.at("/undatedIntents/0")).put("targetName", true));
			default -> throw new IllegalArgumentException(broken);
		};

		Outcome outcome = ResultValidator.validate(output, REFERENCE);

		assertThat(outcome).isInstanceOf(Outcome.InvalidOutput.class);
		assertThat(((Outcome.InvalidOutput) outcome).reason()).doesNotContain("Jazz", "Blue Hall", "02-123");
	}

	@Test
	void itemsThatAreAllInvalidAreInvalidOutput() {
		JsonNode allInvalid = output((o) -> {
			((ObjectNode) o.at("/schedules/0/date")).put("value", "tomorrow");
			((ObjectNode) o.at("/phones/0")).put("raw", "1".repeat(33));
			((ObjectNode) o.at("/accounts/0")).put("number", "2".repeat(33));
			((ArrayNode) o.get("copyTexts")).removeAll();
			((ObjectNode) o.at("/places/0")).put("countryCode", "XX");
			((ArrayNode) o.get("undatedIntents")).removeAll();
		});
		JsonNode oneValid = output((o) -> {
			((ObjectNode) o.at("/schedules/0/date")).put("value", "tomorrow");
			((ObjectNode) o.at("/phones/0")).put("raw", "1".repeat(33));
			((ObjectNode) o.at("/accounts/0")).put("number", "2".repeat(33));
			((ObjectNode) o.at("/places/0")).put("countryCode", "XX");
			((ArrayNode) o.get("undatedIntents")).removeAll();
		});

		assertThat(ResultValidator.validate(allInvalid, REFERENCE))
			.isEqualTo(new Outcome.InvalidOutput("every item is INVALID"));
		assertThat(validations(usable(oneValid).content(), "copyTexts")).containsExactly("VALID");
	}

	@Test
	void allListsEmptyIsAUsableResult() {
		Outcome.Usable usable = usable(output((o) -> {
			for (String items : ITEMS.split(",")) {
				((ArrayNode) o.at(items)).removeAll();
			}
		}));

		assertThat(usable.content().get("phones")).isEmpty();
		assertThat(usable.searchState()).isEqualTo(SearchState.AVAILABLE);
	}

	@Test
	void malformedContextSuggestionsAreLeftOut() {
		Outcome.Usable usable = usable(output((o) -> ((ObjectNode) o.get("context")).put("primaryLanguage", "ko_KR")
			.put("suggestedCountry", "ZZ")));
		Outcome.Usable normalized = usable(output((o) -> ((ObjectNode) o.get("context")).put("primaryLanguage", "ko-kr")
			.putNull("suggestedCountry")));

		assertThat(usable.content().get("context")).isEqualTo(JSON.readTree("""
				{"summary": "A concert poster with a date, a hall and a ticket phone number.",
				 "primaryLanguage": null, "suggestedCountry": null}"""));
		assertThat(normalized.content().at("/context/primaryLanguage").stringValue()).isEqualTo("ko-KR");
	}

	@Test
	void searchBlockThatIsMissingOrNotAnObjectIsDiscarded() {
		for (JsonNode output : List.of(output((o) -> o.remove("search")), output((o) -> o.putNull("search")),
				output((o) -> o.put("search", "poster")))) {
			Outcome.Usable usable = usable(output);

			assertThat(usable.searchState()).isEqualTo(SearchState.DISCARDED);
			assertThat(usable.content().get("search").isNull()).isTrue();
			assertThat(validations(usable.content(), "phones")).containsExactly("VALID");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "entities", "entities[0].label", "entities[0].colors", "backgroundColors[0]", "scenes",
			"activities[0]", "moods", "peopleCount", "contentType", "saveIntent", "observedKeywords[0]" })
	void searchBlockWithATypeErrorIsDiscardedWithItsCuts(String broken) {
		Outcome.Usable usable = usable(output((o) -> {
			ObjectNode search = (ObjectNode) o.get("search");
			search.putArray("scenes").add("s".repeat(21));
			switch (broken) {
				case "entities" -> search.remove("entities");
				case "entities[0].label" -> ((ObjectNode) search.at("/entities/0")).put("label", 1);
				case "entities[0].colors" -> ((ObjectNode) search.at("/entities/0")).put("colors", "BLUE");
				case "backgroundColors[0]" -> search.putArray("backgroundColors").addNull();
				case "scenes" -> search.put("scenes", "wall");
				case "activities[0]" -> search.putArray("activities").add(1);
				case "moods" -> search.putNull("moods");
				case "peopleCount" -> search.put("peopleCount", 2);
				case "contentType" -> search.remove("contentType");
				case "saveIntent" -> search.putArray("saveIntent");
				case "observedKeywords[0]" -> search.putArray("observedKeywords").addObject();
				default -> throw new IllegalArgumentException(broken);
			}
		}));

		assertThat(usable.searchState()).isEqualTo(SearchState.DISCARDED);
		assertThat(usable.content().get("search").isNull()).isTrue();
		assertThat(usable.truncatedFields()).isEmpty();
	}

	@Test
	void searchValuesOutsideTheirEnumAreDroppedAndCutsAreKept() {
		Outcome.Usable usable = usable(output((o) -> {
			ObjectNode search = (ObjectNode) o.get("search");
			ArrayNode entities = search.putArray("entities");
			for (int i = 0; i < 9; i++) {
				entities.addObject().put("label", "entity " + i).putArray("colors").add("RED").add("TEAL").add("BLUE");
			}
			((ObjectNode) entities.get(0)).put("label", "e".repeat(21));
			search.putArray("backgroundColors").add("WHITE").add("BLACK").add("RED");
			search.putArray("scenes").add("a").add("b").add("c").add("d");
			search.putArray("activities").add("x".repeat(21));
			search.putArray("moods").add("ANGRY").add("CALM").add("TENSE");
			search.put("peopleCount", "SEVERAL");
			search.put("contentType", "SCREENSHOT");
			search.put("saveIntent", "FUN");
			ArrayNode keywords = search.putArray("observedKeywords");
			for (int i = 0; i < 11; i++) {
				keywords.add("k".repeat(31));
			}
		}));

		JsonNode search = usable.content().get("search");
		assertThat(usable.searchState()).isEqualTo(SearchState.AVAILABLE);
		assertThat(search.get("entities")).hasSize(8);
		assertThat(search.at("/entities/0/label").stringValue()).hasSize(20);
		assertThat(search.at("/entities/0/colors")).isEqualTo(JSON.readTree("[\"RED\"]"));
		assertThat(search.get("backgroundColors")).isEqualTo(JSON.readTree("[\"WHITE\", \"BLACK\"]"));
		assertThat(search.get("scenes")).isEqualTo(JSON.readTree("[\"a\", \"b\", \"c\"]"));
		assertThat(search.at("/activities/0").stringValue()).hasSize(20);
		assertThat(search.get("moods")).isEqualTo(JSON.readTree("[\"CALM\"]"));
		assertThat(search.get("peopleCount").isNull()).isTrue();
		assertThat(search.get("contentType").isNull()).isTrue();
		assertThat(search.get("saveIntent").isNull()).isTrue();
		assertThat(search.get("observedKeywords")).hasSize(10);
		assertThat(search.at("/observedKeywords/9").stringValue()).hasSize(30);
		assertThat(usable.truncatedFields()).startsWith("search.entities", "search.entities[0].label",
				"search.entities[0].colors")
			.contains("search.backgroundColors", "search.scenes", "search.activities[0]", "search.moods",
					"search.observedKeywords", "search.observedKeywords[9]")
			.doesNotContain("search.observedKeywords[10]");
	}

	@Test
	void searchStateIsEmptyForAWellFormedBlockWithoutValues() {
		Outcome.Usable usable = usable(output((o) -> o.set("search", JSON.readTree("""
				{"entities": [], "backgroundColors": [], "scenes": [], "activities": [], "moods": [],
				 "peopleCount": null, "contentType": null, "saveIntent": null, "observedKeywords": []}"""))));

		assertThat(usable.searchState()).isEqualTo(SearchState.EMPTY);
		assertThat(usable.content().get("search").get("entities")).isEmpty();
	}

	@Test
	void searchStateIsDiscardedWhenDroppedValuesLeaveNone() {
		Outcome.Usable moods = usable(output((o) -> o.set("search", JSON.readTree("""
				{"entities": [], "backgroundColors": [], "scenes": [], "activities": [], "moods": ["ANGRY"],
				 "peopleCount": null, "contentType": null, "saveIntent": null, "observedKeywords": []}"""))));
		Outcome.Usable peopleCount = usable(output((o) -> o.set("search", JSON.readTree("""
				{"entities": [], "backgroundColors": [], "scenes": [], "activities": [], "moods": [],
				 "peopleCount": "SEVERAL", "contentType": null, "saveIntent": null, "observedKeywords": []}"""))));

		assertThat(moods.searchState()).isEqualTo(SearchState.DISCARDED);
		assertThat(moods.content().get("search").isObject()).isTrue();
		assertThat(peopleCount.searchState()).isEqualTo(SearchState.DISCARDED);
	}

	private static Outcome.Usable usable(JsonNode output) {
		Outcome outcome = ResultValidator.validate(output, REFERENCE);
		assertThat(outcome).isInstanceOf(Outcome.Usable.class);
		return (Outcome.Usable) outcome;
	}

	private static ObjectNode output(Consumer<ObjectNode> change) {
		ObjectNode output = (ObjectNode) JSON.readTree(VALID);
		change.accept(output);
		return output;
	}

	// Fills the list with copies of its first item, each changed by the given step.
	private static void repeat(ObjectNode output, String items, int count, ItemChange change) {
		ArrayNode list = (ArrayNode) output.at(items);
		JsonNode first = list.get(0);
		list.removeAll();
		for (int i = 0; i < count; i++) {
			ObjectNode item = (ObjectNode) first.deepCopy();
			change.apply(item, i);
			list.add(item);
		}
	}

	private static List<String> validations(JsonNode content, String items) {
		return content.get(items).valueStream().map((item) -> item.get("validation").stringValue()).toList();
	}

	@FunctionalInterface
	private interface ItemChange {

		void apply(ObjectNode item, int index);

	}

}
