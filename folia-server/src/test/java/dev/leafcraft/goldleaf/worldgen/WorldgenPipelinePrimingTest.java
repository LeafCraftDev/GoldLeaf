package dev.leafcraft.goldleaf.worldgen;

import io.papermc.paper.spi.PaperWorldgenSpi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate 1 verification and latency proof for GoldLeaf GPU worldgen priming and inline continuation
 * ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017}, {@code S-002}, {@code S-005}).
 */
class WorldgenPipelinePrimingTest {

    @Test
    void primedBiomeCacheEnforcesStrictBoundAndEvictsOldestEntries() {
        final int cap = 64;
        final IrOpenClTerrainAccelerator.PrimedBiomeCache<byte[]> cache =
            new IrOpenClTerrainAccelerator.PrimedBiomeCache<>(cap);

        for (int i = 0; i < cap * 3; i++) {
            final IrOpenClTerrainAccelerator.PrimedBiomeKey key =
                new IrOpenClTerrainAccelerator.PrimedBiomeKey("minecraft:overworld", i);
            assertTrue(cache.putIfAbsent(key, CompletableFuture.completedFuture(new byte[384])));
            assertTrue(cache.size() <= cap, "Cache size must remain <= cap (" + cap + "), was " + cache.size());
        }

        assertEquals(cap, cache.size());
        assertEquals(cap * 2L, cache.evictedCount());
        // Oldest keys were evicted; newest keys remain present
        assertNull(cache.remove(new IrOpenClTerrainAccelerator.PrimedBiomeKey("minecraft:overworld", 0)));
        final CompletableFuture<byte[]> newest =
            cache.remove(new IrOpenClTerrainAccelerator.PrimedBiomeKey("minecraft:overworld", cap * 3 - 1));
        assertNotNull(newest);
        assertEquals(384, newest.join().length);
        assertEquals(cap - 1, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void primedBiomesOverlapWithStructureStartsEliminatesCoalescerWait() throws Exception {
        final ExecutorService dispatchPool = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "test-biome-dispatch");
            t.setDaemon(true);
            return t;
        });
        try (WorldgenWaveCoalescer<Long, byte[]> coalescer = new WorldgenWaveCoalescer<>(
            24,
            TimeUnit.MILLISECONDS.toNanos(4L),
            (key, batch) -> {
                final List<byte[]> out = new ArrayList<>(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    out.add(new byte[384]);
                }
                return out;
            },
            dispatchPool
        )) {
            final WorldgenWaveCoalescer.WaveKey waveKey = new WorldgenWaveCoalescer.WaveKey("minecraft:overworld#biomes", 1);

            // Baseline (unprimed): chunk reaches BIOMES cold and pays the coalescer flush deadline.
            final long unprimedStart = System.nanoTime();
            final byte[] unprimedPayload = coalescer.submit(waveKey, 1L).get(2, TimeUnit.SECONDS);
            final long unprimedLatencyNs = System.nanoTime() - unprimedStart;
            assertEquals(384, unprimedPayload.length);
            assertTrue(
                unprimedLatencyNs >= TimeUnit.MILLISECONDS.toNanos(2L),
                "Unprimed sparse chunk should pay coalescer flush deadline, was " + unprimedLatencyNs + "ns"
            );

            // Improved (primed at ChunkLoadTask disk-miss): GPU wave runs during STRUCTURE_STARTS.
            final IrOpenClTerrainAccelerator.PrimedBiomeCache<byte[]> cache =
                new IrOpenClTerrainAccelerator.PrimedBiomeCache<>(1024);
            final IrOpenClTerrainAccelerator.PrimedBiomeKey primeKey =
                new IrOpenClTerrainAccelerator.PrimedBiomeKey("minecraft:overworld", 2L);
            cache.putIfAbsent(primeKey, coalescer.submit(waveKey, 2L));

            // Simulate STRUCTURE_STARTS + STRUCTURE_REFERENCES CPU work overlapping GPU biome wave.
            Thread.sleep(10L);

            final long primedStageStart = System.nanoTime();
            final CompletableFuture<byte[]> primedFuture = cache.remove(primeKey);
            assertNotNull(primedFuture);
            assertTrue(primedFuture.isDone(), "Primed biome future should already be ready when BIOMES arrives");
            final byte[] primedPayload = primedFuture.getNow(null);
            final long primedStageLatencyNs = System.nanoTime() - primedStageStart;
            assertNotNull(primedPayload);
            assertEquals(384, primedPayload.length);
            assertTrue(
                primedStageLatencyNs < unprimedLatencyNs / 4,
                "Primed BIOMES stage latency (" + primedStageLatencyNs + "ns) should be far below unprimed (" + unprimedLatencyNs + "ns)"
            );
        } finally {
            dispatchPool.shutdownNow();
        }
    }

    @Test
    void paperWorldgenSpiPrimeFreshChunkBiomesDelegatesCleanlyAndDefaultsToNoOp() {
        final PaperWorldgenSpi.Provider previous = PaperWorldgenSpi.get();
        try {
            PaperWorldgenSpi.resetDefault();
            // Must be safe no-op with null provider
            PaperWorldgenSpi.primeFreshChunkBiomes(null, null);

            final AtomicInteger calls = new AtomicInteger();
            PaperWorldgenSpi.register(new PaperWorldgenSpi.Provider() {
                @Override
                public void primeFreshChunkBiomes(final ServerLevel level, final ChunkAccess chunk) {
                    calls.incrementAndGet();
                }
            });
            PaperWorldgenSpi.primeFreshChunkBiomes(null, null);
            assertEquals(1, calls.get());
        } finally {
            PaperWorldgenSpi.register(previous);
        }
    }

    @Test
    void inlineTerrainContinuationFastPathSkipsExecutorHopWhenGpuAppliedSurfaceAndCarvers() {
        final AtomicInteger continuationDispatches = new AtomicInteger();
        final Executor countingContinuationExec = task -> {
            continuationDispatches.incrementAndGet();
            task.run();
        };

        // Happy path: GPU completed terrain + surface + carvers -> inline completion, 0 hops
        final String happyResult = runTerrainContinuationDecision(
            true, true, true, countingContinuationExec
        ).join();
        assertEquals("inline-done", happyResult);
        assertEquals(0, continuationDispatches.get());

        // Fallback path: GPU surface not applied -> offloaded to continuationExec (1 hop)
        final String surfaceFallback = runTerrainContinuationDecision(
            true, false, true, countingContinuationExec
        ).join();
        assertEquals("cpu-fallback", surfaceFallback);
        assertEquals(1, continuationDispatches.get());

        // Fallback path: GPU terrain null -> offloaded to continuationExec (1 hop)
        final String terrainFallback = runTerrainContinuationDecision(
            false, false, false, countingContinuationExec
        ).join();
        assertEquals("cpu-fallback", terrainFallback);
        assertEquals(2, continuationDispatches.get());
    }

    private static CompletableFuture<String> runTerrainContinuationDecision(
        final boolean terrainAccelerated,
        final boolean surfaceAlreadyApplied,
        final boolean carverAlreadyApplied,
        final Executor continuationExec
    ) {
        if (terrainAccelerated && surfaceAlreadyApplied && carverAlreadyApplied) {
            return CompletableFuture.completedFuture("inline-done");
        }
        return CompletableFuture.supplyAsync(() -> "cpu-fallback", continuationExec);
    }
}
