package dev.leafcraft.goldleaf.worldgen;

import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import dev.leafcraft.goldleaf.storage.LcaChunkResidencyCache;
import io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr;
import io.github.dailystruggle.bytecode.transpile.BytecodeSsaLifter;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.WorldgenKernelSwapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native GoldLeaf {@link TerrainGenerationAccelerator} backed by {@code :bytecode-api}'s Tier-1
 * SSA IR lifter ({@link BytecodeSsaLifter} / {@link ConceptOptimizerPass} /
 * {@link OpenClBackendEmitter}) and {@link GoldLeafOpenClBridge} + {@link OpenClKernelCache}
 * ({@code goldleaf-ADR-004}).
 *
 * <p>Replaces the retired external {@code LeafGen} plugin's reflective interception and hand-authored
 * {@code FN_*} stampers with a directly testable, single-pipeline IR-to-OpenCL compiler targeting
 * MC {@code 26.3}'s chunk-local {@code DensityVolume} flat {@code float[]} layout.</p>
 *
 * <p><b>Off-tick lift/compile isolation ({@code [DSN-3]} / {@code S-005}):</b> All IR lifting,
 * optimization, OpenCL source emission, and {@code clBuildProgram} calls run exclusively on a
 * dedicated bounded daemon executor ({@code goldleaf-ir-opencl-compiler}), never on Minecraft main
 * or Folia region tick threads.</p>
 *
 * @governedBy goldleaf-ADR-004, leafgen-ADR-008
 * @trace S-002, S-004, S-005
 */
