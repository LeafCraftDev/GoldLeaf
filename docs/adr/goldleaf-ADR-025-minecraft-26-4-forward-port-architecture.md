# goldleaf-ADR-025 — Minecraft 26.4 Forward-Port Architecture (Per-Block Biomes, Worldgen GPU Pipeline, and Lifecycle Patches)

**Status:** Accepted (Storage SDK Implemented; Server Rebase Pending Upstream Paper/Folia `26.4`)
**Date:** 2026-11-26

## Context

Minecraft `26.4` (`26.4-pre-1`, `DataVersion = 5131`, Data Pack `128.1`, Resource Pack `104.0`, Protocol `778`, Java `25`, `server.jar` SHA-1 `7ade6b7bb19a6ffbc3185c9227ab1871bf17e00e`) introduces breaking storage, world-generation, and networking/lifecycle changes across three core GoldLeaf subsystems:

1. **Per-Block Biomes & Chunk Schema (`SerializableChunkData`, `LevelChunkSection`, `NoiseBiomeChunk`, `RetroGen`):**
   - Section biome storage expands from `4×4×4` (`64` cells) to `16×16×16` (`4,096` cells per section via `PalettedContainer.Strategy.createForBiomes`, `bitsPerAxis = 4`).
   - Root chunk NBT renames `"Status"` to lowercase `"status"` and replaces `"below_zero_retrogen"` / `BelowZeroRetrogen` with `"retrogen"` / `RetroGen`.
   - Biome generation splits into two stages: `NOISE_BIOMES` populates a transient `4×4×4` `NoiseBiomeChunk` (`"noise_biomes"`), and `BIOMES` upscales a `3×3` `NoiseBiomeChunk` neighborhood to `16×16×16` per-block biomes (`ChunkStatusTasks.upscaleBiomes`) before calling `dropNoiseBiomes()`.
2. **Worldgen AST & Fused Terrain Builder (`UpscaleFunction`, `DomainWarpFunction`, `ChunkTerrainBuilder`, `SurfaceExtensions`, `AbstractOreFeature`):**
   - `InterpolatedFunction` is replaced by `UpscaleFunction` with five separable FP32 filter modes (`NEAREST_NEIGHBOR`, `INTERPOLATE_LINEAR`, `INTERPOLATE_COSINE`, `INTERPOLATE_CUBIC`, `B_SPLINE_CUBIC`) evaluated strictly in `X -> Z -> Y` order via `PolymorphicUpscaler` / `Upscaler$Coefficients`.
   - `DomainWarpFunction(input, offsetX, offsetY, offsetZ)` adds dynamic integer-truncated (`f2i`) coordinate warping to the density function AST.
   - `NoiseBasedChunkGenerator.doFill`, `MaterialSystem.buildSurface`, and `applyCarvingMask` fuse into `ChunkTerrainBuilder.fillChunk` + `SurfaceExtensions`. `NoiseGeneratorSettings.defaultBlock()` is removed; solid voxels resolve their `BlockState` purely through `MaterialRule` (falling back to `AIR` when a rule yields `null`). `WorldGenerationContext` is replaced by `RandomState.verticalAnchors()` (`VerticalAnchor.Context`), and `RandomState.surfaceSystem()` becomes `RandomState.materialSystem()`.
   - `AbstractOreFeature` makes `canPlaceOre` / `isAdjacentToAir` `private` in favor of `checkAirExposure`, and discards air-exposed positions immediately rather than falling through to subsequent `BlockReplacement` rules. `OreFeature.doPlace` drops two trailing `int` parameters, `JigsawPlacement.addPieces` accepts `HolderGetter<StructureTemplatePool>` with `StructurePoolElement.getCornerConstraint()`, and `PoiTypes.*` uses `Holder<PoiType>`.
