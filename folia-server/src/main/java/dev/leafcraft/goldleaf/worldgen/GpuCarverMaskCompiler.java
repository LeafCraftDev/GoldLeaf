package dev.leafcraft.goldleaf.worldgen;

import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.util.valueproviders.ClampedNormalFloat;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.util.valueproviders.VeryBiasedToBottomInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import org.jspecify.annotations.Nullable;

/**
 * Compiles Mojang's live uniform {@link WorldCarver} list ({@link CaveWorldCarver} and
 * {@link CanyonWorldCarver}) into a vanilla-exact OpenCL 1.2 kernel ({@code generate_carver_masks_u8})
 * that evaluates the entire {@code 17x17} source-chunk neighborhood in parallel across 256 threads
 * per chunk using on-chip {@code __local} memory ({@code 12 KiB}) and exact 48-bit Java LCG
 * {@link LegacyRandomSource} math (zero FP64).
 *
 * <p>Eliminates CPU {@code CARVER_MASK} execution and host-to-device carving-mask PCIe uploads by
 * writing directly into {@code slot->carver_masks_buf} and {@code slot->carver_meta_buf} in VRAM
 * immediately before Pass 3B ({@code apply_carver_aquifer_u8}). Fails closed (returns {@code null})
 * if any custom/unsupported carver or value provider is present ({@code S-004}).</p>
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, leafgen-ADR-008
 * @trace S-002, S-004
 */
public final class GpuCarverMaskCompiler {

    public static final String KERNEL_NAME = "generate_carver_masks_u8";
    public static final int MAX_SUPPORTED_CARVERS = 16;
    public static final int MAX_GEN_DEPTH = 512;

    private GpuCarverMaskCompiler() {
    }

    public static OpenClBackendEmitter.@Nullable DensityVolumeProgram compile(
        final List<WorldCarver> uniformCarvers,
        final long worldSeed,
        final WorldGenerationContext context,
        final String moduleBaseName
    ) {
        Objects.requireNonNull(uniformCarvers, "uniformCarvers");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(moduleBaseName, "moduleBaseName");

        if (uniformCarvers.isEmpty() || uniformCarvers.size() > MAX_SUPPORTED_CARVERS) {
            return null;
        }
        final int minGenY = context.getMinGenY();
        final int genDepth = context.getGenDepth();
        if (genDepth <= 0 || genDepth > MAX_GEN_DEPTH) {
            return null;
        }

        // Validate all carvers and value providers up front (fail-closed S-004).
        for (final WorldCarver carver : uniformCarvers) {
            if (!isSupportedCarver(carver)) {
                return null;
            }
        }

        final StringBuilder sb = new StringBuilder(16384);
        sb.append("// GoldLeaf vanilla-exact OpenCL 1.2 GPU Carver Mask generator (zero FP64)\n");
        sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");

        emitLcgHelpers(sb);
        emitEllipsoidAndTunnelHelpers(sb, minGenY, genDepth);
        emitKernel(sb, uniformCarvers, worldSeed, context, minGenY, genDepth);

        final String src = sb.toString();
        final int[] emptySpill = new int[0];
        return new OpenClBackendEmitter.DensityVolumeProgram(
            KERNEL_NAME,
            src,
            sha256(src),
            emptySpill,
            0,
            0,
            0
        );
    }

    /**
     * Compiles a generalized per-biome {@code generate_carver_masks_u8} kernel for datapacks with
     * non-uniform biome carver lists (e.g., Terralith, Tectonic, Wythers, or any custom pack).
     *
     * <p>Embeds the dimension's already-lowered {@link GpuBiomeRTree} helper so each of the 289
     * source-chunk cells ({@code dx, dz in [-8..+8]}) evaluates {@code getNoiseBiome(sx<<2, 0, sz<<2)}
     * once in VRAM and runs only that biome's exact ordered carver list with its vanilla-exact
     * per-biome slot index ({@code worldSeed + localSlotIdx}). Fails closed ({@code null}) if any
     * biome uses an unsupported carver or provider ({@code S-004}).</p>
     */
    public static OpenClBackendEmitter.@Nullable DensityVolumeProgram compilePerBiome(
        final GpuBiomeRTree.FusedBiomeRTreeProgram fusedBiomeRTree,
        final Function<Holder<Biome>, Iterable<Holder<WorldCarver>>> biomeCarverLookup,
        final long worldSeed,
        final WorldGenerationContext context,
        final String moduleBaseName
    ) {
        Objects.requireNonNull(fusedBiomeRTree, "fusedBiomeRTree");
        Objects.requireNonNull(biomeCarverLookup, "biomeCarverLookup");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(moduleBaseName, "moduleBaseName");

        final int minGenY = context.getMinGenY();
        final int genDepth = context.getGenDepth();
        if (genDepth <= 0 || genDepth > MAX_GEN_DEPTH) {
            return null;
        }

        final String rtreeSrc = fusedBiomeRTree.program().sourceCode();
        final int rtreeKernelIdx = rtreeSrc.indexOf("__kernel void eval_biome_rtree_batch(");
        if (rtreeKernelIdx < 0 || !rtreeSrc.contains("static void gl_eval_biome_climate6(")) {
            return null;
        }
        final String biomeHelperPrefix = rtreeSrc.substring(0, rtreeKernelIdx);

        final Holder<Biome>[] palette = fusedBiomeRTree.rtree().biomePalette();
        final int numBiomes = palette.length;
        if (numBiomes <= 0 || numBiomes > GpuBiomeRTree.MAX_PALETTE_SIZE) {
            return null;
        }

        final List<WorldCarver> distinctCarvers = new ArrayList<>();
        @SuppressWarnings("unchecked")
        final List<Integer>[] biomeSlotCarvers = new List[numBiomes];
        int maxSlotCount = 0;

        for (int b = 0; b < numBiomes; b++) {
            final List<Integer> slots = new ArrayList<>(4);
            final Iterable<Holder<WorldCarver>> carvers = biomeCarverLookup.apply(palette[b]);
            if (carvers != null) {
                for (final Holder<WorldCarver> holder : carvers) {
                    final WorldCarver carver = holder.value();
                    if (!isSupportedCarver(carver)) {
                        return null;
                    }
                    int distinctIdx = distinctCarvers.indexOf(carver);
                    if (distinctIdx < 0) {
                        distinctIdx = distinctCarvers.size();
                        if (distinctIdx >= MAX_SUPPORTED_CARVERS) {
                            return null;
                        }
                        distinctCarvers.add(carver);
                    }
                    slots.add(distinctIdx);
                }
            }
            if (slots.size() > MAX_SUPPORTED_CARVERS) {
                return null;
            }
            biomeSlotCarvers[b] = slots;
            if (slots.size() > maxSlotCount) {
                maxSlotCount = slots.size();
            }
        }

        // If no biome in the dimension has any carvers, emit a fast no-op mask kernel.
        if (distinctCarvers.isEmpty() || maxSlotCount == 0) {
            final String noopSrc = "// GoldLeaf zero-carver fast no-op mask kernel (zero FP64)\n"
                + "#pragma OPENCL FP_CONTRACT OFF\n\n"
                + "__kernel void " + KERNEL_NAME + "(\n"
                + "    __global const int4* batch_origins,\n"
                + "    __global int* carver_meta,\n"
                + "    __global uint* out_carver_masks,\n"
                + "    const int batch_count,\n"
                + "    const int words_per_col,\n"
                + "    const int min_block_y,\n"
                + "    const int min_carve_y,\n"
                + "    const int max_carve_y,\n"
                + "    __global const int* restrict gl_lut_spill\n"
                + ") {\n"
                + "}\n";
            return new OpenClBackendEmitter.DensityVolumeProgram(
                KERNEL_NAME, noopSrc, sha256(noopSrc), new int[0], 0, 0, 0
            );
        }

        // Build per-slot [slot][biome] -> distinct carver index (255 = absent) and classify invariant vs biome-varying slots.
        final int[][] slotBiomeCidx = new int[maxSlotCount][numBiomes];
        final int[] uniformSlotCidx = new int[maxSlotCount];
        boolean anyNonUniformSlot = false;
        for (int s = 0; s < maxSlotCount; s++) {
            for (int b = 0; b < numBiomes; b++) {
                final List<Integer> slots = biomeSlotCarvers[b];
                slotBiomeCidx[s][b] = s < slots.size() ? slots.get(s) : 255;
            }
            final int first = slotBiomeCidx[s][0];
            boolean uniform = first != 255;
            if (uniform) {
                for (int b = 1; b < numBiomes; b++) {
                    if (slotBiomeCidx[s][b] != first) {
                        uniform = false;
                        break;
                    }
                }
            }
            uniformSlotCidx[s] = uniform ? first : -1;
            if (!uniform) {
                anyNonUniformSlot = true;
            }
        }

        final StringBuilder sb = new StringBuilder((anyNonUniformSlot ? biomeHelperPrefix.length() : 0) + 20480);
        sb.append("// GoldLeaf vanilla-exact OpenCL 1.2 Per-Biome GPU Carver Mask generator (zero FP64)\n");
        if (anyNonUniformSlot) {
            sb.append(biomeHelperPrefix);
        } else {
            sb.append("#pragma OPENCL FP_CONTRACT OFF\n\n");
        }
        sb.append("\n// === Per-Biome Carver Mask LCG & Ellipsoid Helpers ===\n");
        emitLcgHelpers(sb);
        emitEllipsoidAndTunnelHelpers(sb, minGenY, genDepth);
        emitPerBiomeKernel(
            sb, distinctCarvers, slotBiomeCidx, uniformSlotCidx, numBiomes,
            maxSlotCount, anyNonUniformSlot, worldSeed, context, minGenY, genDepth
        );

        final String src = sb.toString();
        final OpenClBackendEmitter.DensityVolumeProgram rtreeProg = fusedBiomeRTree.program();
        return new OpenClBackendEmitter.DensityVolumeProgram(
            KERNEL_NAME,
            src,
            sha256(src + (anyNonUniformSlot ? ":rtree:" + fusedBiomeRTree.rtree().digestSha256() : ":all-uniform")),
            anyNonUniformSlot ? rtreeProg.spilledLutWords() : new int[0],
            anyNonUniformSlot ? rtreeProg.usedConstantBytes() : 0,
            anyNonUniformSlot ? rtreeProg.spilledLutCount() : 0,
            anyNonUniformSlot ? rtreeProg.deduplicatedLutCount() : 0
        );
    }

