package io.papermc.paper.telemetry;

import org.bukkit.Chunk;
import org.jspecify.annotations.Nullable;

/**
 * Public plugin-facing service for native per-chunk tick telemetry, primitive activity counters,
 * and pre-tick {@code ChunkExecutionMask} gating ({@code goldleaf-ADR-006}).
 *
 * <p>Plugins (such as {@code LeafSleuth} and {@code LeafShard}) may discover this service via
 * {@code Bukkit.getServicesManager().load(ChunkTelemetryService.class)}. When no listeners are
 * registered and all chunk masks remain {@code 0}, runtime overhead is a single volatile read per
 * tick ({@code ~0 ns}, {@code 0 B/tick}).</p>
 */
public interface ChunkTelemetryService {

    /**
     * Execution mask with no subsystems suppressed.
     */
    int MASK_NONE = 0;

    /**
     * Execution mask bit suppressing redstone updates in the chunk.
     */
    int SUPPRESS_REDSTONE = 1 << 0;

    /**
     * Execution mask bit suppressing hopper transfers in the chunk.
     */
    int SUPPRESS_HOPPERS = 1 << 1;

    /**
     * Execution mask bit suppressing piston actuation in the chunk.
     */
    int SUPPRESS_PISTONS = 1 << 2;

    /**
     * Execution mask bit dampening entity AI ticking in the chunk.
     */
    int DAMPEN_ENTITY_AI = 1 << 3;

    /**
     * Execution mask bit marking the chunk as a virtualized black-box simulation.
     */
    int VIRTUALIZED_BLACKBOX = 1 << 4;

    /**
     * Discrete server tick phases reported by per-chunk telemetry hooks.
     */
    enum TickPhase {
        /**
         * Entity ticking phase.
         */
        ENTITY,
        /**
         * Block entity ticking phase.
         */
        BLOCK_ENTITY,
        /**
         * Scheduled block and fluid tick phase.
         */
        SCHEDULED_TICK,
        /**
         * Random block tick phase.
         */
        RANDOM_TICK,
        /**
         * Redstone signal propagation phase.
         */
        REDSTONE,
        /**
         * Hopper item transfer phase.
         */
        HOPPER
    }

    /**
     * Callback receiving per-chunk tick phase duration samples.
     */
    @FunctionalInterface
    interface PhaseListener {
        /**
         * Invoked when a chunk tick phase sample is recorded.
         *
         * @param regionId      owning region identifier
         * @param worldName     name of the world containing the chunk
         * @param chunkX        chunk X coordinate
         * @param chunkZ        chunk Z coordinate
         * @param phase         sampled tick phase
         * @param durationNanos elapsed duration of the phase in nanoseconds
         */
        void onChunkPhaseSample(
            long regionId,
            String worldName,
            int chunkX,
            int chunkZ,
            TickPhase phase,
            long durationNanos
        );
    }

    /**
     * Immutable snapshot of drained primitive activity counters for a chunk.
     *
     * @param redstoneUpdates count of redstone updates
     * @param hopperTransfers count of hopper transfers
     * @param pistonMoves     count of piston moves
     * @param blockGrowths    count of block growth events
     * @param entityTicks     count of entity ticks
     */
    record ActivitySnapshot(
        long redstoneUpdates,
        long hopperTransfers,
        long pistonMoves,
        long blockGrowths,
        long entityTicks
    ) {
        /**
         * Returns the sum of all recorded activity counters in this snapshot.
         *
         * @return total activity count across all categories
         */
        public long total() {
            return this.redstoneUpdates + this.hopperTransfers + this.pistonMoves + this.blockGrowths + this.entityTicks;
        }
    }

    /**
     * Returns whether the chunk telemetry SPI is enabled in server configuration.
     *
     * @return {@code true} if the telemetry SPI is enabled
     */
    boolean isTelemetrySpiEnabled();

    /**
     * Returns whether telemetry sampling is actively collecting data (at least one listener registered).
     *
     * @return {@code true} if telemetry sampling is active
     */
    boolean isTelemetryActive();

    /**
     * Registers a per-chunk tick phase listener.
     *
     * @param listener listener to register
     * @return {@code true} if the listener was added
     */
    boolean registerPhaseListener(PhaseListener listener);

    /**
     * Unregisters a previously registered per-chunk tick phase listener.
     *
     * @param listener listener to remove
     * @return {@code true} if the listener was present and removed
     */
    boolean unregisterPhaseListener(PhaseListener listener);

    /**
     * Mutates the chunk's execution mask. Must be invoked on the chunk's owning Folia region thread.
     *
     * @param chunk target chunk
     * @param mask  bitmask combining suppression flags or {@link #MASK_NONE}
     * @return {@code true} if the execution mask was updated
     */
    boolean setChunkExecutionMask(Chunk chunk, int mask);

    /**
     * Resets the chunk's execution mask to {@link #MASK_NONE}. Must be invoked on the owning region thread.
     *
     * @param chunk target chunk
     */
    void clearChunkExecutionMask(Chunk chunk);

    /**
     * Returns the current execution bitmask for the given chunk.
     *
     * @param chunk target chunk
     * @return active execution bitmask, or {@link #MASK_NONE}
     */
    int getChunkExecutionMask(Chunk chunk);

    /**
     * Drains and resets the lazily allocated primitive activity counters for {@code chunk}, or
     * returns {@code null} if no activity was recorded.
     *
     * @param chunk target chunk
     * @return drained {@link ActivitySnapshot}, or {@code null} if no activity was recorded
     */
    @Nullable ActivitySnapshot drainChunkActivity(Chunk chunk);

    /**
     * Global service holder populated by the server bootstrap at startup.
     */
    final class Holder {
        private static volatile @Nullable ChunkTelemetryService instance;

        private Holder() {
        }
    }

    /**
     * Returns the registered global {@link ChunkTelemetryService} instance, if available.
     *
     * @return the active {@link ChunkTelemetryService}, or {@code null} if not registered
     */
    static @Nullable ChunkTelemetryService get() {
        return Holder.instance;
    }

    /**
     * Registers or clears the global {@link ChunkTelemetryService} instance.
     *
     * @param service service implementation to register, or {@code null} to clear
     */
    static void register(final @Nullable ChunkTelemetryService service) {
        Holder.instance = service;
    }
}
