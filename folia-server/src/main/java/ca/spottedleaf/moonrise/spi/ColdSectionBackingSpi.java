package ca.spottedleaf.moonrise.spi;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Layer 1 upstream-candidate immutable cold-section backing SPI for {@link PalettedContainer}.
 *
 * <p>Allows a storage engine to back a live {@link PalettedContainer} directly with an immutable
 * compressed section frame plus a bounded sparse dirty overlay while evicting vanilla's
 * {@code SimpleBitStorage} {@code long[]} array to a {@code 0-bit} {@code ZeroBitStorage} shell.
 * Reads, writes, block-counting, vanilla packet serialization, and non-pinning ephemeral copies
 * delegate through this SPI without inflating {@code SimpleBitStorage} onto the live section.</p>
 *
 * @param <T> the container element type (e.g. {@code BlockState})
 */
public interface ColdSectionBackingSpi<T> {

    T get(int x, int y, int z);

    T getByIndex(int index);

    void set(int x, int y, int z, T value);

    void setByIndex(int index, T value);

    T getAndSet(int x, int y, int z, T value);

    boolean maybeHas(Predicate<T> predicate);

    void count(PalettedContainer.CountConsumer<T> output);

    void forEachDistinct(Consumer<T> consumer);

    int getSerializedSize(IdMap<T> globalMap);

    void writeVanilla(FriendlyByteBuf buffer, IdMap<T> globalMap);

    boolean isCleanUniform();

    T uniformFallback();

    /**
     * Populates {@code destination} (a freshly constructed ephemeral {@link PalettedContainer} with
     * no cold backing attached) with the complete current state of this section without pinning
     * {@code SimpleBitStorage} onto the live owner container.
     *
     * @param destination ephemeral target container to populate
     */
    void copyInto(PalettedContainer<T> destination);

    /**
     * Visits every section-local voxel coordinate {@code pos12 = (y << 8) | (z << 4) | x} whose
     * current value satisfies {@code predicate}. Invoked by {@code LevelChunkSection#recalcBlockCounts}
     * only when at least one randomly ticking block is present in the section.
     *
     * @param predicate     voxel state predicate
     * @param pos12Consumer callback receiving each matching packed 12-bit coordinate
     */
    void forEachMatchingCoordinate(Predicate<T> predicate, IntConsumer pos12Consumer);

    /**
     * Returns the number of uncompacted dirty voxels currently held in the sparse overlay.
     *
     * @return dirty voxel count in the sparse overlay
     */
    int dirtyCount();

    /**
     * Returns {@code true} when sustained mutation density warrants promoting this individual
     * {@link PalettedContainer} from sparse overlay mode to native mutable {@code SimpleBitStorage}
     * ({@code HOT_MUTABLE}) so the CPU pays zero overlay overhead during bursts ({@code goldleaf-ADR-005}).
     *
     * @return {@code true} if this section should promote to {@code HOT_MUTABLE}
     */
    default boolean shouldPromoteToHotMutable() {
        return false;
    }

    /**
     * Attempts an O(1), zero-re-encode demotion of a clean hydrated section back to compressed
     * {@code COLD_LCA} base-frame residency.
     *
     * @return {@code true} if demoted
     */
    default boolean tryDemoteCleanToCold() {
        return false;
    }

    /**
     * Returns an isolated lightweight snapshot of this cold backing for {@link PalettedContainer#copy()}
     * (e.g., {@code SerializableChunkData.copyOf} during {@code /stop} or {@code save-all}).
     *
     * @return isolated cold-backing snapshot, or {@code null} to fall back to ephemeral materialization
     */
    default @Nullable ColdSectionBackingSpi<T> snapshotCopy() {
        return null;
    }
}
