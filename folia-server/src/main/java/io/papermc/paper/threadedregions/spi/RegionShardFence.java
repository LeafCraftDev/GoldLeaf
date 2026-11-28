package io.papermc.paper.threadedregions.spi;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate shard-fence SPI for {@code ThreadedRegionizer}.
 *
 * <p>Allows multi-node region-sharding engines to prevent a local region from merging across a
 * section boundary leased to a remote shard, keeping the remote side as a read-only halo.
 * When no fence is registered, {@link #get()} returns {@code null} with zero allocation overhead.</p>
 */
@FunctionalInterface
public interface RegionShardFence {

    /**
     * Immutable lease descriptor for a region-grid section owned by a shard node.
     *
     * @param worldName       name of the world owning the section
     * @param sectionX        region-grid section X coordinate
     * @param sectionZ        region-grid section Z coordinate
     * @param ownerShardId    identifier of the shard holding the lease
     * @param leaseEpoch      monotonically increasing lease epoch
     * @param expiresAtMillis expiration timestamp in epoch milliseconds, or {@code <= 0} if non-expiring
     */
    record RegionLeaseRecord(
        String worldName,
        int sectionX,
        int sectionZ,
        String ownerShardId,
        long leaseEpoch,
        long expiresAtMillis
    ) {
        public RegionLeaseRecord {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(ownerShardId, "ownerShardId");
            if (ownerShardId.isBlank()) {
                throw new IllegalArgumentException("ownerShardId must not be blank");
            }
            if (leaseEpoch < 0L) {
                throw new IllegalArgumentException("leaseEpoch must be >= 0, got " + leaseEpoch);
            }
        }

        public boolean isExpiredAt(final long nowMillis) {
            return this.expiresAtMillis > 0L && nowMillis >= this.expiresAtMillis;
        }
    }

    /**
     * Returns {@code true} if a merge or section-expansion step between {@code (sectionX, sectionZ)}
     * and {@code (neighbourX, neighbourZ)} in {@code world} must be fenced (blocked) because the
     * boundary crosses into a remote-leased shard.
     *
     * @param world      server level containing the sections, if available
     * @param sectionX   local section X coordinate
     * @param sectionZ   local section Z coordinate
     * @param neighbourX adjacent section X coordinate
     * @param neighbourZ adjacent section Z coordinate
     * @return {@code true} if merging across the boundary must be blocked
     */
    boolean isMergeFenced(
        @Nullable ServerLevel world,
        int sectionX,
        int sectionZ,
        int neighbourX,
        int neighbourZ
    );

    /**
     * Optional query indicating whether {@code (sectionX, sectionZ)} itself is currently leased
     * to a remote shard.
     *
     * @param world    server level containing the section, if available
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     * @return {@code true} if the section is currently leased to a remote shard
     */
    default boolean isRemoteLeasedSection(
        final @Nullable ServerLevel world,
        final int sectionX,
        final int sectionZ
    ) {
        return false;
    }

    static @Nullable RegionShardFence get() {
        return Holder.current;
    }

    static void register(final @Nullable RegionShardFence fence) {
        Holder.current = fence;
    }

    static void resetDefault() {
        Holder.current = null;
    }

    final class Holder {
        private static volatile @Nullable RegionShardFence current;

        private Holder() {
        }
    }
}
