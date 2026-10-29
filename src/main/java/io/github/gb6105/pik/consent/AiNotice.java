package io.github.gb6105.pik.consent;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * The notice a consent is given on: what is sent to whom and why. The server serves it so that both apps show the same
 * text and it can change without an app release (ADR-0028 #3).
 *
 * @param draft set while facts are unconfirmed (country and retention, D-08 and P08); a draft only starts in the local
 * and test profiles. Not sent to clients
 * @param notes further statements: no backup of originals, what refusing keeps, what withdrawing does
 */
record AiNotice(int version, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) boolean draft, String title,
		List<Transfer> transfers, List<String> notes) {

	/**
	 * @param country where the recipient processes the data, for the cross-border transfer notice; required once the
	 * notice is final
	 * @param retention how long the recipient keeps the data; required once the notice is final
	 */
	record Transfer(String data, String recipient, String purpose, @Nullable String country,
			@Nullable String retention) {

		private boolean complete() {
			return StringUtils.hasText(country) && StringUtils.hasText(retention);
		}

	}

	static AiNotice read(JsonMapper jsonMapper, Resource resource, int expectedVersion, boolean developmentProfiles) {
		AiNotice notice;
		try (InputStream input = resource.getInputStream()) {
			notice = jsonMapper.readValue(input, AiNotice.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read the AI notice " + resource.getDescription(), ex);
		}
		Assert.state(notice.version() == expectedVersion,
				() -> "AI notice " + resource.getDescription() + " has version " + notice.version() + ", expected "
						+ expectedVersion);
		Assert.state(StringUtils.hasText(notice.title()) && notice.notes() != null && notice.transfers() != null
				&& !notice.transfers().isEmpty(), "AI notice needs a title, notes and transfers");
		Assert.state(notice.transfers().stream().allMatch(AiNotice::described),
				"Every AI notice transfer needs data, recipient and purpose");
		if (notice.draft()) {
			Assert.state(developmentProfiles, () -> "AI notice v" + expectedVersion
					+ " is a draft; only the local and test profiles may start with it");
		}
		else {
			Assert.state(notice.transfers().stream().allMatch(Transfer::complete),
					"A final AI notice needs the country and retention of every transfer");
		}
		return notice;
	}

	private static boolean described(Transfer transfer) {
		return StringUtils.hasText(transfer.data()) && StringUtils.hasText(transfer.recipient())
				&& StringUtils.hasText(transfer.purpose());
	}

}
