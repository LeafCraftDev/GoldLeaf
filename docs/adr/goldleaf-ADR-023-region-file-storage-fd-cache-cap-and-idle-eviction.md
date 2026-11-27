# goldleaf-ADR-023 — Configurable RegionFileStorage FD Cache Cap and Idle Eviction

**Status:** Accepted
**Date:** 2026-11-25

## Context

When running in `.mca` Anvil mode (or reading legacy `.mca` worlds without `RegionStorageSpi`), `net.minecraft.world.level.chunk.storage.RegionFileStorage` caches open `RegionFile` handles in a `Long2ObjectLinkedOpenHashMap` bounded by Paper's `misc.region-file-cache-size` (default `256` per storage folder). Because each Minecraft dimension opens three separate `RegionFileStorage` instances (`region`, `entities`, `poi`), a single dimension holds up to **`768` open OS file descriptors and `768` direct `ByteBuffer` headers** (`8 KiB` each).

In stock Paper/Folia:

1. `GoldLeafConfig.Storage.maxOpenRegions` only governed `.lca` archives; `.mca` `RegionFileStorage` ignored it.
2. `RegionFileStorage#getRegionFile` evicted at most one entry (`if (this.regionCache.size() >= cacheSize)`), so dynamically reducing the cap left excess entries open.
3. Open `RegionFile` handles were **never evicted on idle** — during wide-radius random teleportation or exploration spikes (e.g., `584` open `.mca` FDs observed in `/proc/<pid>/fd` after a 6-hour RTP soak), handles remained pinned indefinitely even after players moved away or logged off.

## Decision

Extend `RegionFileStorage` (exported as `0022-Configurable-RegionFileStorage-FD-cache-and-idle-evi.patch`) and `GoldLeafConfig.Storage` with unified FD-cap resolution and opt-in idle eviction:

1. **Unified Cache Cap Resolution (`goldleaf$resolveCacheSize`):**
   - Respect `-Dgoldleaf.regionFileCacheSize=<N>` (`N >= 1`) as an explicit JVM override when set.
   - Otherwise enforce `min(paper.misc.region-file-cache-size, goldleaf.storage.max-open-regions)` (clamped to `>= 1`).
   - Replace the single-entry `if` eviction check with a `while (this.regionCache.size() >= cacheSize)` loop so cap reductions evict down to the active limit immediately on the next region open.
2. **Opt-In Idle Eviction (`storage.region-file-idle-timeout-seconds`):**
   - Add `storage.region-file-idle-timeout-seconds` to `GoldLeafConfig.Storage` (default `0` = disabled for upstream parity; validated `>= 0`; overridable via `-Dgoldleaf.regionFileIdleTimeoutSeconds=<N>`).
   - Track per-region monotonic last-access timestamps in a primitive `Long2LongOpenHashMap` (`regionLastAccessNanos`) under the existing `synchronized (this)` monitor, updated on cache hits (`getRegionFile`, `moonrise$getRegionFileIfLoaded`) and inserts, and cleared on eviction and `close()`.
   - Expose `synchronized int evictIdleRegionFiles(long nowNanos, long idleTimeoutNanos)` and `synchronized int getCachedRegionFileCount()`. Because `regionCache` is maintained in LRU order (`getAndMoveToFirst` / `putAndMoveToFirst`), eviction walks from `lastLongKey()` and stops at the first non-expired entry in `O(evicted)` time.
   - Trigger opportunistic sweeps inside `getRegionFile` when `idleTimeoutNanos > 0`, throttled to at most once every `min(idleTimeoutNanos / 4, 15s)` so steady-state chunk I/O pays zero scan overhead.
   - Propagate any `IOException` raised during `RegionFile#close()` via `ExceptionCollector<IOException>` (`S-004`, zero silent swallowing).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Spawn a dedicated background timer thread per `RegionFileStorage` | Adds 3+ extra threads per dimension and extra lock contention for a cache that can be swept in `O(evicted)` opportunistically or driven by existing maintenance hooks. |
| Enable aggressive idle eviction by default (e.g., 30s) | Could cause churn on small worlds with infrequent background saves; defaulting to `0` preserves stock Paper behavior while giving high-spread RTP/exploration servers an explicit knob. |
| Lower `MAX_CACHE_SIZE` globally below `256` | Would regress chunk I/O reopen rates on dense multi-region servers with high player counts. |

## Consequences

- **Positive:**
  - Operators running `.mca` worlds with wide teleport/exploration radii can bound open OS file descriptors and direct `ByteBuffer` headers via `goldleaf.yml` (`storage.max-open-regions`, `storage.region-file-idle-timeout-seconds`) or JVM system properties without losing data integrity (evicted `.mca` files transparently reopen on subsequent reads/writes).
  - Zero extra heap boxing (primitive `Long2LongOpenHashMap`) and zero extra locks (`synchronized (this)` already held).
- **Negative / Trade-offs:**
  - When idle eviction is enabled (`> 0`), opportunistic eviction runs on the next `.mca` open after the throttle interval rather than on a completely silent server unless `evictIdleRegionFiles(...)` is invoked periodically.
  - Re-accessing an evicted region file incurs one `RegionFile` open + `8 KiB` header read.

## References

- `GoldLeaf/folia-server/minecraft-patches/features/0022-Configurable-RegionFileStorage-FD-cache-and-idle-evi.patch`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/world/level/chunk/storage/RegionFileStorage.java`
- `GoldLeaf/folia-server/src/main/java/dev/leafcraft/goldleaf/config/GoldLeafConfig.java`
- `GoldLeaf/folia-server/src/test/java/dev/leafcraft/goldleaf/network/ServerPacketHardeningAndRegionCacheTest.java`
- `GoldLeaf/docs/adr/goldleaf-ADR-005-lca-format-adoption-across-disk-memory-and-network.md`
