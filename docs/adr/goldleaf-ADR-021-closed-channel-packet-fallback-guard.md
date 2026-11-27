# goldleaf-ADR-021 — Closed-Channel Guard in PacketSendListener Fallback Delivery

**Status:** Accepted
**Date:** 2026-11-25

## Context

In vanilla Minecraft and stock Paper/Folia, `PacketSendListener#exceptionallySend(Supplier<Packet<?>>)` handles failed outbound packet writes for packets that register a truncated fallback (currently `ServerPlayer#sendClientboundPlayerCombatKillPacket` and `ServerPlayer#sendSystemMessage`, which fall back to shortened death or system-chat packets when encoding fails on an oversized component):

```java
if (!future.isSuccess()) {
    Packet<?> newPacket = handler.get();
    if (newPacket != null) {
        LOGGER.warn("Failed to deliver packet, sending fallback {}", newPacket.type(), future.cause());
        future.channel().writeAndFlush(newPacket, future.channel().voidPromise());
    } else {
        future.channel().pipeline().fireExceptionCaught(future.cause());
    }
}
```

Neither caller relies on any state mutation inside the fallback supplier; both purely construct a smaller replacement packet.

During high-churn disconnect workloads (observed during a 6-hour 48-bot differential soak on Paper `26.2`, producing `39` repeated `HIGH` log findings), a client frequently closes its TCP connection while a system-chat or death packet is still queued on the Netty event loop. The write fails with `io.netty.channel.StacklessClosedChannelException` (a subclass of `java.nio.channels.ClosedChannelException`). Because `PacketSendListener#exceptionallySend` does not check channel liveness or close-cause before invoking the supplier:

1. It allocates a fallback `Component` and `Packet<?>` for an already-disconnected player.
2. It logs a `WARN` message with a stack trace on the Netty IO thread (`Failed to deliver packet, sending fallback clientbound/minecraft:system_chat` + `StacklessClosedChannelException`).
3. It calls `future.channel().writeAndFlush(...)` onto a closed channel, which immediately fails and discards the packet.

## Decision

Add a closed-channel short-circuit inside `PacketSendListener#exceptionallySend` (exported as standalone Paper-compatible feature patch `0020-Guard-PacketSendListener-on-closed-channels.patch`):

1. **Closed-Channel Short-Circuit:** Before invoking `handler.get()`, check:
   `!future.channel().isActive() || future.cause() instanceof java.nio.channels.ClosedChannelException`.
   When either condition holds, `exceptionallySend` shall return immediately without invoking the fallback supplier, logging a `WARN`, or calling `writeAndFlush`.
2. **Active-Channel Parity:** On active channels failing for any non-close cause (such as `EncoderException` on an oversized chat or death component), behavior shall remain byte-for-byte identical to upstream Paper/Folia.
3. **`PacketSendListener#thenRun` Unchanged:** `thenRun` executes completion/cleanup callbacks that may release resources regardless of channel state and shall remain untouched.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Suppress `StacklessClosedChannelException` in log4j filters | Hides symptoms in logs without preventing wasted fallback packet allocations and `writeAndFlush` calls on closed Netty channels. |
| Guard only inside `ServerPlayer#sendSystemMessage` | Leaves `ServerPlayer#sendClientboundPlayerCombatKillPacket` and any future `exceptionallySend` callers vulnerable to the same closed-channel race. |
| Guard on `future.isCancelled()` only | Netty fails writes to closed channels with `ClosedChannelException` / `StacklessClosedChannelException`, not cancellation. |

## Consequences

- **Positive:**
  - Eliminates spurious `StacklessClosedChannelException` `WARN` stack traces on Netty IO threads during mass disconnects and bot/player reconnect churn.
  - Avoids allocating fallback chat/death `Component` trees and writing into closed channels.
  - Self-contained 4-line Layer 1 patch with zero GoldLeaf-specific dependencies, directly upstreamable to Paper.
- **Negative / Trade-offs:**
  - A channel that closes concurrently with an unrelated encode failure will not emit the fallback `WARN` log, which is acceptable because no fallback packet can be delivered to a closed channel anyway.

## References

- `GoldLeaf/folia-server/minecraft-patches/features/0020-Guard-PacketSendListener-on-closed-channels.patch`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/network/PacketSendListener.java`
- `GoldLeaf/folia-server/src/minecraft/java/net/minecraft/server/level/ServerPlayer.java`
- `GoldLeaf/folia-server/src/test/java/dev/leafcraft/goldleaf/network/ServerPacketHardeningAndRegionCacheTest.java`
