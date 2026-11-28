package dev.leafcraft.goldleaf.telemetry;

/**
 * Delegating facade for {@link io.papermc.paper.spi.ChunkActivityCounters}
 * ({@code goldleaf-ADR-006} §1.2).
 */
public final class ChunkActivityCounters {

    private final io.papermc.paper.spi.ChunkActivityCounters delegate =
        new io.papermc.paper.spi.ChunkActivityCounters();

    public record Snapshot(
        int redstoneUpdates,
        int hopperTransfers,
        int pistonMoves,
        int blockGrowths,
        int entityTicks
    ) {
        public boolean isEmpty() {
            return this.redstoneUpdates == 0
                && this.hopperTransfers == 0
                && this.pistonMoves == 0
                && this.blockGrowths == 0
                && this.entityTicks == 0;
        }
    }

    public void recordRedstoneUpdate() {
        this.delegate.recordRedstoneUpdate();
    }

    public void recordHopperTransfer() {
        this.delegate.recordHopperTransfer();
    }

    public void recordPistonMove() {
        this.delegate.recordPistonMove();
    }

    public void recordBlockGrowth() {
        this.delegate.recordBlockGrowth();
    }

    public void recordEntityTick() {
        this.delegate.recordEntityTick();
    }

    public int redstoneUpdates() {
        return this.delegate.redstoneUpdates();
    }

    public int hopperTransfers() {
        return this.delegate.hopperTransfers();
    }

    public int pistonMoves() {
        return this.delegate.pistonMoves();
    }

    public int blockGrowths() {
        return this.delegate.blockGrowths();
    }

    public int entityTicks() {
        return this.delegate.entityTicks();
    }

    public Snapshot drainSnapshot() {
        final io.papermc.paper.spi.ChunkActivityCounters.Snapshot snap = this.delegate.drainSnapshot();
        return new Snapshot(
            snap.redstoneUpdates(),
            snap.hopperTransfers(),
            snap.pistonMoves(),
            snap.blockGrowths(),
            snap.entityTicks()
        );
    }

    public void reset() {
        this.delegate.reset();
    }
}
