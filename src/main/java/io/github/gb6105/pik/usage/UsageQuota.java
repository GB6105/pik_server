package io.github.gb6105.pik.usage;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A principal's quota for one period. Rows are created on first use ({@link UsageRecords#lockQuota}) with the limit
 * configured at that time, so a later change of the setting leaves existing quotas as they were. The database keeps
 * {@code reserved + consumed <= limit}.
 */
@Entity
@IdClass(UsageQuota.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class UsageQuota {

	@Id
	private UUID principalId;

	@Id
	@Enumerated(EnumType.STRING)
	private QuotaKind quotaKind;

	@Id
	private String periodKey;

	private int limitCount;

	private int reservedCount;

	private int consumedCount;

	/** When the three-click subscription guidance was given (stage 6). */
	private @Nullable Instant guidedAt;

	private Instant createdAt;

	private Instant updatedAt;

	@Version
	private long version;

	boolean covers(int photos) {
		return limitCount - reservedCount - consumedCount >= photos;
	}

	void reserve(int photos, Instant now) {
		reservedCount += photos;
		updatedAt = now;
	}

	void consume(int photos, Instant now) {
		reservedCount -= photos;
		consumedCount += photos;
		updatedAt = now;
	}

	void release(int photos, Instant now) {
		reservedCount -= photos;
		updatedAt = now;
	}

	record Key(UUID principalId, QuotaKind quotaKind, String periodKey) implements Serializable {

		static Key trial(UUID principalId) {
			return new Key(principalId, QuotaKind.TRIAL, QuotaKind.ONCE);
		}

	}

}