3. **Networking, Security, and Server Lifecycle (`Connection`, `MinecraftServer`, `DedicatedServer`, `PlayerList`, `ServerLevel`, `ClientboundBlockEntityDataPacket`):**
   - `Connection` adds `disconnectNonBlocking(DisconnectionDetails)` (called from `ServerCommonPacketListenerImpl`), while other `disconnect(DisconnectionDetails)` call sites still risk blocking on `io_uring` without an `inEventLoop()` guard.
   - `DedicatedServer.handleConsoleInput(String)` rejects commands during startup; `PlayerList` adds `removeAllForShutdown()`; `MinecraftServer` adds `SessionKeyExchange.ServerSideFactory`, trailing `long minRekeyTimeMs`, and `acceptsConnection` / `enableLegacyStatus` / `statusContactDetails`; `ServerCommonPacketListenerImpl.onPacketError` narrows to `(Packet<?>)`; `ServerLevel` removes `isDebug` and `biomeZoomSeed` constructor parameters; `ServerExplosion.getHitPlayers()` is removed; `ClientboundBlockEntityDataPacket.create` changes its lambda parameter from `RegistryAccess` to `HolderGetter.Provider`.

## Decision

### 1. Storage & `:anvil-api` — Native `4,096`-Biome & Lowercase `status` Support Inside `LCA_FORMAT_VERSION_V3` (`0x0003`)

Because `LCA_FORMAT_VERSION_V3` (`0x0003`) had not been released externally, `:anvil-api` extends `V3` directly instead of introducing a `V4` superblock version (`anvil-api-ADR-003` Section 7.3):

- **Dual-Resolution `BiomePaletteSection` (Zero Signature Break):**
  - Retains the 3-component record `(int sectionY, List<String> palette, long[] data)`.
  - Single-entry palettes (`palette.size() == 1`, `data == null`) remain resolution-invariant (`0`-byte default elision or 1-entry dict ID).
  - For `palette.size() >= 2`, packed `long[]` lengths for `4×4×4` (`1..7` longs) and `16×16×16` (`64..820` longs) are strictly disjoint; `BiomePaletteSection.isPerBlockGrid(paletteSize, data)` dispatches automatically, and lengths strictly between the two requirements fail closed (`hasMalformedData() == true`, `biomeIdAt(...) == null`, `S-004`).
- **Inline `BIOME_4096_MARKER = 65` Wire Framing:**
  - `65` (`0x41`) lies above legacy `bLen ∈ [0..64]` and below biome template references (`0x80..0xBF`), avoiding wire collisions and failing closed (`CBIT T7`) on legacy decoders.
  - Layout: `varInt(65)` + `varInt(actualBLen)` (`2..4096`) + `actualBLen × varInt(dictId)` + `modeByte`:
    - `0x00` (`BIOME_4096_MODE_RLE`): `CURVE_XZY` extended RLE (`varInt(runCount)`; compact 1-byte `(pIdx << 5) | lenCode` with `lenCode == 31 -> 32 + varInt(ext)` when `actualBLen <= 8`, else `varInt(pIdx) + varInt(len - 1)`).
    - `0x01` (`BIOME_4096_MODE_RAW_PACKED`): raw big-endian Mojang-packed `long[requiredLongs(actualBLen, 4096)]`, chosen whenever `rleTotalBytes >= rawTotalBytes` to bound adversarial high-entropy biome patterns strictly at the vanilla packed ceiling.
  - Vertical `BiomeTemplate` deduplication (`0x80 | tmplIdx`) caches `4,096`-cell layouts transparently.
  - `ScratchArena` allocates `biomeCells4096` lazily (`ensureBiome4096Cells()`) so `64`-biome worlds retain zero added heap cost.
- **Lossless `"Status"` vs. `"status"` Preservation:**
  - `AnvilReader`, `RegionFileReader`, `LcaRegionReader`, `LcaRegionWriter`, and `LcaStreamingNbtEncoder` recognize both `"Status"` and lowercase `"status"`.
  - Lowercase `"status": "minecraft:full"` / `"full"` writes `statusOrdinal = 0xFE` (`STATUS_ORDINAL_ABSENT`) with `"status"` preserved verbatim in residual NBT; non-full lowercase proto-chunks (`minecraft:terrain`, `minecraft:noise_biomes`, `minecraft:structure_starts`, etc.) write `statusOrdinal = 0xFD` (`STATUS_ORDINAL_RESIDUAL`) with `"status"` preserved verbatim in residual NBT. Direct view/probe readers reject `0xFD` as `null` (`UNKNOWN`), while full NBT/transcode paths preserve exact key casing.
