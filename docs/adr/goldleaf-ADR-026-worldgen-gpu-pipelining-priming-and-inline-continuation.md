# goldleaf-ADR-026 — Worldgen GPU Pipelining, `STRUCTURE_REFERENCES` Biome Priming, and Inline Terrain Continuation

**Status:** Accepted (Implemented)
**Date:** 2026-11-26

## Context

In GoldLeaf's native OpenCL worldgen offload (`goldleaf-ADR-004`, `goldleaf-ADR-017`), `ChunkStatus.BIOMES` and `ChunkStatus.TERRAIN` run through non-blocking `CompletableFuture` handoffs in Moonrise (`ChunkUpgradeGenericStatusTask` + `WorldgenWaveCoalescer`). While this avoids blocking Moonrise `parallelGenExecutor` threads (`S-005`), reactive submission at the exact entry point of each status task creates a second async request/response loop stacked inside Moonrise's chunk scheduler:

- In live RTX 3090 `EMPTY -> FULL` profiling (`4,096` fresh chunks, seed `42424242`, `devstack/logs/goldleaf-pass14b-4xwg-20261124T041000Z`), actual GPU kernel, PCIe readback, and host-apply work totaled `0.44 ms/chunk` (`gpuKernel=3,745.5 ms`, `readback=789.7 ms`, `hostApply=775.7 ms` across `11,938` chunks), yet `WorldgenStageProfiler` recorded **`219.1 ms/chunk`** average wall latency in `BIOMES` and **`283.7 ms/chunk`** in `TERRAIN_DO_FILL`.
- Under sparse exploration, reactive submission also pays stacked `WorldgenWaveCoalescer` flush deadlines (`2 ms` for `BIOMES` + `2 ms` for `TERRAIN`) plus an unconditional `terrainContinuationExecutor` hop in `NoiseBasedChunkGenerator#buildTerrain` even when GPU terrain, surface, and carvers are all already applied.

Empirical profiling and inspection of `ChunkPyramid` / `ChunkStep` established two hard architectural boundaries on where priming is valid:

1. **Why `ChunkLoadTask` (`EMPTY` disk-miss) is the wrong biome-priming seam:** `ChunkStatus.STRUCTURE_STARTS` requires an 8-chunk radius halo (`ChunkPyramid.GENERATION_PYRAMID`). For `4,096` `FULL` chunks, Moonrise loads `11,451` chunks to `EMPTY` / `STRUCTURE_STARTS`, of which only `6,299` ever advance to `BIOMES`. Priming at `ChunkLoadTask` wasted GPU work on `5,152` halo-only chunks and evicted `9,335` entries under a `1,024`-entry cap, nearly doubling GPU chunk dispatches (`21,310` vs. `11,938`). By contrast, `ChunkStatusTasks#generateStructureReferences` executes for the exact `6,299` chunks that proceed to `BIOMES` (100% 1:1 correspondence).
2. **Why `LIGHT` priming before `ChunkLightTask` is unsafe:** `ChunkStatus.FEATURES` declares `blockStateWriteRadius(1)` while `ChunkStatus.INITIALIZE_LIGHT` is a synchronous no-op in Moonrise with direct dependency radius `0`; `ChunkStatus.LIGHT` carries `.addRequirement(INITIALIZE_LIGHT, 1)`. A chunk's blocks are therefore not frozen from neighbor `FEATURES` writes until `ChunkLightTask` is scheduled. Priming GPU light earlier would risk stale block reads and violate vanilla parity.

## Decision

1. **Proactive `BIOMES` Priming at `ChunkStatusTasks#generateStructureReferences`:**
   - `PaperWorldgenSpi` **shall** expose a no-op-default `primeFreshChunkBiomes(ServerLevel, ChunkAccess)` hook, delegated via `WorldgenBootstrapRegistrar` to `TerrainGenerationAccelerator#primeFreshChunkBiomes`.
   - `ChunkStatusTasks#generateStructureReferences` **shall** invoke `PaperWorldgenSpi.primeFreshChunkBiomes(level, chunk)` for non-custom, non-void chunks so GPU biome waves execute concurrently with `createReferences` and Moonrise's `STRUCTURE_REFERENCES -> BIOMES` transition.
   - `IrOpenClTerrainAccelerator` **shall** unify `biomeWaveCoalescer` on coordinate-only `BiomeCoordRequest` payloads returning per-chunk copied `PrimedBiomePayload` (`RTreeBiomePayload` `384 B/chunk` or `Climate6BiomePayload`), stored in a strictly bounded `PrimedBiomeCache` (`MAX_PRIMED_BIOME_ENTRIES = 8192`, ~3 MiB max heap, FIFO eviction, zero `ChunkAccess` references, cleared on `close()`, `S-002`).
   - `IrOpenClTerrainAccelerator#submitBiomeAsync` **shall** consume primed entries only when `blender.isEmpty()`. When the primed future is already complete (`isDone()`), the payload **shall** be applied inline on the calling Moonrise worker thread (zero coalescer wait, zero `hostApplyExecutor` hop); when still in-flight, it attaches `hostApplyExecutor` continuation without re-dispatching GPU work. Priming faults **shall** log at `WARNING` and fail soft (`S-004`).
