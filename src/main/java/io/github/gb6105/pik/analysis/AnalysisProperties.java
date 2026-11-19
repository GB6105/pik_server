package io.github.gb6105.pik.analysis;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
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
 * @param cleanup deleting input objects (design 2.2)
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
	 * Call manifest of stage 5-1: the model and the upload policy only; stage 5-2 adds prompt, schema and prices.
	 *
	 * @param model the Claude model, visible ASCII (no space)
	 * @param contentType the image type devices upload, visible ASCII (no space)
	 * @param maxBytes the largest upload
	 * @param maxLongEdgePx the longest image edge devices upload
	 */
	record Manifest(String model, String contentType, int maxBytes, int maxLongEdgePx) {

		Manifest {
			Assert.isTrue(visibleAscii(model),
					"manifest.model must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(visibleAscii(contentType),
					"manifest.content-type must have 1 or more visible ASCII characters (0x21 to 0x7E)");
			Assert.isTrue(maxBytes >= 1, "manifest.max-bytes must be positive");
			Assert.isTrue(maxLongEdgePx >= 1, "manifest.max-long-edge-px must be positive");
		}

	}

	/**
	 * @param urlTtl the longest validity of an upload URL; an attempt keeps min(issue + TTL, upload deadline)
	 * @param confirmGrace time after the URL expires in which an upload can still be confirmed
	 * @param maxAttempts the most upload attempts issued for one job
	 * @param inFlightMargin time after the URL expires until an upload started before it surely ended, before which a
	 * delete is not final (plan 7.4)
	 * @param bucket the input bucket ({@code PIK_ANALYSIS_BUCKET})
	 * @param region the AWS region of the bucket ({@code AWS_REGION})
	 * @param endpoint the S3Mock URL of the local and test profiles; {@code null} uses AWS S3
	 */
	record Upload(Duration urlTtl, Duration confirmGrace, int maxAttempts, Duration inFlightMargin, String bucket,
			String region, @Nullable URI endpoint) {

		Upload {
			Assert.isTrue(urlTtl.isPositive() && urlTtl.compareTo(MAX_URL_TTL) <= 0,
					"upload.url-ttl must be positive and at most 7 days");
			Assert.isTrue(!confirmGrace.isNegative(), "upload.confirm-grace must not be negative");
			Assert.isTrue(maxAttempts >= 1, "upload.max-attempts must be at least 1");
			Assert.isTrue(!inFlightMargin.isNegative(), "upload.in-flight-margin must not be negative");
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
	 */
	record Worker(boolean enabled, Duration pollInterval, int globalSlots, int principalSlots, int claimOversample,
			int claimRounds, Duration lease, Duration sendMargin) {

		Worker {
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
	 */
	record Claude(Duration requestTimeout, String keyVersion) {

		Claude {
			Assert.isTrue(requestTimeout.isPositive(), "claude.request-timeout must be positive");
			Assert.isTrue(visibleAscii(keyVersion),
					"claude.key-version must have 1 or more visible ASCII characters (0x21 to 0x7E)");
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
	 */
	record Budget(long dailyCostLimitMicros, int principalDailySends, int photoLifetimeSends,
			int deviceSendMultiplier) {

		Budget {
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
	 * @param deleteInterval the pause between two runs of the object delete batch
	 * @param overdueAlarm how long a delete may stay pending before an alarm
	 */
	record Cleanup(Duration deleteInterval, Duration overdueAlarm) {

		Cleanup {
			Assert.isTrue(deleteInterval.isPositive(), "cleanup.delete-interval must be positive");
			Assert.isTrue(overdueAlarm.isPositive(), "cleanup.overdue-alarm must be positive");
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
