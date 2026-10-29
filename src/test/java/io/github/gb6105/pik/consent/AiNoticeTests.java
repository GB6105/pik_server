package io.github.gb6105.pik.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class AiNoticeTests {

	static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void bundledNoticeIsADraftOfEveryTransfer() {
		AiNotice notice = AiNotice.read(JSON, new ClassPathResource("consent/ai-notice-v1.json"), 1, true);

		assertThat(notice.version()).isEqualTo(1);
		assertThat(notice.draft()).isTrue();
		assertThat(notice.transfers()).extracting(AiNotice.Transfer::recipient)
			.containsExactly("Anthropic (Claude)", "TypeSafe (Jev)", "Anthropic (Claude)", "Google (Places)");
		assertThat(notice.notes()).hasSize(3);
	}

	@Test
	void draftOnlyStartsInDevelopmentProfiles() {
		assertThatIllegalStateException()
			.isThrownBy(() -> AiNotice.read(JSON, new ClassPathResource("consent/ai-notice-v1.json"), 1, false))
			.withMessageContaining("draft");

		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");
		MockEnvironment test = new MockEnvironment();
		test.setActiveProfiles("test");
		ConsentProperties properties = new ConsentProperties(1, 1);
		assertThatIllegalStateException().isThrownBy(
				() -> new ConsentConfig().aiNotice(properties, JSON, new DefaultResourceLoader(), production));
		assertThat(new ConsentConfig().aiNotice(properties, JSON, new DefaultResourceLoader(), test).version())
			.isEqualTo(1);
	}

	@Test
	void finalNoticeNeedsCountryAndRetentionOfEveryTransfer() {
		assertThatIllegalStateException()
			.isThrownBy(() -> AiNotice.read(JSON, notice(false, "\"미국\"", "null"), 1, false))
			.withMessageContaining("country and retention");

		AiNotice notice = AiNotice.read(JSON, notice(false, "\"미국\"", "\"30일\""), 1, false);
		assertThat(notice.draft()).isFalse();
	}

	@Test
	void noticeMustMatchTheConfiguredVersion() {
		assertThatIllegalStateException().isThrownBy(() -> AiNotice.read(JSON, notice(true, "null", "null"), 2, true))
			.withMessageContaining("expected 2");
		assertThatIllegalStateException()
			.isThrownBy(() -> AiNotice.read(JSON, new ClassPathResource("consent/ai-notice-v99.json"), 99, true))
			.withMessageContaining("Cannot read");
	}

	@Test
	void noticeNeedsTitleAndDescribedTransfers() {
		assertThatIllegalStateException().isThrownBy(() -> AiNotice.read(JSON, resource("""
				{"version": 1, "draft": true, "title": " ", "notes": [],
				 "transfers": [{"data": "d", "recipient": "r", "purpose": "p"}]}
				"""), 1, true));
		assertThatIllegalStateException().isThrownBy(() -> AiNotice.read(JSON, resource("""
				{"version": 1, "draft": true, "title": "t", "notes": [], "transfers": [{"data": "d", "purpose": "p"}]}
				"""), 1, true)).withMessageContaining("recipient");
		assertThatIllegalStateException().isThrownBy(() -> AiNotice.read(JSON, resource("""
				{"version": 1, "draft": true, "title": "t", "notes": [], "transfers": []}
				"""), 1, true));
	}

	@Test
	void draftFlagIsNotSentToClients() {
		AiNotice notice = AiNotice.read(JSON, notice(true, "null", "null"), 1, true);

		assertThat(JSON.writeValueAsString(notice)).doesNotContain("draft").contains("\"title\"");
	}

	private static Resource notice(boolean draft, String country, String retention) {
		return resource("""
				{"version": 1, "draft": %s, "title": "t", "notes": ["n"],
				 "transfers": [{"data": "d", "recipient": "r", "purpose": "p", "country": %s, "retention": %s}]}
				""".formatted(draft, country, retention));
	}

	private static Resource resource(String json) {
		return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
	}

}
