package dev.leafcraft.goldleaf.worldgen;

import io.papermc.paper.spi.PaperWorldgenSpi;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VramStageProgramTest {

    @Test
    void stageOpcodeNamesMatchLoaderTable() {
        assertEquals("TERRAIN_FILL", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_TERRAIN_FILL));
        assertEquals("TERRAIN_AQUIFER", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_TERRAIN_AQUIFER));
        assertEquals("SURFACE_RULES", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_SURFACE_RULES));
        assertEquals("CARVER_MASK", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_CARVER_MASK));
        assertEquals("CARVER_APPLY", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_CARVER_APPLY));
        assertEquals("FEATURES_U8", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_FEATURES_U8));
        assertEquals("VRAM_LIGHT_U8", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_VRAM_LIGHT_U8));
        assertEquals("PACK_SECTIONS_U8", GoldLeafOpenClBridge.stageOpcodeName(GoldLeafOpenClBridge.STAGE_PACK_SECTIONS_U8));
    }

    @Test
    void createVramStageProgramRejectsInvalidStageArrays() {
        assertThrows(
            IllegalArgumentException.class,
            () -> GoldLeafOpenClBridge.createVramStageProgram(0, new int[0], new GoldLeafOpenClBridge.CompiledKernel[0])
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> GoldLeafOpenClBridge.createVramStageProgram(
                0,
                new int[] { GoldLeafOpenClBridge.STAGE_TERRAIN_FILL, GoldLeafOpenClBridge.STAGE_PACK_SECTIONS_U8 },
                new GoldLeafOpenClBridge.CompiledKernel[1]
            )
        );
    }

    @Test
    void writebackBufferExtractsAndValidatesVramPrecomputedLight() {
        final CompactVanillaSurfaceWritebackBuffer buf = new CompactVanillaSurfaceWritebackBuffer(8);
        buf.ensureVramLightCapacity(2, 4);
        buf.setVramLightValid(true);

        // Conditionally-opaque sentinel (-1) falls back cleanly to null
        assertNull(buf.extractVramPrecomputedLight(0, 4, -1, false));

        // Empty chunk (0 active sections) returns empty PrecomputedChunkLight
        final PaperWorldgenSpi.PrecomputedChunkLight empty = buf.extractVramPrecomputedLight(0, 4, 0, false);
        assertNotNull(empty);
        assertEquals(0, empty.activeSections());

        // Bottom-leak flag on section 0 falls back cleanly to null
        final ByteBuffer flags = buf.vramLightFlagsDirectBuffer();
        flags.put(0, PaperWorldgenSpi.LIGHT_FLAG_BOTTOM_LEAK);
        assertNull(buf.extractVramPrecomputedLight(0, 4, 2, false));

        // Valid chunk (batchIndex=1, activeSections=2, with block emission) extracts exact slices
        final int baseSec = 4;
        flags.put(baseSec, (byte) 0);
        flags.put(baseSec + 1, PaperWorldgenSpi.LIGHT_FLAG_SKY_ALL_15);
        buf.vramSkyDirectBuffer().put(baseSec * 2048, (byte) 0xF5);
        buf.vramBlkDirectBuffer().put(baseSec * 2048, (byte) 0x3A);

        final PaperWorldgenSpi.PrecomputedChunkLight valid = buf.extractVramPrecomputedLight(1, 4, 2, true);
        assertNotNull(valid);
        assertEquals(2, valid.activeSections());
        assertEquals(true, valid.hasBlockEmission());
        assertEquals(PaperWorldgenSpi.LIGHT_FLAG_SKY_ALL_15, valid.secFlags()[1]);
        assertEquals((byte) 0xF5, valid.skyNibbles()[0]);
        assertNotNull(valid.blkNibbles());
        assertEquals((byte) 0x3A, valid.blkNibbles()[0]);
    }
}
