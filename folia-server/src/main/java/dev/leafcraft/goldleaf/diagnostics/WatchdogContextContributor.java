package dev.leafcraft.goldleaf.diagnostics;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 non-blocking diagnostic contributor SPI invoked by {@code FoliaWatchdogThread}
 * immediately before {@code WatchdogThread.dumpThread(...)} ({@code goldleaf-ADR-011} §1.1).
 *
 * <p><strong>Lock-Free Contract ({@code S-005}):</strong> Contributors execute on the watchdog
 * thread while the target region thread is stalled. Implementations MUST be strictly lock-free /
 * snapshot-only (or {@code tryLock()} with immediate bailout) and must never acquire region locks
 * or perform blocking I/O. Every contributor is wrapped in fail-closed exception isolation
 * ({@code S-004}) so a diagnostic failure can never suppress the base stack trace.</p>
 */
@FunctionalInterface
public interface WatchdogContextContributor {

    /**
     * Immutable lock-free snapshot of a stalled region tick passed to contributors.
     */
    record StalledTickContext(
        String worldName,
        int centerChunkX,
        int centerChunkZ,
        double elapsedSeconds,
        String threadName,
        boolean globalRegion,
        @Nullable StackTraceElement[] stackTrace,
        int minChunkX,
        int maxChunkX,
        int minChunkZ,
        int maxChunkZ,
        int loadedChunkCount
    ) {
        public StalledTickContext(
            final String worldName,
            final int centerChunkX,
            final int centerChunkZ,
            final double elapsedSeconds,
            final String threadName,
            final boolean globalRegion,
            final @Nullable StackTraceElement[] stackTrace
        ) {
            this(
                worldName,
                centerChunkX,
                centerChunkZ,
                elapsedSeconds,
                threadName,
                globalRegion,
                stackTrace,
                centerChunkX,
                centerChunkX,
                centerChunkZ,
                centerChunkZ,
                -1
            );
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof StalledTickContext other)) {
                return false;
            }
            return this.centerChunkX == other.centerChunkX
                && this.centerChunkZ == other.centerChunkZ
                && Double.compare(this.elapsedSeconds, other.elapsedSeconds) == 0
                && this.globalRegion == other.globalRegion
                && this.minChunkX == other.minChunkX
                && this.maxChunkX == other.maxChunkX
                && this.minChunkZ == other.minChunkZ
                && this.maxChunkZ == other.maxChunkZ
                && this.loadedChunkCount == other.loadedChunkCount
                && Objects.equals(this.worldName, other.worldName)
                && Objects.equals(this.threadName, other.threadName)
                && Arrays.equals(this.stackTrace, other.stackTrace);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                this.worldName,
                this.centerChunkX,
                this.centerChunkZ,
                this.elapsedSeconds,
                this.threadName,
                this.globalRegion,
                this.minChunkX,
                this.maxChunkX,
                this.minChunkZ,
                this.maxChunkZ,
                this.loadedChunkCount
            );
            return 31 * result + Arrays.hashCode(this.stackTrace);
        }

        @Override
        public String toString() {
            return "StalledTickContext[worldName=" + this.worldName
                + ", centerChunkX=" + this.centerChunkX
                + ", centerChunkZ=" + this.centerChunkZ
                + ", elapsedSeconds=" + this.elapsedSeconds
                + ", threadName=" + this.threadName
                + ", globalRegion=" + this.globalRegion
                + ", stackTrace=" + Arrays.toString(this.stackTrace)
                + ", minChunkX=" + this.minChunkX
                + ", maxChunkX=" + this.maxChunkX
                + ", minChunkZ=" + this.minChunkZ
                + ", maxChunkZ=" + this.maxChunkZ
                + ", loadedChunkCount=" + this.loadedChunkCount + "]";
        }
    }

    /**
     * Produces zero or more diagnostic lines to append to the watchdog report before the raw
     * thread dump. Returning {@code null} or an empty list contributes nothing.
     */
    @Nullable List<String> contribute(StalledTickContext context);
}