2. **Inline `TERRAIN` Continuation Fast-Path in `NoiseBasedChunkGenerator#buildTerrain`:**
   - On the async GPU `TERRAIN` path, when `error == null && acceleratedChunk != null`, `NoiseBasedChunkGenerator#buildTerrain` **shall** release `asyncSections`, check `isSurfaceAlreadyApplied(chunk)` and `isCarverAlreadyApplied(chunk)`, and return `CompletableFuture.completedFuture(chunk)` inline when both are `true`, eliminating the `terrainContinuationExecutor` hop on the GPU happy path.
   - Heavy CPU fallback work (`doFill`, `buildSurface`, or `generateCarversLazy`) **shall** remain offloaded to `terrainContinuationExecutor` so `hostApplyExecutor` threads are never blocked.
3. **Prohibition on Pre-`ChunkLightTask` `LIGHT` Priming:**
   - GoldLeaf **shall not** prime `GpuLightEngine` before all radius-1 `FEATURES` neighbors have completed (i.e., before `ChunkLightTask` scheduling), preserving 100% vanilla lighting parity.

## Measured RTX 3090 Impact (`4,096` Fresh Chunks, Seed `42424242`, Warm OpenCL Cache)

| Metric | Baseline (`goldleaf-pass14b`) | Pipelined (`goldleaf-pipelined-proof-v3`) | Delta |
|---|---:|---:|---|
| `BIOMES` stage avg wall latency | `219.137 ms/ch` (`6,314` ch) | **`4.415 ms/ch`** (`6,299` ch) | **`49.6×` lower** |
| `biomePrime` hit rate (`sub` / `hitReady` / `hitInFlight` / `evict`) | — | `6,250` / `5,296` (`84.7%`) / `954` (`15.3%`) / `0` | **`100%` hit, `0` evictions** |
| `TERRAIN_BUILD_SURFACE` avg | `0.518 ms/ch` | **`0.303 ms/ch`** | **`-41.5%`** |
| `TERRAIN_CARVERS` avg | `0.012 ms/ch` | **`0.007 ms/ch`** | **`-41.7%`** |
| `gpuKernel` total / `cpuFallback` chunks | `3,745.5 ms` / `222` | `3,449.3 ms` / `94` | **`-7.9%` kernel, `-57.7%` fallbacks** |

## Consequences

- **Positive:** Collapses `BIOMES` stage wall latency by `49.6×` (`219.1 ms -> 4.4 ms/ch`) with zero wasted radius-8 halo GPU work, zero cache evictions under 4,096-chunk bursts, and zero `ChunkAccess` retention (`S-002`); removes an unnecessary executor hop on every GPU-completed `TERRAIN` chunk.
- **Negative / Trade-offs:** Retains up to `8,192` lightweight `384 B` biome payload buffers (~3 MiB worst-case) in `PrimedBiomeCache` until consumed or FIFO-evicted on chunk cancellation.

## References

- `GoldLeaf/folia-server/src/main/java/io/papermc/paper/spi/PaperWorldgenSpi.java`
- `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/TerrainGenerationAccelerator.java`
- `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/WorldgenBootstrapRegistrar.java`
- `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/worldgen/IrOpenClTerrainAccelerator.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/chunk/status/ChunkStatusTasks.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java`
- `GoldLeaf/folia-server/src/test/java/dev/leafcraft/goldleaf/worldgen/WorldgenPipelinePrimingTest.java`
- Related ADRs: `goldleaf-ADR-004`, `goldleaf-ADR-017`, `goldleaf-ADR-019`
