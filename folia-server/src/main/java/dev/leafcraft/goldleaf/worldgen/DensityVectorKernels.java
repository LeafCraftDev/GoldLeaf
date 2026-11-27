package dev.leafcraft.goldleaf.worldgen;

import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

/**
 * Delegating facade for {@link io.papermc.paper.spi.DensityVectorKernels} ({@code goldleaf-ADR-004}).
 */
public final class DensityVectorKernels {

    private DensityVectorKernels() {
    }

    public static io.papermc.paper.spi.DensityVectorKernels.NoiseVolumeScratch acquireScratch(
        final int sX,
        final int sY,
        final int sZ
    ) {
        return io.papermc.paper.spi.DensityVectorKernels.acquireScratch(sX, sY, sZ);
    }

    public static void interpolateBlockStep(
        final float[] cellValues,
        final DensityVolume cellVolume,
        final float[] outValues,
        final DensityVolume outVolume,
        final int cellCountX,
        final int cellCountY,
        final int cellCountZ,
        final int cellSizeXz,
        final int cellSizeY,
        final float cellSizeXzInv,
        final float cellSizeYInv
    ) {
        io.papermc.paper.spi.DensityVectorKernels.interpolateBlockStep(
            cellValues, cellVolume, outValues, outVolume,
            cellCountX, cellCountY, cellCountZ, cellSizeXz, cellSizeY, cellSizeXzInv, cellSizeYInv
        );
    }

    public static void addPerlinToVolume(
        final char[] pAdj,
        final float[] outValues,
        final int sX,
        final int sY,
        final int sZ,
        final io.papermc.paper.spi.DensityVectorKernels.NoiseVolumeScratch scratch,
        final int yRunCount,
        final float amplitude
    ) {
        io.papermc.paper.spi.DensityVectorKernels.addPerlinToVolume(
            pAdj, outValues, sX, sY, sZ, scratch, yRunCount, amplitude
        );
    }

    public static void add(final float[] out, final float[] rhs, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.add(out, rhs, len);
    }

    public static void constAdd(final float[] out, final float c, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.constAdd(out, c, len);
    }

    public static void sub(final float[] out, final float[] rhs, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.sub(out, rhs, len);
    }

    public static void constSub(final float[] out, final float left, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.constSub(out, left, len);
    }

    public static void mul(final float[] out, final float[] rhs, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.mul(out, rhs, len);
    }

    public static void constMul(final float[] out, final float c, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.constMul(out, c, len);
    }

    public static void min(final float[] out, final float[] rhs, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.min(out, rhs, len);
    }

    public static void max(final float[] out, final float[] rhs, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.max(out, rhs, len);
    }

    public static void constMin(final float[] out, final float c, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.constMin(out, c, len);
    }

    public static void constMax(final float[] out, final float c, final int len) {
        io.papermc.paper.spi.DensityVectorKernels.constMax(out, c, len);
    }
}
