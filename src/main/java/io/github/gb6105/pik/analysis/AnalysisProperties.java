package io.github.gb6105.pik.analysis;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 */
@ConfigurationProperties("pik.analysis")
record AnalysisProperties(boolean intakeEnabled, Intake intake, Grouping grouping, Manifest manifest) {

	/**
	 * Request contract: {@code vector.data} holds at most 12,000 base64 characters, that is 9,000 bytes or 2,250
	 * float32 values (design 1.1).
	 */
	static final int MAX_DIMS = 2250;

	/** Request contract for {@code vector.model} and {@code vector.modelVersion} (design 1.1). */
	static final int MAX_MODEL_LENGTH = 64;

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
	 * Visible ASCII, 0x21 to 0x7E without space, for setting strings that go into a manifest, so that no control
	 * character or lone surrogate reaches {@link CanonicalJson} and the UTF-8 bytes it hashes. Request values take
	 * the wider rule with space ({@link IntakeCommand#ASCII_WITH_SPACE}).
	 */
	private static boolean visibleAscii(@Nullable String value) {
		return value != null && !value.isEmpty() && value.chars().allMatch((c) -> c >= 0x21 && c <= 0x7E);
	}

}