    private static float carverProbability(final WorldCarver carver) {
        if (carver instanceof CaveWorldCarver cave) {
            return cave.probability();
        }
        if (carver instanceof CanyonWorldCarver canyon) {
            return canyon.probability();
        }
        return 1.0f;
    }

    private static void emitPerBiomeKernel(
        final StringBuilder sb,
        final List<WorldCarver> distinctCarvers,
        final int[][] slotBiomeCidx,
        final int[] uniformSlotCidx,
        final int numBiomes,
        final int maxSlotCount,
        final boolean anyNonUniformSlot,
        final long worldSeed,
        final WorldGenerationContext context,
        final int minGenY,
        final int genDepth
    ) {
        final long[] xScales = new long[maxSlotCount];
        final long[] zScales = new long[maxSlotCount];
        final long[] baseSeeds = new long[maxSlotCount];
        final WorldgenRandom tmpRand = new WorldgenRandom(new LegacyRandomSource(0L));
        for (int s = 0; s < maxSlotCount; s++) {
            final long bs = worldSeed + s;
            baseSeeds[s] = bs;
            tmpRand.setSeed(bs);
            xScales[s] = tmpRand.nextLong();
            zScales[s] = tmpRand.nextLong();
        }

        sb.append("__constant long CM_SLOT_BASE_SEED[").append(maxSlotCount).append("] = {");
        for (int s = 0; s < maxSlotCount; s++) {
            if (s > 0) sb.append(", ");
            sb.append(baseSeeds[s]).append("L");
        }
        sb.append("};\n");
        sb.append("__constant long CM_SLOT_X_SCALE[").append(maxSlotCount).append("] = {");
        for (int s = 0; s < maxSlotCount; s++) {
            if (s > 0) sb.append(", ");
            sb.append(xScales[s]).append("L");
        }
        sb.append("};\n");
        sb.append("__constant long CM_SLOT_Z_SCALE[").append(maxSlotCount).append("] = {");
        for (int s = 0; s < maxSlotCount; s++) {
            if (s > 0) sb.append(", ");
            sb.append(zScales[s]).append("L");
        }
        sb.append("};\n");

        final List<Integer> nonUniformSlots = new ArrayList<>();
        for (int s = 0; s < maxSlotCount; s++) {
            if (uniformSlotCidx[s] < 0) {
                nonUniformSlots.add(s);
            }
        }
        if (anyNonUniformSlot) {
            sb.append("__constant uchar CM_SLOT_BIOME_CIDX[").append(nonUniformSlots.size() * numBiomes).append("] = {");
            boolean firstByte = true;
            for (final int s : nonUniformSlots) {
                for (int b = 0; b < numBiomes; b++) {
                    if (!firstByte) sb.append(',');
                    firstByte = false;
                    sb.append(slotBiomeCidx[s][b]);
                }
            }
            sb.append("};\n\n");
        } else {
            sb.append("\n");
        }

        sb.append("__kernel void ").append(KERNEL_NAME).append("(\n");
        sb.append("    __global const int4* batch_origins,\n");
        sb.append("    __global int* carver_meta,\n");
        sb.append("    __global uint* out_carver_masks,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int min_carve_y,\n");
        sb.append("    const int max_carve_y,\n");
        sb.append("    __global const int* restrict gl_lut_spill\n");
        sb.append(") {\n");
        sb.append("    const int wg_id = (int)get_group_id(0);\n");
        sb.append("    const int batch_idx = wg_id >> 2;\n");
        sb.append("    const int sub = wg_id & 3;\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) return;\n");
        sb.append("    if (carver_meta[batch_idx] == -2) return;\n");
        sb.append("    __global uint* g_mask = out_carver_masks + (batch_idx * 256 * words_per_col);\n");
        sb.append("    __local int l_canyon_cnt;\n");
        sb.append("    __local int l_canyon_cidx[32];\n");
        sb.append("    __local int l_canyon_dx[32];\n");
        sb.append("    __local int l_canyon_dz[32];\n");
        sb.append("    __local ulong l_canyon_rng[32];\n");
        sb.append("    __local float l_canyon_wf[").append(genDepth).append("];\n");
        sb.append("    if (tid == 0) { l_canyon_cnt = 0; }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int chunk_x = orig.x >> 4;\n");
        sb.append("    const int chunk_z = orig.z >> 4;\n");
        sb.append("    int my_any = 0;\n");
        sb.append("    for (int cell = (sub << 6) + tid; cell < 289; cell += 256) {\n");
        sb.append("        const int dx = (cell / 17) - 8;\n");
        sb.append("        const int dz = (cell % 17) - 8;\n");
        sb.append("        const int sx = chunk_x + dx;\n");
        sb.append("        const int sz = chunk_z + dz;\n");
        sb.append("        const float base_rx = (float)(dx * 16);\n");
        sb.append("        const float base_rz = (float)(dz * 16);\n");

        // Pass A: Invariant-slot hoisting (identical carver in all biomes -> zero climate/R-Tree evaluation)
        for (int s = 0; s < maxSlotCount; s++) {
            final int uIdx = uniformSlotCidx[s];
            if (uIdx < 0) {
                continue;
            }
            final WorldCarver carver = distinctCarvers.get(uIdx);
            sb.append("        {\n");
            sb.append("            const long lfs = ((long)sx * CM_SLOT_X_SCALE[").append(s)
                .append("]) ^ ((long)sz * CM_SLOT_Z_SCALE[").append(s)
                .append("]) ^ CM_SLOT_BASE_SEED[").append(s).append("];\n");
            sb.append("            ulong rng = cm_lcg_init(lfs);\n");
            sb.append("            int rng_has_g = 0; float rng_next_g = 0.0f;\n");
            if (carver instanceof CaveWorldCarver cave) {
                emitCaveCarverBranch(sb, cave, context);
            } else if (carver instanceof CanyonWorldCarver canyon) {
                sb.append("            if (cm_lcg_next_float(&rng) <= ").append(fmtFloat(canyon.probability())).append(") {\n");
                sb.append("                const int slot = atomic_inc(&l_canyon_cnt);\n");
                sb.append("                if (slot < 32) {\n");
                sb.append("                    l_canyon_cidx[slot] = ").append(uIdx).append(";\n");
                sb.append("                    l_canyon_dx[slot] = dx;\n");
                sb.append("                    l_canyon_dz[slot] = dz;\n");
                sb.append("                    l_canyon_rng[slot] = rng;\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
            sb.append("        }\n");
        }

        // Pass B: Biome-varying slots — probability-gated lazy climate6 + R-Tree evaluation (0.3%-17% of cells!)
        if (anyNonUniformSlot) {
            sb.append("        int bid = -1;\n");
            for (int nuIdx = 0; nuIdx < nonUniformSlots.size(); nuIdx++) {
                final int s = nonUniformSlots.get(nuIdx);
                final List<Integer> slotCarverIds = new ArrayList<>();
                float maxProb = 0.0f;
                for (int b = 0; b < numBiomes; b++) {
                    final int ci = slotBiomeCidx[s][b];
                    if (ci != 255) {
                        if (!slotCarverIds.contains(ci)) {
                            slotCarverIds.add(ci);
                            final float pr = carverProbability(distinctCarvers.get(ci));
                            if (pr > maxProb) {
                                maxProb = pr;
                            }
                        }
                    }
                }
                if (slotCarverIds.isEmpty()) {
                    continue;
                }
                sb.append("        {\n");
                sb.append("            const long lfs = ((long)sx * CM_SLOT_X_SCALE[").append(s)
                    .append("]) ^ ((long)sz * CM_SLOT_Z_SCALE[").append(s)
                    .append("]) ^ CM_SLOT_BASE_SEED[").append(s).append("];\n");
                sb.append("            ulong rng = cm_lcg_init(lfs);\n");
                sb.append("            const float r_start = cm_lcg_next_float(&rng);\n");
                sb.append("            if (r_start <= ").append(fmtFloat(maxProb)).append(") {\n");
                sb.append("                if (bid < 0) {\n");
                sb.append("                    float gl_ch[6];\n");
                sb.append("                    gl_eval_biome_climate6(sx << 4, 0, sz << 4, gl_ch, gl_lut_spill);\n");
                sb.append("                    bid = (int)gl_rtree_lookup_biome(\n");
                sb.append("                        (long)(gl_ch[0] * 10000.0f), (long)(gl_ch[1] * 10000.0f), (long)(gl_ch[2] * 10000.0f),\n");
                sb.append("                        (long)(gl_ch[3] * 10000.0f), (long)(gl_ch[4] * 10000.0f), (long)(gl_ch[5] * 10000.0f),\n");
                sb.append("                        gl_lut_spill\n");
                sb.append("                    );\n");
                sb.append("                }\n");
                sb.append("                const int c_idx = (int)CM_SLOT_BIOME_CIDX[").append(nuIdx * numBiomes).append(" + bid];\n");
                sb.append("                if (c_idx != 255) {\n");
                sb.append("                    int rng_has_g = 0; float rng_next_g = 0.0f;\n");
                for (int idxPos = 0; idxPos < slotCarverIds.size(); idxPos++) {
                    final int ci = slotCarverIds.get(idxPos);
                    final WorldCarver carver = distinctCarvers.get(ci);
                    sb.append(idxPos == 0 ? "                    if (c_idx == " + ci + ") {\n" : "                    else if (c_idx == " + ci + ") {\n");
                    if (carver instanceof CaveWorldCarver cave) {
                        emitCaveCarverBranchWithStartFloat(sb, cave, context, "r_start");
                    } else if (carver instanceof CanyonWorldCarver canyon) {
                        sb.append("                        if (r_start <= ").append(fmtFloat(canyon.probability())).append(") {\n");
                        sb.append("                            const int slot = atomic_inc(&l_canyon_cnt);\n");
                        sb.append("                            if (slot < 32) {\n");
                        sb.append("                                l_canyon_cidx[slot] = ").append(ci).append(";\n");
                        sb.append("                                l_canyon_dx[slot] = dx;\n");
                        sb.append("                                l_canyon_dz[slot] = dz;\n");
                        sb.append("                                l_canyon_rng[slot] = rng;\n");
                        sb.append("                            }\n");
                        sb.append("                        }\n");
                    }
                    sb.append("                    }\n");
                }
                sb.append("                }\n");
                sb.append("            }\n");
                sb.append("        }\n");
            }
        }

        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    if (tid == 0) {\n");
        sb.append("        const int c_total = min(l_canyon_cnt, 32);\n");
        sb.append("        for (int ci = 0; ci < c_total; ci++) {\n");
        sb.append("            const int c_idx = l_canyon_cidx[ci];\n");
        sb.append("            const float base_rx = (float)(l_canyon_dx[ci] * 16);\n");
        sb.append("            const float base_rz = (float)(l_canyon_dz[ci] * 16);\n");
        sb.append("            ulong rng = l_canyon_rng[ci];\n");
        sb.append("            int rng_has_g = 0; float rng_next_g = 0.0f;\n");
        boolean firstCanyon = true;
        for (int i = 0; i < distinctCarvers.size(); i++) {
            final WorldCarver carver = distinctCarvers.get(i);
            if (carver instanceof CanyonWorldCarver canyon) {
                sb.append(firstCanyon ? "            if (c_idx == " + i + ") {\n" : "            else if (c_idx == " + i + ") {\n");
                firstCanyon = false;
                emitCanyonCarverBody(sb, canyon, context, genDepth);
                sb.append("            }\n");
            }
        }
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (my_any != 0) {\n");
        sb.append("        carver_meta[batch_idx] = batch_idx;\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static boolean isSupportedCarver(final WorldCarver carver) {
        if (carver instanceof CaveWorldCarver cave) {
            return isSupportedHeight(cave.y())
                && isSupportedInt(cave.count())
                && isSupportedFloat(cave.thickness())
                && isSupportedFloat(cave.roomVerticalRadiusMultiplier())
                && isSupportedFloat(cave.horizontalRadiusMultiplier())
                && isSupportedFloat(cave.verticalRadiusMultiplier())
                && isSupportedFloat(cave.startVerticalRadiusMultiplier())
                && isSupportedFloat(cave.floorLevel());
        }
        if (carver instanceof CanyonWorldCarver canyon) {
            return isSupportedHeight(canyon.y())
                && isSupportedFloat(canyon.verticalRotation())
                && isSupportedFloat(canyon.shape().distanceFactor())
                && isSupportedFloat(canyon.shape().thickness())
                && isSupportedFloat(canyon.shape().horizontalRadiusFactor())
                && isSupportedFloat(canyon.shape().yScale());
        }
        return false;
    }

    private static boolean isSupportedHeight(final HeightProvider hp) {
        return hp instanceof UniformHeight;
    }

    private static boolean isSupportedInt(final IntProvider ip) {
        return ip instanceof ConstantInt || ip instanceof UniformInt || ip instanceof VeryBiasedToBottomInt;
    }

    private static boolean isSupportedFloat(final FloatProvider fp) {
        return fp instanceof ConstantFloat || fp instanceof UniformFloat || fp instanceof TrapezoidFloat || fp instanceof ClampedNormalFloat;
    }

    private static void emitLcgHelpers(final StringBuilder sb) {
        sb.append("static inline ulong cm_lcg_init(const long seed) {\n");
        sb.append("    return ((ulong)seed ^ 25214903917UL) & 281474976710655UL;\n");
        sb.append("}\n\n");

        sb.append("static inline int cm_lcg_next(ulong* s, const int bits) {\n");
        sb.append("    ulong ns = ((*s) * 25214903917UL + 11UL) & 281474976710655UL;\n");
        sb.append("    *s = ns;\n");
        sb.append("    return (int)((long)ns >> (48 - bits));\n");
        sb.append("}\n\n");

        sb.append("static inline long cm_lcg_next_long(ulong* s) {\n");
        sb.append("    const int hi = cm_lcg_next(s, 32);\n");
        sb.append("    const int lo = cm_lcg_next(s, 32);\n");
        sb.append("    return ((long)hi << 32) + (long)lo;\n");
        sb.append("}\n\n");

        sb.append("static inline int cm_lcg_next_int(ulong* s, const int bound) {\n");
        sb.append("    if ((bound & (bound - 1)) == 0) {\n");
        sb.append("        return (int)(((long)bound * (long)cm_lcg_next(s, 31)) >> 31);\n");
        sb.append("    }\n");
        sb.append("    int bits, val;\n");
        sb.append("    do {\n");
        sb.append("        bits = cm_lcg_next(s, 31);\n");
        sb.append("        val = bits % bound;\n");
        sb.append("    } while (bits - val + (bound - 1) < 0);\n");
        sb.append("    return val;\n");
        sb.append("}\n\n");

        sb.append("static inline float cm_lcg_next_float(ulong* s) {\n");
        sb.append("    return (float)cm_lcg_next(s, 24) * 5.960464477539063e-8f;\n");
        sb.append("}\n\n");

        sb.append("static inline float cm_lcg_next_double_f(ulong* s) {\n");
        sb.append("    const uint hi = (uint)cm_lcg_next(s, 26);\n");
        sb.append("    const uint lo = (uint)cm_lcg_next(s, 27);\n");
        sb.append("    return (float)hi * 1.4901161193847656e-8f + (float)lo * 1.1102230246251565e-16f;\n");
        sb.append("}\n\n");

        sb.append("static inline float cm_clamped_normal(ulong* s, int* has_g, float* next_g, const float mean, const float dev, const float min_v, const float max_v) {\n");
        sb.append("    float g;\n");
        sb.append("    if (*has_g != 0) {\n");
        sb.append("        *has_g = 0;\n");
        sb.append("        g = *next_g;\n");
        sb.append("    } else {\n");
        sb.append("        float x, y, r2;\n");
        sb.append("        do {\n");
        sb.append("            x = 2.0f * cm_lcg_next_double_f(s) - 1.0f;\n");
        sb.append("            y = 2.0f * cm_lcg_next_double_f(s) - 1.0f;\n");
        sb.append("            r2 = x * x + y * y;\n");
        sb.append("        } while (r2 >= 1.0f || r2 == 0.0f);\n");
        sb.append("        const float mult = sqrt(-2.0f * log(r2) / r2);\n");
        sb.append("        *next_g = y * mult;\n");
        sb.append("        *has_g = 1;\n");
        sb.append("        g = x * mult;\n");
        sb.append("    }\n");
        sb.append("    return clamp(mean + g * dev, min_v, max_v);\n");
        sb.append("}\n\n");

        sb.append("static inline float cm_trapezoid(ulong* s, const float min_v, const float max_v, const float plateau) {\n");
        sb.append("    const float range = max_v - min_v;\n");
        sb.append("    const float p_start = (range - plateau) * 0.5f;\n");
        sb.append("    const float p_end = range - p_start;\n");
        sb.append("    const float r1 = cm_lcg_next_float(s);\n");
        sb.append("    const float r2 = cm_lcg_next_float(s);\n");
        sb.append("    return min_v + r1 * p_end + r2 * p_start;\n");
        sb.append("}\n\n");

        sb.append("static inline int cm_very_biased(ulong* s, const int min_v, const int max_v) {\n");
        sb.append("    const int b1 = cm_lcg_next_int(s, max_v - min_v + 1);\n");
        sb.append("    const int b2 = cm_lcg_next_int(s, b1 + 1);\n");
        sb.append("    const int b3 = cm_lcg_next_int(s, b2 + 1);\n");
        sb.append("    return min_v + b3;\n");
        sb.append("}\n\n");
    }

    private static void emitEllipsoidAndTunnelHelpers(
        final StringBuilder sb,
        final int minGenY,
        final int genDepth
    ) {
        sb.append("static inline int cm_can_reach(const float rx, const float rz, const int cur_step, const int total_steps, const float thickness) {\n");
        sb.append("    const float xd = rx - 8.0f;\n");
        sb.append("    const float zd = rz - 8.0f;\n");
        sb.append("    const float rem = (float)(total_steps - cur_step);\n");
        sb.append("    const float rr = thickness + 18.0f;\n");
        sb.append("    return (xd * xd + zd * zd - rem * rem <= rr * rr) ? 1 : 0;\n");
        sb.append("}\n\n");

        // Cave ellipsoid: coordinates rx, rz are relative to target chunk's minBlockX / minBlockZ.
        sb.append("static inline int cm_carve_cave_ellipsoid(\n");
        sb.append("    __global uint* g_mask,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int min_carve_y,\n");
        sb.append("    const int max_carve_y,\n");
        sb.append("    const float rx, const float y, const float rz,\n");
        sb.append("    const float h_rad, const float v_rad,\n");
        sb.append("    const float floor_level\n");
        sb.append(") {\n");
        sb.append("    const float max_delta = 16.0f + h_rad * 2.0f;\n");
        sb.append("    if (fabs(rx - 8.0f) > max_delta || fabs(rz - 8.0f) > max_delta) return 0;\n");
        sb.append("    const int min_xi = max((int)floor(rx - h_rad) - 1, 0);\n");
        sb.append("    const int max_xi = min((int)floor(rx + h_rad), 15);\n");
        sb.append("    const int min_y = max((int)floor(y - v_rad) - 1, min_carve_y);\n");
        sb.append("    const int max_y = min((int)floor(y + v_rad) + 1, max_carve_y);\n");
        sb.append("    const int min_zi = max((int)floor(rz - h_rad) - 1, 0);\n");
        sb.append("    const int max_zi = min((int)floor(rz + h_rad), 15);\n");
        sb.append("    if (min_xi > max_xi || min_zi > max_zi || max_y <= min_y) return 0;\n");
        sb.append("    const float inv_h = 1.0f / h_rad;\n");
        sb.append("    const float inv_v = 1.0f / v_rad;\n");
        sb.append("    int any = 0;\n");
        sb.append("    for (int xi = min_xi; xi <= max_xi; xi++) {\n");
        sb.append("        const float xd = ((float)xi + 0.5f - rx) * inv_h;\n");
        sb.append("        const float xd2 = xd * xd;\n");
        sb.append("        if (xd2 >= 1.0f) continue;\n");
        sb.append("        for (int zi = min_zi; zi <= max_zi; zi++) {\n");
        sb.append("            const float zd = ((float)zi + 0.5f - rz) * inv_h;\n");
        sb.append("            const float h2 = xd2 + zd * zd;\n");
        sb.append("            if (h2 >= 1.0f) continue;\n");
        sb.append("            const int col = (zi << 4) | xi;\n");
        sb.append("            for (int wy = max_y; wy > min_y; wy--) {\n");
        sb.append("                const float yd = ((float)wy - 0.5f - y) * inv_v;\n");
        sb.append("                if (yd > floor_level && (h2 + yd * yd) < 1.0f) {\n");
        sb.append("                    const int ry = wy - min_block_y;\n");
        sb.append("                    atomic_or(&g_mask[col * words_per_col + (ry >> 5)], (1u << (ry & 31)));\n");
        sb.append("                    any = 1;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return any;\n");
        sb.append("}\n\n");

        // Cave tunnel with explicit 2-slot stack (vanilla createTunnel has max recursion depth 1).
        sb.append("static int cm_create_cave_tunnel(\n");
        sb.append("    __global uint* g_mask,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int min_carve_y,\n");
        sb.append("    const int max_carve_y,\n");
        sb.append("    const long init_seed,\n");
        sb.append("    const float init_rx, const float init_y, const float init_rz,\n");
        sb.append("    const float h_mult, const float v_mult,\n");
        sb.append("    const float init_thick,\n");
        sb.append("    const float init_hrot, const float init_vrot,\n");
        sb.append("    const int dist,\n");
        sb.append("    const float init_yscale,\n");
        sb.append("    const float floor_level\n");
        sb.append(") {\n");
        sb.append("    long st_seed[2];\n");
        sb.append("    float st_rx[2], st_y[2], st_rz[2], st_thick[2], st_hrot[2], st_vrot[2], st_yscale[2];\n");
        sb.append("    int st_step[2];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    st_seed[0] = init_seed; st_rx[0] = init_rx; st_y[0] = init_y; st_rz[0] = init_rz;\n");
        sb.append("    st_thick[0] = init_thick; st_hrot[0] = init_hrot; st_vrot[0] = init_vrot;\n");
        sb.append("    st_step[0] = 0; st_yscale[0] = init_yscale;\n");
        sb.append("    sp = 1;\n");
        sb.append("    int any = 0;\n");
        sb.append("    while (sp > 0) {\n");
        sb.append("        sp--;\n");
        sb.append("        ulong rng = cm_lcg_init(st_seed[sp]);\n");
        sb.append("        float rx = st_rx[sp], y = st_y[sp], rz = st_rz[sp];\n");
        sb.append("        const float thick = st_thick[sp];\n");
        sb.append("        float hrot = st_hrot[sp], vrot = st_vrot[sp];\n");
        sb.append("        const int start_step = st_step[sp];\n");
        sb.append("        const float yscale = st_yscale[sp];\n");
        sb.append("        const int split_pt = cm_lcg_next_int(&rng, dist / 2) + dist / 4;\n");
        sb.append("        const int steep = (cm_lcg_next_int(&rng, 6) == 0) ? 1 : 0;\n");
        sb.append("        float y_rota = 0.0f;\n");
        sb.append("        float x_rota = 0.0f;\n");
        sb.append("        for (int cur = start_step; cur < dist; cur++) {\n");
        sb.append("            const float h_rad = 1.5f + sin(3.1415927f * (float)cur / (float)dist) * thick;\n");
        sb.append("            const float v_rad = h_rad * yscale;\n");
        sb.append("            const float cos_x = cos(vrot);\n");
        sb.append("            rx += cos(hrot) * cos_x;\n");
        sb.append("            y += sin(vrot);\n");
        sb.append("            rz += sin(hrot) * cos_x;\n");
        sb.append("            vrot *= (steep != 0) ? 0.92f : 0.7f;\n");
        sb.append("            vrot += x_rota * 0.1f;\n");
        sb.append("            hrot += y_rota * 0.1f;\n");
        sb.append("            x_rota *= 0.9f;\n");
        sb.append("            y_rota *= 0.75f;\n");
        sb.append("            const float xr1 = cm_lcg_next_float(&rng);\n");
        sb.append("            const float xr2 = cm_lcg_next_float(&rng);\n");
        sb.append("            const float xr3 = cm_lcg_next_float(&rng);\n");
        sb.append("            x_rota += (xr1 - xr2) * xr3 * 2.0f;\n");
        sb.append("            const float yr1 = cm_lcg_next_float(&rng);\n");
        sb.append("            const float yr2 = cm_lcg_next_float(&rng);\n");
        sb.append("            const float yr3 = cm_lcg_next_float(&rng);\n");
        sb.append("            y_rota += (yr1 - yr2) * yr3 * 4.0f;\n");
        sb.append("            if (cur == split_pt && thick > 1.0f) {\n");
        sb.append("                const long s_a = cm_lcg_next_long(&rng);\n");
        sb.append("                const float th_a = cm_lcg_next_float(&rng) * 0.5f + 0.5f;\n");
        sb.append("                const long s_b = cm_lcg_next_long(&rng);\n");
        sb.append("                const float th_b = cm_lcg_next_float(&rng) * 0.5f + 0.5f;\n");
        sb.append("                st_seed[0] = s_b; st_rx[0] = rx; st_y[0] = y; st_rz[0] = rz;\n");
        sb.append("                st_thick[0] = th_b; st_hrot[0] = hrot + 1.5707964f; st_vrot[0] = vrot / 3.0f;\n");
        sb.append("                st_step[0] = cur; st_yscale[0] = 1.0f;\n");
        sb.append("                st_seed[1] = s_a; st_rx[1] = rx; st_y[1] = y; st_rz[1] = rz;\n");
        sb.append("                st_thick[1] = th_a; st_hrot[1] = hrot - 1.5707964f; st_vrot[1] = vrot / 3.0f;\n");
        sb.append("                st_step[1] = cur; st_yscale[1] = 1.0f;\n");
        sb.append("                sp = 2;\n");
        sb.append("                break;\n");
        sb.append("            }\n");
        sb.append("            if (cm_lcg_next_int(&rng, 4) != 0) {\n");
        sb.append("                if (!cm_can_reach(rx, rz, cur, dist, thick)) break;\n");
        sb.append("                any |= cm_carve_cave_ellipsoid(\n");
        sb.append("                    g_mask, words_per_col, min_block_y, min_carve_y, max_carve_y,\n");
        sb.append("                    rx, y, rz, h_rad * h_mult, v_rad * v_mult, floor_level\n");
        sb.append("                );\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return any;\n");
        sb.append("}\n\n");

        // Canyon ellipsoid helper
        sb.append("static inline int cm_carve_canyon_ellipsoid(\n");
        sb.append("    __global uint* g_mask,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int min_carve_y,\n");
        sb.append("    const int max_carve_y,\n");
        sb.append("    const float rx, const float y, const float rz,\n");
        sb.append("    const float h_rad, const float v_rad,\n");
        sb.append("    __local const float* width_factors\n");
        sb.append(") {\n");
        sb.append("    const float max_delta = 16.0f + h_rad * 2.0f;\n");
        sb.append("    if (fabs(rx - 8.0f) > max_delta || fabs(rz - 8.0f) > max_delta) return 0;\n");
        sb.append("    const int min_xi = max((int)floor(rx - h_rad) - 1, 0);\n");
        sb.append("    const int max_xi = min((int)floor(rx + h_rad), 15);\n");
        sb.append("    const int min_y = max((int)floor(y - v_rad) - 1, min_carve_y);\n");
        sb.append("    const int max_y = min((int)floor(y + v_rad) + 1, max_carve_y);\n");
        sb.append("    const int min_zi = max((int)floor(rz - h_rad) - 1, 0);\n");
        sb.append("    const int max_zi = min((int)floor(rz + h_rad), 15);\n");
        sb.append("    if (min_xi > max_xi || min_zi > max_zi || max_y <= min_y) return 0;\n");
        sb.append("    const float inv_h = 1.0f / h_rad;\n");
        sb.append("    const float inv_v = 1.0f / v_rad;\n");
        sb.append("    int any = 0;\n");
        sb.append("    for (int xi = min_xi; xi <= max_xi; xi++) {\n");
        sb.append("        const float xd = ((float)xi + 0.5f - rx) * inv_h;\n");
        sb.append("        const float xd2 = xd * xd;\n");
        sb.append("        if (xd2 >= 1.0f) continue;\n");
        sb.append("        for (int zi = min_zi; zi <= max_zi; zi++) {\n");
        sb.append("            const float zd = ((float)zi + 0.5f - rz) * inv_h;\n");
        sb.append("            const float h2 = xd2 + zd * zd;\n");
        sb.append("            if (h2 >= 1.0f) continue;\n");
        sb.append("            const int col = (zi << 4) | xi;\n");
        sb.append("            for (int wy = max_y; wy > min_y; wy--) {\n");
        sb.append("                const float yd = ((float)wy - 0.5f - y) * inv_v;\n");
        sb.append("                const int y_idx = wy - ").append(minGenY).append(";\n");
        sb.append("                if (y_idx >= 1 && y_idx <= ").append(genDepth).append(") {\n");
        sb.append("                    if (h2 * width_factors[y_idx - 1] + (yd * yd) * (1.0f / 6.0f) < 1.0f) {\n");
        sb.append("                        const int ry = wy - min_block_y;\n");
        sb.append("                        atomic_or(&g_mask[col * words_per_col + (ry >> 5)], (1u << (ry & 31)));\n");
        sb.append("                        any = 1;\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    return any;\n");
        sb.append("}\n\n");
    }

    private static void emitKernel(
        final StringBuilder sb,
        final List<WorldCarver> uniformCarvers,
        final long worldSeed,
        final WorldGenerationContext context,
        final int minGenY,
        final int genDepth
    ) {
        final int carverCount = uniformCarvers.size();
        final long[] xScales = new long[carverCount];
        final long[] zScales = new long[carverCount];
        final long[] baseSeeds = new long[carverCount];
        final WorldgenRandom tmpRand = new WorldgenRandom(new LegacyRandomSource(0L));
        for (int i = 0; i < carverCount; i++) {
            final long bs = worldSeed + i;
            baseSeeds[i] = bs;
            tmpRand.setSeed(bs);
            xScales[i] = tmpRand.nextLong();
            zScales[i] = tmpRand.nextLong();
        }

        sb.append("__constant long CM_BASE_SEED[").append(carverCount).append("] = {");
        for (int i = 0; i < carverCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append(baseSeeds[i]).append("L");
        }
        sb.append("};\n");
        sb.append("__constant long CM_X_SCALE[").append(carverCount).append("] = {");
        for (int i = 0; i < carverCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append(xScales[i]).append("L");
        }
        sb.append("};\n");
        sb.append("__constant long CM_Z_SCALE[").append(carverCount).append("] = {");
        for (int i = 0; i < carverCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append(zScales[i]).append("L");
        }
        sb.append("};\n\n");

        sb.append("__kernel void ").append(KERNEL_NAME).append("(\n");
        sb.append("    __global const int4* batch_origins,\n");
        sb.append("    __global int* carver_meta,\n");
        sb.append("    __global uint* out_carver_masks,\n");
        sb.append("    const int batch_count,\n");
        sb.append("    const int words_per_col,\n");
        sb.append("    const int min_block_y,\n");
        sb.append("    const int min_carve_y,\n");
        sb.append("    const int max_carve_y,\n");
        sb.append("    __global const int* restrict gl_lut_spill\n");
        sb.append(") {\n");
        sb.append("    const int wg_id = (int)get_group_id(0);\n");
        sb.append("    const int batch_idx = wg_id >> 2;\n");
        sb.append("    const int sub = wg_id & 3;\n");
        sb.append("    const int tid = (int)get_local_id(0);\n");
        sb.append("    if (batch_idx >= batch_count) return;\n");
        sb.append("    if (carver_meta[batch_idx] == -2) return;\n");
        sb.append("    __global uint* g_mask = out_carver_masks + (batch_idx * 256 * words_per_col);\n");
        sb.append("    __local int l_canyon_cnt;\n");
        sb.append("    __local int l_canyon_cidx[32];\n");
        sb.append("    __local int l_canyon_dx[32];\n");
        sb.append("    __local int l_canyon_dz[32];\n");
        sb.append("    __local ulong l_canyon_rng[32];\n");
        sb.append("    __local float l_canyon_wf[").append(genDepth).append("];\n");
        sb.append("    if (tid == 0) { l_canyon_cnt = 0; }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n\n");

        sb.append("    const int4 orig = batch_origins[batch_idx];\n");
        sb.append("    const int chunk_x = orig.x >> 4;\n");
        sb.append("    const int chunk_z = orig.z >> 4;\n");
        sb.append("    int my_any = 0;\n");
        sb.append("    const int total_tasks = ").append(carverCount * 289).append(";\n");
        sb.append("    for (int task = (sub << 6) + tid; task < total_tasks; task += 256) {\n");
        sb.append("        const int c_idx = task / 289;\n");
        sb.append("        const int cell = task - c_idx * 289;\n");
        sb.append("        const int dx = (cell / 17) - 8;\n");
        sb.append("        const int dz = (cell % 17) - 8;\n");
        sb.append("        const int sx = chunk_x + dx;\n");
        sb.append("        const int sz = chunk_z + dz;\n");
        sb.append("        const long lfs = ((long)sx * CM_X_SCALE[c_idx]) ^ ((long)sz * CM_Z_SCALE[c_idx]) ^ CM_BASE_SEED[c_idx];\n");
        sb.append("        ulong rng = cm_lcg_init(lfs);\n");
        sb.append("        int rng_has_g = 0; float rng_next_g = 0.0f;\n");
        sb.append("        const float base_rx = (float)(dx * 16);\n");
        sb.append("        const float base_rz = (float)(dz * 16);\n");

        for (int i = 0; i < carverCount; i++) {
            final WorldCarver carver = uniformCarvers.get(i);
            sb.append(i == 0 ? "        if (c_idx == 0) {\n" : "        else if (c_idx == " + i + ") {\n");
            if (carver instanceof CaveWorldCarver cave) {
                emitCaveCarverBranch(sb, cave, context);
            } else if (carver instanceof CanyonWorldCarver canyon) {
                sb.append("            if (cm_lcg_next_float(&rng) <= ").append(fmtFloat(canyon.probability())).append(") {\n");
                sb.append("                const int slot = atomic_inc(&l_canyon_cnt);\n");
                sb.append("                if (slot < 32) {\n");
                sb.append("                    l_canyon_cidx[slot] = c_idx;\n");
                sb.append("                    l_canyon_dx[slot] = dx;\n");
                sb.append("                    l_canyon_dz[slot] = dz;\n");
                sb.append("                    l_canyon_rng[slot] = rng;\n");
                sb.append("                }\n");
                sb.append("            }\n");
            }
            sb.append("        }\n");
        }
        sb.append("    }\n");
        sb.append("    barrier(CLK_LOCAL_MEM_FENCE);\n");
        sb.append("    if (tid == 0) {\n");
        sb.append("        const int c_total = min(l_canyon_cnt, 32);\n");
        sb.append("        for (int ci = 0; ci < c_total; ci++) {\n");
        sb.append("            const int c_idx = l_canyon_cidx[ci];\n");
        sb.append("            const float base_rx = (float)(l_canyon_dx[ci] * 16);\n");
        sb.append("            const float base_rz = (float)(l_canyon_dz[ci] * 16);\n");
        sb.append("            ulong rng = l_canyon_rng[ci];\n");
        sb.append("            int rng_has_g = 0; float rng_next_g = 0.0f;\n");
        boolean firstCanyon = true;
        for (int i = 0; i < carverCount; i++) {
            final WorldCarver carver = uniformCarvers.get(i);
            if (carver instanceof CanyonWorldCarver canyon) {
                sb.append(firstCanyon ? "            if (c_idx == " + i + ") {\n" : "            else if (c_idx == " + i + ") {\n");
                firstCanyon = false;
                emitCanyonCarverBody(sb, canyon, context, genDepth);
                sb.append("            }\n");
            }
        }
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("    if (my_any != 0) {\n");
        sb.append("        carver_meta[batch_idx] = batch_idx;\n");
        sb.append("    }\n");
        sb.append("}\n");
    }

    private static void emitCaveCarverBranch(
        final StringBuilder sb,
        final CaveWorldCarver cave,
        final WorldGenerationContext context
    ) {
        emitCaveCarverBranchWithStartFloat(sb, cave, context, "cm_lcg_next_float(&rng)");
    }

    private static void emitCaveCarverBranchWithStartFloat(
        final StringBuilder sb,
        final CaveWorldCarver cave,
        final WorldGenerationContext context,
        final String startFloatExpr
    ) {
        final int maxDist = (cave.getRange() * 2 - 1) * 16;
        sb.append("            if (").append(startFloatExpr).append(" <= ").append(fmtFloat(cave.probability())).append(") {\n");
        sb.append("                const int cave_count = ").append(emitIntProviderExpr(cave.count())).append(";\n");
        sb.append("                for (int cave = 0; cave < cave_count; cave++) {\n");
        sb.append("                    const float rx = base_rx + (float)cm_lcg_next_int(&rng, 16);\n");
        sb.append("                    const float y = (float)(").append(emitHeightProviderExpr(cave.y(), context)).append(");\n");
        sb.append("                    const float rz = base_rz + (float)cm_lcg_next_int(&rng, 16);\n");
        sb.append("                    const float h_mult = ").append(emitFloatProviderExpr(cave.horizontalRadiusMultiplier())).append(";\n");
        sb.append("                    const float v_mult = ").append(emitFloatProviderExpr(cave.verticalRadiusMultiplier())).append(";\n");
        sb.append("                    const float start_v_mult = ").append(emitFloatProviderExpr(cave.startVerticalRadiusMultiplier())).append(";\n");
        sb.append("                    const float floor_lvl = ").append(emitFloatProviderExpr(cave.floorLevel())).append(";\n");
        sb.append("                    int tunnels = 1;\n");
        sb.append("                    if (cm_lcg_next_int(&rng, 4) == 0) {\n");
        sb.append("                        const float r_yscale = ").append(emitFloatProviderExpr(cave.roomVerticalRadiusMultiplier())).append(";\n");
        sb.append("                        const float r_thick = 1.0f + cm_lcg_next_float(&rng) * 6.0f;\n");
        sb.append("                        const float r_hrad = 1.5f + r_thick;\n");
        sb.append("                        my_any |= cm_carve_cave_ellipsoid(\n");
        sb.append("                            g_mask, words_per_col, min_block_y, min_carve_y, max_carve_y,\n");
        sb.append("                            rx + 1.0f, y, rz, r_hrad, r_hrad * r_yscale, floor_lvl\n");
        sb.append("                        );\n");
        sb.append("                        tunnels += cm_lcg_next_int(&rng, 4);\n");
        sb.append("                    }\n");
        sb.append("                    for (int ti = 0; ti < tunnels; ti++) {\n");
        sb.append("                        const float hrot = cm_lcg_next_float(&rng) * 6.2831855f;\n");
        sb.append("                        const float vrot = (cm_lcg_next_float(&rng) - 0.5f) * 0.25f;\n");
        sb.append("                        float thick = ").append(emitFloatProviderExpr(cave.thickness())).append(";\n");
        if (cave.weirdThicknessBias()) {
            sb.append("                        if (cm_lcg_next_int(&rng, 10) == 0) {\n");
            sb.append("                            const float tb1 = cm_lcg_next_float(&rng);\n");
            sb.append("                            const float tb2 = cm_lcg_next_float(&rng);\n");
            sb.append("                            thick *= tb1 * tb2 * 3.0f + 1.0f;\n");
            sb.append("                        }\n");
        }
        sb.append("                        const int dist = ").append(maxDist).append(" - cm_lcg_next_int(&rng, ").append(maxDist / 4).append(");\n");
        sb.append("                        const long t_seed = cm_lcg_next_long(&rng);\n");
        sb.append("                        my_any |= cm_create_cave_tunnel(\n");
        sb.append("                            g_mask, words_per_col, min_block_y, min_carve_y, max_carve_y,\n");
        sb.append("                            t_seed, rx, y, rz, h_mult, v_mult, thick, hrot, vrot, dist, start_v_mult, floor_lvl\n");
        sb.append("                        );\n");
        sb.append("                    }\n");
        sb.append("                }\n");
        sb.append("            }\n");
    }

    private static void emitCanyonCarverBody(
        final StringBuilder sb,
        final CanyonWorldCarver canyon,
        final WorldGenerationContext context,
        final int genDepth
    ) {
        final int maxDist = (canyon.getRange() * 2 - 1) * 16;
        final CanyonWorldCarver.Shape shape = canyon.shape();
        sb.append("                float rx = base_rx + (float)cm_lcg_next_int(&rng, 16);\n");
        sb.append("                float y = (float)(").append(emitHeightProviderExpr(canyon.y(), context)).append(");\n");
        sb.append("                float rz = base_rz + (float)cm_lcg_next_int(&rng, 16);\n");
        sb.append("                float hrot = cm_lcg_next_float(&rng) * 6.2831855f;\n");
        sb.append("                float vrot = ").append(emitFloatProviderExpr(canyon.verticalRotation())).append(";\n");
        sb.append("                const float yscale = ").append(emitFloatProviderExpr(shape.yScale())).append(";\n");
        sb.append("                const float thick = ").append(emitFloatProviderExpr(shape.thickness())).append(";\n");
        sb.append("                const int dist = (int)(").append((float) maxDist).append("f * (")
            .append(emitFloatProviderExpr(shape.distanceFactor())).append("));\n");
        sb.append("                const long t_seed = cm_lcg_next_long(&rng);\n");
        sb.append("                ulong crng = cm_lcg_init(t_seed);\n");
        sb.append("                int crng_has_g = 0; float crng_next_g = 0.0f;\n");
        sb.append("                float cur_wf = 1.0f;\n");
        sb.append("                for (int yi = 0; yi < ").append(genDepth).append("; yi++) {\n");
        sb.append("                    if (yi == 0 || cm_lcg_next_int(&crng, ").append(shape.widthSmoothness()).append(") == 0) {\n");
        sb.append("                        const float w1 = cm_lcg_next_float(&crng);\n");
        sb.append("                        const float w2 = cm_lcg_next_float(&crng);\n");
        sb.append("                        cur_wf = 1.0f + w1 * w2;\n");
        sb.append("                    }\n");
        sb.append("                    l_canyon_wf[yi] = cur_wf * cur_wf;\n");
        sb.append("                }\n");
        sb.append("                float y_rota = 0.0f;\n");
        sb.append("                float x_rota = 0.0f;\n");
        sb.append("                for (int cur = 0; cur < dist; cur++) {\n");
        sb.append("                    float h_rad = 1.5f + sin((float)cur * 3.1415927f / (float)dist) * thick;\n");
        sb.append("                    float v_rad = h_rad * yscale;\n");
        sb.append("                    h_rad *= ").append(emitFloatProviderExpr(shape.horizontalRadiusFactor(), "&crng")).append(";\n");
        sb.append("                    const float v_mult = 1.0f - fabs(0.5f - (float)cur / (float)dist) * 2.0f;\n");
        sb.append("                    const float v_fac = ").append(fmtFloat(shape.verticalRadiusDefaultFactor()))
            .append(" + ").append(fmtFloat(shape.verticalRadiusCenterFactor())).append(" * v_mult;\n");
        sb.append("                    v_rad = v_fac * v_rad * (0.75f + cm_lcg_next_float(&crng) * 0.25f);\n");
        sb.append("                    const float xc = cos(vrot);\n");
        sb.append("                    const float xs = sin(vrot);\n");
        sb.append("                    rx += cos(hrot) * xc;\n");
        sb.append("                    y += xs;\n");
        sb.append("                    rz += sin(hrot) * xc;\n");
        sb.append("                    vrot *= 0.7f;\n");
        sb.append("                    vrot += x_rota * 0.05f;\n");
        sb.append("                    hrot += y_rota * 0.05f;\n");
        sb.append("                    x_rota *= 0.8f;\n");
        sb.append("                    y_rota *= 0.5f;\n");
        sb.append("                    const float xr1 = cm_lcg_next_float(&crng);\n");
        sb.append("                    const float xr2 = cm_lcg_next_float(&crng);\n");
        sb.append("                    const float xr3 = cm_lcg_next_float(&crng);\n");
        sb.append("                    x_rota += (xr1 - xr2) * xr3 * 2.0f;\n");
        sb.append("                    const float yr1 = cm_lcg_next_float(&crng);\n");
        sb.append("                    const float yr2 = cm_lcg_next_float(&crng);\n");
        sb.append("                    const float yr3 = cm_lcg_next_float(&crng);\n");
        sb.append("                    y_rota += (yr1 - yr2) * yr3 * 4.0f;\n");
        sb.append("                    if (cm_lcg_next_int(&crng, 4) != 0) {\n");
        sb.append("                        if (!cm_can_reach(rx, rz, cur, dist, thick)) break;\n");
        sb.append("                        my_any |= cm_carve_canyon_ellipsoid(\n");
        sb.append("                            g_mask, words_per_col, min_block_y, min_carve_y, max_carve_y,\n");
        sb.append("                            rx, y, rz, h_rad, v_rad, l_canyon_wf\n");
        sb.append("                        );\n");
        sb.append("                    }\n");
        sb.append("                }\n");
    }

    private static String emitHeightProviderExpr(final HeightProvider hp, final WorldGenerationContext ctx) {
        if (hp instanceof UniformHeight uh) {
            final int lo = uh.goldleaf$minInclusive().resolveY(ctx);
            final int hi = uh.goldleaf$maxInclusive().resolveY(ctx);
            if (hi <= lo) {
                return Integer.toString(lo);
            }
            return "(" + lo + " + cm_lcg_next_int(&rng, " + (hi - lo + 1) + "))";
        }
        throw new IllegalArgumentException("Unsupported HeightProvider: " + hp);
    }

    private static String emitIntProviderExpr(final IntProvider ip) {
        if (ip instanceof ConstantInt ci) {
            return Integer.toString(ci.value());
        }
        if (ip instanceof UniformInt ui) {
            final int lo = ui.minInclusive();
            final int hi = ui.maxInclusive();
            return "(" + lo + " + cm_lcg_next_int(&rng, " + (hi - lo + 1) + "))";
        }
        if (ip instanceof VeryBiasedToBottomInt vb) {
            return "cm_very_biased(&rng, " + vb.minInclusive() + ", " + vb.maxInclusive() + ")";
        }
        throw new IllegalArgumentException("Unsupported IntProvider: " + ip);
    }

    private static String emitFloatProviderExpr(final FloatProvider fp) {
        return emitFloatProviderExpr(fp, "&rng");
    }

    private static String emitFloatProviderExpr(final FloatProvider fp, final String rngPtr) {
        if (fp instanceof ConstantFloat cf) {
            return fmtFloat(cf.value());
        }
        if (fp instanceof UniformFloat uf) {
            final float lo = uf.min();
            final float span = uf.max() - uf.min();
            return "(" + fmtFloat(lo) + " + cm_lcg_next_float(" + rngPtr + ") * " + fmtFloat(span) + ")";
        }
        if (fp instanceof TrapezoidFloat tf) {
            return "cm_trapezoid(" + rngPtr + ", " + fmtFloat(tf.min()) + ", " + fmtFloat(tf.max()) + ", " + fmtFloat(tf.plateau()) + ")";
        }
        if (fp instanceof ClampedNormalFloat cnf) {
            final String pfx = "&crng".equals(rngPtr) ? "crng" : "rng";
            return "cm_clamped_normal(" + rngPtr + ", &" + pfx + "_has_g, &" + pfx + "_next_g, "
                + fmtFloat(cnf.mean()) + ", " + fmtFloat(cnf.deviation()) + ", "
                + fmtFloat(cnf.min()) + ", " + fmtFloat(cnf.max()) + ")";
        }
        throw new IllegalArgumentException("Unsupported FloatProvider: " + fp);
    }

    private static String fmtFloat(final float v) {
        return Float.toString(v) + "f";
    }

    private static String sha256(final String source) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(source.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