public final class IrOpenClTerrainAccelerator implements TerrainGenerationAccelerator, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IrOpenClTerrainAccelerator.class);
    private static final String DEFAULT_BUILD_OPTS_STRICT = "-cl-std=CL1.2";
    private static final String DEFAULT_BUILD_OPTS_FP32 = "-cl-std=CL1.2 -cl-mad-enable -cl-no-signed-zeros -cl-finite-math-only";

    private final OpenClKernelCache kernelCache;
    private final OpenClBackendEmitter openClEmitter;
    private final ExecutorService compilerExecutor;
    private final ExecutorService waveDispatchExecutor;
    private final ExecutorService biomeDispatchExecutor;
    private final ExecutorService hostApplyExecutor;
    private final ExecutorService terrainContinuationExecutor;
    private final CompactSectionWritebackBufferPool writebackBufferPool;
    private final GpuOrePlacementEngine orePlacementEngine;
    private final GpuLightEngine lightEngine;
    private record PreparedTerrainTask(
        TerrainTask task,
        @Nullable byte[] packedBiomesHalo,
        @Nullable int[] packedCarvingMask
    ) {
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PreparedTerrainTask other)) {
                return false;
            }
            return Objects.equals(this.task, other.task)
                && Arrays.equals(this.packedBiomesHalo, other.packedBiomesHalo)
                && Arrays.equals(this.packedCarvingMask, other.packedCarvingMask);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(this.task);
            result = 31 * result + Arrays.hashCode(this.packedBiomesHalo);
            return 31 * result + Arrays.hashCode(this.packedCarvingMask);
        }

        @Override
        public String toString() {
            return "PreparedTerrainTask[task=" + this.task
                + ", packedBiomesHalo=" + Arrays.toString(this.packedBiomesHalo)
                + ", packedCarvingMask=" + Arrays.toString(this.packedCarvingMask) + "]";
        }
    }

    private static final class VanillaWaveScratch {
        int[] originsXyz4 = new int[0];
        byte[] biomesHalo = new byte[0];
        float[] prelimCorners4 = new float[0];
        int[] beardMeta2 = new int[0];
        int[] beardItems8 = new int[0];
        int[] carverMeta = new int[0];
        int[][] chunkCarverMasks = new int[0][];
        boolean[] carverEligible = new boolean[0];
        int[] carverMasksFlat = new int[0];
        byte[] outChunkFlags = new byte[0];
        int[] outCarverCounts = new int[0];
        int[] outVramLightMeta2 = new int[0];
    }

    private static final ThreadLocal<VanillaWaveScratch> VANILLA_WAVE_SCRATCH =
        ThreadLocal.withInitial(VanillaWaveScratch::new);

    static final int MAX_PRIMED_BIOME_ENTRIES = 8192;

    record PrimedBiomeKey(String levelId, long chunkKey) {}

    record BiomeCoordRequest(
        ServerLevel level,
        NoiseBasedChunkGenerator generator,
        RandomState randomState,
        ChunkPos pos,
        int minY,
        int height
    ) {}

    sealed interface PrimedBiomePayload permits RTreeBiomePayload, Climate6BiomePayload {}

    record RTreeBiomePayload(
        byte[] biomeIds,
        int quartSizeY,
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] palette
    ) implements PrimedBiomePayload {
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof RTreeBiomePayload other)) {
                return false;
            }
            return this.quartSizeY == other.quartSizeY
                && Arrays.equals(this.biomeIds, other.biomeIds)
                && Arrays.equals(this.palette, other.palette);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(this.quartSizeY);
            result = 31 * result + Arrays.hashCode(this.biomeIds);
            return 31 * result + Arrays.hashCode(this.palette);
        }

        @Override
        public String toString() {
            return "RTreeBiomePayload[biomeIds=" + Arrays.toString(this.biomeIds)
                + ", quartSizeY=" + this.quartSizeY
                + ", palette=" + Arrays.toString(this.palette) + "]";
        }
    }

    record Climate6BiomePayload(
        float[] climateInterleaved6,
        int quartSizeY,
        MultiNoiseBiomeSource multiNoise
    ) implements PrimedBiomePayload {
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Climate6BiomePayload other)) {
                return false;
            }
            return this.quartSizeY == other.quartSizeY
                && Objects.equals(this.multiNoise, other.multiNoise)
                && Arrays.equals(this.climateInterleaved6, other.climateInterleaved6);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(this.quartSizeY, this.multiNoise);
            return 31 * result + Arrays.hashCode(this.climateInterleaved6);
        }

        @Override
        public String toString() {
            return "Climate6BiomePayload[climateInterleaved6=" + Arrays.toString(this.climateInterleaved6)
                + ", quartSizeY=" + this.quartSizeY
                + ", multiNoise=" + this.multiNoise + "]";
        }
    }

    /**
     * Strictly bounded, {@code ChunkAccess}-free FIFO cache for proactively primed GPU biome
     * futures ({@code S-002}). Cancelled chunks that never reach {@code ChunkStatus.BIOMES} are
     * automatically evicted once the cap is exceeded, keeping peak payload memory bounded.
     */
    static final class PrimedBiomeCache<T> {
        private final int maxEntries;
        private final ConcurrentHashMap<PrimedBiomeKey, CompletableFuture<@Nullable T>> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<PrimedBiomeKey> fifoOrder = new ConcurrentLinkedQueue<>();
        private final AtomicInteger approxSize = new AtomicInteger(0);
        private final LongAdder evicted = new LongAdder();

        PrimedBiomeCache(final int maxEntries) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
            }
            this.maxEntries = maxEntries;
        }

        boolean containsKey(final PrimedBiomeKey key) {
            return this.entries.containsKey(key);
        }

        boolean putIfAbsent(final PrimedBiomeKey key, final CompletableFuture<@Nullable T> future) {
            if (this.entries.putIfAbsent(key, future) != null) {
                return false;
            }
            this.fifoOrder.add(key);
            int size = this.approxSize.incrementAndGet();
            int steps = 0;
            while (size > this.maxEntries && steps++ < 16) {
                final PrimedBiomeKey oldest = this.fifoOrder.poll();
                if (oldest == null) {
                    break;
                }
                if (this.entries.remove(oldest) != null) {
                    this.evicted.increment();
                    size = this.approxSize.decrementAndGet();
                }
            }
            return true;
        }

        @Nullable CompletableFuture<@Nullable T> remove(final PrimedBiomeKey key) {
            final CompletableFuture<@Nullable T> removed = this.entries.remove(key);
            if (removed != null) {
                this.approxSize.decrementAndGet();
            }
            return removed;
        }

        int size() {
            return this.entries.size();
        }

        long evictedCount() {
            return this.evicted.sum();
        }

        void clear() {
            this.entries.clear();
            this.fifoOrder.clear();
            this.approxSize.set(0);
        }
    }

    private final WorldgenWaveCoalescer<PreparedTerrainTask, CompletableFuture<Boolean>> terrainWaveCoalescer;
    private final WorldgenWaveCoalescer<BiomeCoordRequest, @Nullable PrimedBiomePayload> biomeWaveCoalescer;
    private final PrimedBiomeCache<PrimedBiomePayload> primedBiomeCache = new PrimedBiomeCache<>(MAX_PRIMED_BIOME_ENTRIES);
    private final LongAdder biomePrimedSubmitted = new LongAdder();
    private final LongAdder biomePrimedHitsReady = new LongAdder();
    private final LongAdder biomePrimedHitsInFlight = new LongAdder();
    private final int deviceIndex;
    private final ConcurrentHashMap<String, CompletableFuture<CompiledDensityKernel>> inFlightCompiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledDensityKernel>> routerSectionFillKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledDensityKernel>> routerSurfaceFillKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledVanillaSurfaceKernel>> routerVanillaSurfaceKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledDensityKernel>> routerBiomeKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<@Nullable CompiledBiomeRTreeKernel>> routerBiomeRTreeKernels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Float> prelimCornerCache = new ConcurrentHashMap<>(4096);
    private static final int[] EMPTY_CARVING_MASK = new int[0];
    private final ConcurrentLinkedDeque<CompactSurfaceWritebackBuffer> surfaceBufferPool = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<CompactVanillaSurfaceWritebackBuffer> vanillaSurfaceBufferPool = new ConcurrentLinkedDeque<>();
    private final Set<ChunkAccess> surfaceAppliedChunks = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<ChunkAccess> carverAppliedChunks = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final LongAdder gpuCarvedChunks = new LongAdder();
    private final LongAdder carverOverflowFallbackChunks = new LongAdder();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LongAdder coalescedWaves = new LongAdder();
    private final LongAdder gpuAcceleratedChunks = new LongAdder();
    private final LongAdder cpuFallbackChunks = new LongAdder();
    private final LongAdder surfaceSpecialFallbackChunks = new LongAdder();
    private final LongAdder totalGpuKernelNanos = new LongAdder();
    private final LongAdder totalReadbackNanos = new LongAdder();
    private final LongAdder totalHostApplyNanos = new LongAdder();

    public IrOpenClTerrainAccelerator(final Path cacheDir) {
        this(new OpenClKernelCache(cacheDir), 0);
    }

    public IrOpenClTerrainAccelerator(final OpenClKernelCache kernelCache, final int deviceIndex) {
        this.kernelCache = Objects.requireNonNull(kernelCache, "kernelCache");
        this.deviceIndex = deviceIndex;
        this.openClEmitter = new OpenClBackendEmitter();
        final int bgPriority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);
        final int gpuDispatchPriority = Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1);
        final AtomicInteger threadIds = new AtomicInteger(1);
        this.compilerExecutor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-ir-opencl-compiler-" + threadIds.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(bgPriority);
            return t;
        });
        final AtomicInteger waveThreadIds = new AtomicInteger(1);
        this.waveDispatchExecutor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-opencl-wave-dispatch-" + waveThreadIds.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(gpuDispatchPriority);
            return t;
        });
        this.biomeDispatchExecutor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-opencl-biome-dispatch");
            t.setDaemon(true);
            t.setPriority(gpuDispatchPriority);
            return t;
        });
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final int configuredApplyThreads = cfg != null && cfg.worldgen != null ? cfg.worldgen.applyPoolThreads : 0;
        final int applyThreads = configuredApplyThreads > 0
            ? Math.min(64, configuredApplyThreads)
            : Math.max(4, Runtime.getRuntime().availableProcessors() / 2);
        final AtomicInteger applyThreadIds = new AtomicInteger(1);
        this.hostApplyExecutor = Executors.newFixedThreadPool(applyThreads, runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-opencl-host-apply-" + applyThreadIds.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(gpuDispatchPriority);
            return t;
        });
        final int continuationThreads = Math.max(4, Runtime.getRuntime().availableProcessors() / 2);
        final AtomicInteger contThreadIds = new AtomicInteger(1);
        this.terrainContinuationExecutor = Executors.newFixedThreadPool(continuationThreads, runnable -> {
            final Thread t = new Thread(runnable, "goldleaf-terrain-continuation-" + contThreadIds.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(gpuDispatchPriority);
            return t;
        });
        this.writebackBufferPool = new CompactSectionWritebackBufferPool();
        this.orePlacementEngine = new GpuOrePlacementEngine(this.kernelCache, this.deviceIndex, this.compilerExecutor);
        this.lightEngine = new GpuLightEngine(this.deviceIndex, this.kernelCache);
        this.lightEngine.warmCompileAsync();
        final int waveMax = cfg != null && cfg.worldgen != null
            ? Math.max(1, Math.min(64, cfg.worldgen.waveMaxSize))
            : WorldgenWaveCoalescer.DEFAULT_MAX_WAVE_SIZE;
        final long deadlineNs = cfg != null && cfg.worldgen != null
            ? TimeUnit.MILLISECONDS.toNanos(Math.max(1, Math.min(10, cfg.worldgen.waveFlushDeadlineMs)))
            : WorldgenWaveCoalescer.DEFAULT_FLUSH_DEADLINE_NANOS;
        this.terrainWaveCoalescer = new WorldgenWaveCoalescer<>(
            waveMax,
            deadlineNs,
            (key, batch) -> this.dispatchPreparedTerrainWaveBatchAsync(batch),
            this.waveDispatchExecutor
        );
        this.biomeWaveCoalescer = new WorldgenWaveCoalescer<>(
            waveMax,
            deadlineNs,
            (key, batch) -> this.dispatchBiomeWaveBatchAsync(batch),
            this.biomeDispatchExecutor
        );
    }

    CompactSectionWritebackBufferPool writebackBufferPool() {
        return this.writebackBufferPool;
    }

    ExecutorService hostApplyExecutor() {
        return this.hostApplyExecutor;
    }

    public ExecutorService terrainContinuationExecutor() {
        return this.terrainContinuationExecutor;
    }

    public GpuOrePlacementEngine orePlacementEngine() {
        return this.orePlacementEngine;
    }

    public GpuLightEngine lightEngine() {
        return this.lightEngine;
    }

    @Override
    public io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight tryComputeChunkLight(
        final ChunkAccess chunk,
        final Boolean[] emptySections,
        final boolean hasSkyLight
    ) {
        if (this.vramLightStageActive) {
            return null;
        }
        return this.lightEngine.computeChunkLight(chunk, emptySections, hasSkyLight);
    }

    @Override
    public @Nullable CompletableFuture<io.papermc.paper.spi.PaperWorldgenSpi.@Nullable PrecomputedChunkLight> tryComputeChunkLightAsync(
        final ChunkAccess chunk,
        final Boolean[] emptySections,
        final boolean hasSkyLight
    ) {
        if (this.vramLightStageActive) {
            return null;
        }
        return this.lightEngine.submitChunkLightAsync(chunk, emptySections, hasSkyLight, this.hostApplyExecutor);
    }

    private final LongAdder vramLightAttachedChunks = new LongAdder();
    private volatile boolean vramLightStageActive;

    public OpenClKernelCache kernelCache() {
        return this.kernelCache;
    }

    public boolean isGpuReady() {
        return !this.closed.get() && GoldLeafOpenClBridge.isAvailable() && this.deviceIndex < GoldLeafOpenClBridge.deviceCount();
    }

    static boolean isGpuSurfaceActive(final ParityMode mode, final @Nullable GoldLeafConfig cfg) {
        return cfg != null && cfg.worldgen != null && cfg.worldgen.gpuSurfaceEnabled;
    }

    void markSurfaceApplied(final ChunkAccess chunk) {
        if (chunk != null) {
            this.surfaceAppliedChunks.add(chunk);
        }
    }

    @Override
    public boolean isSurfaceAlreadyApplied(final ChunkAccess chunk) {
        return chunk != null && this.surfaceAppliedChunks.remove(chunk);
    }

    void markCarverApplied(final ChunkAccess chunk) {
        if (chunk != null) {
            this.carverAppliedChunks.add(chunk);
        }
    }

    @Override
    public boolean isCarverAlreadyApplied(final ChunkAccess chunk) {
        return chunk != null && this.carverAppliedChunks.remove(chunk);
    }

    /**
     * Primary GoldLeaf frontend ({@code goldleaf-ADR-017}): directly lowers an MC {@code 26.3}
     * {@link DensityFunction} AST into a {@link OpenClBackendEmitter.DensityVolumeProgram} without
     * ASM bytecode scanning. Returns {@code null} (fail-closed) when the tree contains an
     * unsupported custom node.
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerDensityFunctionProgram(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final ParityMode parityMode,
        final boolean assumeAlignedCoarseGrid
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(parityMode, "parityMode");
        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(
            root,
            compileContext,
            moduleName,
            assumeAlignedCoarseGrid
        );
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] DensityFunction '{}' fell back to CPU: {}", moduleName, lowered.unsupportedReason());
            return null;
        }
        return optimizeAndEmit(lowered.module(), parityMode);
    }

    /**
     * Lowers 6 climate {@link DensityFunction}s ({@code temperature, vegetation, continents,
     * erosion, depth, ridges}) into a single fused 6-channel {@code eval_biome_climate6_batch}
     * OpenCL program with shared noise LUT deduplication.
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerFusedBiomeClimateProgram(
        final List<DensityFunction> climate6,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String baseModuleName,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(climate6, "climate6");
        Objects.requireNonNull(baseModuleName, "baseModuleName");
        Objects.requireNonNull(parityMode, "parityMode");
        if (climate6.size() != 6) {
            throw new IllegalArgumentException("Expected 6 climate functions, got " + climate6.size());
        }
        final String[] names = {"temp", "veg", "cont", "eros", "depth", "ridges"};
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode = selectArithmeticMode(parityMode);
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.DENSITY_VOLUME_BATCH_F32
        );
        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lowerSharedBiomeClimate6(
            climate6,
            compileContext,
            baseModuleName + "shared6",
            true
        );
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] Shared 6-channel climate '{}' not GPU-lowerable: {}", baseModuleName, lowered.unsupportedReason());
            return null;
        }
        // Keep gl_perlin_3d non-inlined so multi-noise datapacks share one Perlin helper across all 6 channels.
        final ConceptOptimizerPass.OptimizationConfig nonInlineOpt =
            new ConceptOptimizerPass.OptimizationConfig(false, true, true, false, true);
        final BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lowered.module(), nonInlineOpt);
        return this.openClEmitter.emitFusedBiomeClimate6SharedModule(optimized, emitOptions);
    }

    /**
     * Executes a compiled fused 6-channel {@code eval_biome_climate6_batch} kernel on GPU in one
     * dispatch and one interleaved {@code float[batchCount * volumeSize * 6]} readback.
     */
    public DispatchStats executeBiomeClimate6Batch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int stepX,
        final int stepY,
        final int stepZ,
        final float[] outInterleaved6
    ) {
        Objects.requireNonNull(kernel, "kernel");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchBiomeClimate6Batch(
            originsXyz4,
            batchCount,
            sizeX,
            sizeY,
            sizeZ,
            stepX,
            stepY,
            stepZ,
            outInterleaved6,
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchBiomeClimate6Batch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    /**
     * Lowers an MC {@code 26.3} {@link DensityFunction} AST into a fused
     * {@code fill_section_palette_batch_u2} OpenCL program that computes density, classifies each
     * voxel into 2-bit palette codes, packs {@code (ly << 8) | (lz << 4) | lx} words
     * ({@code 1,024 B/section}), and reduces per-section uniformity/count headers
     * ({@code 8 B/section}) directly on GPU ({@code goldleaf-ADR-017}).
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerSectionPaletteFillProgram(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final ParityMode parityMode,
        final boolean assumeAlignedCoarseGrid
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(moduleName, "moduleName");
        Objects.requireNonNull(parityMode, "parityMode");
        final DensityFunctionIrLowerer.SplitLoweringResult lowered = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            root,
            compileContext,
            moduleName
        );
        if (!lowered.isSupported() || lowered.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] DensityFunction '{}' fell back to CPU (section-fill): {}", moduleName, lowered.unsupportedReason());
            return null;
        }
        return optimizeAndEmitSectionFill(lowered.module(), lowered.coarseSpec(), parityMode);
    }

    /**
     * Optional secondary frontend: lifts a static 3D density method from {@code ownerClass} bytecode
     * into a {@link OpenClBackendEmitter.DensityVolumeProgram} without requiring a GPU device.
     */
    public OpenClBackendEmitter.DensityVolumeProgram liftStaticMethodProgram(
        final Class<?> ownerClass,
        final String methodName,
        final ParityMode parityMode
    ) throws IOException {
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(parityMode, "parityMode");
        final List<byte[]> classBytes = WorldgenKernelSwapper.pullClassBytes(ownerClass);
        final BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftModule(
            classBytes,
            ownerClass.getName(),
            methodName
        );
        return optimizeAndEmit(lifted, parityMode);
    }

    /**
     * Lifts a live stateful Java density/noise object graph (e.g. a {@code DensityFunction} or
     * custom datapack router instance) via {@link BytecodeSsaLifter#liftInstanceGraph} into a
     * {@link OpenClBackendEmitter.DensityVolumeProgram}.
     */
    public OpenClBackendEmitter.DensityVolumeProgram liftLiveInstanceProgram(
        final Object rootInstance,
        final String entryMethodName,
        final String entryDescriptor,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(rootInstance, "rootInstance");
        Objects.requireNonNull(entryMethodName, "entryMethodName");
        Objects.requireNonNull(entryDescriptor, "entryDescriptor");
        Objects.requireNonNull(parityMode, "parityMode");
        final BytecodeConceptIr.ConceptModule lifted = BytecodeSsaLifter.liftInstanceGraph(
            rootInstance,
            entryMethodName,
            entryDescriptor
        );
        return optimizeAndEmit(lifted, parityMode);
    }

    /**
     * Compiles (or loads from JAR/disk cache) a lifted {@link OpenClBackendEmitter.DensityVolumeProgram}
     * asynchronously on the dedicated compiler worker pool ({@code [DSN-3]} / {@code S-005}).
     */
    public CompletableFuture<CompiledDensityKernel> compileProgramAsync(
        final OpenClBackendEmitter.DensityVolumeProgram program,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(parityMode, "parityMode");
        if (this.closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("IrOpenClTerrainAccelerator is closed"));
        }
        final String compileKey = program.kernelDigestSha256() + ":" + parityMode.name();
        return this.inFlightCompiles.computeIfAbsent(compileKey, ignored ->
            CompletableFuture.supplyAsync(() -> compileProgramBlocking(program, parityMode), this.compilerExecutor)
        );
    }

    /**
     * Compiles (or loads from cache) a lifted program on the calling worker thread. Intended for
     * background worker threads and direct unit tests (never call from region tick threads).
     */
    public CompiledDensityKernel compileProgramBlocking(
        final OpenClBackendEmitter.DensityVolumeProgram program,
        final ParityMode parityMode
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(parityMode, "parityMode");
        if (!isGpuReady()) {
            return new CompiledDensityKernel(program, parityMode, null);
        }
        final String buildOpts = parityMode == ParityMode.FAST
            ? DEFAULT_BUILD_OPTS_FP32
            : DEFAULT_BUILD_OPTS_STRICT;
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = this.kernelCache.getOrCompile(
            this.deviceIndex,
            program,
            buildOpts
        );
        return new CompiledDensityKernel(program, parityMode, gpuKernel);
    }

    /**
     * Executes a compiled {@code DensityVolume} kernel on GPU for {@code batchCount} volumes laid
     * out in MC {@code 26.3} {@code (lx + lz * sizeX) * sizeY + ly} order.
     */
    public DispatchStats executeDensityVolumeBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int stepX,
        final int stepY,
        final int stepZ,
        final float[] outValues
    ) {
        Objects.requireNonNull(kernel, "kernel");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchDensityVolumeBatch(
            originsXyz4,
            batchCount,
            sizeX,
            sizeY,
            sizeZ,
            stepX,
            stepY,
            stepZ,
            outValues,
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchDensityVolumeBatch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    /**
     * Executes a compiled {@code fill_section_palette_batch_u2} kernel on GPU for
     * {@code batchCount} chunks of {@code sectionCount} {@code 16x16x16} sections, writing packed
     * 2-bit palette words and per-section metadata directly into the reusable off-heap
     * {@link CompactSectionWritebackBuffer}.
     */
    public DispatchStats executeSectionPaletteFillBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final CompactSectionWritebackBuffer outBuffer
    ) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(outBuffer, "outBuffer");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        outBuffer.ensureCapacity(batchCount, sectionCount);
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchSectionPaletteFillBatch(
            originsXyz4,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            outBuffer.wordsDirectBuffer(),
            outBuffer.metaDirectBuffer(),
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchSectionPaletteFillBatch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    /**
     * Lowers an MC {@code 26.3} {@link DensityFunction} AST into a fused 4-bit
     * {@code fill_surface_palette_batch_u4} OpenCL program combining density evaluation and
     * column-parallel surface stratification ({@code goldleaf-ADR-017}).
     */
    public OpenClBackendEmitter.@Nullable DensityVolumeProgram lowerFusedSurfaceFillProgram(
        final DensityFunction root,
        final DensityFunction.@Nullable CompileContext compileContext,
        final String moduleName,
        final ParityMode parityMode
    ) {
        final DensityFunctionIrLowerer.SplitLoweringResult split = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            root,
            compileContext,
            moduleName
        );
        if (!split.isSupported() || split.module() == null) {
            LOGGER.debug("[GoldLeaf-OpenCL] DensityFunction '{}' fell back to CPU (surface-fill): {}", moduleName, split.unsupportedReason());
            return null;
        }
        final OpenClBackendEmitter.DensityVolumeProgram densityProg = optimizeAndEmitSectionFill(
            split.module(),
            split.coarseSpec(),
            parityMode
        );
        final String fusedSource = CompactSurfaceWritebackApplier.buildFusedSurfaceKernelSource(
            densityProg,
            split.coarseSpec()
        );
        final String digestTag = split.coarseSpec() != null ? ":surface4bit:coarse" : ":surface4bit:dense";
        final String digest = sha256Hex(densityProg.kernelDigestSha256() + digestTag);
        return new OpenClBackendEmitter.DensityVolumeProgram(
            CompactSurfaceWritebackApplier.SURFACE_KERNEL_NAME,
            fusedSource,
            digest,
            densityProg.spilledLutWords(),
            densityProg.usedConstantBytes(),
            densityProg.spilledLutCount(),
            densityProg.deduplicatedLutCount()
        );
    }

    /**
     * Executes a compiled fused 4-bit {@code fill_surface_palette_batch_u4} kernel on GPU using
     * the 2-slot native DMA ring, writing directly into {@code outBuffer}.
     */
    public DispatchStats executeSurfaceSectionFill4BitBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final int minBlockY,
        final CompactSurfaceWritebackBuffer outBuffer
    ) {
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(outBuffer, "outBuffer");
        final GoldLeafOpenClBridge.CompiledKernel gpuKernel = kernel.gpuKernel();
        if (gpuKernel == null || gpuKernel.isClosed()) {
            throw new IllegalStateException("GPU kernel is unavailable for digest " + kernel.program().kernelDigestSha256());
        }
        outBuffer.ensureCapacity(batchCount, sectionCount);
        final long[] timings = new long[2];
        final int err = gpuKernel.dispatchSurfaceSectionFill4BitBatch(
            originsXyz4,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            minBlockY,
            outBuffer.wordsDirectBuffer(),
            outBuffer.metaDirectBuffer(),
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchSurfaceSectionFill4BitBatch failed (err=" + err + ", kernel=" + kernel.program().kernelName() + ")"
            );
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    private static String sha256Hex(final String input) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * End-to-end zero-translation GPU terrain wave: dispatches {@code fill_section_palette_batch_u2}
     * into the caller-owned off-heap {@code scratchBuffer}, converts each chunk directly into a
     * spec-compliant {@code LCA-ICD-3.0} ({@code mode 10} FlatBitPack) {@code RawChunkFrameSnapshot}
     * via {@link CompactLcaChunkFrameBuilder}, and installs it directly into {@code residencyCache}
     * without hydrating {@code LevelChunkSection} or {@code PalettedContainer}
     * ({@code goldleaf-ADR-005}, {@code goldleaf-ADR-017}).
     */
    public LcaWaveDispatchResult executeSectionPaletteFillToLcaCache(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final long[] chunkKeys,
        final int batchCount,
        final int minSectionY,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final int dataVersion,
        final int[] runtimeStateIds4,
        final boolean computeHeightmaps,
        final CompactSectionWritebackBuffer scratchBuffer,
        final LcaChunkResidencyCache residencyCache
    ) {
        Objects.requireNonNull(chunkKeys, "chunkKeys");
        Objects.requireNonNull(residencyCache, "residencyCache");
        if (chunkKeys.length < batchCount) {
            throw new IllegalArgumentException("chunkKeys.length (" + chunkKeys.length + ") < batchCount (" + batchCount + ")");
        }
        final DispatchStats dispatchStats = executeSectionPaletteFillBatch(
            kernel,
            originsXyz4,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            scratchBuffer
        );
        final CompactLcaChunkFrameBuilder.BuildResult[] results = new CompactLcaChunkFrameBuilder.BuildResult[batchCount];
        long totalLcaBytes = 0L;
        for (int b = 0; b < batchCount; b++) {
            final int chunkX = originsXyz4[b * 4] >> 4;
            final int chunkZ = originsXyz4[b * 4 + 2] >> 4;
            final CompactLcaChunkFrameBuilder.BuildResult built = CompactLcaChunkFrameBuilder.buildChunkFrame(
                scratchBuffer,
                b,
                chunkX,
                chunkZ,
                minSectionY,
                sectionCount,
                dataVersion,
                runtimeStateIds4,
                computeHeightmaps
            );
            residencyCache.installBaseFrame(chunkKeys[b], built.snapshot());
            results[b] = built;
            totalLcaBytes += built.frameByteLength();
        }
        return new LcaWaveDispatchResult(dispatchStats, results, totalLcaBytes);
    }

    @Override
    public void primeFreshChunkBiomes(final ServerLevel level, final ChunkAccess chunk) {
        if (this.closed.get() || !isGpuReady() || level == null || chunk == null) {
            return;
        }
        try {
            final ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (!(generator instanceof NoiseBasedChunkGenerator nbGenerator)) {
                return;
            }
            if (!(nbGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource)) {
                return;
            }
            final ChunkPos pos = chunk.getPos();
            if (SharedConstants.debugVoidTerrain(pos)) {
                return;
            }
            final int height = chunk.getHeight();
            if (height <= 0 || (height & 3) != 0) {
                return;
            }
            final String dimId = level.dimension().identifier().toString();
            final PrimedBiomeKey primeKey = new PrimedBiomeKey(dimId, chunk.coordinateKey);
            if (this.primedBiomeCache.containsKey(primeKey)) {
                return;
            }
            final BiomeCoordRequest req = new BiomeCoordRequest(
                level,
                nbGenerator,
                level.getChunkSource().randomState(),
                pos,
                chunk.getMinY(),
                height
            );
            final WorldgenWaveCoalescer.WaveKey waveKey = new WorldgenWaveCoalescer.WaveKey(
                dimId + "#biomes",
                System.identityHashCode(nbGenerator.generatorSettings().value().noiseRouter())
            );
            final CompletableFuture<@Nullable PrimedBiomePayload> future = this.biomeWaveCoalescer.submit(waveKey, req);
            if (this.primedBiomeCache.putIfAbsent(primeKey, future)) {
                this.biomePrimedSubmitted.increment();
            }
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Failed to prime GPU biomes for fresh chunk {}", chunk.getPos(), ex);
        }
    }

    long biomePrimedSubmitted() {
        return this.biomePrimedSubmitted.sum();
    }

    long biomePrimedHitsReady() {
        return this.biomePrimedHitsReady.sum();
    }

    long biomePrimedHitsInFlight() {
        return this.biomePrimedHitsInFlight.sum();
    }

    long biomePrimedEvicted() {
        return this.primedBiomeCache.evictedCount();
    }

    @Override
    public CompletableFuture<Boolean> submitBiomeAsync(final WorldGenContext context, final BiomeTask task) {
        if (!isGpuReady() || !isEligibleForGpuBiomes(task)) {
            this.cpuFallbackChunks.increment();
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final NoiseBasedChunkGenerator nbGenerator = (NoiseBasedChunkGenerator) task.generator();
        final String dimId = task.level().dimension().identifier().toString();
        final PrimedBiomeKey primeKey = new PrimedBiomeKey(dimId, task.chunk().coordinateKey);
        final CompletableFuture<@Nullable PrimedBiomePayload> primed = this.primedBiomeCache.remove(primeKey);
        if (primed != null) {
            if (primed.isDone()) {
                this.biomePrimedHitsReady.increment();
                final PrimedBiomePayload payload;
                try {
                    payload = primed.getNow(null);
                } catch (final Throwable ex) {
                    LOGGER.warn("[GoldLeaf-OpenCL S-004] Primed GPU biome future failed for chunk {}; falling back to CPU", task.chunk().getPos(), ex);
                    this.cpuFallbackChunks.increment();
                    return CompletableFuture.completedFuture(Boolean.FALSE);
                }
                return CompletableFuture.completedFuture(applyPrimedBiomePayload(task.chunk(), payload));
            }
            this.biomePrimedHitsInFlight.increment();
            return attachBiomeApplyContinuation(task.chunk(), primed);
        }
        final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey(
            dimId + "#biomes",
            System.identityHashCode(nbGenerator.generatorSettings().value().noiseRouter())
        );
        final BiomeCoordRequest req = new BiomeCoordRequest(
            task.level(),
            nbGenerator,
            task.randomState(),
            task.chunk().getPos(),
            task.chunk().getMinY(),
            task.chunk().getHeight()
        );
        return attachBiomeApplyContinuation(task.chunk(), this.biomeWaveCoalescer.submit(key, req));
    }

    private CompletableFuture<Boolean> attachBiomeApplyContinuation(
        final ChunkAccess chunk,
        final CompletableFuture<@Nullable PrimedBiomePayload> payloadFuture
    ) {
        try {
            return payloadFuture.handleAsync((payload, err) -> {
                if (err != null) {
                    LOGGER.warn("[GoldLeaf-OpenCL S-004] Async GPU biome wave failed for chunk {}; falling back to CPU", chunk.getPos(), err);
                    this.cpuFallbackChunks.increment();
                    return Boolean.FALSE;
                }
                return applyPrimedBiomePayload(chunk, payload);
            }, this.hostApplyExecutor);
        } catch (final RejectedExecutionException ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] hostApplyExecutor rejected biome chunk {}; falling back to CPU", chunk.getPos(), ex);
            this.cpuFallbackChunks.increment();
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
    }

    private boolean applyPrimedBiomePayload(
        final ChunkAccess chunk,
        final @Nullable PrimedBiomePayload payload
    ) {
        if (payload == null) {
            return false;
        }
        final long applyStart = System.nanoTime();
        try {
            final int minQuartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
            final int minQuartY = QuartPos.fromBlock(chunk.getMinY());
            final int minQuartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
            if (payload instanceof RTreeBiomePayload rtree) {
                final byte[] outBiomeIds = rtree.biomeIds();
                final int quartSizeY = rtree.quartSizeY();
                final net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] palette = rtree.palette();
                final int secCount = chunk.getSectionsCount();
                final BiomeResolver resolver = (quartX, quartY, quartZ) -> {
                    final int lx = quartX - minQuartX;
                    final int ly = quartY - minQuartY;
                    final int lz = quartZ - minQuartZ;
                    final int id = outBiomeIds[(lx + lz * 4) * quartSizeY + ly] & 0xFF;
                    return palette[id];
                };
                for (int s = 0; s < secCount; s++) {
                    final int lyBase = s << 2;
                    final int firstId = outBiomeIds[lyBase] & 0xFF;
                    boolean uniform = true;
                    for (int xz = 0; xz < 16 && uniform; xz++) {
                        final int colOff = xz * quartSizeY + lyBase;
                        if ((outBiomeIds[colOff] & 0xFF) != firstId
                            || (outBiomeIds[colOff + 1] & 0xFF) != firstId
                            || (outBiomeIds[colOff + 2] & 0xFF) != firstId
                            || (outBiomeIds[colOff + 3] & 0xFF) != firstId) {
                            uniform = false;
                        }
                    }
                    final net.minecraft.world.level.chunk.LevelChunkSection sec = chunk.getSection(s);
                    if (uniform) {
                        sec.paper$installUniformBiomeSection(palette[firstId]);
                    } else {
                        sec.fillBiomesFromNoise(resolver, minQuartX, minQuartY + lyBase, minQuartZ);
                    }
                }
            } else if (payload instanceof Climate6BiomePayload climate6) {
                final float[] interleaved = climate6.climateInterleaved6();
                final int quartSizeY = climate6.quartSizeY();
                final MultiNoiseBiomeSource multiNoise = climate6.multiNoise();
                final BiomeResolver resolver = (quartX, quartY, quartZ) -> {
                    final int lx = quartX - minQuartX;
                    final int ly = quartY - minQuartY;
                    final int lz = quartZ - minQuartZ;
                    final int off = ((lx + lz * 4) * quartSizeY + ly) * 6;
                    return multiNoise.getNoiseBiome(
                        Climate.target(
                            interleaved[off],
                            interleaved[off + 1],
                            interleaved[off + 2],
                            interleaved[off + 3],
                            interleaved[off + 4],
                            interleaved[off + 5]
                        )
                    );
                };
                chunk.fillBiomesFromNoise(resolver);
            } else {
                return false;
            }
            this.totalHostApplyNanos.add(System.nanoTime() - applyStart);
            return true;
        } catch (final Throwable ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Biome payload apply failed for chunk {}; falling back to CPU", chunk.getPos(), ex);
            this.cpuFallbackChunks.increment();
            return false;
        }
    }

    @Override
    public WaveResult accelerateBiomes(final WorldGenContext context, final List<BiomeTask> tasks) {
        if (tasks.isEmpty() || !isGpuReady()) {
            return WaveResult.noneHandled(tasks.size());
        }
        if (tasks.size() == 1) {
            final Boolean handled = submitBiomeAsync(context, tasks.get(0)).join();
            final boolean[] flags = new boolean[] {Boolean.TRUE.equals(handled)};
            return new WaveResult(flags, 0L, 0L, 0L);
        }
        final List<BiomeCoordRequest> requests = new ArrayList<>(tasks.size());
        final int[] origIndices = new int[tasks.size()];
        int eligibleCount = 0;
        for (int i = 0; i < tasks.size(); i++) {
            final BiomeTask task = tasks.get(i);
            if (isEligibleForGpuBiomes(task)) {
                final NoiseBasedChunkGenerator nbGenerator = (NoiseBasedChunkGenerator) task.generator();
                requests.add(new BiomeCoordRequest(
                    task.level(),
                    nbGenerator,
                    task.randomState(),
                    task.chunk().getPos(),
                    task.chunk().getMinY(),
                    task.chunk().getHeight()
                ));
                origIndices[eligibleCount++] = i;
            }
        }
        final boolean[] flags = new boolean[tasks.size()];
        if (requests.isEmpty()) {
            this.cpuFallbackChunks.add(tasks.size());
            return new WaveResult(flags, 0L, 0L, 0L);
        }
        final List<@Nullable PrimedBiomePayload> payloads = dispatchBiomeWaveBatchAsync(requests);
        for (int i = 0; i < eligibleCount; i++) {
            final PrimedBiomePayload payload = i < payloads.size() ? payloads.get(i) : null;
            flags[origIndices[i]] = applyPrimedBiomePayload(tasks.get(origIndices[i]).chunk(), payload);
        }
        return new WaveResult(flags, 0L, 0L, 0L);
    }

    private boolean isEligibleForGpuBiomes(final BiomeTask task) {
        if (!(task.generator() instanceof NoiseBasedChunkGenerator nbGenerator)) {
            return false;
        }
        if (!(nbGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource)) {
            return false;
        }
        if (!task.blender().isEmpty()) {
            return false;
        }
        final int height = task.chunk().getHeight();
        return height > 0 && (height & 3) == 0;
    }

    private List<@Nullable PrimedBiomePayload> dispatchBiomeWaveBatchAsync(final List<BiomeCoordRequest> batch) {
        final int n = batch.size();
        final List<@Nullable PrimedBiomePayload> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        if (n == 0 || !isGpuReady()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final BiomeCoordRequest first = batch.get(0);
        final ParityMode mode = parityMode();
        final CompiledBiomeRTreeKernel rtreeCompiled = getOrScheduleBiomeRTreeKernel(first, mode);
        if (rtreeCompiled != null && rtreeCompiled.kernel().hasLiveGpuKernel()) {
            return dispatchBiomeRTreeWaveBatchAsync(batch, rtreeCompiled);
        }
        final CompiledDensityKernel compiled = getOrScheduleBiomeKernel(first, mode);
        if (compiled == null || !compiled.hasLiveGpuKernel()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int firstHeight = first.height();
        final int quartSizeY = QuartPos.fromBlock(firstHeight);
        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final BiomeCoordRequest req = batch.get(i);
            if (req.height() == firstHeight && req.generator().getBiomeSource() instanceof MultiNoiseBiomeSource) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int b = 0; b < waveSize; b++) {
            final BiomeCoordRequest req = batch.get(eligibleIndices.get(b));
            originsXyz4[b * 4] = req.pos().getMinBlockX();
            originsXyz4[b * 4 + 1] = req.minY();
            originsXyz4[b * 4 + 2] = req.pos().getMinBlockZ();
            originsXyz4[b * 4 + 3] = 0;
        }

        final int quartPerChunk = 4 * quartSizeY * 4;
        final float[] climateInterleaved6 = new float[waveSize * quartPerChunk * 6];
        final DispatchStats stats;
        try {
            stats = executeBiomeClimate6Batch(
                compiled,
                originsXyz4,
                waveSize,
                4,
                quartSizeY,
                4,
                4,
                4,
                4,
                climateInterleaved6
            );
        } catch (final RuntimeException ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Fused GPU biome wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());

        final int floatsPerChunk = quartPerChunk * 6;
        for (int b = 0; b < waveSize; b++) {
            final int origIdx = eligibleIndices.get(b);
            final BiomeCoordRequest req = batch.get(origIdx);
            final int base = b * floatsPerChunk;
            final float[] chunkClimate = Arrays.copyOfRange(climateInterleaved6, base, base + floatsPerChunk);
            results.set(origIdx, new Climate6BiomePayload(
                chunkClimate,
                quartSizeY,
                (MultiNoiseBiomeSource) req.generator().getBiomeSource()
            ));
        }
        return results;
    }

    private @Nullable CompiledDensityKernel getOrScheduleBiomeKernel(
        final BiomeCoordRequest sampleTask,
        final ParityMode mode
    ) {
        final NoiseBasedChunkGenerator nbGenerator = sampleTask.generator();
        final NoiseRouter router = nbGenerator.generatorSettings().value().noiseRouter();
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(router) + ":" + mode.name() + ":biomes6";
        final CompletableFuture<@Nullable CompiledDensityKernel> future = this.routerBiomeKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final DensityFunction.CompileContext compileCtx = sampleTask.randomState().paper$compileContext();
                final String baseName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_biome_";
                final List<DensityFunction> climate6 = List.of(
                    router.temperature(),
                    router.vegetation(),
                    router.continents(),
                    router.erosion(),
                    router.depth(),
                    router.ridges()
                );
                final OpenClBackendEmitter.DensityVolumeProgram program = lowerFusedBiomeClimateProgram(
                    climate6,
                    compileCtx,
                    baseName,
                    mode
                );
                if (program == null) {
                    return null;
                }
                return compileProgramBlocking(program, mode);
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async fused biome climate kernel compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    public DispatchStats executeBiomeRTreeBatch(
        final CompiledDensityKernel kernel,
        final int[] originsXyz4,
        final int batchCount,
        final int sizeX,
        final int sizeY,
        final int sizeZ,
        final int stepX,
        final int stepY,
        final int stepZ,
        final byte[] outBiomeIds
    ) {
        Objects.requireNonNull(kernel, "kernel");
        if (!kernel.hasLiveGpuKernel()) {
            throw new IllegalStateException("CompiledDensityKernel has no live OpenCL GPU handle");
        }
        final long[] timings = new long[2];
        final int err = kernel.gpuKernel().dispatchBiomeRTreeBatch(
            originsXyz4, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, outBiomeIds, timings
        );
        if (err != 0) {
            throw new IllegalStateException("OpenCL eval_biome_rtree_batch dispatch failed with error code " + err);
        }
        return new DispatchStats(timings[0], timings[1]);
    }

    private List<@Nullable PrimedBiomePayload> dispatchBiomeRTreeWaveBatchAsync(
        final List<BiomeCoordRequest> batch,
        final CompiledBiomeRTreeKernel compiled
    ) {
        final int n = batch.size();
        final List<@Nullable PrimedBiomePayload> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        final BiomeCoordRequest first = batch.get(0);
        final int firstHeight = first.height();
        final int quartSizeY = QuartPos.fromBlock(firstHeight);
        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final BiomeCoordRequest req = batch.get(i);
            if (req.height() == firstHeight) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int b = 0; b < waveSize; b++) {
            final BiomeCoordRequest req = batch.get(eligibleIndices.get(b));
            originsXyz4[b * 4] = req.pos().getMinBlockX();
            originsXyz4[b * 4 + 1] = req.minY();
            originsXyz4[b * 4 + 2] = req.pos().getMinBlockZ();
            originsXyz4[b * 4 + 3] = 0;
        }

        final int quartPerChunk = 4 * quartSizeY * 4;
        final byte[] outBiomeIds = new byte[waveSize * quartPerChunk];
        final DispatchStats stats;
        try {
            stats = executeBiomeRTreeBatch(
                compiled.kernel(),
                originsXyz4,
                waveSize,
                4,
                quartSizeY,
                4,
                4,
                4,
                4,
                outBiomeIds
            );
        } catch (final RuntimeException ex) {
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU biome R-tree wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());

        final net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] palette = compiled.biomePalette();
        for (int b = 0; b < waveSize; b++) {
            final int origIdx = eligibleIndices.get(b);
            final int base = b * quartPerChunk;
            final byte[] chunkBiomeIds = Arrays.copyOfRange(outBiomeIds, base, base + quartPerChunk);
            results.set(origIdx, new RTreeBiomePayload(chunkBiomeIds, quartSizeY, palette));
        }
        return results;
    }

    static final int MAX_GPU_RTREE_NODES = 65536;

    private @Nullable CompiledBiomeRTreeKernel getOrScheduleBiomeRTreeKernel(
        final BiomeCoordRequest sampleTask,
        final ParityMode mode
    ) {
        final NoiseBasedChunkGenerator nbGenerator = sampleTask.generator();
        if (!(nbGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource multiNoise)) {
            return null;
        }
        final NoiseRouter router = nbGenerator.generatorSettings().value().noiseRouter();
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(router)
            + ":" + System.identityHashCode(multiNoise)
            + ":" + mode.name() + ":biomes_rtree";
        final CompletableFuture<@Nullable CompiledBiomeRTreeKernel> future = this.routerBiomeRTreeKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final GpuBiomeRTree.FlattenedRTree flattened = GpuBiomeRTree.flatten(multiNoise);
                if (flattened == null || flattened.nodeCount() > MAX_GPU_RTREE_NODES) {
                    return null;
                }
                final DensityFunction.CompileContext compileCtx = sampleTask.randomState().paper$compileContext();
                final String baseName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_biome_";
                final List<DensityFunction> climate6 = List.of(
                    router.temperature(),
                    router.vegetation(),
                    router.continents(),
                    router.erosion(),
                    router.depth(),
                    router.ridges()
                );
                final OpenClBackendEmitter.DensityVolumeProgram climate6Prog = lowerFusedBiomeClimateProgram(
                    climate6,
                    compileCtx,
                    baseName,
                    mode
                );
                if (climate6Prog == null) {
                    return null;
                }
                final GpuBiomeRTree.FusedBiomeRTreeProgram fused = GpuBiomeRTree.buildFusedProgram(climate6Prog, flattened);
                if (fused == null) {
                    return null;
                }
                final CompiledDensityKernel compiled = compileProgramBlocking(fused.program(), mode);
                if (!compiled.hasLiveGpuKernel()) {
                    return null;
                }
                LOGGER.info(
                    "[GoldLeaf-OpenCL] GPU Biome R-Tree ready for '{}' (nodes={}, biomes={}, depth={}, dma=1B/quart)",
                    dimId,
                    flattened.nodeCount(),
                    flattened.biomePalette().length,
                    flattened.maxDepth()
                );
                return new CompiledBiomeRTreeKernel(compiled, flattened.biomePalette());
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async GPU biome R-tree compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    @Override
    public CompletableFuture<Boolean> submitTerrainAsync(final @Nullable WorldGenContext context, final TerrainTask task) {
        if (!isGpuReady() || !isEligibleForGpuVolume(task)) {
            this.cpuFallbackChunks.increment();
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final boolean hasBeardifier = task.beardifier() != null && task.beardifier() != Beardifier.EMPTY;
        final boolean useVanillaSurface = task.region() != null && !isGpuSurfaceActive(parityMode(), GoldLeafConfig.get());
        final CompiledVanillaSurfaceKernel vanillaSurface = useVanillaSurface
            ? getOrScheduleVanillaSurfaceKernel(task, parityMode())
            : null;
        final boolean vanillaSurfaceReady = vanillaSurface != null
            && vanillaSurface.terrainKernel().hasLiveGpuKernel()
            && vanillaSurface.surfaceKernel().hasLiveGpuKernel();
        if (hasBeardifier && !vanillaSurfaceReady) {
            this.cpuFallbackChunks.increment();
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        final WorldgenWaveCoalescer.WaveKey key = new WorldgenWaveCoalescer.WaveKey(
            task.level().dimension().identifier().toString(),
            System.identityHashCode(task.noiseRouter())
        );
        // Pre-warm shared 16-block prelimSurface corner cache and pre-pack 6x6 biome halo + carving mask in parallel on submitting worker
        byte[] packedHalo = null;
        int[] packedMask = null;
        if (useVanillaSurface) {
            final ChunkPos pos = task.chunk().getPos();
            final int minX = pos.getMinBlockX();
            final int minZ = pos.getMinBlockZ();
            final long seedSalt = task.randomState().seed() * 0x9E3779B97F4A7C15L;
            final var prelimSampler = task.randomState().getSampler(task.settings().noiseRouter().chunkSurfaceLevel());
            getOrSamplePrelimCorner(prelimSampler, seedSalt, minX, minZ);
            getOrSamplePrelimCorner(prelimSampler, seedSalt, minX + 16, minZ);
            getOrSamplePrelimCorner(prelimSampler, seedSalt, minX, minZ + 16);
            getOrSamplePrelimCorner(prelimSampler, seedSalt, minX + 16, minZ + 16);
            if (vanillaSurfaceReady) {
                final int minY = task.volume().minBlockY();
                final int secCnt = task.volume().sizeY() >>> 4;
                packedHalo = packChunkBiomesHalo(
                    task.chunk(), task.region(), vanillaSurface.surfaceProgram(), minX, minY, minZ, secCnt
                );
            }
            if (vanillaSurfaceReady
                && vanillaSurface.hasLiveCarverKernel()
                && !vanillaSurface.hasLiveCarverMaskKernel()
                && !net.minecraft.SharedConstants.DEBUG_DISABLE_CARVERS
                && !net.minecraft.SharedConstants.debugVoidTerrain(pos)) {
                final java.util.List<net.minecraft.world.level.levelgen.carver.WorldCarver> uniformCarvers =
                    task.generator().goldleaf$getUniformCarvers();
                if (uniformCarvers != null) {
                    final long maskStart = System.nanoTime();
                    packedMask = computePackedCarvingMask(task, uniformCarvers);
                    io.papermc.paper.spi.PaperWorldgenSpi.recordStageTiming(
                        io.papermc.paper.spi.PaperWorldgenSpi.STAGE_CARVER_MASK, System.nanoTime() - maskStart
                    );
                }
            }
        }
        return this.terrainWaveCoalescer.submit(key, new PreparedTerrainTask(task, packedHalo, packedMask))
            .thenCompose(Function.identity());
    }

    @Override
    public WaveResult accelerateTerrain(final @Nullable WorldGenContext context, final List<TerrainTask> tasks) {
        if (tasks.isEmpty() || !isGpuReady()) {
            return WaveResult.noneHandled(tasks.size());
        }
        if (tasks.size() == 1) {
            final Boolean handled = submitTerrainAsync(context, tasks.get(0)).join();
            final boolean[] flags = new boolean[] {Boolean.TRUE.equals(handled)};
            return new WaveResult(flags, 0L, 0L, 0L);
        }
        final List<CompletableFuture<Boolean>> futures = dispatchTerrainWaveBatchAsync(tasks);
        final boolean[] flags = new boolean[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            flags[i] = i < futures.size() && Boolean.TRUE.equals(futures.get(i).join());
        }
        return new WaveResult(flags, 0L, 0L, 0L);
    }

    private boolean isEligibleForGpuVolume(final TerrainTask task) {
        if (!task.blender().isEmpty()) {
            return false;
        }
        final DensityVolume volume = task.volume();
        if (volume.sizeX() != 16 || volume.sizeZ() != 16 || volume.sizeY() <= 0 || (volume.sizeY() & 15) != 0) {
            return false;
        }
        return volume.stepBlockX() == 1 && volume.stepBlockY() == 1 && volume.stepBlockZ() == 1;
    }

    private boolean isEligibleForGpuSectionFill(final TerrainTask task) {
        if (task.beardifier() != null && task.beardifier() != Beardifier.EMPTY) {
            return false;
        }
        return isEligibleForGpuVolume(task);
    }

    private List<CompletableFuture<Boolean>> dispatchPreparedTerrainWaveBatchAsync(final List<PreparedTerrainTask> preparedBatch) {
        final int n = preparedBatch.size();
        if (n == 0 || !isGpuReady()) {
            final List<CompletableFuture<Boolean>> results = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                results.add(CompletableFuture.completedFuture(Boolean.FALSE));
            }
            this.cpuFallbackChunks.add(n);
            return results;
        }
        final TerrainTask first = preparedBatch.get(0).task();
        final ParityMode mode = parityMode();
        if (!isGpuSurfaceActive(mode, GoldLeafConfig.get()) && first.region() != null) {
            final CompiledVanillaSurfaceKernel vanillaSurface = getOrScheduleVanillaSurfaceKernel(first, mode);
            if (vanillaSurface != null
                && vanillaSurface.terrainKernel().hasLiveGpuKernel()
                && vanillaSurface.surfaceKernel().hasLiveGpuKernel()) {
                return dispatchVanillaSurfaceWaveBatchAsync(preparedBatch, vanillaSurface);
            }
        }
        final List<TerrainTask> rawBatch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rawBatch.add(preparedBatch.get(i).task());
        }
        return dispatchTerrainWaveBatchAsync(rawBatch);
    }

    private List<CompletableFuture<Boolean>> dispatchTerrainWaveBatchAsync(final List<TerrainTask> batch) {
        final int n = batch.size();
        final List<CompletableFuture<Boolean>> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(CompletableFuture.completedFuture(Boolean.FALSE));
        }
        if (n == 0 || !isGpuReady()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final TerrainTask first = batch.get(0);
        final ParityMode mode = parityMode();
        if (isGpuSurfaceActive(mode, GoldLeafConfig.get())) {
            return dispatchTerrainSurfaceWaveBatchAsync(batch, mode);
        }
        // Vanilla-exact fused GPU TERRAIN + MaterialRule (buildSurface) when compiled & region halo is available
        if (first.region() != null) {
            final CompiledVanillaSurfaceKernel vanillaSurface = getOrScheduleVanillaSurfaceKernel(first, mode);
            if (vanillaSurface != null
                && vanillaSurface.terrainKernel().hasLiveGpuKernel()
                && vanillaSurface.surfaceKernel().hasLiveGpuKernel()) {
                final List<PreparedTerrainTask> wrapped = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    wrapped.add(new PreparedTerrainTask(batch.get(i), null, null));
                }
                return dispatchVanillaSurfaceWaveBatchAsync(wrapped, vanillaSurface);
            }
        }
        final CompiledDensityKernel compiled = getOrScheduleRouterKernel(first, mode);
        if (compiled == null || !compiled.hasLiveGpuKernel()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final NoiseGeneratorSettings settings = first.settings();
        final DensityVolume firstVol = first.volume();
        final int sectionCount = firstVol.sizeY() >>> 4;
        final int seaLevel = settings.seaLevel();
        final int deepFluidY = Math.min(-54, seaLevel);

        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final TerrainTask task = batch.get(i);
            final DensityVolume vol = task.volume();
            if (isEligibleForGpuSectionFill(task)
                && (vol.sizeY() >>> 4) == sectionCount
                && task.settings().seaLevel() == seaLevel) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int k = 0; k < waveSize; k++) {
            final DensityVolume vol = batch.get(eligibleIndices.get(k)).volume();
            originsXyz4[k * 4] = vol.minBlockX();
            originsXyz4[k * 4 + 1] = vol.minBlockY();
            originsXyz4[k * 4 + 2] = vol.minBlockZ();
            originsXyz4[k * 4 + 3] = 0;
        }

        final CompactSectionWritebackBufferPool.PooledWaveLease lease =
            this.writebackBufferPool.acquire(waveSize * sectionCount, waveSize);
        final DispatchStats stats;
        try {
            stats = executeSectionPaletteFillBatch(
                compiled,
                originsXyz4,
                waveSize,
                sectionCount,
                seaLevel,
                deepFluidY,
                lease.buffer()
            );
        } catch (final RuntimeException ex) {
            lease.releaseAll();
            LOGGER.warn("[GoldLeaf-OpenCL S-004] GPU wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());

        final long totalAccel = this.gpuAcceleratedChunks.sum();
        if (totalAccel == waveSize || (totalAccel % 128L) < waveSize) {
            logTelemetrySummary("progress");
        }

        final CompactSectionWritebackBuffer dmaBuffer = lease.buffer();
        for (int k = 0; k < waveSize; k++) {
            final int chunkIndexInWave = k;
            final int origIdx = eligibleIndices.get(k);
            final TerrainTask task = batch.get(origIdx);
            final DensityVolume vol = task.volume();
            final CompletableFuture<Boolean> chunkFuture = new CompletableFuture<>();
            results.set(origIdx, chunkFuture);
            try {
                this.hostApplyExecutor.execute(() -> {
                    final long applyStart = System.nanoTime();
                    try {
                        CompactSectionWritebackApplier.applyToChunk(
                            dmaBuffer,
                            chunkIndexInWave,
                            sectionCount,
                            vol.minBlockY(),
                            task.chunk(),
                            Blocks.AIR.defaultBlockState(),
                            task.settings().defaultBlock(),
                            task.settings().defaultFluid(),
                            Blocks.LAVA.defaultBlockState()
                        );
                        this.totalHostApplyNanos.add(System.nanoTime() - applyStart);
                        chunkFuture.complete(Boolean.TRUE);
                    } catch (final Throwable ex) {
                        LOGGER.warn(
                            "[GoldLeaf-OpenCL S-004] Parallel terrain hostApply failed for chunk {}; falling back to CPU",
                            task.chunk().getPos(),
                            ex
                        );
                        this.cpuFallbackChunks.increment();
                        chunkFuture.complete(Boolean.FALSE);
                    } finally {
                        lease.releaseChunk();
                    }
                });
            } catch (final RejectedExecutionException ex) {
                lease.releaseChunk();
                LOGGER.warn(
                    "[GoldLeaf-OpenCL S-004] hostApplyExecutor rejected terrain chunk {}; falling back to CPU",
                    task.chunk().getPos(),
                    ex
                );
                this.cpuFallbackChunks.increment();
                chunkFuture.complete(Boolean.FALSE);
            }
        }
        return results;
    }

    private List<CompletableFuture<Boolean>> dispatchTerrainSurfaceWaveBatchAsync(
        final List<TerrainTask> batch,
        final ParityMode mode
    ) {
        final int n = batch.size();
        final List<CompletableFuture<Boolean>> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(CompletableFuture.completedFuture(Boolean.FALSE));
        }
        final TerrainTask first = batch.get(0);
        final CompiledDensityKernel compiled = getOrScheduleRouterSurfaceKernel(first, mode);
        if (compiled == null || !compiled.hasLiveGpuKernel()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final NoiseGeneratorSettings settings = first.settings();
        final DensityVolume firstVol = first.volume();
        final int sectionCount = firstVol.sizeY() >>> 4;
        final int minBlockY = firstVol.minBlockY();
        final int seaLevel = settings.seaLevel();
        final int deepFluidY = Math.min(-54, seaLevel);

        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final TerrainTask task = batch.get(i);
            final DensityVolume vol = task.volume();
            if (isEligibleForGpuSectionFill(task)
                && (vol.sizeY() >>> 4) == sectionCount
                && vol.minBlockY() == minBlockY
                && task.settings().seaLevel() == seaLevel) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int[] originsXyz4 = new int[waveSize * 4];
        for (int k = 0; k < waveSize; k++) {
            final DensityVolume vol = batch.get(eligibleIndices.get(k)).volume();
            originsXyz4[k * 4] = vol.minBlockX();
            originsXyz4[k * 4 + 1] = vol.minBlockY();
            originsXyz4[k * 4 + 2] = vol.minBlockZ();
            originsXyz4[k * 4 + 3] = 0;
        }

        CompactSurfaceWritebackBuffer surfaceBuf = this.surfaceBufferPool.pollFirst();
        if (surfaceBuf == null) {
            surfaceBuf = new CompactSurfaceWritebackBuffer(Math.max(waveSize * sectionCount, 24 * 24));
        } else {
            surfaceBuf.ensureCapacity(waveSize, sectionCount);
        }

        final DispatchStats stats;
        try {
            stats = executeSurfaceSectionFill4BitBatch(
                compiled,
                originsXyz4,
                waveSize,
                sectionCount,
                seaLevel,
                deepFluidY,
                minBlockY,
                surfaceBuf
            );
        } catch (final RuntimeException ex) {
            returnSurfaceBuffer(surfaceBuf);
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Fused GPU surface wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());

        final CompactSurfaceWritebackBuffer leasedBuf = surfaceBuf;
        final AtomicInteger remaining = new AtomicInteger(waveSize);
        final net.minecraft.world.level.block.state.BlockState[] palette16 =
            CompactSurfaceWritebackApplier.defaultOverworldPalette16();
        for (int k = 0; k < waveSize; k++) {
            final int chunkIndexInWave = k;
            final int origIdx = eligibleIndices.get(k);
            final TerrainTask task = batch.get(origIdx);
            final CompletableFuture<Boolean> chunkFuture = new CompletableFuture<>();
            results.set(origIdx, chunkFuture);
            try {
                this.hostApplyExecutor.execute(() -> {
                    final long applyStart = System.nanoTime();
                    try {
                        CompactSurfaceWritebackApplier.applyToChunk(
                            leasedBuf,
                            chunkIndexInWave,
                            sectionCount,
                            minBlockY,
                            task.chunk(),
                            palette16
                        );
                        markSurfaceApplied(task.chunk());
                        this.totalHostApplyNanos.add(System.nanoTime() - applyStart);
                        chunkFuture.complete(Boolean.TRUE);
                    } catch (final Throwable ex) {
                        LOGGER.warn(
                            "[GoldLeaf-OpenCL S-004] Parallel surface hostApply failed for chunk {}; falling back to CPU",
                            task.chunk().getPos(),
                            ex
                        );
                        this.cpuFallbackChunks.increment();
                        chunkFuture.complete(Boolean.FALSE);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            returnSurfaceBuffer(leasedBuf);
                        }
                    }
                });
            } catch (final RejectedExecutionException ex) {
                if (remaining.decrementAndGet() == 0) {
                    returnSurfaceBuffer(leasedBuf);
                }
                LOGGER.warn(
                    "[GoldLeaf-OpenCL S-004] hostApplyExecutor rejected surface chunk {}; falling back to CPU",
                    task.chunk().getPos(),
                    ex
                );
                this.cpuFallbackChunks.increment();
                chunkFuture.complete(Boolean.FALSE);
            }
        }
        return results;
    }

    private void returnSurfaceBuffer(final CompactSurfaceWritebackBuffer buf) {
        if (!this.closed.get() && this.surfaceBufferPool.size() < CompactSectionWritebackBufferPool.DEFAULT_MAX_POOLED_BUFFERS) {
            this.surfaceBufferPool.offerFirst(buf);
        }
    }

    private @Nullable CompiledDensityKernel getOrScheduleRouterSurfaceKernel(
        final TerrainTask sampleTask,
        final ParityMode mode
    ) {
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(sampleTask.noiseRouter()) + ":" + mode.name() + ":surface4bit";
        final CompletableFuture<@Nullable CompiledDensityKernel> future = this.routerSurfaceFillKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final String moduleName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_surface";
                final OpenClBackendEmitter.DensityVolumeProgram program = lowerFusedSurfaceFillProgram(
                    sampleTask.noiseRouter().finalDensity(),
                    sampleTask.randomState().paper$compileContext(),
                    moduleName,
                    mode
                );
                if (program == null) {
                    return null;
                }
                return compileProgramBlocking(program, mode);
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async fused surface kernel compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    private @Nullable CompiledDensityKernel getOrScheduleRouterKernel(
        final TerrainTask sampleTask,
        final ParityMode mode
    ) {
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String routerKey = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(sampleTask.noiseRouter()) + ":" + mode.name();
        final CompletableFuture<@Nullable CompiledDensityKernel> future = this.routerSectionFillKernels.computeIfAbsent(
            routerKey,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final long startNs = System.nanoTime();
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Starting async IR lowering + OpenCL compile for dimension '{}' (mode={})...",
                    dimId,
                    mode
                );
                final String moduleName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_terrain";
                final DensityFunctionIrLowerer.SplitLoweringResult lowered = DensityFunctionIrLowerer.lowerSplitForSectionFill(
                    sampleTask.noiseRouter().finalDensity(),
                    sampleTask.randomState().paper$compileContext(),
                    moduleName
                );
                if (!lowered.isSupported() || lowered.module() == null) {
                    LOGGER.info(
                        "[GoldLeaf-OpenCL] Dimension '{}' finalDensity not GPU-lowerable ({}); keeping on CPU.",
                        dimId,
                        lowered.unsupportedReason()
                    );
                    return null;
                }
                final double lowerMs = (System.nanoTime() - startNs) / 1_000_000.0;
                final long emitStartNs = System.nanoTime();
                final OpenClBackendEmitter.DensityVolumeProgram program = optimizeAndEmitSectionFill(
                    lowered.module(),
                    lowered.coarseSpec(),
                    mode
                );
                final double emitMs = (System.nanoTime() - emitStartNs) / 1_000_000.0;
                final String coarseDesc = lowered.coarseSpec() != null
                    ? (lowered.coarseSpec().gridSizeXz() + "x" + lowered.coarseSpec().gridSizeY() + "x"
                        + lowered.coarseSpec().gridSizeXz() + " ch=" + lowered.coarseSpec().coarseFunctionNames().size())
                    : "dense";
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Dimension '{}' lowered ({} ms) + emitted OpenCL ({} ms, {} chars, coarse={}, nodes={}, dedup={}, octaves={}, lutWords={}, digest={}..); compiling on GPU...",
                    dimId,
                    String.format(Locale.ROOT, "%.1f", lowerMs),
                    String.format(Locale.ROOT, "%.1f", emitMs),
                    program.sourceCode().length(),
                    coarseDesc,
                    lowered.loweredNodes(),
                    lowered.deduplicatedNodes(),
                    lowered.noiseOctaves(),
                    program.spilledLutWords().length,
                    program.kernelDigestSha256().substring(0, Math.min(12, program.kernelDigestSha256().length()))
                );
                final long buildStartNs = System.nanoTime();
                final CompiledDensityKernel compiled = compileProgramBlocking(program, mode);
                final double buildMs = (System.nanoTime() - buildStartNs) / 1_000_000.0;
                final double totalMs = (System.nanoTime() - startNs) / 1_000_000.0;
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Dimension '{}' GPU kernel ready in {} ms (build/load={} ms, mode={}, digest={}..)",
                    dimId,
                    String.format(Locale.ROOT, "%.1f", totalMs),
                    String.format(Locale.ROOT, "%.1f", buildMs),
                    mode,
                    program.kernelDigestSha256().substring(0, Math.min(12, program.kernelDigestSha256().length()))
                );
                return compiled;
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async kernel lowering/compile failed for dimension '{}'", dimId, ex);
                return null;
            })
        );
        final GoldLeafConfig cfg = GoldLeafConfig.get();
        final boolean requireAccel = cfg != null && cfg.worldgen != null && cfg.worldgen.requireAccelerator;
        if (!future.isDone() && !requireAccel) {
            return null;
        }
        return future.join();
    }

    private String formatVramStageTimingsSummary() {
        final StringBuilder sb = new StringBuilder();
        for (final CompletableFuture<@Nullable CompiledVanillaSurfaceKernel> fut : this.routerVanillaSurfaceKernels.values()) {
            if (!fut.isDone() || fut.isCompletedExceptionally()) {
                continue;
            }
            final CompiledVanillaSurfaceKernel k = fut.getNow(null);
            if (k == null || !k.hasLiveVramStageProgram()) {
                continue;
            }
            final GoldLeafOpenClBridge.VramStageTiming[] timings = k.vramStageProgram().readStageTimings();
            for (final GoldLeafOpenClBridge.VramStageTiming t : timings) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(t.name())
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.2fms", t.totalGpuNanos() / 1_000_000.0));
            }
        }
        return sb.toString();
    }

    public void logTelemetrySummary(final String phase) {
        final long waves = this.coalescedWaves.sum();
        final long accel = this.gpuAcceleratedChunks.sum();
        final long fallback = this.cpuFallbackChunks.sum();
        final double avgWave = waves == 0L ? 0.0 : (double) accel / (double) waves;
        LOGGER.info(
            "[GoldLeaf-OpenCL] Telemetry ({}): waves={}, gpuChunks={}, cpuFallback={}, surfSpecialFallback={}, gpuCarved={}, carverOverflow={}, avgChunksPerWave={}, gpuKernel={}ms, readback={}ms, hostApply={}ms, biomePrime=[sub={}, hitReady={}, hitInFlight={}, evict={}], oreBatches={}, oreVeins={}, oreVoxels={}, oreKern={}ms, oreRead={}ms, oreOverflow={}, oreProf=[{}], light=[{}, vramLightAttached={}], vramStages=[{}]",
            phase,
            waves,
            accel,
            fallback,
            this.surfaceSpecialFallbackChunks.sum(),
            this.gpuCarvedChunks.sum(),
            this.carverOverflowFallbackChunks.sum(),
            String.format(Locale.ROOT, "%.2f", avgWave),
            String.format(Locale.ROOT, "%.2f", this.totalGpuKernelNanos.sum() / 1_000_000.0),
            String.format(Locale.ROOT, "%.2f", this.totalReadbackNanos.sum() / 1_000_000.0),
            String.format(Locale.ROOT, "%.2f", this.totalHostApplyNanos.sum() / 1_000_000.0),
            this.biomePrimedSubmitted.sum(),
            this.biomePrimedHitsReady.sum(),
            this.biomePrimedHitsInFlight.sum(),
            this.primedBiomeCache.evictedCount(),
            this.orePlacementEngine.gpuBatches(),
            this.orePlacementEngine.gpuVeins(),
            this.orePlacementEngine.gpuVoxels(),
            String.format(Locale.ROOT, "%.2f", this.orePlacementEngine.gpuKernelNanos() / 1_000_000.0),
            String.format(Locale.ROOT, "%.2f", this.orePlacementEngine.gpuReadbackNanos() / 1_000_000.0),
            this.orePlacementEngine.cpuOverflowFallbackVeins(),
            this.orePlacementEngine.profileBreakdown(),
            this.lightEngine.telemetrySummary(),
            this.vramLightAttachedChunks.sum(),
            formatVramStageTimingsSummary()
        );
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.terrainWaveCoalescer.close();
            this.biomeWaveCoalescer.close();
            if (this.gpuAcceleratedChunks.sum() > 0L || this.cpuFallbackChunks.sum() > 0L) {
                logTelemetrySummary("shutdown");
            }
            for (final CompletableFuture<@Nullable CompiledVanillaSurfaceKernel> fut : this.routerVanillaSurfaceKernels.values()) {
                if (fut.isDone() && !fut.isCompletedExceptionally()) {
                    final CompiledVanillaSurfaceKernel k = fut.getNow(null);
                    if (k != null && k.vramStageProgram() != null) {
                        k.vramStageProgram().close();
                    }
                }
            }
            this.inFlightCompiles.clear();
            this.routerSectionFillKernels.clear();
            this.routerSurfaceFillKernels.clear();
            this.routerVanillaSurfaceKernels.clear();
            this.routerBiomeKernels.clear();
            this.routerBiomeRTreeKernels.clear();
            this.primedBiomeCache.clear();
            this.prelimCornerCache.clear();
            this.surfaceAppliedChunks.clear();
            this.carverAppliedChunks.clear();
            this.surfaceBufferPool.clear();
            this.vanillaSurfaceBufferPool.clear();
            this.waveDispatchExecutor.shutdownNow();
            this.biomeDispatchExecutor.shutdownNow();
            this.hostApplyExecutor.shutdownNow();
            this.terrainContinuationExecutor.shutdownNow();
            this.compilerExecutor.shutdownNow();
            try {
                this.compilerExecutor.awaitTermination(250, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            this.lightEngine.close();
            this.orePlacementEngine.close();
            this.writebackBufferPool.close();
            this.kernelCache.close();
        }
    }

    private OpenClBackendEmitter.DensityVolumeProgram optimizeAndEmit(
        final BytecodeConceptIr.ConceptModule lifted,
        final ParityMode parityMode
    ) {
        if (!lifted.unmappedReasons().isEmpty()) {
            LOGGER.warn(
                "[GoldLeaf-IR S-004] Lifted module '{}' reported {} unmapped construct(s): {}",
                lifted.moduleName(),
                lifted.unmappedReasons().size(),
                lifted.unmappedReasons()
            );
        }
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode = selectArithmeticMode(parityMode);
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.DENSITY_VOLUME_BATCH_F32
        );
        final BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lifted, emitOptions);
        return this.openClEmitter.emitDensityVolumeBatchProgram(optimized, emitOptions);
    }

    private OpenClBackendEmitter.DensityVolumeProgram optimizeAndEmitSectionFill(
        final BytecodeConceptIr.ConceptModule lifted,
        final OpenClBackendEmitter.@Nullable CoarseInterpSpec coarseSpec,
        final ParityMode parityMode
    ) {
        if (!lifted.unmappedReasons().isEmpty()) {
            LOGGER.warn(
                "[GoldLeaf-IR S-004] Lifted module '{}' reported {} unmapped construct(s): {}",
                lifted.moduleName(),
                lifted.unmappedReasons().size(),
                lifted.unmappedReasons()
            );
        }
        final ConceptBackendEmitter.GpuExactArithmeticMode arithmeticMode = selectArithmeticMode(parityMode);
        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            arithmeticMode,
            ConceptBackendEmitter.BatchLayout.SECTION_PALETTE_FILL_U2
        );
        final BytecodeConceptIr.ConceptModule optimized = ConceptOptimizerPass.optimize(lifted, emitOptions);
        if (coarseSpec != null) {
            return this.openClEmitter.emitInterpolatedSectionPaletteFillProgram(optimized, emitOptions, coarseSpec);
        }
        return this.openClEmitter.emitSectionPaletteFillProgram(optimized, emitOptions);
    }

    ConceptBackendEmitter.GpuExactArithmeticMode selectArithmeticMode(final ParityMode parityMode) {
        return switch (parityMode) {
            case FAST -> ConceptBackendEmitter.GpuExactArithmeticMode.DOWNCAST_FP32;
            case VANILLA_EXACT -> ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
            case VANILLA_EXACT_FP64 -> {
                if (isGpuReady() && !GoldLeafOpenClBridge.deviceSupportsFp64(this.deviceIndex)) {
                    yield ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
                }
                yield ConceptBackendEmitter.GpuExactArithmeticMode.NATIVE_FP64;
            }
        };
    }

    public DispatchStats executeVanillaSurfaceU8Batch(
        final CompiledDensityKernel terrainKernel,
        final CompiledDensityKernel surfaceKernel,
        final CompiledDensityKernel packKernel,
        final @Nullable CompiledDensityKernel carverMaskKernel,
        final @Nullable CompiledDensityKernel carverKernel,
        final @Nullable CompiledDensityKernel featureKernel,
        final int[] originsXyz4,
        final byte[] biomesHalo,
        final float[] prelimCorners4,
        final int[] beardMeta2,
        final int[] beardItems8,
        final int beardItemCount,
        final int @Nullable [] carverMeta,
        final int @Nullable [] carverMasks,
        final int carvedChunkCount,
        final int wordsPerCol,
        final int minCarveY,
        final int maxCarveY,
        final int batchCount,
        final int sectionCount,
        final int seaLevel,
        final int deepFluidY,
        final int minBlockY,
        final long biomeZoomSeed,
        final CompactVanillaSurfaceWritebackBuffer outBuffer,
        final byte[] outChunkFlags,
        final int @Nullable [] outCarverEvents,
        final int @Nullable [] outCarverCounts
    ) {
        Objects.requireNonNull(terrainKernel, "terrainKernel");
        Objects.requireNonNull(surfaceKernel, "surfaceKernel");
        Objects.requireNonNull(packKernel, "packKernel");
        Objects.requireNonNull(outBuffer, "outBuffer");
        final GoldLeafOpenClBridge.CompiledKernel tGpu = terrainKernel.gpuKernel();
        final GoldLeafOpenClBridge.CompiledKernel sGpu = surfaceKernel.gpuKernel();
        final GoldLeafOpenClBridge.CompiledKernel pGpu = packKernel.gpuKernel();
        final GoldLeafOpenClBridge.CompiledKernel cmGpu = carverMaskKernel != null ? carverMaskKernel.gpuKernel() : null;
        final GoldLeafOpenClBridge.CompiledKernel cGpu = carverKernel != null ? carverKernel.gpuKernel() : null;
        final GoldLeafOpenClBridge.CompiledKernel fGpu = featureKernel != null ? featureKernel.gpuKernel() : null;
        if (tGpu == null || tGpu.isClosed() || sGpu == null || sGpu.isClosed() || pGpu == null || pGpu.isClosed()
            || (carverMaskKernel != null && (cmGpu == null || cmGpu.isClosed()))
            || (carverKernel != null && (cGpu == null || cGpu.isClosed()))
            || (featureKernel != null && (fGpu == null || fGpu.isClosed()))) {
            throw new IllegalStateException("GPU vanilla surface kernel tuple is unavailable");
        }
        outBuffer.ensureCapacity(batchCount, sectionCount);
        final long[] timings = new long[2];
        final int err = tGpu.dispatchVanillaSurfaceU8Batch(
            sGpu,
            pGpu,
            cmGpu,
            cGpu,
            fGpu,
            originsXyz4,
            biomesHalo,
            prelimCorners4,
            beardMeta2,
            beardItems8,
            beardItemCount,
            carverMeta,
            carverMasks,
            carvedChunkCount,
            wordsPerCol,
            minCarveY,
            maxCarveY,
            batchCount,
            sectionCount,
            seaLevel,
            deepFluidY,
            minBlockY,
            biomeZoomSeed,
            outBuffer.voxelsDirectBuffer(),
            outBuffer.metaDirectBuffer(),
            outBuffer.heightmapsDirectBuffer(),
            outChunkFlags,
            outCarverEvents,
            outCarverCounts,
            timings
        );
        if (err != 0) {
            throw new IllegalStateException(
                "OpenCL dispatchVanillaSurfaceU8Batch failed (err=" + err + ")"
            );
        }
        outBuffer.setGpuHeightmapsValid(true);
        return new DispatchStats(timings[0], timings[1]);
    }

    static boolean isVanillaOverworldForFeaturePass(
        final TerrainTask sampleTask,
        final net.minecraft.world.level.levelgen.WorldGenerationContext wgCtx
    ) {
        if (!sampleTask.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return false;
        }
        final NoiseGeneratorSettings settings = sampleTask.settings();
        if (wgCtx.getMinGenY() != -64 || wgCtx.getGenDepth() != 384 || settings.seaLevel() != 63) {
            return false;
        }
        if (!settings.defaultBlock().is(net.minecraft.world.level.block.Blocks.STONE)
            || !settings.defaultFluid().is(net.minecraft.world.level.block.Blocks.WATER)) {
            return false;
        }
        for (final net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b : sampleTask.generator().getBiomeSource().possibleBiomes()) {
            final var keyOpt = b.unwrapKey();
            if (keyOpt.isEmpty() || !"minecraft".equals(keyOpt.get().identifier().getNamespace())) {
                return false;
            }
        }
        return true;
    }

    private @Nullable CompiledVanillaSurfaceKernel getOrScheduleVanillaSurfaceKernel(
        final TerrainTask sampleTask,
        final ParityMode mode
    ) {
        final String dimId = sampleTask.level().dimension().identifier().toString();
        final String key = dimId + ":" + sampleTask.randomState().seed()
            + ":" + System.identityHashCode(sampleTask.settings())
            + ":" + sampleTask.settings().hashCode()
            + ":" + mode.name() + ":vanilla_surface_u8_2pass";
        final CompletableFuture<@Nullable CompiledVanillaSurfaceKernel> future = this.routerVanillaSurfaceKernels.computeIfAbsent(
            key,
            ignored -> CompletableFuture.supplyAsync(() -> {
                final long startNs = System.nanoTime();
                final String baseName = "goldleaf_" + dimId.replace(':', '_').replace('/', '_') + "_vsurf";
                final net.minecraft.world.level.levelgen.WorldGenerationContext wgCtx =
                    new net.minecraft.world.level.levelgen.WorldGenerationContext(sampleTask.generator(), sampleTask.level());
                final boolean flatBedrock = sampleTask.level().paperConfig().environment.generateFlatBedrock;
                final GoldLeafConfig cfg = GoldLeafConfig.get();
                final boolean macroNoise = cfg != null && cfg.worldgen != null && cfg.worldgen.macroNoiseOctaves;
                final boolean gpuCarverMaskOpt = cfg == null || cfg.worldgen == null || cfg.worldgen.gpuCarverMask;
                final boolean vanillaOverworldFeaturesEligible = isVanillaOverworldForFeaturePass(sampleTask, wgCtx);
                final boolean gpuInVramFeaturesOpt = cfg != null && cfg.worldgen != null && cfg.worldgen.gpuInVramFeatures
                    && vanillaOverworldFeaturesEligible;
                final java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> possibleBiomes =
                    sampleTask.generator().getBiomeSource().possibleBiomes();
                final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram lowered = GpuVanillaSurfaceCompiler.compile(
                    sampleTask.settings(),
                    sampleTask.randomState(),
                    wgCtx,
                    flatBedrock,
                    macroNoise,
                    gpuInVramFeaturesOpt,
                    possibleBiomes,
                    this.openClEmitter,
                    selectArithmeticMode(mode),
                    baseName
                );
                if (lowered == null) {
                    LOGGER.info("[GoldLeaf-OpenCL] Dimension '{}' MaterialRule not GPU-lowerable; using 2-bit terrain + CPU buildSurface.", dimId);
                    return null;
                }
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Compiling 2-pass vanilla-exact GPU TERRAIN+SURFACE for '{}' (terrain={} chars, surface={} chars, blocks={}, biomes={})...",
                    dimId,
                    lowered.terrainProgram().sourceCode().length(),
                    lowered.surfaceProgram().sourceCode().length(),
                    lowered.blockPalette().length,
                    lowered.biomeKeyToId().size()
                );
                final CompiledDensityKernel compiledTerrain = compileProgramBlocking(lowered.terrainProgram(), mode);
                final CompiledDensityKernel compiledSurface = compileProgramBlocking(lowered.surfaceProgram(), mode);
                final CompiledDensityKernel compiledPack = compileProgramBlocking(lowered.packProgram(), mode);
                if (!compiledTerrain.hasLiveGpuKernel() || !compiledSurface.hasLiveGpuKernel() || !compiledPack.hasLiveGpuKernel()) {
                    LOGGER.warn(
                        "[GoldLeaf-OpenCL S-004] Vanilla-exact GPU TERRAIN+SURFACE+PACK clBuildProgram failed for '{}': {}",
                        dimId,
                        GoldLeafOpenClBridge.lastBuildLog()
                    );
                    return null;
                }
                final net.minecraft.world.level.block.state.BlockState[] wgPalette = lowered.blockPalette();
                final String[] canonicalWgPalette = new String[wgPalette.length];
                final int[] dmaDictIds = new int[256];
                java.util.Arrays.fill(dmaDictIds, -1);
                for (int i = 0; i < wgPalette.length; i++) {
                    canonicalWgPalette[i] = dev.leafcraft.goldleaf.storage.LcaDirectSectionSerializer.canonicalBlockStateString(wgPalette[i]);
                    if (i < dmaDictIds.length) {
                        dmaDictIds[i] = i;
                    }
                }
                dev.leafcraft.goldleaf.storage.LcaResidencyManager.global()
                    .registerWorldgenBlockDictionary(dimId, canonicalWgPalette);
                final int dictUploadErr = compiledPack.gpuKernel().uploadBlockDictLut(dmaDictIds);
                if (dictUploadErr != 0) {
                    LOGGER.warn(
                        "[GoldLeaf-OpenCL S-004] Failed uploading worldgen block dictionary LUT ({} entries) for '{}' (err={}); continuing with CPU dictionary resolution.",
                        wgPalette.length,
                        dimId,
                        dictUploadErr
                    );
                }
                final java.util.List<net.minecraft.world.level.levelgen.carver.WorldCarver> uniformCarvers =
                    sampleTask.generator().goldleaf$getUniformCarvers();
                final boolean needsCarverKernel = sampleTask.settings().aquifers().isPresent()
                    || uniformCarvers == null
                    || !uniformCarvers.isEmpty();
                CompiledDensityKernel compiledCarver = null;
                if (needsCarverKernel) {
                    final OpenClBackendEmitter.DensityVolumeProgram carverProg = GpuCarverAquiferCompiler.compile(
                        sampleTask.settings(),
                        sampleTask.randomState(),
                        lowered.blockPalette(),
                        this.openClEmitter,
                        selectArithmeticMode(mode),
                        baseName
                    );
                    if (carverProg != null) {
                        final CompiledDensityKernel candidate = compileProgramBlocking(carverProg, mode);
                        if (candidate.hasLiveGpuKernel()) {
                            compiledCarver = candidate;
                        } else {
                            LOGGER.warn(
                                "[GoldLeaf-OpenCL S-004] Vanilla-exact GPU CARVER+AQUIFER clBuildProgram failed for '{}': {}",
                                dimId,
                                GoldLeafOpenClBridge.lastBuildLog()
                            );
                        }
                    }
                }
                if (sampleTask.settings().aquifers().isPresent() && compiledCarver == null) {
                    LOGGER.warn(
                        "[GoldLeaf-OpenCL S-004] Dimension '{}' requires aquifers but GPU CARVER+AQUIFER kernel was unavailable (blocks={}); failing closed to 2-bit terrain + CPU buildSurface.",
                        dimId,
                        lowered.blockPalette().length
                    );
                    return null;
                }
                CompiledDensityKernel compiledCarverMask = null;
                boolean perBiomeCarverMask = false;
                if (compiledCarver != null && gpuCarverMaskOpt) {
                    OpenClBackendEmitter.DensityVolumeProgram maskProg = null;
                    if (uniformCarvers != null && !uniformCarvers.isEmpty()) {
                        maskProg = GpuCarverMaskCompiler.compile(
                            uniformCarvers,
                            sampleTask.randomState().seed(),
                            wgCtx,
                            baseName
                        );
                    } else if (uniformCarvers == null
                            && sampleTask.generator().getBiomeSource() instanceof MultiNoiseBiomeSource multiNoise) {
                        final GpuBiomeRTree.FlattenedRTree flattened = GpuBiomeRTree.flatten(multiNoise);
                        if (flattened != null && flattened.nodeCount() <= MAX_GPU_RTREE_NODES) {
                            final NoiseRouter router = sampleTask.settings().noiseRouter();
                            final List<DensityFunction> climate6 = List.of(
                                router.temperature(),
                                router.vegetation(),
                                router.continents(),
                                router.erosion(),
                                router.depth(),
                                router.ridges()
                            );
                            final OpenClBackendEmitter.DensityVolumeProgram climate6Prog = lowerFusedBiomeClimateProgram(
                                climate6,
                                sampleTask.randomState().paper$compileContext(),
                                baseName + "cm_biome_",
                                mode
                            );
                            if (climate6Prog != null) {
                                final GpuBiomeRTree.FusedBiomeRTreeProgram fused = GpuBiomeRTree.buildFusedProgram(
                                    climate6Prog,
                                    flattened
                                );
                                if (fused != null) {
                                    maskProg = GpuCarverMaskCompiler.compilePerBiome(
                                        fused,
                                        biome -> sampleTask.generator().getBiomeGenerationSettings(biome).getCarvers(),
                                        sampleTask.randomState().seed(),
                                        wgCtx,
                                        baseName
                                    );
                                    perBiomeCarverMask = maskProg != null;
                                }
                            }
                        }
                    }
                    if (maskProg != null) {
                        final CompiledDensityKernel candidateMask = compileProgramBlocking(maskProg, mode);
                        if (candidateMask.hasLiveGpuKernel()) {
                            compiledCarverMask = candidateMask;
                        } else {
                            LOGGER.warn(
                                "[GoldLeaf-OpenCL S-004] Vanilla-exact GPU CARVER_MASK ({}) clBuildProgram failed for '{}' (falling back to CPU mask): {}",
                                perBiomeCarverMask ? "per-biome" : "uniform",
                                dimId,
                                GoldLeafOpenClBridge.lastBuildLog()
                            );
                        }
                    }
                }
                CompiledDensityKernel compiledFeaturePass = null;
                if (gpuInVramFeaturesOpt && compiledCarver != null) {
                    final OpenClBackendEmitter.DensityVolumeProgram featProg = GpuFeaturePassCompiler.compile(
                        lowered.blockPalette(),
                        lowered.biomeKeyToId(),
                        sampleTask.randomState().seed(),
                        baseName
                    );
                    if (featProg != null) {
                        final CompiledDensityKernel candidateFeat = compileProgramBlocking(featProg, mode);
                        if (candidateFeat.hasLiveGpuKernel()) {
                            compiledFeaturePass = candidateFeat;
                        } else {
                            LOGGER.warn(
                                "[GoldLeaf-OpenCL S-004] GPU Pass 4 FEATURES clBuildProgram failed for '{}' (falling back to CPU features): {}",
                                dimId,
                                GoldLeafOpenClBridge.lastBuildLog()
                            );
                        }
                    }
                }
                CompiledDensityKernel compiledVramLight = null;
                final boolean gpuLightOpt = cfg != null && cfg.worldgen != null && cfg.worldgen.gpuLightEnabled;
                if (gpuLightOpt && sampleTask.level().dimensionType().hasSkyLight()) {
                    final OpenClBackendEmitter.DensityVolumeProgram vramLightProg =
                        GpuLightEngine.buildVramLightProgram(lowered.blockPalette());
                    if (vramLightProg != null) {
                        final CompiledDensityKernel candidateLight = compileProgramBlocking(vramLightProg, mode);
                        if (candidateLight.hasLiveGpuKernel()) {
                            compiledVramLight = candidateLight;
                        } else {
                            LOGGER.warn(
                                "[GoldLeaf-OpenCL S-004] GPU VRAM_LIGHT stage clBuildProgram failed for '{}' (falling back to CPU Starlight): {}",
                                dimId,
                                GoldLeafOpenClBridge.lastBuildLog()
                            );
                        }
                    }
                }
                final GoldLeafOpenClBridge.VramStageProgram vramStageProgram = buildVramStageProgram(
                    this.deviceIndex,
                    compiledTerrain,
                    compiledSurface,
                    compiledPack,
                    compiledCarverMask,
                    compiledCarver,
                    sampleTask.settings().aquifers().isPresent(),
                    compiledFeaturePass,
                    compiledVramLight
                );
                if (vramStageProgram != null && vramStageProgram.hasVramLight()) {
                    this.vramLightStageActive = true;
                }
                final double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
                LOGGER.info(
                    "[GoldLeaf-OpenCL] Vanilla-exact GPU TERRAIN+SURFACE{}{}{}{}{} kernel ready for '{}' in {} ms (blocks={}, biomes={}, mode={}, macroNoise={}, vramStages={})",
                    compiledCarver != null ? "+CARVER" : "",
                    compiledCarverMask != null ? (perBiomeCarverMask ? "+MASK(per-biome)" : "+MASK") : "",
                    compiledFeaturePass != null ? "+FEATURES" : "",
                    compiledVramLight != null ? "+VRAM_LIGHT" : "",
                    vramStageProgram != null ? " [VRAM-Loader]" : "",
                    dimId,
                    String.format(Locale.ROOT, "%.1f", elapsedMs),
                    lowered.blockPalette().length,
                    lowered.biomeKeyToId().size(),
                    mode,
                    macroNoise,
                    vramStageProgram != null ? vramStageProgram.stageCount() : 0
                );
                return new CompiledVanillaSurfaceKernel(
                    compiledTerrain,
                    compiledSurface,
                    compiledPack,
                    compiledCarverMask,
                    compiledCarver,
                    compiledFeaturePass,
                    compiledVramLight,
                    vramStageProgram,
                    lowered
                );
            }, this.compilerExecutor).exceptionally(ex -> {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] Async vanilla-exact GPU surface compile failed for '{}'", dimId, ex);
                return null;
            })
        );
        if (!future.isDone()) {
            return null;
        }
        return future.join();
    }

    private List<CompletableFuture<Boolean>> dispatchVanillaSurfaceWaveBatchAsync(
        final List<PreparedTerrainTask> batch,
        final CompiledVanillaSurfaceKernel compiled
    ) {
        final int n = batch.size();
        final List<CompletableFuture<Boolean>> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(CompletableFuture.completedFuture(Boolean.FALSE));
        }
        final TerrainTask first = batch.get(0).task();
        final int firstMinY = first.volume().minBlockY();
        final int firstSizeY = first.volume().sizeY();
        final int sectionCount = firstSizeY >>> 4;
        final List<Integer> eligibleIndices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final TerrainTask task = batch.get(i).task();
            if (isEligibleForGpuVolume(task)
                && task.region() != null
                && task.volume().minBlockY() == firstMinY
                && task.volume().sizeY() == firstSizeY) {
                eligibleIndices.add(i);
            }
        }
        if (eligibleIndices.isEmpty()) {
            this.cpuFallbackChunks.add(n);
            return results;
        }

        final int waveSize = eligibleIndices.size();
        final int qSizeY = sectionCount * 4;
        final int haloPerChunk = 36 * qSizeY;
        final VanillaWaveScratch scratch = VANILLA_WAVE_SCRATCH.get();
        if (scratch.originsXyz4.length < waveSize * 4) {
            scratch.originsXyz4 = new int[waveSize * 4];
            scratch.prelimCorners4 = new float[waveSize * 4];
            scratch.beardMeta2 = new int[waveSize * 2];
            scratch.carverMeta = new int[waveSize];
            scratch.chunkCarverMasks = new int[waveSize][];
            scratch.carverEligible = new boolean[waveSize];
            scratch.outChunkFlags = new byte[waveSize];
            scratch.outCarverCounts = new int[waveSize];
            scratch.outVramLightMeta2 = new int[waveSize * 2];
        }
        if (scratch.biomesHalo.length < waveSize * haloPerChunk) {
            scratch.biomesHalo = new byte[waveSize * haloPerChunk];
        }
        final int[] originsXyz4 = scratch.originsXyz4;
        final byte[] biomesHalo = scratch.biomesHalo;
        final float[] prelimCorners4 = scratch.prelimCorners4;
        final int[] beardMeta2 = scratch.beardMeta2;
        int totalBeardItems = 0;
        for (int b = 0; b < waveSize; b++) {
            final Beardifier bf = batch.get(eligibleIndices.get(b)).task().beardifier();
            if (bf != null && bf != Beardifier.EMPTY) {
                totalBeardItems += bf.goldleaf$piecesArray().length + bf.goldleaf$junctionsArray().length;
            }
        }
        final int reqBeardInts = Math.max(1, totalBeardItems) * 8;
        if (scratch.beardItems8.length < reqBeardInts) {
            scratch.beardItems8 = new int[reqBeardInts];
        }
        final int[] beardItems8 = scratch.beardItems8;
        int beardCursor = 0;
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfProg = compiled.surfaceProgram();
        final var prelimSampler = first.randomState().getSampler(first.settings().noiseRouter().chunkSurfaceLevel());
        final long seedSalt = first.randomState().seed() * 0x9E3779B97F4A7C15L;
        final int minQy = firstMinY >> 2;

        final boolean useGpuCarver = compiled.hasLiveCarverKernel();
        final boolean useGpuCarverMask = useGpuCarver && compiled.hasLiveCarverMaskKernel();
        final boolean useGpuFeatures = useGpuCarver && compiled.hasLiveFeatureKernel();
        final int wordsPerCol = (firstSizeY + 31) >>> 5;
        final int[] carverMeta = useGpuCarver ? scratch.carverMeta : null;
        final int[][] chunkCarverMasks = useGpuCarver ? scratch.chunkCarverMasks : null;
        final boolean[] carverEligible = useGpuCarver ? scratch.carverEligible : null;
        int carvedChunkCount = 0;

        for (int b = 0; b < waveSize; b++) {
            final PreparedTerrainTask prep = batch.get(eligibleIndices.get(b));
            final TerrainTask task = prep.task();
            final ChunkPos pos = task.chunk().getPos();
            final int minBlockX = pos.getMinBlockX();
            final int minBlockZ = pos.getMinBlockZ();
            originsXyz4[b * 4] = minBlockX;
            originsXyz4[b * 4 + 1] = firstMinY;
            originsXyz4[b * 4 + 2] = minBlockZ;
            originsXyz4[b * 4 + 3] = 0;
            prelimCorners4[b * 4] = getOrSamplePrelimCorner(prelimSampler, seedSalt, minBlockX, minBlockZ);
            prelimCorners4[b * 4 + 1] = getOrSamplePrelimCorner(prelimSampler, seedSalt, minBlockX + 16, minBlockZ);
            prelimCorners4[b * 4 + 2] = getOrSamplePrelimCorner(prelimSampler, seedSalt, minBlockX, minBlockZ + 16);
            prelimCorners4[b * 4 + 3] = getOrSamplePrelimCorner(prelimSampler, seedSalt, minBlockX + 16, minBlockZ + 16);

            if (useGpuCarverMask) {
                if (!task.chunk().isUpgrading()
                    && !net.minecraft.SharedConstants.DEBUG_DISABLE_CARVERS
                    && !net.minecraft.SharedConstants.debugVoidTerrain(pos)) {
                    carverMeta[b] = -1;
                    carverEligible[b] = true;
                } else {
                    carverMeta[b] = -2;
                    carverEligible[b] = false;
                }
                chunkCarverMasks[b] = null;
            } else if (useGpuCarver) {
                final int[] preMask = prep.packedCarvingMask();
                if (preMask == null) {
                    carverMeta[b] = -2;
                    carverEligible[b] = false;
                    chunkCarverMasks[b] = null;
                } else if (preMask.length == 0) {
                    carverMeta[b] = -1;
                    carverEligible[b] = true;
                    chunkCarverMasks[b] = null;
                } else {
                    carverMeta[b] = carvedChunkCount++;
                    chunkCarverMasks[b] = preMask;
                    carverEligible[b] = true;
                }
            }

            final Beardifier bf = task.beardifier();
            final int startOff = beardCursor;
            if (bf != null && bf != Beardifier.EMPTY) {
                for (final Beardifier.Rigid r : bf.goldleaf$piecesArray()) {
                    final int mode = switch (r.terrainAdjustment()) {
                        case NONE -> 0;
                        case BURY -> 1;
                        case BEARD_THIN -> 2;
                        case BEARD_BOX -> 3;
                        case ENCAPSULATE -> 4;
                    };
                    if (mode == 0) {
                        continue;
                    }
                    final var box = r.box();
                    final int base = beardCursor * 8;
                    beardItems8[base] = box.minX();
                    beardItems8[base + 1] = box.minY();
                    beardItems8[base + 2] = box.minZ();
                    beardItems8[base + 3] = box.maxX();
                    beardItems8[base + 4] = box.maxY();
                    beardItems8[base + 5] = box.maxZ();
                    beardItems8[base + 6] = box.minY() + r.groundLevelDelta();
                    beardItems8[base + 7] = mode;
                    beardCursor++;
                }
                for (final var j : bf.goldleaf$junctionsArray()) {
                    final int base = beardCursor * 8;
                    beardItems8[base] = j.getSourceX();
                    beardItems8[base + 1] = j.getSourceGroundY();
                    beardItems8[base + 2] = j.getSourceZ();
                    beardItems8[base + 3] = 0;
                    beardItems8[base + 4] = 0;
                    beardItems8[base + 5] = 0;
                    beardItems8[base + 6] = 0;
                    beardItems8[base + 7] = 5;
                    beardCursor++;
                }
            }
            beardMeta2[b * 2] = startOff;
            beardMeta2[b * 2 + 1] = beardCursor - startOff;

            final int chunkHaloBase = b * haloPerChunk;
            final byte[] prepacked = prep.packedBiomesHalo();
            if (prepacked != null && prepacked.length == haloPerChunk) {
                System.arraycopy(prepacked, 0, biomesHalo, chunkHaloBase, haloPerChunk);
            } else {
                final byte[] packed = packChunkBiomesHalo(
                    task.chunk(), task.region(), surfProg, minBlockX, firstMinY, minBlockZ, sectionCount
                );
                System.arraycopy(packed, 0, biomesHalo, chunkHaloBase, haloPerChunk);
            }
        }

        CompactVanillaSurfaceWritebackBuffer pooled = this.vanillaSurfaceBufferPool.pollFirst();
        if (pooled == null) {
            pooled = new CompactVanillaSurfaceWritebackBuffer(waveSize * sectionCount);
        }
        final CompactVanillaSurfaceWritebackBuffer waveBuffer = pooled;
        final byte[] outChunkFlags = scratch.outChunkFlags;
        final int seaLevel = first.settings().seaLevel();
        final int deepFluidY = Math.min(seaLevel, -54);
        final long biomeZoomSeed = net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(first.level().getSeed());

        final int[] carverMasksFlat;
        final int[] outCarverEvents;
        final int[] outCarverCounts;
        if (useGpuCarverMask) {
            carverMasksFlat = null;
            outCarverEvents = new int[waveSize * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS];
            outCarverCounts = scratch.outCarverCounts;
        } else if (useGpuCarver) {
            final int intsPerChunk = 256 * wordsPerCol;
            final int reqMasks = Math.max(1, carvedChunkCount * intsPerChunk);
            if (scratch.carverMasksFlat.length < reqMasks) {
                scratch.carverMasksFlat = new int[reqMasks];
            }
            carverMasksFlat = scratch.carverMasksFlat;
            for (int b = 0; b < waveSize; b++) {
                final int slot = carverMeta[b];
                if (slot >= 0) {
                    System.arraycopy(chunkCarverMasks[b], 0, carverMasksFlat, slot * intsPerChunk, intsPerChunk);
                    chunkCarverMasks[b] = null;
                }
            }
            outCarverEvents = carvedChunkCount > 0 ? new int[waveSize * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS] : null;
            outCarverCounts = scratch.outCarverCounts;
        } else {
            carverMasksFlat = null;
            outCarverEvents = null;
            outCarverCounts = null;
        }

        final boolean useVramStageProgram = compiled.hasLiveVramStageProgram();
        final boolean useVramLight = useVramStageProgram && compiled.vramStageProgram().hasVramLight();
        final int[] outVramLightMeta2 = useVramLight ? scratch.outVramLightMeta2 : null;

        final DispatchStats stats;
        try {
            if (useVramStageProgram) {
                waveBuffer.ensureCapacity(waveSize, sectionCount);
                if (useVramLight) {
                    waveBuffer.ensureVramLightCapacity(waveSize, sectionCount);
                }
                final long[] timings = new long[2];
                final int err = compiled.vramStageProgram().execute(
                    originsXyz4,
                    biomesHalo,
                    prelimCorners4,
                    beardMeta2,
                    beardItems8,
                    beardCursor,
                    carverMeta,
                    carverMasksFlat,
                    carvedChunkCount,
                    wordsPerCol,
                    firstMinY + 1,
                    firstMinY + firstSizeY - 1 - 7,
                    waveSize,
                    sectionCount,
                    seaLevel,
                    deepFluidY,
                    firstMinY,
                    biomeZoomSeed,
                    waveBuffer.voxelsDirectBuffer(),
                    waveBuffer.metaDirectBuffer(),
                    waveBuffer.heightmapsDirectBuffer(),
                    outChunkFlags,
                    outCarverEvents,
                    outCarverCounts,
                    useVramLight ? waveBuffer.vramSkyDirectBuffer() : null,
                    useVramLight ? waveBuffer.vramBlkDirectBuffer() : null,
                    useVramLight ? waveBuffer.vramLightFlagsDirectBuffer() : null,
                    outVramLightMeta2,
                    timings
                );
                if (err != 0) {
                    throw new IllegalStateException("OpenCL executeVramPipeline failed (err=" + err + ")");
                }
                waveBuffer.setGpuHeightmapsValid(true);
                waveBuffer.setVramLightValid(useVramLight);
                stats = new DispatchStats(timings[0], timings[1]);
            } else {
                waveBuffer.setVramLightValid(false);
                stats = executeVanillaSurfaceU8Batch(
                    compiled.terrainKernel(),
                    compiled.surfaceKernel(),
                    compiled.packKernel(),
                    useGpuCarverMask ? compiled.carverMaskKernel() : null,
                    useGpuCarver ? compiled.carverKernel() : null,
                    useGpuFeatures ? compiled.featureKernel() : null,
                    originsXyz4,
                    biomesHalo,
                    prelimCorners4,
                    beardMeta2,
                    beardItems8,
                    beardCursor,
                    carverMeta,
                    carverMasksFlat,
                    carvedChunkCount,
                    wordsPerCol,
                    firstMinY + 1,
                    firstMinY + firstSizeY - 1 - 7,
                    waveSize,
                    sectionCount,
                    seaLevel,
                    deepFluidY,
                    firstMinY,
                    biomeZoomSeed,
                    waveBuffer,
                    outChunkFlags,
                    outCarverEvents,
                    outCarverCounts
                );
            }
        } catch (final RuntimeException ex) {
            this.vanillaSurfaceBufferPool.offerFirst(waveBuffer);
            LOGGER.warn("[GoldLeaf-OpenCL S-004] Vanilla-exact GPU surface wave dispatch failed for {} chunks; falling back to CPU", waveSize, ex);
            this.cpuFallbackChunks.add(n);
            return results;
        }

        this.coalescedWaves.increment();
        this.gpuAcceleratedChunks.add(waveSize);
        if (waveSize < n) {
            this.cpuFallbackChunks.add(n - waveSize);
        }
        this.totalGpuKernelNanos.add(stats.gpuKernelNanos());
        this.totalReadbackNanos.add(stats.readbackNanos());

        final AtomicInteger remaining = new AtomicInteger(waveSize);
        final net.minecraft.world.level.block.state.BlockState[] palette = surfProg.blockPalette();

        for (int b = 0; b < waveSize; b++) {
            final int origIdx = eligibleIndices.get(b);
            final TerrainTask task = batch.get(origIdx).task();
            final int batchIdx = b;
            final boolean surfaceComplete = (outChunkFlags[b] & 3) == 0;
            final boolean carverOverflow = (outChunkFlags[b] & GpuCarverAquiferCompiler.FLAG_CARVER_EVENT_OVERFLOW) != 0;
            final boolean carverComplete = useGpuCarver && surfaceComplete && carverEligible[b] && !carverOverflow;
            final int carverEventCount = (outCarverCounts != null) ? outCarverCounts[b] : 0;
            if (!surfaceComplete) {
                this.surfaceSpecialFallbackChunks.increment();
            }
            if (useGpuCarver && carverOverflow) {
                this.carverOverflowFallbackChunks.increment();
            }
            final CompletableFuture<Boolean> chunkFuture = new CompletableFuture<>();
            results.set(origIdx, chunkFuture);
            try {
                this.hostApplyExecutor.execute(() -> {
                    final long applyStart = System.nanoTime();
                    try {
                        final ChunkAccess chunk = task.chunk();
                        final int bottomSectionIndex = chunk.getSectionIndex(firstMinY);
                        CompactVanillaSurfaceWritebackApplier.applyToChunk(
                            chunk, waveBuffer, batchIdx, bottomSectionIndex, sectionCount, palette, surfaceComplete && carverComplete
                        );
                        if (surfaceComplete) {
                            markSurfaceApplied(chunk);
                        }
                        if (useVramLight && outVramLightMeta2 != null
                            && surfaceComplete && (!useGpuCarver || carverComplete)) {
                            final int activeSecs = outVramLightMeta2[batchIdx * 2];
                            final boolean hasBlk = outVramLightMeta2[batchIdx * 2 + 1] != 0;
                            final io.papermc.paper.spi.PaperWorldgenSpi.PrecomputedChunkLight vramLight =
                                waveBuffer.extractVramPrecomputedLight(batchIdx, sectionCount, activeSecs, hasBlk);
                            if (vramLight != null) {
                                chunk.goldleaf$setVramPrecomputedLight(vramLight);
                                this.vramLightAttachedChunks.increment();
                            }
                        }
                        if (carverEventCount > 0 && outCarverEvents != null) {
                            applyCarverPostEvents(
                                task,
                                chunk,
                                firstMinY,
                                outCarverEvents,
                                batchIdx * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS,
                                carverEventCount
                            );
                        }
                        if (carverComplete) {
                            markCarverApplied(chunk);
                            this.gpuCarvedChunks.increment();
                            if (useGpuFeatures) {
                                chunk.goldleaf$markGpuFeaturesApplied();
                            }
                        }
                        this.totalHostApplyNanos.add(System.nanoTime() - applyStart);
                        chunkFuture.complete(Boolean.TRUE);
                    } catch (final Throwable ex) {
                        LOGGER.warn("[GoldLeaf-OpenCL S-004] Parallel vanilla surface hostApply failed for chunk {}; falling back to CPU", task.chunk().getPos(), ex);
                        this.cpuFallbackChunks.increment();
                        chunkFuture.complete(Boolean.FALSE);
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            if (this.vanillaSurfaceBufferPool.size() < 6) {
                                this.vanillaSurfaceBufferPool.offerFirst(waveBuffer);
                            }
                        }
                    }
                });
            } catch (final RejectedExecutionException ex) {
                LOGGER.warn("[GoldLeaf-OpenCL S-004] hostApplyExecutor rejected vanilla surface chunk {}; falling back to CPU", task.chunk().getPos(), ex);
                this.cpuFallbackChunks.increment();
                chunkFuture.complete(Boolean.FALSE);
                if (remaining.decrementAndGet() == 0) {
                    if (this.vanillaSurfaceBufferPool.size() < 4) {
                        this.vanillaSurfaceBufferPool.offerFirst(waveBuffer);
                    }
                }
            }
        }
        return results;
    }

    private static byte[] packChunkBiomesHalo(
        final ChunkAccess chunk,
        final net.minecraft.world.level.LevelAccessor region,
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfProg,
        final int minBlockX,
        final int minBlockY,
        final int minBlockZ,
        final int sectionCount
    ) {
        final int qSizeY = sectionCount * 4;
        final byte[] halo = new byte[36 * qSizeY];
        final int minQx = minBlockX >> 2;
        final int minQy = minBlockY >> 2;
        final int minQz = minBlockZ >> 2;
        final int bottomSec = chunk.getSectionIndex(minBlockY);
        for (int s = 0; s < sectionCount; s++) {
            final var secBiomes = chunk.getSection(bottomSec + s).getBiomes();
            final int hyBase = s << 2;
            for (int lz = 0; lz < 4; lz++) {
                for (int lx = 0; lx < 4; lx++) {
                    final int colBase = ((lx + 1) + (lz + 1) * 6) * qSizeY + hyBase;
                    for (int ly = 0; ly < 4; ly++) {
                        halo[colBase + ly] = surfProg.mapBiomeByte(secBiomes.get(lx, ly, lz));
                    }
                }
            }
        }
        for (int hz = 0; hz < 6; hz++) {
            final int qz = minQz + hz - 1;
            for (int hx = 0; hx < 6; hx++) {
                if (hx >= 1 && hx <= 4 && hz >= 1 && hz <= 4) {
                    continue;
                }
                final int qx = minQx + hx - 1;
                final int colBase = (hx + hz * 6) * qSizeY;
                for (int hy = 0; hy < qSizeY; hy++) {
                    halo[colBase + hy] = surfProg.mapBiomeByte(region.getNoiseBiome(qx, minQy + hy, qz));
                }
            }
        }
        return halo;
    }

    private float getOrSamplePrelimCorner(
        final net.minecraft.world.level.levelgen.densityfunction.DensitySampler prelimSampler,
        final long seedSalt,
        final int blockX,
        final int blockZ
    ) {
        final long key = ((((long) (blockX >> 4)) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL)) ^ seedSalt;
        final Float cached = this.prelimCornerCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (this.prelimCornerCache.size() > 65536) {
            this.prelimCornerCache.clear();
        }
        final float val = prelimSampler.sampleValue(
            net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED, blockX, 0, blockZ
        );
        this.prelimCornerCache.put(key, val);
        return val;
    }

    private static int[] computePackedCarvingMask(
        final TerrainTask task,
        final java.util.List<net.minecraft.world.level.levelgen.carver.WorldCarver> uniformCarvers
    ) {
        if (uniformCarvers.isEmpty()) {
            return EMPTY_CARVING_MASK;
        }
        final ChunkAccess chunk = task.chunk();
        final ChunkPos pos = chunk.getPos();
        final net.minecraft.world.level.levelgen.WorldGenerationContext wgCtx =
            new net.minecraft.world.level.levelgen.WorldGenerationContext(
                task.generator(), chunk.getHeightAccessorForGeneration(), task.level()
            );
        final int protectedTop = chunk.isUpgrading() ? 0 : 7;
        final int maxY = wgCtx.getMinGenY() + wgCtx.getGenDepth() - 1 - protectedTop;
        final net.minecraft.world.level.chunk.CarvingMask mask =
            new net.minecraft.world.level.chunk.CarvingMask(wgCtx.getMinGenY() + 1, maxY);
        final net.minecraft.world.level.levelgen.WorldgenRandom random =
            new net.minecraft.world.level.levelgen.WorldgenRandom(
                new net.minecraft.world.level.levelgen.LegacyRandomSource(
                    net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed()
                )
            );
        final long baseSeed = task.randomState().seed();
        final int carverCount = uniformCarvers.size();
        for (int dx = -8; dx <= 8; dx++) {
            final int sx = pos.x() + dx;
            for (int dz = -8; dz <= 8; dz++) {
                final int sz = pos.z() + dz;
                ChunkPos sourcePos = null;
                for (int index = 0; index < carverCount; index++) {
                    final var carver = uniformCarvers.get(index);
                    random.setLargeFeatureSeed(baseSeed + index, sx, sz);
                    if (carver.isStartChunk(random)) {
                        if (sourcePos == null) {
                            sourcePos = new ChunkPos(sx, sz);
                        }
                        carver.carve(wgCtx, random, pos, sourcePos, mask);
                    }
                }
            }
        }
        if (mask.isEmpty()) {
            return EMPTY_CARVING_MASK;
        }
        final int wordsPerCol = (task.volume().sizeY() + 31) >>> 5;
        return mask.goldleaf$packColumnMasks(task.volume().minBlockY(), wordsPerCol);
    }

    private static void applyCarverPostEvents(
        final TerrainTask task,
        final ChunkAccess chunk,
        final int minBlockY,
        final int[] events,
        final int baseOffset,
        final int count
    ) {
        final ChunkPos pos = chunk.getPos();
        final int minBlockX = pos.getMinBlockX();
        final int minBlockZ = pos.getMinBlockZ();
        final net.minecraft.core.BlockPos.MutableBlockPos bp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int i = 0; i < count; i++) {
            final int ev = events[baseOffset + i];
            final int lx = ev & 15;
            final int lz = (ev >>> 4) & 15;
            final int relY = (ev >>> 8) & 0x3FF;
            bp.set(minBlockX + lx, minBlockY + relY, minBlockZ + lz);
            chunk.markPosForPostProcessing(bp);
        }
    }

    private static GoldLeafOpenClBridge.@Nullable VramStageProgram buildVramStageProgram(
        final int deviceIndex,
        final CompiledDensityKernel terrainKernel,
        final CompiledDensityKernel surfaceKernel,
        final CompiledDensityKernel packKernel,
        final @Nullable CompiledDensityKernel carverMaskKernel,
        final @Nullable CompiledDensityKernel carverKernel,
        final boolean hasAquifers,
        final @Nullable CompiledDensityKernel featureKernel,
        final @Nullable CompiledDensityKernel vramLightKernel
    ) {
        final int[] ops = new int[8];
        final GoldLeafOpenClBridge.CompiledKernel[] kerns = new GoldLeafOpenClBridge.CompiledKernel[8];
        int count = 0;
        ops[count] = GoldLeafOpenClBridge.STAGE_TERRAIN_FILL;
        kerns[count++] = terrainKernel.gpuKernel();
        if (hasAquifers && carverKernel != null && carverKernel.hasLiveGpuKernel()) {
            ops[count] = GoldLeafOpenClBridge.STAGE_TERRAIN_AQUIFER;
            kerns[count++] = carverKernel.gpuKernel();
        }
        ops[count] = GoldLeafOpenClBridge.STAGE_SURFACE_RULES;
        kerns[count++] = surfaceKernel.gpuKernel();
        if (carverMaskKernel != null && carverMaskKernel.hasLiveGpuKernel()
            && carverKernel != null && carverKernel.hasLiveGpuKernel()) {
            ops[count] = GoldLeafOpenClBridge.STAGE_CARVER_MASK;
            kerns[count++] = carverMaskKernel.gpuKernel();
        }
        if (carverKernel != null && carverKernel.hasLiveGpuKernel()) {
            ops[count] = GoldLeafOpenClBridge.STAGE_CARVER_APPLY;
            kerns[count++] = carverKernel.gpuKernel();
        }
        if (featureKernel != null && featureKernel.hasLiveGpuKernel()) {
            ops[count] = GoldLeafOpenClBridge.STAGE_FEATURES_U8;
            kerns[count++] = featureKernel.gpuKernel();
        }
        if (vramLightKernel != null && vramLightKernel.hasLiveGpuKernel()) {
            ops[count] = GoldLeafOpenClBridge.STAGE_VRAM_LIGHT_U8;
            kerns[count++] = vramLightKernel.gpuKernel();
        }
        ops[count] = GoldLeafOpenClBridge.STAGE_PACK_SECTIONS_U8;
        kerns[count++] = packKernel.gpuKernel();
        return GoldLeafOpenClBridge.createVramStageProgram(
            deviceIndex,
            java.util.Arrays.copyOf(ops, count),
            java.util.Arrays.copyOf(kerns, count)
        );
    }

    public record CompiledVanillaSurfaceKernel(
        CompiledDensityKernel terrainKernel,
        CompiledDensityKernel surfaceKernel,
        CompiledDensityKernel packKernel,
        @Nullable CompiledDensityKernel carverMaskKernel,
        @Nullable CompiledDensityKernel carverKernel,
        @Nullable CompiledDensityKernel featureKernel,
        @Nullable CompiledDensityKernel vramLightKernel,
        GoldLeafOpenClBridge.@Nullable VramStageProgram vramStageProgram,
        GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfaceProgram
    ) {
        public boolean hasLiveCarverMaskKernel() {
            return this.carverMaskKernel != null && this.carverMaskKernel.hasLiveGpuKernel();
        }

        public boolean hasLiveCarverKernel() {
            return this.carverKernel != null && this.carverKernel.hasLiveGpuKernel();
        }

        public boolean hasLiveFeatureKernel() {
            return this.featureKernel != null && this.featureKernel.hasLiveGpuKernel();
        }

        public boolean hasLiveVramLightKernel() {
            return this.vramLightKernel != null && this.vramLightKernel.hasLiveGpuKernel();
        }

        public boolean hasLiveVramStageProgram() {
            return this.vramStageProgram != null && !this.vramStageProgram.isClosed();
        }
    }

    public record CompiledBiomeRTreeKernel(
        CompiledDensityKernel kernel,
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>[] biomePalette
    ) {
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CompiledBiomeRTreeKernel other)) {
                return false;
            }
            return Objects.equals(this.kernel, other.kernel)
                && Arrays.equals(this.biomePalette, other.biomePalette);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(this.kernel);
            return 31 * result + Arrays.hashCode(this.biomePalette);
        }

        @Override
        public String toString() {
            return "CompiledBiomeRTreeKernel[kernel=" + this.kernel
                + ", biomePalette=" + Arrays.toString(this.biomePalette) + "]";
        }
    }

    public record CompiledDensityKernel(
        OpenClBackendEmitter.DensityVolumeProgram program,
        ParityMode parityMode,
        GoldLeafOpenClBridge.@Nullable CompiledKernel gpuKernel
    ) {
        public CompiledDensityKernel {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(parityMode, "parityMode");
        }

        public boolean hasLiveGpuKernel() {
            return this.gpuKernel != null && !this.gpuKernel.isClosed();
        }
    }

    public record DispatchStats(long gpuKernelNanos, long readbackNanos) {
    }

    public record LcaWaveDispatchResult(
        DispatchStats dispatchStats,
        CompactLcaChunkFrameBuilder.BuildResult[] chunkResults,
        long totalLcaBytes
    ) {
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof LcaWaveDispatchResult other)) {
                return false;
            }
            return this.totalLcaBytes == other.totalLcaBytes
                && Objects.equals(this.dispatchStats, other.dispatchStats)
                && Arrays.equals(this.chunkResults, other.chunkResults);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(this.dispatchStats, this.totalLcaBytes);
            return 31 * result + Arrays.hashCode(this.chunkResults);
        }

        @Override
        public String toString() {
            return "LcaWaveDispatchResult[dispatchStats=" + this.dispatchStats
                + ", chunkResults=" + Arrays.toString(this.chunkResults)
                + ", totalLcaBytes=" + this.totalLcaBytes + "]";
        }
    }
}