- **GoldLeaf `folia-server` Storage Rebase (`0015`, `LcaDirectSectionSerializer`, `CompactLcaChunkFrameBuilder`):**
  - Replace `BelowZeroRetrogen` / `"below_zero_retrogen"` with `RetroGen` / `"retrogen"`.
  - Persist and restore transient `NoiseBiomeChunk` (`"noise_biomes"`) on incomplete `ProtoChunk`s while omitting it on full chunks.
  - Update `LevelChunkSection` calls from `getNoiseBiome` / `fillBiomesFromNoise` to `getBiome` / `fillBiome`.

### 2. Worldgen & OpenCL GPU Pipeline (`0018`, `goldleaf-ADR-017`, `goldleaf-ADR-024`)

- **5-Mode `UpscaleFunction` Lowering (`DensityFunctionIrLowerer` + `goldleaf_opencl.c`):**
  - Replace `InterpolatedFunction` lowering with all five `UpscaleFunction.Mode` filters (`NEAREST_NEIGHBOR`, `INTERPOLATE_LINEAR`, `INTERPOLATE_COSINE`, `INTERPOLATE_CUBIC`, `B_SPLINE_CUBIC`), evaluated strictly in `X -> Z -> Y` separable 1D passes in OpenCL `__local float` memory (`12` FMAs/voxel for 4-tap cubic vs. `64` corner fetches) using precomputed FP32 coefficient LUTs matching `Upscaler$Coefficients` (zero `FP64`).
  - Expand coarse-grid dispatch bounds by each filter's `(before, after)` halo (`(0,0)`, `(0,1)`, or `(1,2)`).
  - Fail closed cleanly to CPU `DensitySampler` (`S-004`) when coarse cell sizes are non-divisors, exceed `__local` budget (`cellSize > 16`), or mix incompatible multi-rate coarse lattices.
- **`DomainWarpFunction` Lowering:**
  - Constant offsets fold into shifted coordinate origins (compatible with coarse-grid children).
  - Dynamic offsets over point-evaluable subtrees (no nested `UpscaleFunction` or chunk-scoped `CacheFunction`) lower to inline coordinate transforms with Java `f2i` / OpenCL `(int)(float)` truncation toward zero.
  - All other shapes fail closed cleanly to CPU (`UnsupportedNodeException`).
- **Fused `ChunkTerrainBuilder` + `SurfaceExtensions` Integration:**
  - Hook `NoiseBasedChunkGenerator.buildTerrain` / `ChunkTerrainBuilder.fillChunk` directly and remove all `defaultBlock()` references from `GpuVanillaSurfaceCompiler` and `apply_vanilla_surface_u8` (resolving solid voxels strictly via `MaterialRule`, defaulting to `AIR` on `null`).
  - Migrate `WorldGenerationContext` -> `RandomState.verticalAnchors()` and `RandomState.surfaceSystem()` -> `RandomState.materialSystem()`.
  - Compute `SteepCondition` gradients from `NoiseColumn.topBlockY()` before `SurfaceExtensions.extendErodedBadlands`.
  - When datapacks exceed the `u8` `255`-block / `254`-biome surface cap, keep GPU density + aquifer execution and hand off compact density/top-Y buffers to CPU `ChunkTerrainBuilder.fillColumn`.
- **Two-Stage Biome Acceleration (`NOISE_BIOMES` -> `upscaleBiomes`):**
  - Stage 1 (`generateNoiseBiomes`): `GpuBiomeRTree` populates the `4×4×4` `NoiseBiomeChunk` via `NoiseBiomeResolver`.
  - Stage 2 (`upscaleBiomes`): Inspect each section's covering `6×6×6` padded quart window across the `3×3` `NoiseBiomeChunk` neighborhood; if uniform (`~70%+` of sections), emit a `SingleValuePalette` in $O(1)$ with zero Voronoi evaluations. Offload only non-uniform border sections via a bit-exact integer/FP32 Voronoi kernel (`obfuscatedSeed` + `LinearCongruentialGenerator`).
- **Ores, Jigsaw, and POIs:**
  - Migrate `AbstractOreFeature` hook to `checkAirExposure` and update `GpuOrePlacementEngine` to stop immediately on air-exposure discard rather than falling through to subsequent `BlockReplacement` rules.
  - Rebase `OreFeature.doPlace`, `JigsawPlacement.addPieces` (`HolderGetter<StructureTemplatePool>` + `getCornerConstraint()`), and `PoiTypes` (`Holder<PoiType>`).

