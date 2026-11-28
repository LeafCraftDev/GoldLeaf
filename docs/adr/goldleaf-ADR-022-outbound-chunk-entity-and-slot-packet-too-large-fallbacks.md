# goldleaf-ADR-022 — Outbound Chunk, Block-Entity, Entity, and Container-Slot PacketTooLarge Fallbacks (Chunk-Ban Protection)

**Status:** Accepted
**Date:** 2026-11-25

## Context

`goldleaf-ADR-012` hardened **serverbound** packet decoding against NBT depth/byte bombs and oversized book edits. However, **clientbound** oversized packets remain an active denial-of-service ("chunk ban" / "book ban") vector in stock Paper/Folia:

1. `PacketEncoder` enforces `MAX_PACKET_SIZE = 8,388,608` bytes (`8 MiB`) unconditionally and `MAX_FINAL_PACKET_SIZE = (1 << 21) - 1 = 2,097,151` bytes (`~2 MiB`, the 3-byte VarInt / `CompressionDecoder.MAXIMUM_COMPRESSED_LENGTH` ceiling) whenever `packet.hasLargePacketFallback()` returns `true`.
2. When `PacketEncoder` throws `PacketTooLargeException`, `Connection#exceptionCaught` invokes `packet.packetTooLarge(Connection)`. If `packetTooLarge` returns `false`, `Connection` disconnects the client.
3. In stock Paper/Folia, **only `ClientboundContainerSetContentPacket`** implements `hasLargePacketFallback()` and `packetTooLarge(...)`. Every other large clientbound game packet lacks a fallback:
   - **`ClientboundLevelChunkWithLightPacket`:** `ClientboundLevelChunkPacketData` splits block entities into `extraPackets` only by count (`Paper.excessiveTELimit`, default `750`), not by serialized byte size. A chunk containing `100–749` heavy block entities (signs, lecterns, decorated pots, banners, skulls, spawners) can exceed `2 MiB` compressed or `8 MiB` uncompressed and disconnect every player who loads the chunk.
   - **`ClientboundBlockEntityDataPacket`:** A single oversized block entity (whether split out of a chunk or sent on update) exceeds the client decode ceiling and kicks the viewer.
   - **`ClientboundSetEquipmentPacket` and `ClientboundSetEntityDataPacket`:** An armor stand, item frame, item display, or dropped item holding deeply nested item components (e.g., books/shulkers/bundles) disconnects every player entering entity tracking range.
   - **`ClientboundContainerSetSlotPacket`:** Although `ClientboundContainerSetContentPacket#packetTooLarge` splits a chest/inventory into per-slot `ClientboundContainerSetSlotPacket`s, a single poisoned slot still fails `PacketEncoder` and kicks the player.

## Decision

Implement `hasLargePacketFallback() -> true` and strictly terminating, non-recursive `packetTooLarge(Connection)` fallbacks across the five clientbound packet types (exported as `0021-Chunk-and-entity-packet-too-large-fallbacks.patch`):

1. **`ClientboundLevelChunkWithLightPacket` + `ClientboundLevelChunkPacketData`:**
   - Add package-private `hasBlockEntities()` and `withoutBlockEntities()` helpers on `ClientboundLevelChunkPacketData` (sharing `heightmaps`, `buffer`, and `ready` with zero array copy, setting `blockEntitiesData = List.of()`).
   - When `ClientboundLevelChunkWithLightPacket#packetTooLarge` triggers and `chunkData.hasBlockEntities()` is `true`, log a `WARN` with chunk coordinates `(x, z)`, send the bare chunk via `withoutBlockEntities()`, send each inline block entity individually as `ClientboundBlockEntityDataPacket(pos.immutable(), type, tag)` (explicitly copying `pos.immutable()` because `forEachBlockEntityTag` reuses a `BlockPos.MutableBlockPos`), and re-send any `extraPackets` afterward so block-entity updates never race ahead of the chunk packet.
   - When `blockEntitiesData` is already empty, return `false` to prevent recursion.
2. **`ClientboundBlockEntityDataPacket`:**
   - When `tag` is non-empty, log a `WARN` with `(pos, type)` and send an empty `CompoundTag` fallback (`new ClientboundBlockEntityDataPacket(pos, type, new CompoundTag())`).
   - When `tag` is already empty, return `false` to prevent recursion.
