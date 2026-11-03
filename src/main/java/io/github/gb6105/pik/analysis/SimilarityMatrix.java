package io.github.gb6105.pik.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.Assert;

/**
 * Cosine similarities between the valid vectors of one request that share (model, modelVersion), computed once
 * outside the transaction and reused when the transaction groups a subset again. A similarity is the sum of the
 * products of the normalized double values in index order, so it does not depend on the order of the photos
 * (plan 6.5).
 */
final class SimilarityMatrix {

	private final Map<UUID, Integer> indexes;

	private final List<VectorValidator.Valid> vectors;

	private final double[] similarities;

	private SimilarityMatrix(Map<UUID, Integer> indexes, List<VectorValidator.Valid> vectors, double[] similarities) {
		this.indexes = indexes;
		this.vectors = vectors;
		this.similarities = similarities;
	}

	/**
	 * @param photos the photos of one request; those without a valid vector are left out
	 */
	static SimilarityMatrix of(List<GroupingCandidate> photos) {
		Map<UUID, Integer> indexes = new HashMap<>();
		List<VectorValidator.Valid> vectors = new ArrayList<>();
		List<double[]> units = new ArrayList<>();
		for (GroupingCandidate photo : photos) {
			if (photo.vector() != null) {
				Assert.isTrue(indexes.put(photo.photoId(), vectors.size()) == null, "photo IDs must be unique");
				vectors.add(photo.vector());
				units.add(photo.vector().unit());
			}
		}
		int size = vectors.size();
		double[] similarities = new double[size * size];
		for (int i = 0; i < size; i++) {
			for (int j = i + 1; j < size; j++) {
				if (sameModel(vectors.get(i), vectors.get(j))) {
					double similarity = dot(units.get(i), units.get(j));
					similarities[i * size + j] = similarity;
					similarities[j * size + i] = similarity;
				}
			}
		}
		return new SimilarityMatrix(indexes, vectors, similarities);
	}

	/**
	 * @return the cosine similarity of two different photos whose vectors share (model, modelVersion)
	 */
	double between(UUID photoId, UUID otherPhotoId) {
		Integer i = indexes.get(photoId);
		Integer j = indexes.get(otherPhotoId);
		Assert.isTrue(i != null && j != null, "both photos must have a valid vector");
		Assert.isTrue(!i.equals(j), "the photos must differ");
		Assert.isTrue(sameModel(vectors.get(i), vectors.get(j)), "the vectors must share model and version");
		return similarities[i * vectors.size() + j];
	}

	private static boolean sameModel(VectorValidator.Valid vector, VectorValidator.Valid other) {
		return vector.model().equals(other.model()) && vector.modelVersion().equals(other.modelVersion())
				&& vector.dims() == other.dims();
	}

	private static double dot(double[] unit, double[] other) {
		double sum = 0;
		for (int k = 0; k < unit.length; k++) {
			sum += unit[k] * other[k];
		}
		return sum;
	}

}
