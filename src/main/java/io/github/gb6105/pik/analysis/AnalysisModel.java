package io.github.gb6105.pik.analysis;

/**
 * The Claude call of one job (plan 3.2, 6.2). An implementation sends the request at most once, without retries, and
 * reports provider and transport failures in the {@link ModelReply} instead of throwing, so that the settlement table
 * can be applied to every outcome (design 2.5, plan 6.4).
 */
interface AnalysisModel {

	/**
	 * @param manifest the call manifest of the job
	 * @param jpeg the confirmed input image
	 * @return what the call observed
	 */
	ModelReply call(CallManifest manifest, byte[] jpeg);

}