3. **`ClientboundSetEquipmentPacket`:**
   - When `slots.size() > 1`, split into one single-slot `ClientboundSetEquipmentPacket` per slot (preserving `sanitize`).
   - When `slots.size() == 1` and the `ItemStack` has a non-empty `DataComponentPatch`, log a `WARN` (`entity`, `slot`, `item`) and send a visual-only stripped `new ItemStack(stack.getItem(), stack.getCount())`.
   - If already empty or stripped, log a `WARN` and skip (`return true`) without re-sending.
4. **`ClientboundSetEntityDataPacket`:**
   - When `packedItems.size() > 1`, split into one single-entry `ClientboundSetEntityDataPacket` per `SynchedEntityData.DataValue<?>`.
   - When `packedItems.size() == 1`: if the value is a non-empty `ItemStack` with a non-empty `DataComponentPatch`, log a `WARN` and send a visual-only stripped `new ItemStack(stack.getItem(), stack.getCount())`; otherwise log a `WARN` (`entity`, `data id`) and skip (`return true`).
5. **`ClientboundContainerSetSlotPacket`:**
   - When `itemStack` is non-empty with a non-empty `DataComponentPatch`, log a `WARN` (`containerId`, `slot`, `item`) and send a visual-only stripped `new ItemStack(itemStack.getItem(), itemStack.getCount())`; otherwise log a `WARN` and skip (`return true`).

### Invariants

- **Zero Hot-Path Cost:** Fallback code shall run only inside `Connection#exceptionCaught` after `PacketEncoder` rejects an oversized packet (`> 2,097,151` bytes). Normal chunks, entities, and inventories shall transmit identical wire bytes with zero extra allocation.
- **Server State Immutability:** Visual stripping shall apply strictly to the outbound fallback packet; in-memory world, entity, and container `ItemStack` / `BlockEntity` data shall never be mutated.
- **No Silent Swallowing (`S-004`):** Every degraded or skipped fallback packet shall emit a `WARN` containing coordinates or entity/container identifiers so operators can locate and remediate the offending object.
- **Guaranteed Termination:** Every `packetTooLarge` implementation shall strictly reduce packet size or structure on each step and terminate cleanly without infinite retry.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Measure serialized NBT bytes eagerly during `ClientboundLevelChunkPacketData` construction | Adds serialization overhead to every normal chunk send even though >99.99% of chunks are well below `2 MiB`. |
| Raise `MAX_PACKET_SIZE` / `MAX_FINAL_PACKET_SIZE` | Vanilla clients hard-cap `Varint21FrameDecoder` / `CompressionDecoder.MAXIMUM_COMPRESSED_LENGTH` at `2,097,152` bytes and `NbtAccounter` at `2 MiB`; raising server caps still disconnects vanilla clients. |
| Mutate or delete oversized block entities / items on disk during packet send | Packet encoding runs on Netty IO threads (`S-005`), and silently destroying player/world data on transient send errors violates `S-004` and vanilla-faithful persistence guarantees. |

## Consequences

- **Positive:**
  - Neutralizes multi-BE chunk bans (`100–749` heavy BEs), single-BE NBT bans, equipment/item-frame/display entity bans, and single-slot container book bans at the server boundary.
  - Because `hasLargePacketFallback()` lowers `PacketEncoder`'s check from `8 MiB` to `2,097,151` bytes for these packets, even incompressible payloads trigger safe splitting/stripping before exceeding vanilla client framing limits.
  - Pure Layer 1 patch with zero GoldLeaf-specific dependencies, directly upstreamable to Paper.
- **Negative / Trade-offs:**
  - A genuinely `> 2 MiB` single block entity or item renders with empty NBT / unstyled visual item components on the client rather than disconnecting the player (intended availability trade-off).
  - Splitting a chunk with hundreds of heavy block entities sends one `ClientboundBlockEntityDataPacket` per block entity, matching Paper's existing `Paper.excessiveTELimit` overflow pattern.

## References

- `GoldLeaf/folia-server/minecraft-patches/features/0021-Chunk-and-entity-packet-too-large-fallbacks.patch`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/PacketEncoder.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/Connection.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundSetEquipmentPacket.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundSetEntityDataPacket.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/protocol/game/ClientboundContainerSetSlotPacket.java`
- `GoldLeaf/folia-server/src/test/java/dev/leafcraft/goldleaf/network/ServerPacketHardeningAndRegionCacheTest.java`
- `GoldLeaf/docs/adr/goldleaf-ADR-012-per-player-chunk-gen-backpressure-and-decode-time-exploit-hardening.md`