### 3. Networking, Security, and Server Lifecycle Patches (`0017`, `0021`, `0023`)

- **Patch `0023` (`Connection.java`):** Retain GoldLeaf's `inEventLoop()` non-blocking close guard inside `Connection.disconnect(DisconnectionDetails)` alongside vanilla's new `disconnectNonBlocking(DisconnectionDetails)` so non-`ServerCommonPacketListenerImpl` call sites cannot stall `io_uring`.
- **Patch `0017`:** Rebase against `DedicatedServer.handleConsoleInput(String)` (preserving startup command rejection), `PlayerList.removeAllForShutdown()`, updated `MinecraftServer` security/rekey/status signatures, narrowed `ServerCommonPacketListenerImpl.onPacketError(Packet<?>)`, updated `ServerLevel` constructor, and removal of `ServerExplosion.getHitPlayers()`.
- **Patch `0021`:** Update `ClientboundBlockEntityDataPacket.create` lambda parameter to `HolderGetter.Provider`.

## Verification Contract

- **Storage SDK (`:anvil-api`):**
  - Real `26.4-pre-1` `.mca` fixture (`api/anvil-api/src/test/resources/anvil/real/26_4_R1/r.0.0.mca`, `DataVersion = 5131`, full chunks `(0,0)` & `(0,1)` plus proto-chunks `(5,0)` `minecraft:terrain`, `(6,0)` `minecraft:noise_biomes`, `(7,0)` `minecraft:structure_starts`) verified across `AnvilFixtureParityTest` and `Mc264BiomeLcaParityTest`.
  - Full voxel and per-block `(lx, ly, lz)` biome parity verified across `.mca` -> `.lca` `V3` (Mode A & Mode B) -> `readChunkView`, `readChunkNbtBytes`, `transcodeToMca`, and `LcaWorldStorageSession` (`LcaStreamingNbtEncoder`), plus synthetic `1..70`-biome edge cases, adversarial 3D checkerboard raw ceiling, 24-section buffer growth, and wire-level `CBIT T5/T6/T7` rejection tests.
- **GoldLeaf `folia-server` (upon upstream Paper/Folia `26.4` rebase):**
  - `DatapackWorldgenParityTest`, `IrOpenClTerrainAcceleratorTest`, `AmdahlWorldgenTracksTest`, and `WorldgenCpuOptimizationParityTest` shall pass at `<= 0.05%` density-boundary tolerance across all five `UpscaleFunction.Mode` filters, `DomainWarpFunction`, `ChunkTerrainBuilder`, and `upscaleBiomes`, with zero `FP64` and zero reflection.

## Consequences

- **Positive:**
  - Downstream monorepo consumers (`:anvil-api`, `LeafRTP`, `LeafAnvil`, `LeafMap`, `GoldLeaf`) read and write Minecraft `26.4` `16×16×16` per-block biome regions and lowercase `"status"` chunks losslessly inside `LCA_FORMAT_VERSION_V3` (`0x0003`) with zero `4×4×4` regression and zero extra heap overhead on `<= 26.3` worlds.
  - GoldLeaf's `26.4` server rebase has a bytecode-verified specification across storage, GPU worldgen, and lifecycle patches before upstream Paper/Folia `26.4` ships.
- **Negative / Trade-offs:**
  - Server-side patch rebases (`0015`, `0017`, `0018`, `0021`, `0023`) remain gated on upstream Paper/Folia `26.4` availability and may require minor signature adjustments if Mojang modifies internals between `26.4-pre-1` and `26.4` GA.

## References

- [anvil-api-ADR-003](../../../RTP/api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md) — Hilbert-Roaring-Gray Chunk Archive (`.lca` `V3` `BIOME_4096_MARKER = 65` specification)
- [goldleaf-ADR-005](goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md) — Native `.lca` Format Adoption
- [goldleaf-ADR-017](goldleaf-ADR-017-typed-ast-ir-opencl-worldgen-pipeline-and-throughput-envelope.md) — Native Typed-AST-to-IR OpenCL Worldgen Pipeline
- [goldleaf-ADR-024](goldleaf-ADR-024-datapack-worldgen-ast-coverage-and-parity-contract.md) — Datapack Worldgen AST Coverage, Dimension Isolation, and Parity Contract
