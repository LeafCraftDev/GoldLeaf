package dev.leafcraft.goldleaf.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Pair;
import dev.leafcraft.goldleaf.config.GoldLeafConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import io.papermc.paper.configuration.GlobalConfigTestingBase;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurateException;

@VanillaFeature
class ServerPacketHardeningAndRegionCacheTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GlobalConfigTestingBase.setupGlobalConfigForTest(RegistryAccess.EMPTY);
    }

    private static final class RecordingConnection extends Connection {
        final List<Packet<?>> sent = new ArrayList<>();

        RecordingConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(final Packet<?> packet) {
            this.sent.add(packet);
        }
    }

    @Test
    void packetSendListenerShortCircuitsOnClosedChannelAndClosedChannelException() throws Exception {
        final EmbeddedChannel closed = new EmbeddedChannel();
        closed.close().syncUninterruptibly();
        final AtomicInteger calls = new AtomicInteger();
        final ChannelFutureListener listener = PacketSendListener.exceptionallySend(() -> {
            calls.incrementAndGet();
            return new ClientboundSystemChatPacket(Component.literal("fallback"), false);
        });

        final ChannelFuture closedFuture = closed.newFailedFuture(new ClosedChannelException());
        listener.operationComplete(closedFuture);
        assertEquals(0, calls.get(), "closed channel must not invoke fallback supplier");
        assertNull(closed.readOutbound(), "closed channel must not receive fallback writes");

        // Even on an active channel, a ClosedChannelException cause must short-circuit cleanly
        final EmbeddedChannel active = new EmbeddedChannel();
        try {
            final ChannelFuture cceFuture = active.newFailedFuture(new ClosedChannelException());
            listener.operationComplete(cceFuture);
            assertEquals(0, calls.get(), "ClosedChannelException must not invoke fallback supplier");
            assertNull(active.readOutbound());

            // Non-close failure on an active channel still sends the fallback packet
            final ChannelFuture encodeFailure = active.newFailedFuture(new IllegalStateException("oversized"));
            listener.operationComplete(encodeFailure);
            assertEquals(1, calls.get(), "active channel encode failure must invoke fallback supplier");
            final Object outbound = active.readOutbound();
            assertInstanceOf(ClientboundSystemChatPacket.class, outbound);
        } finally {
            active.finishAndReleaseAll();
        }
    }

    @Test
    void oversizedChunkPacketSplitsIntoBareChunkAndDistinctImmutableBlockEntityPackets() throws Exception {
        final Class<?> beInfoClass = Class.forName(
            "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo"
        );
        final Constructor<?> beInfoCtor = beInfoClass.getDeclaredConstructor(
            byte.class, short.class, BlockEntityType.class, Optional.class
        );
        beInfoCtor.setAccessible(true);

        final CompoundTag tagA = new CompoundTag();
        tagA.putString("id", "sign-a");
        final CompoundTag tagB = new CompoundTag();
        tagB.putString("id", "sign-b");

        final Object infoA = beInfoCtor.newInstance((byte) ((2 << 4) | 3), (short) 64, BlockEntityTypes.SIGN, Optional.of(tagA));
        final Object infoB = beInfoCtor.newInstance((byte) ((7 << 4) | 11), (short) 72, BlockEntityTypes.SIGN, Optional.of(tagB));

        final Constructor<ClientboundLevelChunkPacketData> dataCtor =
            ClientboundLevelChunkPacketData.class.getDeclaredConstructor(Map.class, byte[].class, List.class);
        dataCtor.setAccessible(true);
        final ClientboundLevelChunkPacketData chunkData = dataCtor.newInstance(
            new EnumMap<>(Heightmap.Types.class),
            new byte[16],
            List.of(infoA, infoB)
        );
        final ClientboundBlockEntityDataPacket extraPacket =
            new ClientboundBlockEntityDataPacket(new BlockPos(35, 80, -10), BlockEntityTypes.SIGN, new CompoundTag());
        chunkData.getExtraPackets().add(extraPacket);

        final ClientboundLightUpdatePacketData lightData = new ClientboundLightUpdatePacketData(
            new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()
        );
        final ClientboundLevelChunkWithLightPacket packet =
            new ClientboundLevelChunkWithLightPacket(2, -1, chunkData, lightData);

        assertTrue(packet.hasLargePacketFallback());

        final RecordingConnection conn = new RecordingConnection();
        assertTrue(packet.packetTooLarge(conn));
        // 1 stripped chunk + 2 split BE packets + 1 re-ordered extraPacket
        assertEquals(4, conn.sent.size());

        final ClientboundLevelChunkWithLightPacket strippedChunk =
            assertInstanceOf(ClientboundLevelChunkWithLightPacket.class, conn.sent.get(0));
        assertEquals(2, strippedChunk.x());
        assertEquals(-1, strippedChunk.z());
        // Calling packetTooLarge on the already-stripped chunk must terminate (no recursion)
        assertFalse(strippedChunk.packetTooLarge(new RecordingConnection()));

        final ClientboundBlockEntityDataPacket be0 =
            assertInstanceOf(ClientboundBlockEntityDataPacket.class, conn.sent.get(1));
        final ClientboundBlockEntityDataPacket be1 =
            assertInstanceOf(ClientboundBlockEntityDataPacket.class, conn.sent.get(2));
        assertEquals(new BlockPos(32 + 2, 64, -16 + 3), be0.getPos());
        assertEquals(new BlockPos(32 + 7, 72, -16 + 11), be1.getPos());
        assertNotEquals(be0.getPos(), be1.getPos(), "BlockPos must be immutable per BE packet");
        assertEquals("sign-a", be0.getTag().getStringOr("id", ""));
        assertEquals("sign-b", be1.getTag().getStringOr("id", ""));
        assertEquals(extraPacket, conn.sent.get(3));
    }

    @Test
    void oversizedBlockEntityEquipmentEntityDataAndContainerSlotFallbackWithoutRecursion() {
        final RecordingConnection conn = new RecordingConnection();

        // 1. Block entity packet downgrades to empty NBT and does not recurse
        final CompoundTag hugeTag = new CompoundTag();
        hugeTag.putString("payload", "x".repeat(64));
        final ClientboundBlockEntityDataPacket bePacket =
            new ClientboundBlockEntityDataPacket(new BlockPos(10, 65, 20), BlockEntityTypes.SIGN, hugeTag);
        assertTrue(bePacket.hasLargePacketFallback());
        assertTrue(bePacket.packetTooLarge(conn));
        assertEquals(1, conn.sent.size());
        final ClientboundBlockEntityDataPacket beFallback =
            assertInstanceOf(ClientboundBlockEntityDataPacket.class, conn.sent.get(0));
        assertTrue(beFallback.getTag().isEmpty());
        assertFalse(beFallback.packetTooLarge(new RecordingConnection()));

        // 2. Multi-slot equipment splits; single oversized patched slot strips components cleanly
        conn.sent.clear();
        final ItemStack patchedBook = new ItemStack(Items.WRITTEN_BOOK, 1);
        patchedBook.set(DataComponents.CUSTOM_NAME, Component.literal("heavy-book"));
        final ItemStack plainSword = new ItemStack(Items.DIAMOND_SWORD, 1);
        final ClientboundSetEquipmentPacket multiEquip = new ClientboundSetEquipmentPacket(
            42,
            List.of(Pair.of(EquipmentSlot.MAINHAND, patchedBook), Pair.of(EquipmentSlot.OFFHAND, plainSword))
        );
        assertTrue(multiEquip.hasLargePacketFallback());
        assertTrue(multiEquip.packetTooLarge(conn));
        assertEquals(2, conn.sent.size());
        final ClientboundSetEquipmentPacket singleEquip =
            assertInstanceOf(ClientboundSetEquipmentPacket.class, conn.sent.get(0));
        conn.sent.clear();
        assertTrue(singleEquip.packetTooLarge(conn));
        assertEquals(1, conn.sent.size());
        final ClientboundSetEquipmentPacket strippedEquip =
            assertInstanceOf(ClientboundSetEquipmentPacket.class, conn.sent.get(0));
        assertTrue(strippedEquip.getSlots().getFirst().getSecond().getComponentsPatch().isEmpty());
        assertEquals(Items.WRITTEN_BOOK, strippedEquip.getSlots().getFirst().getSecond().getItem());
        // Already stripped -> skips cleanly without re-sending
        conn.sent.clear();
        assertTrue(strippedEquip.packetTooLarge(conn));
        assertEquals(0, conn.sent.size());

        // 3. Entity metadata splits multi-entry, strips single oversized ItemStack, skips non-item oversized entry
        final SynchedEntityData.DataValue<ItemStack> itemVal =
            new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ITEM_STACK, patchedBook);
        final SynchedEntityData.DataValue<Component> textVal =
            new SynchedEntityData.DataValue<>(2, EntityDataSerializers.COMPONENT, Component.literal("huge-name"));
        final ClientboundSetEntityDataPacket multiData = new ClientboundSetEntityDataPacket(99, List.of(itemVal, textVal));
        assertTrue(multiData.hasLargePacketFallback());
        assertTrue(multiData.packetTooLarge(conn));
        assertEquals(2, conn.sent.size());

        final ClientboundSetEntityDataPacket singleItemData =
            assertInstanceOf(ClientboundSetEntityDataPacket.class, conn.sent.get(0));
        final ClientboundSetEntityDataPacket singleTextData =
            assertInstanceOf(ClientboundSetEntityDataPacket.class, conn.sent.get(1));
        conn.sent.clear();
        assertTrue(singleItemData.packetTooLarge(conn));
        assertEquals(1, conn.sent.size());
        final ClientboundSetEntityDataPacket strippedItemData =
            assertInstanceOf(ClientboundSetEntityDataPacket.class, conn.sent.get(0));
        final ItemStack strippedMetaStack =
            assertInstanceOf(ItemStack.class, strippedItemData.packedItems().getFirst().value());
        assertTrue(strippedMetaStack.getComponentsPatch().isEmpty());

        conn.sent.clear();
        assertTrue(singleTextData.packetTooLarge(conn));
        assertEquals(0, conn.sent.size(), "non-item oversized single metadata entry should be skipped");

        // 4. Container slot strips visual item components, then skips if already stripped
        final ClientboundContainerSetSlotPacket slotPacket = new ClientboundContainerSetSlotPacket(1, 5, 3, patchedBook);
        assertTrue(slotPacket.hasLargePacketFallback());
        assertTrue(slotPacket.packetTooLarge(conn));
        assertEquals(1, conn.sent.size());
        final ClientboundContainerSetSlotPacket strippedSlot =
            assertInstanceOf(ClientboundContainerSetSlotPacket.class, conn.sent.get(0));
        assertEquals(1, strippedSlot.getContainerId());
        assertEquals(5, strippedSlot.getStateId());
        assertEquals(3, strippedSlot.getSlot());
        assertTrue(strippedSlot.getItem().getComponentsPatch().isEmpty());
        conn.sent.clear();
        assertTrue(strippedSlot.packetTooLarge(conn));
        assertEquals(0, conn.sent.size());
    }

    @Test
    void regionFileStorageRespectsConfiguredCacheCapAndIdleEviction() throws Exception {
        final Path configDir = this.tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve(GoldLeafConfig.FILE_NAME),
            """
                storage:
                  format: mca
                  max-open-regions: 2
                  region-file-idle-timeout-seconds: 60
                """,
            StandardCharsets.UTF_8
        );
        GoldLeafConfig.init(configDir);
        assertEquals(60, GoldLeafConfig.get().storage.regionFileIdleTimeoutSeconds);

        // Negative timeout must fail validation
        Files.writeString(
            configDir.resolve(GoldLeafConfig.FILE_NAME),
            """
                storage:
                  region-file-idle-timeout-seconds: -1
                """,
            StandardCharsets.UTF_8
        );
        assertThrows(ConfigurateException.class, () -> GoldLeafConfig.init(configDir));

        // Restore valid MCA config for live RegionFileStorage test
        Files.writeString(
            configDir.resolve(GoldLeafConfig.FILE_NAME),
            """
                storage:
                  format: mca
                  max-open-regions: 2
                  region-file-idle-timeout-seconds: 60
                """,
            StandardCharsets.UTF_8
        );
        GoldLeafConfig.init(configDir);

        final Path regionDir = this.tempDir.resolve("region");
        final RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        final String prevCap = System.getProperty("goldleaf.regionFileCacheSize");
        final String prevIdle = System.getProperty("goldleaf.regionFileIdleTimeoutSeconds");
        System.setProperty("goldleaf.regionFileCacheSize", "2");
        System.setProperty("goldleaf.regionFileIdleTimeoutSeconds", "60");
        try (RegionFileStorage storage = new RegionFileStorage(info, regionDir, false)) {
            final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
            final CompoundTag tag0 = new CompoundTag();
            tag0.putInt("DataVersion", dataVersion);
            tag0.putInt("xPos", 0);
            tag0.putInt("zPos", 0);
            tag0.putInt("v", 10);
            final CompoundTag tag1 = new CompoundTag();
            tag1.putInt("DataVersion", dataVersion);
            tag1.putInt("xPos", 32);
            tag1.putInt("zPos", 0);
            tag1.putInt("v", 20);
            final CompoundTag tag2 = new CompoundTag();
            tag2.putInt("DataVersion", dataVersion);
            tag2.putInt("xPos", 64);
            tag2.putInt("zPos", 0);
            tag2.putInt("v", 30);

            storage.write(new ChunkPos(0, 0), tag0);
            storage.write(new ChunkPos(32, 0), tag1);
            assertEquals(2, storage.getCachedRegionFileCount());

            // Third distinct region must evict LRU down to configured cap of 2
            storage.write(new ChunkPos(64, 0), tag2);
            assertEquals(2, storage.getCachedRegionFileCount());

            // Evicting with future clock closes all idle handles
            final int evicted = storage.evictIdleRegionFiles(System.nanoTime() + 120_000_000_000L, 60_000_000_000L);
            assertEquals(2, evicted);
            assertEquals(0, storage.getCachedRegionFileCount());

            // Transparent reopen preserves data intact
            final CompoundTag readBack = storage.read(new ChunkPos(64, 0));
            assertNotNull(readBack);
            assertEquals(30, readBack.getIntOr("v", -1));
            assertEquals(1, storage.getCachedRegionFileCount());
        } finally {
            if (prevCap == null) {
                System.clearProperty("goldleaf.regionFileCacheSize");
            } else {
                System.setProperty("goldleaf.regionFileCacheSize", prevCap);
            }
            if (prevIdle == null) {
                System.clearProperty("goldleaf.regionFileIdleTimeoutSeconds");
            } else {
                System.setProperty("goldleaf.regionFileIdleTimeoutSeconds", prevIdle);
            }
        }
    }
}
