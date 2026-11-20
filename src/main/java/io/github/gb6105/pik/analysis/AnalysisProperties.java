package io.github.gb6105.pik.analysis;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

/**
 * Analysis settings. The range checks run when the settings are bound, so wrong values fail startup.
 *
 * @param intakeEnabled whether {@code POST /analysis-jobs} accepts requests (design 4.4)
 * @param intake deadlines fixed on new jobs
 * @param grouping grouping rule v1 and vector checks (design 1.3, 1.4; P05)
 * @param manifest the call manifest fixed on new jobs (design 2.5)
 * @param upload presigned uploads and the input bucket (design 2.2, 2.3)
 * @param worker claiming and running jobs (design 2.4)
 * @param claude the Claude call (design 2.2, 2.5)
 * @param sendRate the global Claude send rate (design 2.7)
 * @param budget send and cost budgets (design 2.7)
 * @param input blocking photos whose input keeps failing (design 2.7)
 * @param inputRate upload rate limits (design 2.7)
 * @param circuit the provider circuits of {@code ai_gate} (design 2.5, plan 5.10)
 * @param cleanup the upload batches: recovery, object deletes and their reconciliation, input rate windows (plan 7)
 * @param poll the polling hints of the status responses (design 2.3)
 */
@ConfigurationProperties("pik.analysis")
record AnalysisProperties(boolean intakeEnabled, Intake intake, Grouping grouping, Manifest manifest, Upload upload,
		Worker worker, Claude claude, SendRate sendRate, Budget budget, Input input, InputRate inputRate,
		Circuit circuit, Cleanup cleanup, Poll poll) {

	/**
	 * Request contract: {@code vector.data} holds at most 12,000 base64 characters, that is 9,000 bytes or 2,250
	 * float32 values (design 1.1).
	 */
	static final int MAX_DIMS = 2250;

	/** Request contract for {@code vector.model} and {@code vector.modelVersion} (design 1.1). */
	static final int MAX_MODEL_LENGTH = 64;

	// Characters and length (3 to 63) of S3 bucket names; S3 checks the other rules when the bucket is created.
	private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

	private static final Pattern REGION = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

	// The longest validity of an S3 presigned URL.
	private static final Duration MAX_URL_TTL = Duration.ofDays(7);

	// The validity an attempt needs at issue and replay, and 50 seconds for the commit and the signing after it.
	private static final Duration MIN_URL_TTL = UploadSigning.MIN_URL_VALIDITY.plusSeconds(50);

	// The issued_count range of analysis_job (V6).
	private static final int MAX_UPLOAD_ATTEMPTS = 5;

	AnalysisProperties {
		// A send that starts with the margin left ends, timeout included, before the lease is over (design 2.2).
		Assert.isTrue(worker.lease().compareTo(worker.sendMargin()) > 0,
				"worker.lease must be longer than worker.send-margin");
		Assert.isTrue(worker.sendMargin().compareTo(claude.requestTimeout()) > 0,
				"worker.send-margin must be longer than claude.request-timeout");
	}

	/**
	 * @param uploadDeadline time from intake until the image must be uploaded (design 2.2)
	 * @param processingDeadline time from intake until the analysis must be done (design 2.2)
	 * @param maxBodySize the largest request body, checked before it is parsed (design 1.1, decision Q16)
	 */
	record Intake(Duration uploadDeadline, Duration processingDeadline, DataSize maxBodySize) {

		Intake {
			Assert.isTrue(uploadDeadline.isPositive(), "intake.upload-deadline must be positive");
			Assert.isTrue(processingDeadline.isPositive(), "intake.processing-deadline must be positive");
			// The body is read into one array with one byte more than the limit.
			Assert.isTrue(maxBodySize != null && maxBodySize.toBytes() >= 1
					&& maxBodySize.toBytes() < Integer.MAX_VALUE - 8,
					"intake.max-body-size must be between 1 byte and 2 GB");
		}

	}

	/**
	 * @param enabled whether photos are grouped. Off: vectors are only checked for structure and every photo is single
	 * @param window W, the largest time span between the photos of a group
	 * @param maxGroupSize the most photos in a group
	 * @param zeroNormEpsilon ε; vectors whose norm is not above it are ignored
	 * @param models registered vector models; must not be empty when grouping is on
	 */
	record Grouping(boolean enabled, Duration window, int maxGroupSize, double zeroNormEpsilon,
			@DefaultValue List<Model> models) {

		Grouping {
			Assert.isTrue(window.isPositive(), "grouping.window must be positive");
			Assert.isTrue(maxGroupSize >= 1, "grouping.max-group-size must be at least 1");
			Assert.isTrue(Double.isFinite(zeroNormEpsilon) && zeroNormEpsilon > 0,
					"grouping.zero-norm-epsilon must be positive");
			models = List.copyOf(models);
			Assert.isTrue(!enabled || !models.isEmpty(), "grouping.models must not be empty when grouping is enabled");
			Set<List<String>> pairs = new HashSet<>();
			for (Model model : models) {
				Assert.isTrue(pairs.add(List.of(model.model(), model.modelVersion())),
						() -> "grouping.models registers model " + model.model() + " version " + model.modelVersion()
								+ " more than once");
			}
		}

		@Nullable Model registered(String model, String modelVersion) {
			for (Model registered : models) {
				if (registered.model().equals(model) && registered.modelVersion().equals(modelVersion)) {
					return registered;
				}
			}
			return null;
		}

	}

	/**
	 * A vector model the devices send.
	 *
	 * @param model the model ID, 1 to 64 visible ASCII characters (no space)
	 * @param modelVersion the model version, 1 to 64 visible ASCII characters (no space)
	 * @param dims the number of float32 values of its vectors
	 * @param threshold θ, the cosine similarity that every pair of photos in a group must reach. Below 1, since the
	 * double cosine of equal vectors can be just below 1
	 */
	record Model(String model, String modelVersion, int dims, double threshold) {

		Model {
			Assert.isTrue(visibleAscii(model) && model.length() <= MAX_MODEL_LENGTH,
					"grouping.models[].model must have 1 to " + MAX_MODEL_LENGTH
							+ " visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(visibleAscii(modelVersion) && modelVersion.length() <= MAX_MODEL_LENGTH,
					"grouping.models[].model-version must have 1 to " + MAX_MODEL_LENGTH
							+ " visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(dims >= 1 && dims <= MAX_DIMS, "grouping.models[].dims must be between 1 and " + MAX_DIMS);
			Assert.isTrue(threshold > 0 && threshold < 1, "grouping.models[].threshold must be in (0, 1)");
		}

	}

	/**
	 * Settings of the call manifest fixed on new jobs (design 2.5, plan 6.1). {@link CallManifests} adds the hashes of
	 * the prompt and schema resources these settings name; the values are settled with the D-07 measurements.
	 *
	 * @param model the Claude model, visible ASCII (no space)
	 * @param contentType the image type devices upload, visible ASCII (no space)
	 * @param maxBytes the largest upload
	 * @param maxLongEdgePx the longest image edge devices upload
	 * @param maxPixels the most pixels of an input image, checked in its header before it is decoded
	 * @param prompt the prompt resource directory {@code analysis/prompt/<prompt>/}, such as {@code v1}
	 * @param schemaVersion the result schema version, resource {@code analysis/schema/v<schemaVersion>.json}
	 * @param thinking {@code adaptive}, or {@code disabled} (only with effort {@code low}, {@code medium} or
	 * {@code high})
	 * @param effort the {@code output_config.effort} of the request
	 * @param maxTokens the {@code max_tokens} of the request; thinking tokens count toward it
	 * @param imageTokenCap the most input tokens of the image
	 * @param promptTokenCap the most input tokens of the rest of the request: system prompt, the tokens the output
	 * schema adds and the instruction text
	 * @param cache prompt caching of the system prompt
	 * @param outputLocale the BCP 47 language tag of the labels the model writes
	 * @param price the prices the maximum cost of a call is computed with
	 * @param previousIds IDs of earlier manifests that jobs fixed before a change still run with; not part of the
	 * content. Their resources stay in place until those jobs have ended
	 */
	record Manifest(String model, String contentType, int maxBytes, int maxLongEdgePx, long maxPixels, String prompt,
			int schemaVersion, String thinking, String effort, int maxTokens, int imageTokenCap, int promptTokenCap,
			Cache cache, String outputLocale, Price price, @DefaultValue List<String> previousIds) {

		/** {@code thinking} values of the request. */
		static final Set<String> THINKING = Set.of("adaptive", "disabled");

		/** {@code output_config.effort} values of the request. */
		static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

		// The effort values thinking may be disabled with (Claude Haiku 5.5 rejects disabled at xhigh and max).
		private static final Set<String> EFFORTS_WITHOUT_THINKING = Set.of("low", "medium", "high");

		// The largest max_tokens of the current Claude models (128K output).
		private static final int MAX_TOKENS = 128_000;

		private static final Pattern PROMPT = Pattern.compile("v[1-9][0-9]{0,8}");

		private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

		Manifest {
			Assert.isTrue(visibleAscii(model),
					"manifest.model must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(visibleAscii(contentType),
					"manifest.content-type must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(maxBytes >= 1, "manifest.max-bytes must be positive");
			Assert.isTrue(maxLongEdgePx >= 1, "manifest.max-long-edge-px must be positive");
			Assert.isTrue(maxPixels >= 1 && maxPixels <= (long) maxLongEdgePx * maxLongEdgePx,
					"manifest.max-pixels must be between 1 and max-long-edge-px squared");
			Assert.isTrue(prompt != null && PROMPT.matcher(prompt).matches(),
					"manifest.prompt must name a prompt resource directory: v followed by a version number");
			Assert.isTrue(schemaVersion >= 1, "manifest.schema-version must be at least 1");
			Assert.isTrue(THINKING.contains(thinking), "manifest.thinking must be adaptive or disabled");
			Assert.isTrue(EFFORTS.contains(effort), "manifest.effort must be low, medium, high, xhigh or max");
			Assert.isTrue(!"disabled".equals(thinking) || EFFORTS_WITHOUT_THINKING.contains(effort),
					"manifest.thinking disabled needs manifest.effort low, medium or high");
			Assert.isTrue(maxTokens >= 1 && maxTokens <= MAX_TOKENS,
					"manifest.max-tokens must be between 1 and " + MAX_TOKENS);
			Assert.isTrue(imageTokenCap >= 1, "manifest.image-token-cap must be positive");
			Assert.isTrue(promptTokenCap >= 1, "manifest.prompt-token-cap must be positive");
			Assert.isTrue(visibleAscii(outputLocale)
					&& Locale.forLanguageTag(outputLocale).toLanguageTag().equals(outputLocale),
					"manifest.output-locale must be a well-formed BCP 47 language tag, such as ko-KR");
			previousIds = List.copyOf(previousIds);
			Assert.isTrue(previousIds.stream().allMatch((id) -> SHA256_HEX.matcher(id).matches()),
					"manifest.previous-ids must be manifest IDs: 64 lowercase hexadecimal characters");
			Assert.isTrue(Set.copyOf(previousIds).size() == previousIds.size(),
					"manifest.previous-ids must not name a manifest more than once");
			try {
				maxCostMicros(promptTokenCap, imageTokenCap, maxTokens, price);
			}
			catch (ArithmeticException ex) {
				// The message says all; like the other checks, it is the root cause of the startup failure.
				throw new IllegalArgumentException("manifest token caps and prices give a maximum cost beyond 2^63");
			}
		}

		/**
		 * The most a call can cost, in micro dollars, rounded up: prompt tokens at the cache write price, image tokens
		 * at the input price, {@code max_tokens} at the output price (plan 6.1). Thinking tokens are output tokens
		 * inside {@code max_tokens}.
		 */
		long maxCostMicros() {
			return maxCostMicros(promptTokenCap, imageTokenCap, maxTokens, price);
		}

		private static long maxCostMicros(int promptTokenCap, int imageTokenCap, int maxTokens, Price price) {
			long microsPerMillion = Math.addExact(
					Math.addExact(Math.multiplyExact((long) promptTokenCap, price.cacheWriteMicrosPerMtok()),
							Math.multiplyExact((long) imageTokenCap, price.inputMicrosPerMtok())),
					Math.multiplyExact((long) maxTokens, price.outputMicrosPerMtok()));
			return Math.ceilDiv(microsPerMillion, 1_000_000L);
		}

	}

	/**
	 * @param enabled whether the system prompt carries {@code cache_control}; the cached prefix is the system prompt
	 * only (plan 6.3)
	 * @param ttl the cache lifetime: 5 minutes or 1 hour, the two the API offers. The cache write price must be the
	 * one of this lifetime
	 */
	record Cache(boolean enabled, Duration ttl) {

		Cache {
			Assert.isTrue(Duration.ofMinutes(5).equals(ttl) || Duration.ofHours(1).equals(ttl),
					"manifest.cache.ttl must be PT5M or PT1H");
		}

	}

	/**
	 * Prices in micro dollars per million tokens.
	 *
	 * @param version a label of the price table, visible ASCII (no space)
	 * @param inputMicrosPerMtok input tokens
	 * @param cacheWriteMicrosPerMtok input tokens written to the prompt cache
	 * @param cacheReadMicrosPerMtok input tokens read from the prompt cache
	 * @param outputMicrosPerMtok output tokens, thinking included
	 */
	record Price(String version, long inputMicrosPerMtok, long cacheWriteMicrosPerMtok, long cacheReadMicrosPerMtok,
			long outputMicrosPerMtok) {

		Price {
			Assert.isTrue(visibleAscii(version),
					"manifest.price.version must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(inputMicrosPerMtok >= 1, "manifest.price.input-micros-per-mtok must be positive");
			Assert.isTrue(cacheWriteMicrosPerMtok >= 1, "manifest.price.cache-write-micros-per-mtok must be positive");
			Assert.isTrue(cacheReadMicrosPerMtok >= 1, "manifest.price.cache-read-micros-per-mtok must be positive");
			Assert.isTrue(outputMicrosPerMtok >= 1, "manifest.price.output-micros-per-mtok must be positive");
		}

	}

	/**
	 * @param urlTtl the longest validity of an upload URL; an attempt keeps min(issue + TTL, upload deadline)
	 * @param confirmGrace time after the URL expires in which an upload can still be confirmed
	 * @param maxAttempts the most upload attempts issued for one job, at most 5 ({@code issued_count} of V6)
	 * @param inFlightMargin time after the URL expires until an upload started before it surely ended, before which a
	 * delete is not final (plan 7.4)
	 * @param notFoundRetryAfter the {@code Retry-After} of a confirmation that finds no object yet
	 * @param storageCallTimeout the limit of one storage call (HEAD, GET, DELETE) with the SDK retries
	 * @param storageAttemptTimeout the limit of each HTTP attempt of a storage call
	 * @param bucket the input bucket ({@code PIK_ANALYSIS_BUCKET})
	 * @param region the AWS region of the bucket ({@code AWS_REGION})
	 * @param endpoint the S3Mock URL of the local and test profiles; {@code null} uses AWS S3
	 */
	record Upload(Duration urlTtl, Duration confirmGrace, int maxAttempts, Duration inFlightMargin,
			Duration notFoundRetryAfter, Duration storageCallTimeout, Duration storageAttemptTimeout, String bucket,
			String region, @Nullable URI endpoint) {

		Upload {
			Assert.isTrue(urlTtl.compareTo(MIN_URL_TTL) >= 0 && urlTtl.compareTo(MAX_URL_TTL) <= 0,
					"upload.url-ttl must be between 1 minute (10 s replay minimum + 50 s signing margin) and 7 days");
			Assert.isTrue(!confirmGrace.isNegative(), "upload.confirm-grace must not be negative");
			Assert.isTrue(maxAttempts >= 1 && maxAttempts <= MAX_UPLOAD_ATTEMPTS,
					"upload.max-attempts must be between 1 and 5");
			Assert.isTrue(!inFlightMargin.isNegative(), "upload.in-flight-margin must not be negative");
			Assert.isTrue(notFoundRetryAfter.isPositive(), "upload.not-found-retry-after must be positive");
			Assert.isTrue(storageAttemptTimeout.isPositive(), "upload.storage-attempt-timeout must be positive");
			Assert.isTrue(storageCallTimeout.compareTo(storageAttemptTimeout) >= 0,
					"upload.storage-call-timeout must not be shorter than upload.storage-attempt-timeout");
			Assert.isTrue(bucket != null && BUCKET.matcher(bucket).matches(),
					"upload.bucket must be an S3 bucket name (PIK_ANALYSIS_BUCKET)");
			Assert.isTrue(region != null && REGION.matcher(region).matches(),
					"upload.region must be an AWS region name (AWS_REGION)");
			Assert.isTrue(endpoint == null || (("http".equals(endpoint.getScheme())
					|| "https".equals(endpoint.getScheme())) && endpoint.getHost() != null),
					"upload.endpoint must be an http or https URL");
		}

	}

	/**
	 * @param enabled whether this instance claims and runs jobs
	 * @param pollInterval the pause between two claim rounds of an instance
	 * @param globalSlots the most jobs running at once over all instances
	 * @param principalSlots the most jobs of one principal running at once
	 * @param claimOversample the candidates read per free slot, so that locked rows can be skipped
	 * @param claimRounds the candidate queries of one claim while locked rows leave slots free (plan 5.2)
	 * @param lease how long a claimed job stays with its worker
	 * @param sendMargin the lease that must be left for a send to be permitted
	 * @param initialDelay the time after startup before the first claim round
	 * @param retryBackoff the delay of a job sent back to {@code READY} without a delay of its own: storage or send
	 * rate unavailable, a call not sent, a configuration error, an unknown outcome retried, a closed gate without a
	 * deadline
	 * @param retryJitter the share by which such delays and the circuit backoffs grow at random
	 * @param completionRetries the repeats of a completion transaction that failed on a transient database error,
	 * while the lease lasts (plan 5.6)
	 */
	record Worker(boolean enabled, Duration pollInterval, int globalSlots, int principalSlots, int claimOversample,
			int claimRounds, Duration lease, Duration sendMargin, Duration initialDelay, Duration retryBackoff,
			double retryJitter, int completionRetries) {

		Worker {
			Assert.isTrue(!initialDelay.isNegative(), "worker.initial-delay must not be negative");
			Assert.isTrue(retryBackoff.isPositive(), "worker.retry-backoff must be positive");
			Assert.isTrue(retryJitter >= 0 && retryJitter < 1, "worker.retry-jitter must be in [0, 1)");
			Assert.isTrue(completionRetries >= 0, "worker.completion-retries must not be negative");
			Assert.isTrue(pollInterval.isPositive(), "worker.poll-interval must be positive");
			Assert.isTrue(globalSlots >= 1, "worker.global-slots must be at least 1");
			Assert.isTrue(principalSlots >= 1, "worker.principal-slots must be at least 1");
			Assert.isTrue(claimOversample >= 1, "worker.claim-oversample must be at least 1");
			Assert.isTrue(claimRounds >= 1, "worker.claim-rounds must be at least 1");
			Assert.isTrue(lease.isPositive(), "worker.lease must be positive");
			Assert.isTrue(sendMargin.isPositive(), "worker.send-margin must be positive");
		}

	}

	/**
	 * @param requestTimeout the limit of one whole Claude request, without retries
	 * @param keyVersion a label, not a secret, that operators change when they replace the API key or release the
	 * configuration circuit; visible ASCII (no space)
	 * @param apiKey the Anthropic API key ({@code ANTHROPIC_API_KEY}); blank when not set. Required when the worker is
	 * enabled
	 * @param baseUrl the fake server URL of the local and test profiles; {@code null} uses the Anthropic API
	 */
	record Claude(Duration requestTimeout, String keyVersion, @Nullable String apiKey, @Nullable URI baseUrl) {

		Claude {
			Assert.isTrue(requestTimeout.isPositive(), "claude.request-timeout must be positive");
			Assert.isTrue(visibleAscii(keyVersion),
					"claude.key-version must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(baseUrl == null || (("http".equals(baseUrl.getScheme())
					|| "https".equals(baseUrl.getScheme())) && baseUrl.getHost() != null),
					"claude.base-url must be an http or https URL");
		}

		boolean hasApiKey() {
			return apiKey != null && !apiKey.isBlank();
		}

		// The API key must not reach logs or assertion messages.
		@Override
		public String toString() {
			return "Claude[requestTimeout=" + requestTimeout + ", keyVersion=" + keyVersion + ", apiKey="
					+ (hasApiKey() ? "(set)" : "(not set)") + ", baseUrl=" + baseUrl + "]";
		}

	}

	/**
	 * @param globalPerSecond the most Claude sends per second over all instances
	 * @param maxInstances the most instances that run workers; each instance sends at most
	 * {@code globalPerSecond / maxInstances}
	 */
	record SendRate(double globalPerSecond, int maxInstances) {

		SendRate {
			Assert.isTrue(Double.isFinite(globalPerSecond) && globalPerSecond > 0,
					"send-rate.global-per-second must be positive");
			Assert.isTrue(maxInstances >= 1, "send-rate.max-instances must be at least 1");
		}

		double perInstancePerSecond() {
			return globalPerSecond / maxInstances;
		}

	}

	/**
	 * @param dailyCostLimitMicros the most Claude cost of one KST day over all principals, in micro dollars
	 * @param principalDailySends the most sends of one principal in one KST day
	 * @param photoLifetimeSends the most risky sends of one photo
	 * @param deviceSendMultiplier the sends of a trial device, as a multiple of the trial limit
	 * @param reservedRetry how long the budget gate stays open when only reserved costs fill the day (design 2.7)
	 */
	record Budget(long dailyCostLimitMicros, int principalDailySends, int photoLifetimeSends,
			int deviceSendMultiplier, Duration reservedRetry) {

		Budget {
			Assert.isTrue(reservedRetry.isPositive(), "budget.reserved-retry must be positive");
			Assert.isTrue(dailyCostLimitMicros >= 0, "budget.daily-cost-limit-micros must not be negative");
			Assert.isTrue(principalDailySends >= 1, "budget.principal-daily-sends must be at least 1");
			Assert.isTrue(photoLifetimeSends >= 1, "budget.photo-lifetime-sends must be at least 1");
			Assert.isTrue(deviceSendMultiplier >= 1, "budget.device-send-multiplier must be at least 1");
		}

	}

	/**
	 * @param invalidThreshold the invalid inputs of one photo after which it is blocked
	 * @param blockDuration how long such a photo is blocked
	 */
	record Input(int invalidThreshold, Duration blockDuration) {

		Input {
			Assert.isTrue(invalidThreshold >= 1, "input.invalid-threshold must be at least 1");
			Assert.isTrue(blockDuration.isPositive(), "input.block-duration must be positive");
		}

	}

	/**
	 * Limits of the upload attempts issued in one hour window. Bytes count each attempt as the largest upload of its
	 * manifest.
	 *
	 * @param principalItemsPerHour the most attempts issued to one principal
	 * @param principalBytesPerHour the most bytes of those attempts
	 * @param trialItemsPerHour the most attempts issued to the jobs of one trial device record
	 * @param trialBytesPerHour the most bytes of those attempts
	 */
	record InputRate(int principalItemsPerHour, DataSize principalBytesPerHour, int trialItemsPerHour,
			DataSize trialBytesPerHour) {

		InputRate {
			Assert.isTrue(principalItemsPerHour >= 1, "input-rate.principal-items-per-hour must be at least 1");
			Assert.isTrue(principalBytesPerHour != null && principalBytesPerHour.toBytes() >= 1,
					"input-rate.principal-bytes-per-hour must be positive");
			Assert.isTrue(trialItemsPerHour >= 1, "input-rate.trial-items-per-hour must be at least 1");
			Assert.isTrue(trialBytesPerHour != null && trialBytesPerHour.toBytes() >= 1,
					"input-rate.trial-bytes-per-hour must be positive");
		}

	}

	/**
	 * @param badRequestRatio the share of 400 answers in the window that opens the configuration circuit
	 * @param window the time span of that share
	 * @param minSamples the fewest answers in the window before the share counts
	 * @param transientThreshold the 429, 529 and 5xx answers in a row that open the transient circuit
	 * @param transientBaseBackoff the first half-open delay of the transient circuit
	 * @param transientMaxBackoff the longest half-open delay of the transient circuit
	 * @param probeExclusion how long a job whose probe died is not picked as a probe again
	 * @param halfOpenAlarm how long a circuit may stay half-open before an alarm
	 */
	record Circuit(double badRequestRatio, Duration window, int minSamples, int transientThreshold,
			Duration transientBaseBackoff, Duration transientMaxBackoff, Duration probeExclusion,
			Duration halfOpenAlarm) {

		Circuit {
			Assert.isTrue(badRequestRatio > 0 && badRequestRatio <= 1, "circuit.bad-request-ratio must be in (0, 1]");
			Assert.isTrue(window.isPositive(), "circuit.window must be positive");
			Assert.isTrue(minSamples >= 1, "circuit.min-samples must be at least 1");
			Assert.isTrue(transientThreshold >= 1, "circuit.transient-threshold must be at least 1");
			Assert.isTrue(transientBaseBackoff.isPositive(), "circuit.transient-base-backoff must be positive");
			Assert.isTrue(transientMaxBackoff.compareTo(transientBaseBackoff) >= 0,
					"circuit.transient-max-backoff must not be shorter than circuit.transient-base-backoff");
			Assert.isTrue(probeExclusion.isPositive(), "circuit.probe-exclusion must be positive");
			Assert.isTrue(halfOpenAlarm.isPositive(), "circuit.half-open-alarm must be positive");
		}

	}

	/**
	 * @param initialDelay the time after startup before the first run of each upload batch
	 * @param recoveryInterval the pause between two runs of the upload recovery batch (plan 7.1)
	 * @param deleteInterval the pause between two runs of the object delete batch
	 * @param reconcileInterval the pause between two runs of the delete reconciliation (plan 7.4)
	 * @param overdueAlarm how long a delete may stay pending before an alarm
	 * @param rateWindowInterval the pause between two runs of the input rate window cleanup (plan 7.5)
	 * @param batchSize the most rows a batch reads or deletes at once
	 */
	record Cleanup(Duration initialDelay, Duration recoveryInterval, Duration deleteInterval,
			Duration reconcileInterval, Duration overdueAlarm, Duration rateWindowInterval, int batchSize) {

		Cleanup {
			Assert.isTrue(!initialDelay.isNegative(), "cleanup.initial-delay must not be negative");
			Assert.isTrue(recoveryInterval.isPositive(), "cleanup.recovery-interval must be positive");
			Assert.isTrue(deleteInterval.isPositive(), "cleanup.delete-interval must be positive");
			Assert.isTrue(reconcileInterval.isPositive(), "cleanup.reconcile-interval must be positive");
			Assert.isTrue(overdueAlarm.isPositive(), "cleanup.overdue-alarm must be positive");
			Assert.isTrue(rateWindowInterval.isPositive(), "cleanup.rate-window-interval must be positive");
			Assert.isTrue(batchSize >= 1, "cleanup.batch-size must be at least 1");
		}

	}

	/**
	 * @param afterRunning the polling delay suggested while jobs run
	 * @param jitter the share by which the suggested delay varies, fixed per request
	 */
	record Poll(Duration afterRunning, double jitter) {

		Poll {
			Assert.isTrue(afterRunning.isPositive(), "poll.after-running must be positive");
			Assert.isTrue(jitter >= 0 && jitter < 1, "poll.jitter must be in [0, 1)");
		}

	}

	/**
	 * Visible ASCII, 0x21 to 0x7E without space, for setting strings that go into a manifest, so that no control
	 * character or lone surrogate reaches {@link CanonicalJson} and the UTF-8 bytes it hashes. Request values take
	 * the wider rule with space ({@link IntakeCommand#ASCII_WITH_SPACE}).
	 */
	private static boolean visibleAscii(@Nullable String value) {
		return value != null && !value.isEmpty() && value.chars().allMatch((c) -> c >= 0x21 && c <= 0x7E);
	}

}
