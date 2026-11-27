package dev.leafcraft.goldleaf.worldgen;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import io.github.dailystruggle.bytecode.transpile.ConceptBackendEmitter;
import io.github.dailystruggle.bytecode.transpile.OpenClBackendEmitter;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.DistanceMetric;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.TilingMode;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.EndIslandFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.FindTopSurfaceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.PowFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RoundFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.WeightedListHeight;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.HoleCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NoiseThresholdCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.SteepCondition;
import net.minecraft.world.level.levelgen.material.condition.StoneDepthCondition;
import net.minecraft.world.level.levelgen.material.condition.TemperatureCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.condition.WaterCondition;
import net.minecraft.world.level.levelgen.material.condition.YCondition;
import net.minecraft.world.level.levelgen.material.rule.BandlandsRule;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import java.nio.ByteBuffer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.AllOfRuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.HeightMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic datapack worldgen AST coverage, CPU-vs-GPU parity, cache provenance, and
 * fail-closed boundary tests for MC 26.3 ({@code goldleaf-ADR-004}, {@code goldleaf-ADR-017},
 * {@code goldleaf-ADR-024}).
 *
 * @governedBy goldleaf-ADR-004, goldleaf-ADR-017, goldleaf-ADR-024
 * @trace S-004, S-005
 */
class DatapackWorldgenParityTest {

    private static final Pattern FP64_TYPE_PATTERN = Pattern.compile("\\bdouble\\b");
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createWorldLookup();
    }

    /**
     * Synthetic unsupported {@link DensityFunction} verifying fail-closed {@code S-004} lowering.
     */
    private record SyntheticUnsupportedDensityFunction(float bias) implements DensityFunction {
        @Override
        public DensitySampler compileSampler(final CompileContext context) {
            return new DensitySampler() {
                @Override
                public void sampleVolume(final SamplerContext ctx, final DensityBuffer out, final DensityVolume vol) {
                    out.setRange(0, vol.size(), SyntheticUnsupportedDensityFunction.this.bias);
                }

                @Override
                public float sampleValue(final SamplerContext ctx, final int x, final int y, final int z) {
                    return SyntheticUnsupportedDensityFunction.this.bias;
                }
            };
        }

        @Override
        public Interval range() {
            return Interval.ofExact(this.bias);
        }

        @Override
        public int domainAxes() {
            return DensityFunction.ALL_AXES;
        }

        @Override
        public MapCodec<? extends DensityFunction> codec() {
            return ConstantFunction.CODEC;
        }

        @Override
        public DensityFunction rewriteChildren(final DfRewriteRule rule) {
            return this;
        }
    }

    /**
     * Synthetic unsupported {@link MaterialCondition} verifying fail-closed {@code S-004} surface compile.
     */
    private record SyntheticUnsupportedMaterialCondition() implements MaterialCondition {
        @Override
        public MapCodec<? extends MaterialCondition> codec() {
            return HoleCondition.CODEC;
        }

        @Override
        public ConditionEvaluator compile(final MaterialRuleContext context) {
            return ConditionEvaluator.ALWAYS_FALSE;
        }
    }

    private static Biome makeSyntheticBiome(final float temperature, final Biome.TemperatureModifier modifier) {
        return new Biome.BiomeBuilder()
            .hasPrecipitation(true)
            .temperature(temperature)
            .temperatureAdjustment(modifier)
            .downfall(0.4f)
            .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0x3F76E4).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY)
            .generationSettings(BiomeGenerationSettings.EMPTY)
            .build();
    }

    private static final java.util.concurrent.atomic.AtomicInteger SYNTHETIC_BIOME_SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    private static Holder.Reference<Biome> makeKeyedBiome(
        final String path,
        final float temperature,
        final Biome.TemperatureModifier modifier
    ) {
        final MappedRegistry<Biome> reg = new MappedRegistry<>(Registries.BIOME, Lifecycle.experimental());
        final String uniquePath = path + "_" + SYNTHETIC_BIOME_SEQ.incrementAndGet();
        final ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("datapack_test", uniquePath));
        final Holder.Reference<Biome> ref = reg.register(key, makeSyntheticBiome(temperature, modifier), RegistrationInfo.BUILT_IN);
        reg.freeze();
        return ref;
    }

    private static BlockState decodeGpuSectionVoxel(
        final CompactVanillaSurfaceWritebackBuffer buf,
        final int globalSection,
        final int lx,
        final int ly,
        final int lz,
        final BlockState[] palette
    ) {
        final int nonEmpty = buf.readNonEmptyCount(globalSection);
        if (nonEmpty == 0) {
            return palette[0];
        }
        final int mode = buf.readSectionMode(globalSection);
        if (mode == CompactVanillaSurfaceWritebackBuffer.MODE_UNIFORM) {
            final int code = buf.readUniformCode(globalSection);
            return code < palette.length ? palette[code] : palette[0];
        }
        final ByteBuffer voxels = buf.voxelsDirectBuffer();
        final int byteOffset = globalSection * CompactVanillaSurfaceWritebackBuffer.VOXEL_BYTES_PER_SECTION;
        final int voxelIdx = (ly << 8) | (lz << 4) | lx;
        if (mode == CompactVanillaSurfaceWritebackBuffer.MODE_PACKED_4BIT) {
            final long usedMask = voxels.getLong(byteOffset + 2048);
            final int rawFlatBits = Byte.toUnsignedInt(voxels.get(byteOffset + 2058));
            final int flatBits = (rawFlatBits >= 1 && rawFlatBits <= 4) ? rawFlatBits : 4;
            final BlockState[] localPalette = new BlockState[Long.bitCount(usedMask)];
            long m = usedMask;
            int k = 0;
            while (m != 0L) {
                final int code = Long.numberOfTrailingZeros(m);
                m &= m - 1L;
                localPalette[k++] = code < palette.length ? palette[code] : palette[0];
            }
            final int bitPos = voxelIdx * flatBits;
            final int bIdx = byteOffset + (bitPos >>> 3);
            final int shift = bitPos & 7;
            int raw = Byte.toUnsignedInt(voxels.get(bIdx)) >>> shift;
            if (shift + flatBits > 8) {
                raw |= Byte.toUnsignedInt(voxels.get(bIdx + 1)) << (8 - shift);
            }
            final int localIdx = raw & ((1 << flatBits) - 1);
            return localIdx < localPalette.length ? localPalette[localIdx] : palette[0];
        }
        final int code = Byte.toUnsignedInt(voxels.get(byteOffset + voxelIdx));
        return code < palette.length ? palette[code] : palette[0];
    }

    private static WorldGenerationContext makeWgContext(final int minY, final int height, final int seaLevel) {
        final Holder<Biome> dummy = Holder.direct(makeSyntheticBiome(0.8f, Biome.TemperatureModifier.NONE));
        return new WorldGenerationContext(
            new FlatLevelSource(new FlatLevelGeneratorSettings(Optional.empty(), dummy, List.of())),
            LevelHeightAccessor.create(minY, height)
        ) {
            @Override
            public int getMinGenY() {
                return minY;
            }

            @Override
            public int getGenDepth() {
                return height;
            }

            @Override
            public int seaLevel() {
                return seaLevel;
            }
        };
    }

    private static void assertZeroFp64(final String source, final String label) {
        assertFalse(source.contains("cl_khr_fp64"), label + " must not enable cl_khr_fp64");
        assertFalse(FP64_TYPE_PATTERN.matcher(source).find(), label + " must not contain FP64 double declarations");
    }

    @Test
    void terralithStyleExtendedHeightAstBiomesAndSurfaceParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final Holder<NormalNoise> continentalNoise = noiseGetter.getOrThrow(Noises.CONTINENTALNESS);
        final Holder<NormalNoise> erosionNoise = noiseGetter.getOrThrow(Noises.EROSION);
        final Holder<NormalNoise> shiftNoise = noiseGetter.getOrThrow(Noises.SHIFT);

        // Build a Terralith/Tectonic-style DensityFunction DAG exercising all 21 MC 26.3 AST node types
        final DensityFunction shiftAll = new ShiftNoiseFunction.Shift(shiftNoise);
        final DensityFunction shiftA = new ShiftNoiseFunction.ShiftA(shiftNoise);
        final DensityFunction shiftB = new ShiftNoiseFunction.ShiftB(shiftNoise);
        final DensityFunction baseNoise = new CacheFunction(
            new NoiseFunction(continentalNoise, 0.015625, 0.03125, shiftA, DensityFunctions.zero(), shiftB)
        );
        final DensityFunction detailNoise = new NoiseFunction(
            erosionNoise, 0.03125, 0.03125, shiftAll, DensityFunctions.zero(), DensityFunctions.zero()
        );
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 448, 1.5f, -1.5f);
        final DensityFunction distEuclid = new DistanceToPointFunction(new Vec3i(64, 80, -32), DistanceMetric.EUCLIDEAN);
        final DensityFunction distSq = new DistanceToPointFunction(new Vec3i(0, 64, 0), DistanceMetric.EUCLIDEAN_SQUARED);
        final DensityFunction distManhattan = new DistanceToPointFunction(new Vec3i(-48, 32, 96), DistanceMetric.MANHATTAN);
        final DensityFunction distChebyshev = new DistanceToPointFunction(new Vec3i(16, 128, 16), DistanceMetric.CHEBYSHEV);

        final DensityFunction distanceMod = distEuclid.mul(DensityFunctions.constant(0.002f))
            .add(distSq.clamp(0.0f, 10000.0f).mul(DensityFunctions.constant(0.00001f)))
            .add(distManhattan.mul(DensityFunctions.constant(0.001f)))
            .add(distChebyshev.mul(DensityFunctions.constant(0.0015f)))
            .clamp(0.0f, 1.25f);

        final CubicSpline<SplineFunction.Coordinate> spline = CubicSpline.builder(new SplineFunction.Coordinate(baseNoise))
            .addPoint(-1.0f, -0.5f, 0.25f)
            .addPoint(0.0f, 0.2f, 0.6f)
            .addPoint(1.0f, 0.9f, 0.1f)
            .build();
        final DensityFunction intervalSel = new IntervalSelectFunction(
            baseNoise,
            new FloatArrayList(new float[]{-0.3f, 0.4f}),
            List.of(
                new RangeChoiceFunction(detailNoise, -0.2f, 0.5f, baseNoise.squeeze(), baseNoise.abs().halfNegative()),
                new LerpFunction(
                    baseNoise.clamp(0.0f, 1.0f),
                    new SplineFunction(spline),
                    new PowFunction(detailNoise.abs().add(DensityFunctions.constant(0.1f)), DensityFunctions.constant(2.0f))
                ),
                new RoundFunction(RoundFunction.Type.FLOOR, detailNoise, DensityFunctions.constant(0.5f))
            )
        );

        final DensityFunction preliminaryColumn = yGrad.add(baseNoise.mul(DensityFunctions.constant(0.75f)));
        final DensityFunction topSurface = new FindTopSurfaceFunction(
            preliminaryColumn,
            DensityFunctions.constant(256.0f),
            -64,
            8
        );
        final DensityFunction slicedNoise = new SliceFunction(Direction.Axis.Y, 64, detailNoise);
        final DensityFunction surfaceBonus = topSurface.mul(DensityFunctions.constant(0.004f))
            .add(slicedNoise.mul(DensityFunctions.constant(0.15f)))
            .sub(distanceMod);
        final DensityFunction blended = new BlendedNoise(0.25, 0.125, 80.0, 160.0, 8.0);
        final DensityFunction rawFinal = new BlendDensityFunction(
            new ClampFunction(
                new BinaryFunction(
                    BinaryFunction.Type.ADD,
                    new UnaryFunction(UnaryFunction.Type.QUARTER_NEGATIVE, intervalSel.add(yGrad)),
                    surfaceBonus.add(blended.mul(DensityFunctions.constant(0.25f)))
                ),
                -2.0f,
                2.0f
            )
        );
        final DensityFunction finalDensity = new InterpolatedFunction(rawFinal, 4, 8).add(SimpleDensityFunction.BEARDIFIER);

        final NoiseRouter router = new NoiseRouter(
            baseNoise,
            detailNoise,
            baseNoise,
            detailNoise,
            yGrad,
            slicedNoise,
            topSurface,
            finalDensity
        );

        // Biomes covering TemperatureCondition edge cases: cold (<0.15), temperate mountain (0.25 -> crosses 0.15 above seaLevel+17),
        // warm (0.9), and TemperatureModifier.FROZEN
        final Holder.Reference<Biome> coldBiome = makeKeyedBiome("alpine_peak", 0.05f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> snowlineBiome = makeKeyedBiome("highland_ridge", 0.25f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> warmBiome = makeKeyedBiome("volcanic_mesa", 0.90f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> frozenModBiome = makeKeyedBiome("glacial_fjord", -0.20f, Biome.TemperatureModifier.FROZEN);

        // Full 10/10 MaterialCondition + 5/5 MaterialRule tree
        final MaterialRule surfaceRule = new SequenceRule(List.of(
            new ConditionRule(
                new VerticalGradientCondition(
                    Identifier.withDefaultNamespace("bedrock_floor"),
                    VerticalAnchor.aboveBottom(0),
                    VerticalAnchor.aboveBottom(5)
                ),
                new BlockRule(Blocks.BEDROCK.defaultBlockState())
            ),
            new ConditionRule(
                AbovePreliminarySurfaceCondition.INSTANCE,
                new SequenceRule(List.of(
                    new ConditionRule(
                        new StoneDepthCondition(0, false, 0, CaveSurface.FLOOR),
                        new SequenceRule(List.of(
                            new ConditionRule(
                                new WaterCondition(-1, 0, false),
                                new SequenceRule(List.of(
                                    new ConditionRule(
                                        TemperatureCondition.INSTANCE,
                                        new BlockRule(Blocks.SNOW_BLOCK.defaultBlockState())
                                    ),
                                    new ConditionRule(
                                        SteepCondition.INSTANCE,
                                        new BlockRule(Blocks.CALCITE.defaultBlockState())
                                    ),
                                    new ConditionRule(
                                        new BiomeCondition(HolderSet.direct(warmBiome)),
                                        BandlandsRule.INSTANCE
                                    ),
                                    new ConditionRule(
                                        new NotCondition(HoleCondition.INSTANCE),
                                        new ConditionRule(
                                            new NoiseThresholdCondition(Noises.SURFACE, -0.25, 0.35, false),
                                            new BlockRule(Blocks.GRASS_BLOCK.defaultBlockState())
                                        )
                                    ),
                                    new ConditionRule(
                                        new NoiseThresholdCondition(Noises.CALCITE, -0.2, 0.2, true),
                                        new BlockRule(Blocks.MOSS_BLOCK.defaultBlockState())
                                    )
                                ))
                            )
                        ))
                    ),
                    new ConditionRule(
                        new YCondition(VerticalAnchor.absolute(64), 0, false),
                        new ConditionRule(
                            new BiomeCondition(HolderSet.direct(coldBiome, snowlineBiome, frozenModBiome)),
                            new BlockRule(Blocks.PACKED_ICE.defaultBlockState())
                        )
                    )
                ))
            ),
            new OreVeinRule(
                Blocks.COPPER_ORE.defaultBlockState(),
                Blocks.RAW_COPPER_BLOCK.defaultBlockState(),
                Blocks.GRANITE.defaultBlockState(),
                0.02f,
                new RangeChoiceFunction(
                    new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, 0, 50, 0.0f, 50.0f),
                    0.0f,
                    50.0f,
                    DensityFunctions.constant(0.5f),
                    DensityFunctions.zero()
                ),
                DensityFunctions.constant(0.25f),
                DensityFunctions.constant(-0.1f)
            )
        ));

        final NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 512),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            router,
            Holder.direct(surfaceRule),
            List.of(),
            63,
            false,
            Optional.empty(),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState randomState = RandomState.create(
            noiseGetter,
            42424242L,
            false,
            settings.defaultBlock(),
            settings.seaLevel(),
            router
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        // 1. Assert FindTopSurfaceFunction & DistanceToPointFunction & full 21-node AST lower cleanly
        final DensityFunctionIrLowerer.LoweringResult ftsLower = DensityFunctionIrLowerer.lower(
            topSurface, compileCtx, "DatapackFindTopSurface", true
        );
        assertTrue(ftsLower.isSupported(), "FindTopSurfaceFunction must lower cleanly: " + ftsLower.unsupportedReason());

        final DensityFunctionIrLowerer.LoweringResult distLower = DensityFunctionIrLowerer.lower(
            distanceMod, compileCtx, "DatapackDistanceMetrics", true
        );
        assertTrue(distLower.isSupported(), "DistanceToPointFunction (4 metrics) must lower cleanly: " + distLower.unsupportedReason());

        final DensityFunctionIrLowerer.LoweringResult fullLower = DensityFunctionIrLowerer.lower(
            finalDensity, compileCtx, "TerralithFullAst", true
        );
        assertTrue(fullLower.isSupported(), "Full 21-node datapack AST must lower cleanly: " + fullLower.unsupportedReason());

        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        // 2. Assert 110 custom biomes in MultiNoiseBiomeSource R-tree compile cleanly
        final List<Pair<Climate.ParameterPoint, Holder<Biome>>> biomeEntries = new ArrayList<>(110);
        for (int i = 0; i < 110; i++) {
            final float t = -1.0f + (2.0f * (i % 11)) / 10.0f;
            final float h = -1.0f + (2.0f * (i / 11)) / 9.0f;
            final Holder<Biome> bh = makeKeyedBiome("custom_biome_" + i, -0.5f + (i % 20) * 0.1f, Biome.TemperatureModifier.NONE);
            biomeEntries.add(Pair.of(
                Climate.parameters(
                    Climate.Parameter.span(t - 0.1f, t + 0.1f),
                    Climate.Parameter.span(h - 0.1f, h + 0.1f),
                    Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f),
                    0.0f
                ),
                bh
            ));
        }
        final Climate.RTree<Holder<Biome>> customRTree = Climate.RTree.create(biomeEntries);
        final GpuBiomeRTree.FlattenedRTree flattenedRTree = GpuBiomeRTree.flatten(customRTree);
        assertNotNull(flattenedRTree, "110-biome datapack R-tree must flatten cleanly");
        assertEquals(110, flattenedRTree.biomePalette().length);

        // 3. Assert 10/10 MaterialCondition (including TemperatureCondition) + 5/5 MaterialRule compile cleanly
        final WorldGenerationContext wgCtx = makeWgContext(-64, 512, 63);
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfProg = GpuVanillaSurfaceCompiler.compile(
            settings, randomState, wgCtx, false, false, false, emitter, exactMode, "terralith_surface"
        );
        assertNotNull(surfProg, "Datapack MaterialRule with TemperatureCondition must compile cleanly on GPU");
        assertZeroFp64(surfProg.terrainProgram().sourceCode(), "terralith terrain kernel");
        assertZeroFp64(surfProg.surfaceProgram().sourceCode(), "terralith surface kernel");

        // 4. Live GPU vs Mojang CPU 1:1 parity on full 21-node DensityFunction AST + FindTopSurfaceFunction
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("terralith-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerDensityFunctionProgram(
                finalDensity, compileCtx, "TerralithFullAst", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT, true
            );
            assertNotNull(prog, "Terralith AST must emit OpenCL program");
            assertZeroFp64(prog.sourceCode(), "TerralithFullAst");

            if (!accel.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel(), "Terralith OpenCL kernel must compile cleanly on live GPU");
            assertTrue(compiled.gpuKernel() != null, "Supported datapack AST must compile to live GPU kernel (zero CPU fallback)");

            final int batchCount = 4;
            final int sizeX = 5;
            final int sizeY = 65; // Extended 512-block height: 512 / 8 + 1 = 65 coarse Y levels
            final int sizeZ = 5;
            final int stepX = 4;
            final int stepY = 8;
            final int stepZ = 4;
            final int volSize = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, -64, 0, 0,
                64, -64, -48, 0,
                -128, -64, 96, 0,
                1024, -64, -2048, 0
            };
            final float[] gpuOut = new float[batchCount * volSize];
            final IrOpenClTerrainAccelerator.DispatchStats dispStats = accel.executeDensityVolumeBatch(
                compiled, origins, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, gpuOut
            );
            assertTrue(dispStats.gpuKernelNanos() >= 0L, "Live GPU dispatch must record non-negative kernel duration");

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(finalDensity);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            int total = batchCount * volSize;
            int mismatchedSign = 0;
            for (int b = 0; b < batchCount; b++) {
                final DensityVolume vol = new DensityVolume(
                    sizeX, sizeY, sizeZ, origins[b * 4], origins[b * 4 + 1], origins[b * 4 + 2], stepX, stepY, stepZ
                );
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    final int base = b * volSize;
                    for (int i = 0; i < volSize; i++) {
                        final float cpuVal = cpuBuf.get(i);
                        final float gpuVal = gpuOut[base + i];
                        if ((cpuVal > 0.0f) != (gpuVal > 0.0f) && Math.abs(cpuVal - gpuVal) > 1e-3f) {
                            mismatchedSign++;
                        }
                    }
                }
            }
            final double diffPct = 100.0 * mismatchedSign / (double) total;
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU Terralith 21-node AST parity: %d/%d exact (%.4f%% diff, gpuKernel=%.3fms, readback=%.3fms, sourceCompiles=%d)%n",
                total - mismatchedSign, total, diffPct,
                dispStats.gpuKernelNanos() / 1_000_000.0, dispStats.readbackNanos() / 1_000_000.0,
                accel.kernelCache().sourceCompiles()
            );
            assertTrue(
                diffPct <= 0.05,
                String.format(Locale.ROOT, "Terralith 21-node AST density-boundary diff %.4f%% exceeded 0.05%%", diffPct)
            );
        }
    }

    @Test
    void endDatapackAstAndSurfaceParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction endIslands = new EndIslandFunction();
        final DensityFunction distOrigin = new DistanceToPointFunction(new Vec3i(0, 64, 0), DistanceMetric.EUCLIDEAN);
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, 0, 128, 1.0f, -1.0f);
        final DensityFunction endFinal = new InterpolatedFunction(
            endIslands.add(yGrad.mul(DensityFunctions.constant(0.5f)))
                .sub(distOrigin.mul(DensityFunctions.constant(0.0001f)).clamp(0.0f, 0.25f)),
            4,
            8
        );

        final NoiseRouter router = new NoiseRouter(
            DensityFunctions.zero(),
            DensityFunctions.zero(),
            DensityFunctions.zero(),
            endIslands,
            DensityFunctions.zero(),
            DensityFunctions.zero(),
            DensityFunctions.constant(64.0f),
            endFinal
        );
        final MaterialRule endRule = new SequenceRule(List.of(
            new ConditionRule(
                new StoneDepthCondition(0, false, 0, CaveSurface.FLOOR),
                new BlockRule(Blocks.PURPUR_BLOCK.defaultBlockState())
            ),
            new ConditionRule(
                new YCondition(VerticalAnchor.absolute(16), 0, false),
                new BlockRule(Blocks.END_STONE_BRICKS.defaultBlockState())
            ),
            new BlockRule(Blocks.OBSIDIAN.defaultBlockState())
        ));
        final NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
            NoiseSettings.create(0, 128),
            Blocks.END_STONE.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            router,
            Holder.direct(endRule),
            List.of(),
            0,
            true,
            Optional.empty(),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState randomState = RandomState.create(
            noiseGetter,
            987654321L,
            false,
            settings.defaultBlock(),
            settings.seaLevel(),
            router
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        // 1. EndIslandFunction must lower cleanly with zero FP64
        final DensityFunctionIrLowerer.LoweringResult islandLower = DensityFunctionIrLowerer.lower(
            endIslands, compileCtx, "DatapackEndIslands", true
        );
        assertTrue(islandLower.isSupported(), "EndIslandFunction must lower cleanly: " + islandLower.unsupportedReason());

        // 2. End surface compiler must preserve fixed header slots [0=AIR, 1=END_STONE, 2=defaultFluid(AIR), 3=LAVA]
        // so user BlockRule blocks (PURPUR_BLOCK, END_STONE_BRICKS, OBSIDIAN) start at index >= 4
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram surfProg = GpuVanillaSurfaceCompiler.compile(
            settings, randomState, makeWgContext(0, 128, 0), false, false, false, emitter, exactMode, "end_datapack_surf"
        );
        assertNotNull(surfProg, "End datapack MaterialRule must compile cleanly on GPU");
        assertZeroFp64(surfProg.terrainProgram().sourceCode(), "end terrain kernel");
        assertZeroFp64(surfProg.surfaceProgram().sourceCode(), "end surface kernel");
        final BlockState[] pal = surfProg.blockPalette();
        assertTrue(pal.length >= 7, "Expected 4 reserved header slots + 3 End datapack blocks, got " + pal.length);
        assertEquals(Blocks.AIR.defaultBlockState(), pal[0]);
        assertEquals(Blocks.END_STONE.defaultBlockState(), pal[1]);
        assertEquals(Blocks.AIR.defaultBlockState(), pal[2]);
        assertEquals(Blocks.LAVA.defaultBlockState(), pal[3]);
        assertFalse(pal[3].is(Blocks.OBSIDIAN) || pal[3].is(Blocks.PURPUR_BLOCK), "User BlockRule must never collide with fluid slot 3");

        // 3. Live GPU vs Mojang CPU 1:1 parity on EndIslandFunction across origin, outer ring, negative & far coordinates
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("end-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerDensityFunctionProgram(
                endIslands, compileCtx, "DatapackEndIslands", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT, true
            );
            assertNotNull(prog);
            assertZeroFp64(prog.sourceCode(), "DatapackEndIslands");

            if (!accel.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 4;
            final int sizeX = 5, sizeY = 1, sizeZ = 5;
            final int stepX = 8, stepY = 1, stepZ = 8;
            final int volSize = sizeX * sizeY * sizeZ;
            final int[] origins = {
                0, 0, 0, 0,             // inner void / main island boundary
                1280, 0, 1280, 0,       // outer End islands (> 64 chunks)
                -2048, 0, 1536, 0,      // negative coordinates
                100000, 0, -100000, 0   // far coordinates (Q32 precision check)
            };
            final float[] gpuOut = new float[batchCount * volSize];
            final IrOpenClTerrainAccelerator.DispatchStats dispStats = accel.executeDensityVolumeBatch(
                compiled, origins, batchCount, sizeX, sizeY, sizeZ, stepX, stepY, stepZ, gpuOut
            );
            assertTrue(dispStats.gpuKernelNanos() >= 0L, "Live GPU dispatch must record non-negative kernel duration");

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(endIslands);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            for (int b = 0; b < batchCount; b++) {
                final DensityVolume vol = new DensityVolume(
                    sizeX, sizeY, sizeZ, origins[b * 4], origins[b * 4 + 1], origins[b * 4 + 2], stepX, stepY, stepZ
                );
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    final int base = b * volSize;
                    for (int i = 0; i < volSize; i++) {
                        assertEquals(
                            cpuBuf.get(i),
                            gpuOut[base + i],
                            1e-3f,
                            "EndIslandFunction mismatch at batch=" + b + " idx=" + i
                        );
                    }
                }
            }
        }
    }

    @Test
    void paletteBoundaryScaleHardeningAndFailClosedChecks() {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final NoiseGeneratorSettings vanillaOverworld = registries
            .lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD)
            .value();
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        final WorldGenerationContext wgCtx = makeWgContext(-64, 384, 63);
        final DensityFunction simpleFinal = new InterpolatedFunction(
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 320, 1.0f, -1.0f),
            4,
            8
        );
        final NoiseRouter simpleRouter = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), simpleFinal
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 12345L, false, Blocks.STONE.defaultBlockState(), 63, simpleRouter
        );

        // 1. > 254 biomes referenced in BiomeCondition -> fail closed cleanly (zero AIOOBE)
        final List<Holder<Biome>> tooManyBiomes = new ArrayList<>(260);
        for (int i = 0; i < 260; i++) {
            tooManyBiomes.add(makeKeyedBiome("overflow_biome_" + i, 0.5f, Biome.TemperatureModifier.NONE));
        }
        final NoiseGeneratorSettings biomeOverflowSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            simpleRouter,
            Holder.direct(new ConditionRule(new BiomeCondition(HolderSet.direct(tooManyBiomes)), new BlockRule(Blocks.DIRT.defaultBlockState()))),
            List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        assertNull(
            GpuVanillaSurfaceCompiler.compile(biomeOverflowSettings, randomState, wgCtx, false, false, false, emitter, exactMode, "overflow_biomes"),
            "> 254 biomes in MaterialRule must fail closed cleanly"
        );

        // 2. > 255 distinct BlockStates in MaterialRule -> fail closed cleanly (zero AIOOBE)
        final List<MaterialRule> blockRules = new ArrayList<>(265);
        int addedBlocks = 0;
        for (final Block block : BuiltInRegistries.BLOCK) {
            blockRules.add(new BlockRule(block.defaultBlockState()));
            if (++addedBlocks >= 265) {
                break;
            }
        }
        final NoiseGeneratorSettings blockOverflowSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            simpleRouter,
            Holder.direct(new SequenceRule(blockRules)),
            List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        assertNull(
            GpuVanillaSurfaceCompiler.compile(blockOverflowSettings, randomState, wgCtx, false, false, false, emitter, exactMode, "overflow_blocks"),
            "> 255 distinct surface blocks must fail closed cleanly"
        );

        // 3. 80 surface blocks (65..255 range) with aquifers enabled -> BOTH surface AND aquifer compiler must succeed
        final List<MaterialRule> eightyBlockRules = blockRules.subList(0, 80);
        final NoiseGeneratorSettings eightyBlockSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            simpleRouter,
            Holder.direct(new SequenceRule(eightyBlockRules)),
            List.of(), 63, false, vanillaOverworld.aquifers(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram eightySurf = GpuVanillaSurfaceCompiler.compile(
            eightyBlockSettings, randomState, wgCtx, false, false, false, emitter, exactMode, "eighty_blocks_surf"
        );
        assertNotNull(eightySurf, "80-block datapack MaterialRule must compile cleanly on GPU");
        assertTrue(eightySurf.blockPalette().length > 64 && eightySurf.blockPalette().length <= 255);
        final OpenClBackendEmitter.DensityVolumeProgram eightyAquifer = GpuCarverAquiferCompiler.compile(
            eightyBlockSettings, randomState, eightySurf.blockPalette(), emitter, exactMode, "eighty_blocks_aq"
        );
        assertNotNull(eightyAquifer, "GpuCarverAquiferCompiler must support 65..255 surface block palettes without dropping aquifers");

        // 4. > 256 biomes in MultiNoiseBiomeSource -> GpuBiomeRTree.flatten returns null cleanly
        final List<Pair<Climate.ParameterPoint, Holder<Biome>>> over256 = new ArrayList<>(260);
        for (int i = 0; i < 260; i++) {
            final float t = -1.0f + (2.0f * i) / 260.0f;
            over256.add(Pair.of(
                Climate.parameters(
                    Climate.Parameter.point(t), Climate.Parameter.point(0.0f), Climate.Parameter.point(0.0f),
                    Climate.Parameter.point(0.0f), Climate.Parameter.point(0.0f), Climate.Parameter.point(0.0f), 0.0f
                ),
                tooManyBiomes.get(i)
            ));
        }
        assertNull(GpuBiomeRTree.flatten(Climate.RTree.create(over256)), "> 256 biomes must fail closed cleanly in GpuBiomeRTree");

        // 5. Non-divisor-of-16 InterpolatedFunction cell sizes must fail closed instead of silently dropping trilinear interp
        final DensityFunction nonDivisorInterp = new InterpolatedFunction(
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.0f, -1.0f), 3, 8
        );
        final DensityFunctionIrLowerer.SplitLoweringResult nonDivisorSplit = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            nonDivisorInterp, randomState.paper$compileContext(), "NonDivisorInterp"
        );
        assertFalse(
            nonDivisorSplit.isSupported(),
            "Non-divisor-of-16 InterpolatedFunction cell size (3x8) must fail closed"
        );

        // 6. LegacyRandomSource is supported cleanly; unkeyed BiomeCondition holders must still fail closed cleanly
        final RandomState legacyRandom = RandomState.create(
            noiseGetter, 12345L, true, Blocks.STONE.defaultBlockState(), 63, simpleRouter
        );
        final NoiseGeneratorSettings legacySettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            simpleRouter, Holder.direct(new BlockRule(Blocks.STONE.defaultBlockState())),
            List.of(), 63, false, Optional.empty(), true, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        assertNotNull(
            GpuVanillaSurfaceCompiler.compile(legacySettings, legacyRandom, wgCtx, false, false, false, emitter, exactMode, "legacy_rng"),
            "LegacyRandomSource must compile cleanly in GpuVanillaSurfaceCompiler (Nether/End support)"
        );
        final Holder<Biome> unkeyedBiome = Holder.direct(makeSyntheticBiome(0.5f, Biome.TemperatureModifier.NONE));
        final NoiseGeneratorSettings unkeyedSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            simpleRouter,
            Holder.direct(new ConditionRule(new BiomeCondition(HolderSet.direct(unkeyedBiome)), new BlockRule(Blocks.DIRT.defaultBlockState()))),
            List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        assertNull(
            GpuVanillaSurfaceCompiler.compile(unkeyedSettings, randomState, wgCtx, false, false, false, emitter, exactMode, "unkeyed_biome"),
            "Unkeyed biome holder in BiomeCondition must fail closed cleanly"
        );

        // 7. possibleBiomes temperature-class deduplication keeps 110+ datapack biomes well within MAX_BIOME_PALETTE=254
        final List<Holder<Biome>> tempDedupBiomes = new ArrayList<>(110);
        final float[] fiveTemps = {-0.5f, 0.0f, 0.25f, 0.8f, 2.0f};
        for (int i = 0; i < 110; i++) {
            final float temp = fiveTemps[i % fiveTemps.length];
            final Biome.TemperatureModifier mod = (i % 10 == 1)
                ? Biome.TemperatureModifier.FROZEN
                : Biome.TemperatureModifier.NONE;
            tempDedupBiomes.add(makeKeyedBiome("dedup_biome_" + i, temp, mod));
        }
        final NoiseGeneratorSettings tempDedupSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            simpleRouter,
            Holder.direct(new ConditionRule(TemperatureCondition.INSTANCE, new BlockRule(Blocks.SNOW_BLOCK.defaultBlockState()))),
            List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram dedupProg = GpuVanillaSurfaceCompiler.compile(
            tempDedupSettings, randomState, wgCtx, false, false, false, tempDedupBiomes, emitter, exactMode, "temp_dedup_110"
        );
        assertNotNull(dedupProg, "110 possibleBiomes sharing 6 temperature classes must compile cleanly via dedup");
        assertEquals(113, dedupProg.biomeKeyToId().size(), "3 reserved + 110 possibleBiomes must all have mapped IDs");
        final long distinctIds = dedupProg.biomeKeyToId().values().stream().distinct().count();
        assertTrue(distinctIds <= 12, "110 unreferenced biomes must deduplicate into <= 12 distinct IDs, got " + distinctIds);

        // Unkeyed biome in possibleBiomes under TemperatureCondition must fail closed (S-004)
        final List<Holder<Biome>> withUnkeyedPossible = new ArrayList<>(tempDedupBiomes.subList(0, 5));
        withUnkeyedPossible.add(unkeyedBiome);
        assertNull(
            GpuVanillaSurfaceCompiler.compile(
                tempDedupSettings, randomState, wgCtx, false, false, false, withUnkeyedPossible, emitter, exactMode, "unkeyed_possible"
            ),
            "Unkeyed biome in possibleBiomes under TemperatureCondition must fail closed cleanly"
        );
    }

    @Test
    void unsupportedConstructsCarversAndOreRuleTestsFailClosedCleanly() {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction simpleFinal = new InterpolatedFunction(
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 320, 1.0f, -1.0f), 4, 8
        );
        final NoiseRouter router = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), simpleFinal
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 777L, false, Blocks.STONE.defaultBlockState(), 63, router
        );

        // 1. Unsupported custom DensityFunction fails closed cleanly
        final DensityFunctionIrLowerer.LoweringResult unsupDf = DensityFunctionIrLowerer.lower(
            new SyntheticUnsupportedDensityFunction(0.42f), randomState.paper$compileContext(), "UnsupDf", true
        );
        assertFalse(unsupDf.isSupported());
        assertNotNull(unsupDf.unsupportedReason());

        // 2. Unsupported custom MaterialCondition fails closed cleanly
        final NoiseGeneratorSettings unsupCondSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            router,
            Holder.direct(new ConditionRule(new SyntheticUnsupportedMaterialCondition(), new BlockRule(Blocks.DIRT.defaultBlockState()))),
            List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        assertNull(GpuVanillaSurfaceCompiler.compile(
            unsupCondSettings, randomState, makeWgContext(-64, 384, 63), false, false, false,
            new OpenClBackendEmitter(), ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32, "unsup_cond"
        ));

        // 3. Unsupported WorldCarver HeightProvider (WeightedListHeight) fails closed cleanly
        final HeightProvider weightedHeight = new WeightedListHeight(
            WeightedList.<HeightProvider>builder()
                .add(ConstantHeight.of(VerticalAnchor.absolute(20)), 1)
                .add(ConstantHeight.of(VerticalAnchor.absolute(40)), 2)
                .build()
        );
        final CaveWorldCarver unsupportedCarver = new CaveWorldCarver(
            0.15f, weightedHeight, ConstantInt.of(10), ConstantFloat.of(0.5f), false,
            ConstantFloat.of(1.0f), ConstantFloat.of(1.0f),
            UniformFloat.of(0.8f, 1.2f), ConstantFloat.of(1.0f), ConstantFloat.of(-0.7f)
        );
        assertNull(
            GpuCarverMaskCompiler.compile(List.of(unsupportedCarver), 777L, makeWgContext(-64, 384, 63), "unsup_carver"),
            "WeightedListHeight carver provider must fail closed cleanly"
        );

        // 4. GpuOrePlacementEngine.isStaticTargetCacheSafe enforces zero-reflection fail-closed RuleTest safety
        final OreFeature safeTagOre = new OreFeature(
            new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
            Blocks.IRON_ORE.defaultBlockState(),
            9
        );
        final OreFeature safeBlockOre = new OreFeature(
            new BlockMatchTest(Blocks.STONE),
            Blocks.COAL_ORE.defaultBlockState(),
            12
        );
        final OreFeature safeStateOre = new OreFeature(
            new BlockStateMatchTest(Blocks.DEEPSLATE.defaultBlockState()),
            Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState(),
            8
        );
        final OreFeature safeAlwaysOre = new OreFeature(
            AlwaysTrueTest.INSTANCE,
            Blocks.COPPER_ORE.defaultBlockState(),
            8
        );
        final OreFeature safeVanillaHeightOre = new OreFeature(
            new AllOfRuleTest(List.of(
                new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                HeightMatchTest.min(0)
            )),
            Blocks.IRON_ORE.defaultBlockState(),
            9
        );
        final OreFeature safeMax8HeightOre = new OreFeature(
            HeightMatchTest.max(8),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            9
        );
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeTagOre), "TagMatchTest must be static-cache safe");
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeBlockOre), "BlockMatchTest must be static-cache safe");
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeStateOre), "BlockStateMatchTest must be static-cache safe");
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeAlwaysOre), "AlwaysTrueTest must be static-cache safe");
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeVanillaHeightOre), "AllOf(Tag, HeightMatchTest.min(0)) must be safe");
        assertTrue(GpuOrePlacementEngine.isStaticTargetCacheSafe(safeMax8HeightOre), "HeightMatchTest.max(8) must be safe");

        final OreFeature randomRuleOre = new OreFeature(
            new RandomBlockMatchTest(Blocks.STONE, 0.5f),
            Blocks.DIAMOND_ORE.defaultBlockState(),
            8
        );
        final OreFeature randomStateOre = new OreFeature(
            new RandomBlockStateMatchTest(Blocks.STONE.defaultBlockState(), 0.25f),
            Blocks.DIAMOND_ORE.defaultBlockState(),
            8
        );
        final OreFeature customHeightOre = new OreFeature(
            new HeightMatchTest(40, 80),
            Blocks.EMERALD_ORE.defaultBlockState(),
            8
        );
        final OreFeature nestedRandomOre = new OreFeature(
            new AllOfRuleTest(List.of(
                new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                new RandomBlockMatchTest(Blocks.STONE, 0.5f)
            )),
            Blocks.EMERALD_ORE.defaultBlockState(),
            8
        );
        assertFalse(GpuOrePlacementEngine.isStaticTargetCacheSafe(randomRuleOre), "RandomBlockMatchTest must fail closed to CPU");
        assertFalse(GpuOrePlacementEngine.isStaticTargetCacheSafe(randomStateOre), "RandomBlockStateMatchTest must fail closed to CPU");
        assertFalse(GpuOrePlacementEngine.isStaticTargetCacheSafe(customHeightOre), "HeightMatchTest(40,80) must fail closed to CPU");
        assertFalse(GpuOrePlacementEngine.isStaticTargetCacheSafe(nestedRandomOre), "AllOf containing RandomBlockMatchTest must fail closed");
    }

    @Test
    void datapackMutationProducesDistinctOpenClKernelCacheKeys(@TempDir final Path tempDir) {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        final WorldGenerationContext wgCtx = makeWgContext(-64, 384, 63);

        final DensityFunction baseA = new InterpolatedFunction(
            new FindTopSurfaceFunction(
                new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 256, 1.0f, -1.0f),
                DensityFunctions.constant(200.0f),
                -64,
                8
            ).mul(DensityFunctions.constant(0.01f))
                .add(new DistanceToPointFunction(new Vec3i(0, 64, 0), DistanceMetric.EUCLIDEAN).mul(DensityFunctions.constant(0.001f))),
            4,
            8
        );
        final DensityFunction baseB = new InterpolatedFunction(
            new FindTopSurfaceFunction(
                new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 256, 1.0f, -1.0f),
                DensityFunctions.constant(200.0f),
                -32, // mutated lowerBound
                8
            ).mul(DensityFunctions.constant(0.01f))
                .add(new DistanceToPointFunction(new Vec3i(0, 64, 0), DistanceMetric.EUCLIDEAN).mul(DensityFunctions.constant(0.001f))),
            4,
            8
        );

        final NoiseRouter routerA = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), baseA
        );
        final NoiseRouter routerB = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), baseB
        );
        final RandomState rsA = RandomState.create(noiseGetter, 424242L, false, Blocks.STONE.defaultBlockState(), 63, routerA);
        final RandomState rsB = RandomState.create(noiseGetter, 424242L, false, Blocks.STONE.defaultBlockState(), 63, routerB);

        final Holder.Reference<Biome> biomeWarm = makeKeyedBiome("cache_biome", 0.30f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> biomeCold = makeKeyedBiome("cache_biome", 0.05f, Biome.TemperatureModifier.NONE);

        final MaterialRule ruleA = new ConditionRule(
            new BiomeCondition(HolderSet.direct(biomeWarm)),
            new ConditionRule(TemperatureCondition.INSTANCE, new BlockRule(Blocks.SNOW_BLOCK.defaultBlockState()))
        );
        final MaterialRule ruleB = new ConditionRule(
            new BiomeCondition(HolderSet.direct(biomeCold)),
            new ConditionRule(TemperatureCondition.INSTANCE, new BlockRule(Blocks.SNOW_BLOCK.defaultBlockState()))
        );

        final NoiseGeneratorSettings settingsA = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            routerA, Holder.direct(ruleA), List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final NoiseGeneratorSettings settingsB = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            routerB, Holder.direct(ruleB), List.of(), 63, false, Optional.empty(), false, NoiseGeneratorSettings.DebugFunctions.EMPTY
        );

        // Identical moduleBaseName & seed simulating an edited datapack in the same dimension
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram progA = GpuVanillaSurfaceCompiler.compile(
            settingsA, rsA, wgCtx, false, false, false, emitter, exactMode, "minecraft_overworld"
        );
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram progB = GpuVanillaSurfaceCompiler.compile(
            settingsB, rsB, wgCtx, false, false, false, emitter, exactMode, "minecraft_overworld"
        );
        assertNotNull(progA);
        assertNotNull(progB);

        assertNotEquals(
            progA.terrainProgram().kernelDigestSha256(),
            progB.terrainProgram().kernelDigestSha256(),
            "Mutating DensityFunction must produce a distinct terrain kernel SHA-256"
        );
        assertNotEquals(
            progA.surfaceProgram().kernelDigestSha256(),
            progB.surfaceProgram().kernelDigestSha256(),
            "Mutating biome temperature under TemperatureCondition must produce a distinct surface kernel SHA-256"
        );
        final String binKeyA = OpenClKernelCache.buildBinaryCacheKey("dev-sig", "-cl-std=CL1.2", progA.surfaceProgram().kernelDigestSha256());
        final String binKeyB = OpenClKernelCache.buildBinaryCacheKey("dev-sig", "-cl-std=CL1.2", progB.surfaceProgram().kernelDigestSha256());
        assertNotEquals(binKeyA, binKeyB, "OpenClKernelCache binary keys must differ after datapack edit");
    }

    @Test
    void temperatureConditionAndEndSurfaceLiveGpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        final Path cacheDir = tempDir.resolve("live-surface-cache");

        // --- Part A: TemperatureCondition live GPU vs Mojang Biome#coldEnoughToSnow 1:1 parity ---
        // Sloped terrain across Y in [60, 124] so snowline (seaLevel + 17 = 80) is crossed in every chunk
        final int seaLevel = 63;
        final int minY = 0;
        final int height = 160; // 10 sections
        final int sectionCount = height / 16;
        final DensityFunction hillTargetY = new GradientFunction(Direction.Axis.X, TilingMode.REPEAT, 0, 64, 62.0f, 126.0f)
            .add(new GradientFunction(Direction.Axis.Z, TilingMode.REPEAT, 0, 64, -8.0f, 12.0f));
        final DensityFunction yCoord = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, 0, 160, 0.0f, 160.0f);
        final DensityFunction tempFinalDensity = new InterpolatedFunction(
            hillTargetY.sub(yCoord).mul(DensityFunctions.constant(0.05f)),
            4,
            8
        );
        final NoiseRouter tempRouter = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), tempFinalDensity
        );
        final Holder.Reference<Biome> coldBiome = makeKeyedBiome("live_cold", -0.30f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> snowlineBiome = makeKeyedBiome("live_snowline", 0.25f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> warmBiome = makeKeyedBiome("live_warm", 0.85f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> frozenBiome = makeKeyedBiome("live_frozen", 0.0f, Biome.TemperatureModifier.FROZEN);
        final List<Holder<Biome>> fourBiomes = List.of(coldBiome, snowlineBiome, warmBiome, frozenBiome);

        final MaterialRule tempRule = new ConditionRule(
            new StoneDepthCondition(0, false, 0, CaveSurface.FLOOR),
            new SequenceRule(List.of(
                new ConditionRule(TemperatureCondition.INSTANCE, new BlockRule(Blocks.SNOW_BLOCK.defaultBlockState())),
                new BlockRule(Blocks.GRASS_BLOCK.defaultBlockState())
            ))
        );
        final NoiseGeneratorSettings tempSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(minY, height),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            tempRouter,
            Holder.direct(tempRule),
            List.of(),
            seaLevel,
            false,
            Optional.empty(),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState tempRs = RandomState.create(noiseGetter, 20260326L, false, Blocks.STONE.defaultBlockState(), seaLevel, tempRouter);
        final WorldGenerationContext tempWg = makeWgContext(minY, height, seaLevel);

        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram tempProg = GpuVanillaSurfaceCompiler.compile(
            tempSettings, tempRs, tempWg, false, false, false, fourBiomes, emitter, exactMode, "live_temp_parity"
        );
        assertNotNull(tempProg, "TemperatureCondition surface program must compile");
        assertZeroFp64(tempProg.terrainProgram().sourceCode(), "live_temp_parity terrain");
        assertZeroFp64(tempProg.surfaceProgram().sourceCode(), "live_temp_parity surface");
        assertZeroFp64(tempProg.packProgram().sourceCode(), "live_temp_parity pack");

        // --- Part B: End datapack surface writeback program ---
        // EndIslandFunction returns -0.84375f inside the inner 64-chunk void ring and up to +0.5625f
        // on outer islands, so gradient [2.0 -> -0.5] guarantees solid terrain crossing y=32..59.
        final DensityFunction endIslands = new EndIslandFunction();
        final DensityFunction endYGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, 0, 128, 2.0f, -0.5f);
        final DensityFunction endFinal = new InterpolatedFunction(
            endIslands.add(endYGrad), 4, 8
        );
        final NoiseRouter endRouter = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            endIslands, DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), endFinal
        );
        final MaterialRule endRule = new SequenceRule(List.of(
            new ConditionRule(new StoneDepthCondition(0, false, 0, CaveSurface.FLOOR), new BlockRule(Blocks.PURPUR_BLOCK.defaultBlockState())),
            new ConditionRule(new YCondition(VerticalAnchor.absolute(32), 0, false), new BlockRule(Blocks.END_STONE_BRICKS.defaultBlockState())),
            new BlockRule(Blocks.OBSIDIAN.defaultBlockState())
        ));
        final NoiseGeneratorSettings endSettings = new NoiseGeneratorSettings(
            NoiseSettings.create(0, 128),
            Blocks.END_STONE.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            endRouter,
            Holder.direct(endRule),
            List.of(),
            0,
            true,
            Optional.empty(),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState endRs = RandomState.create(noiseGetter, 987654321L, false, Blocks.END_STONE.defaultBlockState(), 0, endRouter);
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram endProg = GpuVanillaSurfaceCompiler.compile(
            endSettings, endRs, makeWgContext(0, 128, 0), false, false, false, null, emitter, exactMode, "live_end_surface"
        );
        assertNotNull(endProg, "End datapack surface program must compile");
        assertZeroFp64(endProg.surfaceProgram().sourceCode(), "live_end_surface surface");

        long coldCompileNanos;
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(cacheDir)) {
            if (!accel.isGpuReady()) {
                return;
            }
            final long t0 = System.nanoTime();
            final var tKernel = accel.compileProgramBlocking(tempProg.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var sKernel = accel.compileProgramBlocking(tempProg.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var pKernel = accel.compileProgramBlocking(tempProg.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            coldCompileNanos = System.nanoTime() - t0;
            assertTrue(tKernel.hasLiveGpuKernel() && sKernel.hasLiveGpuKernel() && pKernel.hasLiveGpuKernel());

            final int batchCount = 4;
            final int qSizeY = sectionCount * 4;
            final int haloPerChunk = 36 * qSizeY;
            final int[] origins = {
                0, minY, 0, 0,
                16, minY, 0, 0,
                32, minY, 16, 0,
                48, minY, 32, 0
            };
            final byte[] biomesHalo = new byte[batchCount * haloPerChunk];
            for (int c = 0; c < batchCount; c++) {
                final int bid = tempProg.biomeKeyToId().get(fourBiomes.get(c).unwrapKey().orElseThrow());
                java.util.Arrays.fill(biomesHalo, c * haloPerChunk, (c + 1) * haloPerChunk, (byte) bid);
            }
            final float[] prelim = new float[batchCount * 4];
            java.util.Arrays.fill(prelim, 64.0f);
            final int[] beardMeta = new int[batchCount * 2];
            final int[] beardItems = new int[8];
            final byte[] chunkFlags = new byte[batchCount];
            final CompactVanillaSurfaceWritebackBuffer outBuf = new CompactVanillaSurfaceWritebackBuffer(batchCount * sectionCount);
            accel.executeVanillaSurfaceU8Batch(
                tKernel, sKernel, pKernel, null, null, null,
                origins, biomesHalo, prelim, beardMeta, beardItems, 0,
                null, null, 0, 1, minY + 1, minY + height - 8,
                batchCount, sectionCount, seaLevel, Math.min(seaLevel, -54), minY,
                BiomeManager.obfuscateSeed(20260326L), outBuf, chunkFlags, null, null
            );

            int checkedSurfaceCols = 0;
            int tempMismatches = 0;
            int snowCols = 0;
            int grassCols = 0;
            final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
            for (int c = 0; c < batchCount; c++) {
                final Biome cpuBiome = fourBiomes.get(c).value();
                final int baseX = origins[c * 4];
                final int baseZ = origins[c * 4 + 2];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        // Find top solid block (above seaLevel so it is exposed to air, not water)
                        for (int y = minY + height - 1; y >= seaLevel + 1; y--) {
                            final int s = (y - minY) >> 4;
                            final int ly = (y - minY) & 15;
                            final BlockState st = decodeGpuSectionVoxel(
                                outBuf, c * sectionCount + s, lx, ly, lz, tempProg.blockPalette()
                            );
                            if (st.isAir() || st.is(Blocks.WATER)) {
                                continue;
                            }
                            checkedSurfaceCols++;
                            final boolean cpuCold = cpuBiome.coldEnoughToSnow(mpos.set(baseX + lx, y, baseZ + lz), seaLevel);
                            final boolean gpuSnow = st.is(Blocks.SNOW_BLOCK);
                            final boolean gpuGrass = st.is(Blocks.GRASS_BLOCK);
                            assertTrue(gpuSnow || gpuGrass, "Top exposed block must be SNOW_BLOCK or GRASS_BLOCK, got " + st);
                            if (gpuSnow) {
                                snowCols++;
                            } else {
                                grassCols++;
                            }
                            if (gpuSnow != cpuCold) {
                                tempMismatches++;
                            }
                            break;
                        }
                    }
                }
            }
            assertTrue(checkedSurfaceCols >= 800, "Expected >= 800 exposed surface columns, got " + checkedSurfaceCols);
            assertTrue(snowCols > 0 && grassCols > 0, "Fixture must exercise both snow and non-snow columns (snow=" + snowCols + ", grass=" + grassCols + ")");
            final double mismatchPct = 100.0 * tempMismatches / (double) checkedSurfaceCols;
            assertTrue(
                mismatchPct <= 0.05,
                String.format(Locale.ROOT, "TemperatureCondition GPU vs Mojang CPU mismatch %.4f%% (%d/%d) exceeded 0.05%%",
                    mismatchPct, tempMismatches, checkedSurfaceCols)
            );

            // Execute End datapack surface writeback on GPU
            final var etKernel = accel.compileProgramBlocking(endProg.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var esKernel = accel.compileProgramBlocking(endProg.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var epKernel = accel.compileProgramBlocking(endProg.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(etKernel.hasLiveGpuKernel() && esKernel.hasLiveGpuKernel() && epKernel.hasLiveGpuKernel());
            final int endSections = 8;
            final int[] endOrigins = {0, 0, 0, 0};
            final byte[] endBiomes = new byte[36 * endSections * 4];
            java.util.Arrays.fill(endBiomes, (byte) 255);
            final CompactVanillaSurfaceWritebackBuffer endBuf = new CompactVanillaSurfaceWritebackBuffer(endSections);
            accel.executeVanillaSurfaceU8Batch(
                etKernel, esKernel, epKernel, null, null, null,
                endOrigins, endBiomes, new float[]{64, 64, 64, 64}, new int[2], new int[8], 0,
                null, null, 0, 1, 1, 120,
                1, endSections, 0, -54, 0,
                BiomeManager.obfuscateSeed(987654321L), endBuf, new byte[1], null, null
            );
            int purpurCount = 0;
            int endBrickCount = 0;
            int obsidianCount = 0;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    boolean aboveWasAir = true;
                    for (int y = 127; y >= 0; y--) {
                        final BlockState st = decodeGpuSectionVoxel(endBuf, y >> 4, lx, y & 15, lz, endProg.blockPalette());
                        assertFalse(st.is(Blocks.LAVA), "End chunk must never contain LAVA header-slot leakage");
                        if (st.isAir()) {
                            aboveWasAir = true;
                            continue;
                        }
                        if (aboveWasAir) {
                            assertEquals(Blocks.PURPUR_BLOCK.defaultBlockState(), st, "Exposed floor End block at y=" + y + " must be PURPUR_BLOCK");
                            purpurCount++;
                            aboveWasAir = false;
                        } else if (y >= 32) {
                            assertEquals(Blocks.END_STONE_BRICKS.defaultBlockState(), st, "Sub-surface End block at y=" + y + " must be END_STONE_BRICKS");
                            endBrickCount++;
                        } else {
                            assertEquals(Blocks.OBSIDIAN.defaultBlockState(), st, "Deep End block at y=" + y + " must be OBSIDIAN");
                            obsidianCount++;
                        }
                    }
                }
            }
            assertTrue(purpurCount > 0 && endBrickCount > 0 && obsidianCount > 0,
                "End surface writeback must produce PURPUR_BLOCK (" + purpurCount + "), END_STONE_BRICKS (" + endBrickCount + "), and OBSIDIAN (" + obsidianCount + ")");

            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU TemperatureCondition parity: %d/%d exact (%.4f%% diff, snow=%d, grass=%d); End surface: purpur=%d, bricks=%d, obsidian=%d; coldCompile=%.1fms%n",
                checkedSurfaceCols - tempMismatches, checkedSurfaceCols, mismatchPct, snowCols, grassCols,
                purpurCount, endBrickCount, obsidianCount, coldCompileNanos / 1_000_000.0
            );
        }

        // Warm .clbin cache reload timing using a fresh IrOpenClTerrainAccelerator over the same cacheDir
        try (IrOpenClTerrainAccelerator warmAccel = new IrOpenClTerrainAccelerator(cacheDir)) {
            if (!warmAccel.isGpuReady()) {
                return;
            }
            final long t0 = System.nanoTime();
            final var tKernel = warmAccel.compileProgramBlocking(tempProg.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var sKernel = warmAccel.compileProgramBlocking(tempProg.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var pKernel = warmAccel.compileProgramBlocking(tempProg.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final long warmReloadNanos = System.nanoTime() - t0;
            assertTrue(tKernel.hasLiveGpuKernel() && sKernel.hasLiveGpuKernel() && pKernel.hasLiveGpuKernel());
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU warm .clbin reload: %.1fms (cold=%.1fms)%n",
                warmReloadNanos / 1_000_000.0, coldCompileNanos / 1_000_000.0
            );
        }
    }

    @Test
    void vanillaOverworldNetherEndSurfaceCompilersRemainSupportedWithZeroFp64() {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final var noiseSettingsLookup = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        final NoiseGeneratorSettings overworld = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        final RandomState owRs = RandomState.create(noiseGetter, 1001L, false, overworld.defaultBlock(), overworld.seaLevel(), overworld.noiseRouter());
        final var owSurf = GpuVanillaSurfaceCompiler.compile(
            overworld, owRs, makeWgContext(-64, 384, overworld.seaLevel()), false, false, false, null, emitter, exactMode, "vanilla_ow"
        );
        assertNotNull(owSurf, "Vanilla Overworld MaterialRule (including OreVeinRule fast-path) must compile cleanly on GPU");
        assertZeroFp64(owSurf.terrainProgram().sourceCode(), "vanilla_ow terrain");
        assertZeroFp64(owSurf.surfaceProgram().sourceCode(), "vanilla_ow surface");
        assertTrue(owSurf.surfaceProgram().sourceCode().contains("gl_ore_trilinear"), "Vanilla Overworld must use fast coarse ore vein path");

        final NoiseGeneratorSettings nether = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.NETHER).value();
        assertTrue(nether.useLegacyRandomSource(), "Vanilla Nether uses LegacyRandomSource");
        final RandomState netherRs = RandomState.create(noiseGetter, 1002L, nether);
        final var netherSurf = GpuVanillaSurfaceCompiler.compile(
            nether, netherRs, makeWgContext(0, 128, nether.seaLevel()), false, false, false, null, emitter, exactMode, "vanilla_nether"
        );
        assertNotNull(netherSurf, "Vanilla Nether MaterialRule (LegacyPositionalRandomFactory) must compile cleanly on GPU");
        assertZeroFp64(netherSurf.terrainProgram().sourceCode(), "vanilla_nether terrain");
        assertZeroFp64(netherSurf.surfaceProgram().sourceCode(), "vanilla_nether surface");
        assertTrue(netherSurf.surfaceProgram().sourceCode().contains("gl_legacy_init("), "Vanilla Nether must emit LegacyRandomSource helpers");

        final NoiseGeneratorSettings end = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.END).value();
        assertTrue(end.useLegacyRandomSource(), "Vanilla End uses LegacyRandomSource");
        final RandomState endRs = RandomState.create(noiseGetter, 1003L, end);
        final var endSurf = GpuVanillaSurfaceCompiler.compile(
            end, endRs, makeWgContext(0, 128, end.seaLevel()), false, false, false, null, emitter, exactMode, "vanilla_end"
        );
        assertNotNull(endSurf, "Vanilla End MaterialRule (LegacyPositionalRandomFactory + EndIslandFunction) must compile cleanly on GPU");
        assertZeroFp64(endSurf.terrainProgram().sourceCode(), "vanilla_end terrain");
        assertZeroFp64(endSurf.surfaceProgram().sourceCode(), "vanilla_end surface");
    }

    @Test
    void largeDatapackBiomeRTreeLiveGpuParityAndThroughput(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final NoiseGeneratorSettings overworld = registries
            .lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD)
            .value();
        final NoiseRouter owRouter = overworld.noiseRouter();
        final RandomState owRs = RandomState.create(
            noiseGetter, 20261126L, false, overworld.defaultBlock(), overworld.seaLevel(), owRouter
        );
        final DensityFunction.CompileContext compileCtx = owRs.paper$compileContext();

        // Build a 110-biome, 19,800-entry 6D climate ParameterList matching leaf-worldgen (19,807 entries) / Terralith scale
        final List<Holder<Biome>> customBiomes = new ArrayList<>(110);
        for (int i = 0; i < 110; i++) {
            customBiomes.add(makeKeyedBiome("rtree_110_" + i, -0.5f + (i % 20) * 0.1f, Biome.TemperatureModifier.NONE));
        }
        final List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new ArrayList<>(19800);
        int idx = 0;
        for (int t = 0; t < 11; t++) {
            final float tLo = -1.0f + t * (2.0f / 11.0f);
            final float tHi = tLo + (2.0f / 11.0f);
            for (int h = 0; h < 10; h++) {
                final float hLo = -1.0f + h * 0.2f;
                final float hHi = hLo + 0.2f;
                for (int c = 0; c < 6; c++) {
                    final float cLo = -1.0f + c * (2.0f / 6.0f);
                    final float cHi = cLo + (2.0f / 6.0f);
                    for (int e = 0; e < 5; e++) {
                        final float eLo = -1.0f + e * 0.4f;
                        final float eHi = eLo + 0.4f;
                        for (int w = 0; w < 6; w++) {
                            final float wLo = -1.0f + w * (2.0f / 6.0f);
                            final float wHi = wLo + (2.0f / 6.0f);
                            entries.add(Pair.of(
                                Climate.parameters(
                                    Climate.Parameter.span(tLo, tHi),
                                    Climate.Parameter.span(hLo, hHi),
                                    Climate.Parameter.span(cLo, cHi),
                                    Climate.Parameter.span(eLo, eHi),
                                    Climate.Parameter.span(-1.0f, 1.0f),
                                    Climate.Parameter.span(wLo, wHi),
                                    0.0f
                                ),
                                customBiomes.get(idx % 110)
                            ));
                            idx++;
                        }
                    }
                }
            }
        }
        final Climate.ParameterList<Holder<Biome>> paramList = new Climate.ParameterList<>(entries);
        final GpuBiomeRTree.FlattenedRTree flattened = GpuBiomeRTree.flatten(paramList.goldleaf$index());
        assertNotNull(flattened, "19,800-entry / 110-biome datapack R-tree must flatten cleanly");
        assertEquals(110, flattened.biomePalette().length);
        assertTrue(
            flattened.nodeCount() > 16384 && flattened.nodeCount() <= IrOpenClTerrainAccelerator.MAX_GPU_RTREE_NODES,
            "19,800-entry R-tree must exceed legacy 16384 cap and fit within widened 65536 cap, got " + flattened.nodeCount()
        );

        final List<DensityFunction> climate6 = List.of(
            owRouter.temperature(),
            owRouter.vegetation(),
            owRouter.continents(),
            owRouter.erosion(),
            owRouter.depth(),
            owRouter.ridges()
        );

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("rtree-110-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram climate6Prog = accel.lowerFusedBiomeClimateProgram(
                climate6, compileCtx, "dp_rtree_110_", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertNotNull(climate6Prog);
            assertZeroFp64(climate6Prog.sourceCode(), "dp_rtree_110_climate6");
            final GpuBiomeRTree.FusedBiomeRTreeProgram fused = GpuBiomeRTree.buildFusedProgram(climate6Prog, flattened);
            assertNotNull(fused);
            assertZeroFp64(fused.program().sourceCode(), "dp_rtree_110_fused");

            if (!accel.isGpuReady()) {
                return;
            }
            final var climate6Kernel = accel.compileProgramBlocking(climate6Prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var rtreeKernel = accel.compileProgramBlocking(fused.program(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(climate6Kernel.hasLiveGpuKernel() && rtreeKernel.hasLiveGpuKernel());

            // 1. Bit-identical CPU vs GPU R-tree parity across 4 chunks (4x32x4 = 2,048 quarts)
            final int parityBatch = 4;
            final int sizeX = 4, sizeY = 32, sizeZ = 4;
            final int qPerChunk = sizeX * sizeY * sizeZ;
            final int[] parityOrigins = {
                0, -64, 0, 0,
                256, -64, -192, 0,
                -512, -64, 384, 0,
                1024, -64, 1024, 0
            };
            final float[] climateOut = new float[parityBatch * qPerChunk * 6];
            accel.executeBiomeClimate6Batch(climate6Kernel, parityOrigins, parityBatch, sizeX, sizeY, sizeZ, 4, 4, 4, climateOut);
            final byte[] gpuBiomeIds = new byte[parityBatch * qPerChunk];
            accel.executeBiomeRTreeBatch(rtreeKernel, parityOrigins, parityBatch, sizeX, sizeY, sizeZ, 4, 4, 4, gpuBiomeIds);

            final Holder<Biome>[] palette = flattened.biomePalette();
            final java.util.Set<Holder<Biome>> distinctSeen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int i = 0; i < parityBatch * qPerChunk; i++) {
                final int off = i * 6;
                final Holder<Biome> cpuBiome = paramList.findValueQuantized(
                    Climate.quantizeCoord(climateOut[off]),
                    Climate.quantizeCoord(climateOut[off + 1]),
                    Climate.quantizeCoord(climateOut[off + 2]),
                    Climate.quantizeCoord(climateOut[off + 3]),
                    Climate.quantizeCoord(climateOut[off + 4]),
                    Climate.quantizeCoord(climateOut[off + 5])
                );
                final Holder<Biome> gpuBiome = palette[gpuBiomeIds[i] & 0xFF];
                org.junit.jupiter.api.Assertions.assertSame(
                    cpuBiome, gpuBiome,
                    "110-biome / 19,800-entry GPU R-Tree mismatch at quart " + i
                );
                distinctSeen.add(gpuBiome);
            }
            assertTrue(distinctSeen.size() >= 2, "Fixture must hit multiple distinct biomes, got " + distinctSeen.size());

            // 2. Comparative CPU (1T) vs live GPU (batch=32) 512-height (4x128x4) biome throughput
            final int benchBatch = 32;
            final int bSizeY = 128; // 512 blocks tall at quart resolution
            final int bQuarts = 4 * bSizeY * 4;
            final int[] benchOrigins = new int[benchBatch * 4];
            for (int b = 0; b < benchBatch; b++) {
                benchOrigins[b * 4] = (b & 7) * 64;
                benchOrigins[b * 4 + 1] = -64;
                benchOrigins[b * 4 + 2] = (b >> 3) * 64;
                benchOrigins[b * 4 + 3] = 0;
            }
            final byte[] benchGpuOut = new byte[benchBatch * bQuarts];
            // Warmup
            accel.executeBiomeRTreeBatch(rtreeKernel, benchOrigins, benchBatch, 4, bSizeY, 4, 4, 4, 4, benchGpuOut);
            final long gpuT0 = System.nanoTime();
            final int gpuRuns = 5;
            for (int r = 0; r < gpuRuns; r++) {
                accel.executeBiomeRTreeBatch(rtreeKernel, benchOrigins, benchBatch, 4, bSizeY, 4, 4, 4, 4, benchGpuOut);
            }
            final double gpuMs = (System.nanoTime() - gpuT0) / (gpuRuns * 1_000_000.0);
            final double gpuChPerSec = benchBatch / (gpuMs / 1000.0);

            // CPU 1-thread reference (4 chunks scaled to ch/s to keep test fast)
            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler[] cpuSamplers = new DensitySampler[6];
            for (int c = 0; c < 6; c++) {
                cpuSamplers[c] = mojangCompiler.getSampler(climate6.get(c));
            }
            final SamplerContext cpuCtx = SamplerContext.builder().enableCaches().build();
            final int cpuChunks = 4;
            final long cpuT0 = System.nanoTime();
            int cpuChecksum = 0;
            for (int b = 0; b < cpuChunks; b++) {
                final DensityVolume vol = new DensityVolume(
                    4, bSizeY, 4, benchOrigins[b * 4], -64, benchOrigins[b * 4 + 2], 4, 4, 4
                );
                try (
                    ScopedDensityBuffer b0 = cpuCtx.acquireBuffer(vol);
                    ScopedDensityBuffer b1 = cpuCtx.acquireBuffer(vol);
                    ScopedDensityBuffer b2 = cpuCtx.acquireBuffer(vol);
                    ScopedDensityBuffer b3 = cpuCtx.acquireBuffer(vol);
                    ScopedDensityBuffer b4 = cpuCtx.acquireBuffer(vol);
                    ScopedDensityBuffer b5 = cpuCtx.acquireBuffer(vol)
                ) {
                    cpuSamplers[0].sampleVolume(cpuCtx, b0, vol);
                    cpuSamplers[1].sampleVolume(cpuCtx, b1, vol);
                    cpuSamplers[2].sampleVolume(cpuCtx, b2, vol);
                    cpuSamplers[3].sampleVolume(cpuCtx, b3, vol);
                    cpuSamplers[4].sampleVolume(cpuCtx, b4, vol);
                    cpuSamplers[5].sampleVolume(cpuCtx, b5, vol);
                    for (int i = 0; i < bQuarts; i++) {
                        final Holder<Biome> bh = paramList.findValueQuantized(
                            Climate.quantizeCoord(b0.get(i)),
                            Climate.quantizeCoord(b1.get(i)),
                            Climate.quantizeCoord(b2.get(i)),
                            Climate.quantizeCoord(b3.get(i)),
                            Climate.quantizeCoord(b4.get(i)),
                            Climate.quantizeCoord(b5.get(i))
                        );
                        cpuChecksum ^= bh.hashCode();
                    }
                }
            }
            final double cpuMs = (System.nanoTime() - cpuT0) / 1_000_000.0;
            final double cpu1TChPerSec = cpuChunks / (cpuMs / 1000.0);
            assertNotEquals(Integer.MIN_VALUE, cpuChecksum);
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU 110-biome/19,800-entry R-Tree (nodes=%d, depth=%d): %d/%d bit-identical (0 mismatches, %d distinct); 512h BIOMES: GPU(b=32)=%.1f ch/s (%.2fms/32ch) vs CPU-1T=%.1f ch/s (%.1fx)%n",
                flattened.nodeCount(), flattened.maxDepth(), parityBatch * qPerChunk, parityBatch * qPerChunk,
                distinctSeen.size(), gpuChPerSec, gpuMs, cpu1TChPerSec, gpuChPerSec / Math.max(cpu1TChPerSec, 0.001)
            );
        }
    }

    @Test
    void extendedHeight512AquiferLiveGpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final NoiseGeneratorSettings vanillaOverworld = registries
            .lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD)
            .value();
        final var aqCfg = vanillaOverworld.aquifers().orElseThrow();
        final int minY = -64;
        final int height = 512; // 32 sections -> gs_y=46, total_cells=414 (> legacy 384, <= widened 512)
        final int sectionCount = height / 16;
        final int seaLevel = 63;

        // Underground cavern band in Y=[-54..15] below solid stone roof at Y=[16..95]
        final DensityFunction caveDensity = new RangeChoiceFunction(
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 448, -64.0f, 448.0f),
            -54.0f,
            16.0f,
            DensityFunctions.constant(-0.5f),
            new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, 16, 128, 1.5f, -1.5f)
        );
        final NoiseRouter router = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(80.0f), caveDensity
        );
        final NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
            NoiseSettings.create(minY, height),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            router,
            Holder.direct(new BlockRule(Blocks.STONE.defaultBlockState())),
            List.of(),
            seaLevel,
            false,
            Optional.of(aqCfg),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 20260512L, false, Blocks.STONE.defaultBlockState(), seaLevel, router
        );
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;
        final WorldGenerationContext wgCtx = makeWgContext(minY, height, seaLevel);

        final var surfProg = GpuVanillaSurfaceCompiler.compile(
            settings, randomState, wgCtx, false, false, false, null, emitter, exactMode, "aq_512_surf"
        );
        assertNotNull(surfProg, "512-height surface program must compile");
        final var aqProg = GpuCarverAquiferCompiler.compile(
            settings, randomState, surfProg.blockPalette(), emitter, exactMode, "aq_512_pass"
        );
        assertNotNull(aqProg, "512-height aquifer program must compile");
        assertZeroFp64(aqProg.sourceCode(), "aq_512_pass");
        assertTrue(aqProg.sourceCode().contains("l_aq_x[512]"), "Aquifer kernel must allocate widened 512-cell local buffers");

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("aq-512-cache"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final var kTerrain = accel.compileProgramBlocking(surfProg.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var kSurface = accel.compileProgramBlocking(surfProg.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var kPack = accel.compileProgramBlocking(surfProg.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var kAq = accel.compileProgramBlocking(aqProg, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(kTerrain.hasLiveGpuKernel() && kSurface.hasLiveGpuKernel() && kPack.hasLiveGpuKernel() && kAq.hasLiveGpuKernel());

            final int batchCount = 4;
            final int[] origins = {
                0, minY, 0, 0,
                64, minY, 64, 0,
                -128, minY, 96, 0,
                256, minY, -192, 0
            };
            final byte[] biomesHalo = new byte[batchCount * 36 * sectionCount * 4];
            Arrays.fill(biomesHalo, (byte) 255);
            final float[] prelim = new float[batchCount * 4];
            Arrays.fill(prelim, 80.0f);
            final int[] beardMeta = new int[batchCount * 2];
            final int[] beardItems = new int[8];
            final int[] carverMeta = new int[batchCount];
            Arrays.fill(carverMeta, -1);
            final byte[] outFlags = new byte[batchCount];
            final int[] outEvents = new int[batchCount * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS];
            final int[] outCounts = new int[batchCount];
            final CompactVanillaSurfaceWritebackBuffer outBuf = new CompactVanillaSurfaceWritebackBuffer(batchCount * sectionCount);
            accel.executeVanillaSurfaceU8Batch(
                kTerrain, kSurface, kPack, null, kAq, null,
                origins, biomesHalo, prelim, beardMeta, beardItems, 0,
                carverMeta, null, 0, 16, minY + 1, minY + height - 8,
                batchCount, sectionCount, seaLevel, -54, minY, 0L,
                outBuf, outFlags, outEvents, outCounts
            );
            for (int b = 0; b < batchCount; b++) {
                assertTrue(
                    outCounts[b] <= GpuCarverAquiferCompiler.MAX_CARVER_EVENTS,
                    "512-height aquifer (total_cells=414 <= 512) must not overflow at chunk " + b
                );
            }

            final var lavaStatus = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            final var seaStatus = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(seaLevel, Blocks.WATER.defaultBlockState());
            final net.minecraft.world.level.levelgen.Aquifer.FluidPicker picker = (x, y, z) -> y < -54 ? lavaStatus : seaStatus;
            final var posRand = randomState.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));

            long totalCaveVoxels = 0L;
            long gpuDryAir = 0L;
            long cpuDryAir = 0L;
            long exactMatches = 0L;
            for (int b = 0; b < batchCount; b++) {
                final int ox = origins[b * 4];
                final int oz = origins[b * 4 + 2];
                final var vol = new DensityVolume(16, height, 16, ox, minY, oz);
                final var samplers = randomState.samplersWithContext(SamplerContext.EMPTY_UNCACHED);
                final var cpuAquifer = aqCfg.create(samplers, posRand, vol, picker);
                final var densitySampler = samplers.get(caveDensity);
                for (int by = -54; by < 16; by++) {
                    final int relY = by - minY;
                    final int secIdx = b * sectionCount + (relY >> 4);
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            final int bx = ox + lx;
                            final int bz = oz + lz;
                            final double d = densitySampler.sampleValue(bx, by, bz);
                            if (d > 0.0) {
                                continue;
                            }
                            final BlockState cpuState = cpuAquifer.computeSubstance(bx, by, bz, d);
                            final BlockState expected = cpuState == null ? Blocks.STONE.defaultBlockState() : cpuState;
                            final BlockState actual = decodeGpuSectionVoxel(outBuf, secIdx, lx, relY & 15, lz, surfProg.blockPalette());
                            totalCaveVoxels++;
                            if (actual.isAir()) gpuDryAir++;
                            if (expected.isAir()) cpuDryAir++;
                            if (actual == expected) exactMatches++;
                        }
                    }
                }
            }
            assertTrue(cpuDryAir > totalCaveVoxels / 2, "512-height cavern fixture must be mostly dry air on CPU");
            assertTrue(gpuDryAir > totalCaveVoxels / 2, "512-height GPU aquifer must leave caves dry (not flooded!), got " + gpuDryAir + "/" + totalCaveVoxels);
            final double matchPct = 100.0 * exactMatches / (double) totalCaveVoxels;
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU 512-height Aquifer (total_cells=414<=512): %.4f%% exact (%d/%d, gpuDry=%d, cpuDry=%d)%n",
                matchPct, exactMatches, totalCaveVoxels, gpuDryAir, cpuDryAir
            );
            assertTrue(matchPct >= 99.5, "512-height GPU aquifer parity must be >= 99.5%, got " + matchPct);
        }
    }

    @Test
    void terralithStyle512HeightCpuVsGpuThroughputBenchmark(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final Holder<NormalNoise> continentalNoise = noiseGetter.getOrThrow(Noises.CONTINENTALNESS);
        final Holder<NormalNoise> erosionNoise = noiseGetter.getOrThrow(Noises.EROSION);
        final Holder<NormalNoise> shiftNoise = noiseGetter.getOrThrow(Noises.SHIFT);

        final DensityFunction shiftAll = new ShiftNoiseFunction.Shift(shiftNoise);
        final DensityFunction shiftA = new ShiftNoiseFunction.ShiftA(shiftNoise);
        final DensityFunction shiftB = new ShiftNoiseFunction.ShiftB(shiftNoise);
        final DensityFunction baseNoise = new CacheFunction(
            new NoiseFunction(continentalNoise, 0.015625, 0.03125, shiftA, DensityFunctions.zero(), shiftB)
        );
        final DensityFunction detailNoise = new NoiseFunction(
            erosionNoise, 0.03125, 0.03125, shiftAll, DensityFunctions.zero(), DensityFunctions.zero()
        );
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 448, 1.5f, -1.5f);
        final DensityFunction distEuclid = new DistanceToPointFunction(new Vec3i(64, 80, -32), DistanceMetric.EUCLIDEAN);
        final CubicSpline<SplineFunction.Coordinate> spline = CubicSpline.builder(new SplineFunction.Coordinate(baseNoise))
            .addPoint(-1.0f, -0.5f, 0.25f)
            .addPoint(0.0f, 0.2f, 0.6f)
            .addPoint(1.0f, 0.9f, 0.1f)
            .build();
        final DensityFunction topSurface = new FindTopSurfaceFunction(
            yGrad.add(baseNoise.mul(DensityFunctions.constant(0.75f))),
            DensityFunctions.constant(256.0f),
            -64,
            8
        );
        final DensityFunction blended = new BlendedNoise(0.25, 0.125, 80.0, 160.0, 8.0);
        final DensityFunction finalDensity = new InterpolatedFunction(
            new ClampFunction(
                new SplineFunction(spline)
                    .add(yGrad)
                    .add(topSurface.mul(DensityFunctions.constant(0.004f)))
                    .add(detailNoise.mul(DensityFunctions.constant(0.2f)))
                    .sub(distEuclid.mul(DensityFunctions.constant(0.001f)).clamp(0.0f, 0.5f))
                    .add(blended.mul(DensityFunctions.constant(0.25f))),
                -2.0f,
                2.0f
            ),
            4,
            8
        );
        final NoiseRouter router = new NoiseRouter(
            baseNoise, detailNoise, baseNoise, detailNoise, yGrad, detailNoise, topSurface, finalDensity
        );
        final RandomState rs = RandomState.create(noiseGetter, 20261127L, false, Blocks.STONE.defaultBlockState(), 63, router);
        final DensityFunction.CompileContext compileCtx = rs.paper$compileContext();

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("bench-terralith-cache"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final var prog = accel.lowerDensityFunctionProgram(
                finalDensity, compileCtx, "Terralith512Bench", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT, true
            );
            assertNotNull(prog);
            assertZeroFp64(prog.sourceCode(), "Terralith512Bench");
            final var compiled = accel.compileProgramBlocking(prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 32;
            final int sizeX = 5, sizeY = 65, sizeZ = 5; // 512-block height coarse grid: 1,625 cells/chunk
            final int volSize = sizeX * sizeY * sizeZ;
            final int[] origins = new int[batchCount * 4];
            for (int b = 0; b < batchCount; b++) {
                origins[b * 4] = (b & 7) * 32;
                origins[b * 4 + 1] = -64;
                origins[b * 4 + 2] = (b >> 3) * 32;
                origins[b * 4 + 3] = 0;
            }
            final float[] gpuOut = new float[batchCount * volSize];

            // Warmup GPU & CPU
            accel.executeDensityVolumeBatch(compiled, origins, batchCount, sizeX, sizeY, sizeZ, 4, 8, 4, gpuOut);
            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(finalDensity);
            {
                final SamplerContext warmCtx = SamplerContext.builder().enableCaches().build();
                final DensityVolume wVol = new DensityVolume(sizeX, sizeY, sizeZ, 0, -64, 0, 4, 8, 4);
                try (ScopedDensityBuffer wBuf = warmCtx.acquireBuffer(wVol)) {
                    mojangSampler.sampleVolume(warmCtx, wBuf, wVol);
                }
            }

            // Timed GPU (batch=32 x 5 iterations)
            final int gpuIters = 5;
            long totalKernelNs = 0L;
            long totalReadbackNs = 0L;
            final long gpuWallT0 = System.nanoTime();
            for (int it = 0; it < gpuIters; it++) {
                final var st = accel.executeDensityVolumeBatch(compiled, origins, batchCount, sizeX, sizeY, sizeZ, 4, 8, 4, gpuOut);
                totalKernelNs += st.gpuKernelNanos();
                totalReadbackNs += st.readbackNanos();
            }
            final double gpuWallMs = (System.nanoTime() - gpuWallT0) / (gpuIters * 1_000_000.0);
            final double gpuChPerSec = batchCount / (gpuWallMs / 1000.0);

            // Timed CPU 1-thread (32 chunks) + parity verification against gpuOut
            final SamplerContext cpu1Ctx = SamplerContext.builder().enableCaches().build();
            int mismatchedSign = 0;
            final long cpu1T0 = System.nanoTime();
            for (int b = 0; b < batchCount; b++) {
                final DensityVolume vol = new DensityVolume(
                    sizeX, sizeY, sizeZ, origins[b * 4], -64, origins[b * 4 + 2], 4, 8, 4
                );
                try (ScopedDensityBuffer cpuBuf = cpu1Ctx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(cpu1Ctx, cpuBuf, vol);
                    final int base = b * volSize;
                    for (int i = 0; i < volSize; i++) {
                        final float cv = cpuBuf.get(i);
                        final float gv = gpuOut[base + i];
                        if ((cv > 0.0f) != (gv > 0.0f) && Math.abs(cv - gv) > 1e-3f) {
                            mismatchedSign++;
                        }
                    }
                }
            }
            final double cpu1Ms = (System.nanoTime() - cpu1T0) / 1_000_000.0;
            final double cpu1ChPerSec = batchCount / (cpu1Ms / 1000.0);

            // Timed CPU 8-thread (32 chunks)
            final int threads = Math.min(8, Runtime.getRuntime().availableProcessors());
            final var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            final double cpu8Ms;
            try {
                final long cpu8T0 = System.nanoTime();
                final List<java.util.concurrent.Future<?>> futs = new ArrayList<>(threads);
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    futs.add(pool.submit(() -> {
                        final SamplerContext tCtx = SamplerContext.builder().enableCaches().build();
                        for (int b = tid; b < batchCount; b += threads) {
                            final DensityVolume vol = new DensityVolume(
                                sizeX, sizeY, sizeZ, origins[b * 4], -64, origins[b * 4 + 2], 4, 8, 4
                            );
                            try (ScopedDensityBuffer buf = tCtx.acquireBuffer(vol)) {
                                mojangSampler.sampleVolume(tCtx, buf, vol);
                            }
                        }
                    }));
                }
                for (final var f : futs) {
                    f.get();
                }
                cpu8Ms = (System.nanoTime() - cpu8T0) / 1_000_000.0;
            } finally {
                pool.shutdownNow();
            }
            final double cpu8ChPerSec = batchCount / (cpu8Ms / 1000.0);

            final double diffPct = 100.0 * mismatchedSign / (double) (batchCount * volSize);
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] BENCH Terralith-512 (21-node AST + FindTopSurface, 5x65x5, b=32): GPU=%.1f ch/s (wall=%.2fms, kern=%.2fms, dma=%.2fms) | CPU-1T=%.1f ch/s (%.1fms, %.1fx) | CPU-%dT=%.1f ch/s (%.1fms, %.1fx) | parity=%.4f%% diff (%d/%d)%n",
                gpuChPerSec, gpuWallMs, totalKernelNs / (gpuIters * 1_000_000.0), totalReadbackNs / (gpuIters * 1_000_000.0),
                cpu1ChPerSec, cpu1Ms, gpuChPerSec / Math.max(cpu1ChPerSec, 0.001),
                threads, cpu8ChPerSec, cpu8Ms, gpuChPerSec / Math.max(cpu8ChPerSec, 0.001),
                diffPct, mismatchedSign, batchCount * volSize
            );
            assertTrue(diffPct <= 0.05, "Terralith-512 benchmark parity diff exceeded 0.05%: " + diffPct);
        }
    }

    @Test
    void mixedCellSizeInterpolatedSectionFillParity(@TempDir final Path tempDir) {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final NoiseRouter dummyRouter = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), DensityFunctions.zero()
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 8675309L, false, Blocks.STONE.defaultBlockState(), 63, dummyRouter
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        final Holder<NormalNoise> n1 = noiseGetter.getOrThrow(Noises.CONTINENTALNESS);
        final Holder<NormalNoise> n2 = noiseGetter.getOrThrow(Noises.EROSION);
        final Holder<NormalNoise> n3 = noiseGetter.getOrThrow(Noises.NOODLE);

        // Exact Terralith pattern: (4, 4) main terrain combined with (4, 8) noodle/blend channels
        // plus an (8, 8) channel to exercise 3D resampling onto the finest (4, 4) common grid.
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.5f, -1.5f);
        final DensityFunction ch4x4 = new InterpolatedFunction(
            DensityFunctions.noise(n1, 1.0, 1.0).add(yGrad),
            4, 4
        );
        final DensityFunction ch4x8 = new InterpolatedFunction(
            DensityFunctions.noise(n2, 0.5, 0.5).mul(DensityFunctions.constant(0.6f)),
            4, 8
        );
        final DensityFunction ch8x8 = new InterpolatedFunction(
            DensityFunctions.noise(n3, 0.25, 0.25).mul(DensityFunctions.constant(0.4f)),
            8, 8
        );
        final DensityFunction root = ch4x4.add(ch4x8).add(ch8x8);

        // 1. Split lowering must succeed on the finest (4, 4) common grid
        final DensityFunctionIrLowerer.SplitLoweringResult split = DensityFunctionIrLowerer.lowerSplitForSectionFill(
            root, compileCtx, "MixedCellTerralith"
        );
        assertTrue(split.isSupported(), "Mixed (4,4)+(4,8)+(8,8) InterpolatedFunction must lower: " + split.unsupportedReason());
        assertNotNull(split.coarseSpec(), "Mixed-cell split lowering must produce a CoarseInterpSpec");
        assertEquals(4, split.coarseSpec().cellSizeXz());
        assertEquals(4, split.coarseSpec().cellSizeY());
        assertEquals(3, split.coarseSpec().coarseFunctionNames().size());

        // 2. Live GPU section fill parity against Mojang CPU across positive and negative coordinates
        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("mixed-cell-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = accel.lowerSectionPaletteFillProgram(
                root, compileCtx, "MixedCellTerralith", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT, false
            );
            assertNotNull(prog, "Section palette fill program must lower for mixed-cell InterpolatedFunction");
            assertZeroFp64(prog.sourceCode(), "MixedCellTerralith");
            if (!accel.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = accel.compileProgramBlocking(
                prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel());

            final int batchCount = 2;
            final int sectionCount = 8; // 128 blocks tall (-64..64)
            final int minBlockY = -64;
            final int seaLevel = 63;
            final int deepFluidY = -54;
            final int[] origins = {
                0, minBlockY, 0, 0,
                -32, minBlockY, -48, 0
            };
            final CompactSectionWritebackBuffer dmaBuf = new CompactSectionWritebackBuffer(batchCount * sectionCount);
            accel.executeSectionPaletteFillBatch(compiled, origins, batchCount, sectionCount, seaLevel, deepFluidY, dmaBuf);

            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler mojangSampler = mojangCompiler.getSampler(root);
            final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
            final Integer[] palette4 = {0, 1, 2, 3};

            int mismatched = 0;
            final int totalVoxels = batchCount * sectionCount * 4096;
            for (int b = 0; b < batchCount; b++) {
                final int ox = origins[b * 4];
                final int oz = origins[b * 4 + 2];
                final int[][] gpuSections = new int[sectionCount][4096];
                CompactSectionWritebackApplier.applySections(
                    dmaBuf,
                    b,
                    sectionCount,
                    minBlockY,
                    offset -> gpuSections[offset],
                    palette4,
                    (sec, lx, ly, lz, blockY, code) -> sec[(ly << 8) | (lz << 4) | lx] = code,
                    (lx, blockY, lz, code) -> {}
                );

                final DensityVolume vol = new DensityVolume(16, sectionCount * 16, 16, ox, minBlockY, oz, 1, 1, 1);
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    mojangSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    for (int s = 0; s < sectionCount; s++) {
                        final int[] sec = gpuSections[s];
                        for (int ly = 0; ly < 16; ly++) {
                            final int by = minBlockY + (s << 4) + ly;
                            final int gy = (s << 4) + ly;
                            for (int lz = 0; lz < 16; lz++) {
                                for (int lx = 0; lx < 16; lx++) {
                                    final float d = cpuBuf.get(vol.indexUnchecked(lx, gy, lz));
                                    final int expected = d > 0.0f ? 1 : (by < deepFluidY ? 3 : (by < seaLevel ? 2 : 0));
                                    final int actual = sec[(ly << 8) | (lz << 4) | lx];
                                    if (actual != expected && Math.abs(d) > 1e-3f) {
                                        mismatched++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            final double diffPct = 100.0 * mismatched / (double) totalVoxels;
            assertTrue(diffPct <= 0.05, "Mixed-cell section fill parity diff exceeded 0.05%: " + diffPct + "% (" + mismatched + "/" + totalVoxels + ")");
        }
    }

    @Test
    void multiNoiseDatapackSurfaceStaysCompactAndNonInline() {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 192, 1.5f, -1.5f);
        final DensityFunction finalDensity = new InterpolatedFunction(
            DensityFunctions.noise(noiseGetter.getOrThrow(Noises.CONTINENTALNESS), 1.0, 1.0).add(yGrad),
            4, 4
        ).add(new InterpolatedFunction(
            DensityFunctions.noise(noiseGetter.getOrThrow(Noises.NOODLE), 0.5, 0.5).mul(DensityFunctions.constant(0.25f)),
            4, 8
        ));
        final NoiseRouter router = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.constant(64.0f), finalDensity
        );

        // Exercise 16 distinct noises in MaterialRule (representative of Terralith-scale multi-noise surface rules)
        final List<ResourceKey<NormalNoise>> surfaceNoises = List.of(
            Noises.GRAVEL, Noises.PACKED_ICE, Noises.ICE, Noises.POWDER_SNOW,
            Noises.CALCITE, Noises.CLAY_BANDS_OFFSET, Noises.BADLANDS_SURFACE, Noises.BADLANDS_PILLAR,
            Noises.BADLANDS_PILLAR_ROOF, Noises.ICEBERG_SURFACE, Noises.ICEBERG_PILLAR, Noises.ICEBERG_PILLAR_ROOF,
            Noises.SURFACE_SECONDARY, Noises.SOUL_SAND_LAYER, Noises.GRAVEL_LAYER, Noises.PATCH
        );
        final List<MaterialRule> rules = new ArrayList<>(surfaceNoises.size() + 1);
        for (final ResourceKey<NormalNoise> key : surfaceNoises) {
            rules.add(new ConditionRule(
                new NoiseThresholdCondition(key, 0.1, 0.9, false),
                new BlockRule(Blocks.RED_SANDSTONE.defaultBlockState())
            ));
        }
        rules.add(new BlockRule(Blocks.GRASS_BLOCK.defaultBlockState()));

        final NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
            NoiseSettings.create(-64, 384),
            Blocks.STONE.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            router,
            Holder.direct(new SequenceRule(rules)),
            List.of(),
            63,
            false,
            Optional.empty(),
            false,
            NoiseGeneratorSettings.DebugFunctions.EMPTY
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 8675309L, false, Blocks.STONE.defaultBlockState(), 63, router
        );
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final GpuVanillaSurfaceCompiler.CompiledVanillaSurfaceProgram compiled = GpuVanillaSurfaceCompiler.compile(
            settings,
            randomState,
            makeWgContext(-64, 384, 63),
            false,
            false,
            false,
            emitter,
            ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32,
            "multi_noise_compact"
        );
        assertNotNull(compiled, "Multi-noise datapack surface must compile cleanly");
        final String surfSrc = compiled.surfaceProgram().sourceCode();
        assertZeroFp64(surfSrc, "multi_noise_compact_surface");
        final int auxMarker = surfSrc.indexOf("// === Auxiliary MaterialRule Noise Helpers ===");
        assertTrue(auxMarker >= 0, "Auxiliary helpers section must be present");
        final int xorMarker = surfSrc.indexOf("static inline ulong gl_rotl64(", auxMarker);
        assertTrue(xorMarker > auxMarker, "Xoroshiro helpers must follow auxiliary noise helpers");
        final String auxSection = surfSrc.substring(auxMarker, xorMarker);
        assertFalse(
            Pattern.compile("(?m)^(?:static\\s+)?inline\\s+").matcher(auxSection).find(),
            "Auxiliary MaterialRule noise helpers must be emitted as non-inline static functions to avoid LLVM compile blowup"
        );
        assertTrue(
            surfSrc.length() < 180_000,
            "16-noise surface kernel must stay ultra-compact (< 180 KB) with shared gl_perlin_3d + lazy 2D cache, got " + surfSrc.length()
        );
        final long perlinDefs = surfSrc.lines()
            .filter(l -> l.contains("gl_perlin_3d(") && l.trim().endsWith("{"))
            .count();
        assertEquals(1L, perlinDefs, "16-noise surface kernel must emit exactly ONE shared gl_perlin_3d helper");
        assertTrue(surfSrc.contains("static float gl_get_n2d("), "16-noise surface kernel must emit lazy gl_get_n2d helper");
        assertTrue(surfSrc.contains("short n2d_tag[8]"), "N>8 surface noises must use bounded 8-slot direct-mapped micro-cache");
        assertFalse(surfSrc.contains("n2d_cache[15]"), "Surface kernel must NOT eagerly pre-evaluate all 16 2D noises per column");
    }

    @Test
    void findTopSurface2dLicmHoists2dNoiseOutOfLoopAndMatchesCpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction shiftX = new ShiftNoiseFunction.ShiftA(noiseGetter.getOrThrow(Noises.SHIFT));
        final DensityFunction shiftZ = new ShiftNoiseFunction.ShiftB(noiseGetter.getOrThrow(Noises.SHIFT));
        final DensityFunction shared2d = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.CONTINENTALNESS)
        );
        final DensityFunction upperBound = shared2d.mul(DensityFunctions.constant(32.0f)).add(DensityFunctions.constant(96.0f));
        final DensityFunction yGrad = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 160, 1.0f, -1.0f);
        final DensityFunction density = shared2d.mul(DensityFunctions.constant(0.6f)).add(yGrad);
        final DensityFunction findTop = new FindTopSurfaceFunction(density, upperBound, -64, 8);

        final NoiseRouter router = new NoiseRouter(
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            DensityFunctions.zero(), DensityFunctions.zero(), DensityFunctions.zero(),
            findTop, density
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 424242L, false, Blocks.STONE.defaultBlockState(), 63, router
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        final DensityFunctionIrLowerer.LoweringResult lowered = DensityFunctionIrLowerer.lower(
            findTop, compileCtx, "fts_licm_test", true
        );
        assertTrue(lowered.isSupported() && lowered.module() != null, "FindTopSurface LICM must lower cleanly");
        assertTrue(lowered.deduplicatedNodes() > 0, "Expected shared 2D nodes between upperBound and density to deduplicate");

        final ConceptBackendEmitter.EmitOptions emitOptions = ConceptBackendEmitter.EmitOptions.of(
            ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32,
            ConceptBackendEmitter.BatchLayout.DENSITY_VOLUME_BATCH_F32
        );
        final io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.OptimizationConfig nonInline =
            new io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.OptimizationConfig(false, true, true, false, true);
        final io.github.dailystruggle.bytecode.transpile.BytecodeConceptIr.ConceptModule opt =
            io.github.dailystruggle.bytecode.transpile.ConceptOptimizerPass.optimize(lowered.module(), nonInline);
        final OpenClBackendEmitter.DensityVolumeProgram prog = new OpenClBackendEmitter().emitDensityVolumeBatchProgram(opt, emitOptions);
        final String clSrc = prog.sourceCode();
        assertZeroFp64(clSrc, "fts_licm_test");

        // Verify the while loop body does NOT call gl_perlin_3d (2D noise was hoisted out of the py-loop!)
        final int whileIdx = clSrc.indexOf("while (");
        assertTrue(whileIdx > 0, "FindTopSurface must emit inline while loop:\n" + clSrc);
        final int kernelIdx = clSrc.indexOf("__kernel void ", whileIdx);
        final String loopTail = clSrc.substring(whileIdx, kernelIdx > whileIdx ? kernelIdx : clSrc.length());
        assertFalse(
            loopTail.contains("gl_perlin_3d("),
            "2D Perlin noise must be hoisted out of FindTopSurface while loop! Loop section:\n" + loopTail
        );

        // Live GPU vs CPU parity verification
        final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
        final DensitySampler cpuSampler = mojangCompiler.getSampler(findTop);
        final SamplerContext samplerCtx = SamplerContext.builder().enableCaches().build();
        try (IrOpenClTerrainAccelerator acc = new IrOpenClTerrainAccelerator(tempDir.resolve("fts-licm-cache"))) {
            if (!acc.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = acc.compileProgramBlocking(
                prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel(), "FindTopSurface LICM kernel must compile on live GPU");
            final int batchCount = 2;
            final int sizeX = 16, sizeY = 1, sizeZ = 16;
            final int volSize = sizeX * sizeY * sizeZ;
            final int[] origins = new int[] { -128, 0, -64, 0, 256, 0, 512, 0 };
            final float[] gpuOut = new float[batchCount * volSize];
            acc.executeDensityVolumeBatch(compiled, origins, batchCount, sizeX, sizeY, sizeZ, 4, 1, 4, gpuOut);
            for (int b = 0; b < batchCount; b++) {
                final DensityVolume vol = new DensityVolume(
                    sizeX, sizeY, sizeZ, origins[b * 4], origins[b * 4 + 1], origins[b * 4 + 2], 4, 1, 4
                );
                try (ScopedDensityBuffer cpuBuf = samplerCtx.acquireBuffer(vol)) {
                    cpuSampler.sampleVolume(samplerCtx, cpuBuf, vol);
                    final int base = b * volSize;
                    for (int i = 0; i < volSize; i++) {
                        assertEquals(cpuBuf.get(i), gpuOut[base + i], 0.01f,
                            "FindTopSurface LICM parity mismatch at batch=" + b + " idx=" + i);
                    }
                }
            }
        }
    }

    @Test
    void sharedDagBiomeClimate6DeduplicatesSharedSubDagsAndMatchesCpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction shiftX = new ShiftNoiseFunction.ShiftA(noiseGetter.getOrThrow(Noises.SHIFT));
        final DensityFunction shiftZ = new ShiftNoiseFunction.ShiftB(noiseGetter.getOrThrow(Noises.SHIFT));
        final DensityFunction continents = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.CONTINENTALNESS)
        );
        final DensityFunction erosion = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.EROSION)
        );
        final DensityFunction temperature = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.TEMPERATURE)
        );
        final DensityFunction vegetation = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.VEGETATION)
        );
        final DensityFunction depth = continents.mul(DensityFunctions.constant(0.5f))
            .add(erosion.mul(DensityFunctions.constant(-0.25f)))
            .add(new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 320, 1.5f, -1.5f));
        final DensityFunction ridges = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.RIDGE)
        );
        final List<DensityFunction> climate6 = List.of(temperature, vegetation, continents, erosion, depth, ridges);
        final NoiseRouter router = new NoiseRouter(
            temperature, vegetation, continents, erosion, depth, ridges,
            DensityFunctions.constant(64.0f), depth
        );
        final RandomState randomState = RandomState.create(
            noiseGetter, 777777L, false, Blocks.STONE.defaultBlockState(), 63, router
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        final DensityFunctionIrLowerer.LoweringResult sharedLowered = DensityFunctionIrLowerer.lowerSharedBiomeClimate6(
            climate6, compileCtx, "shared_climate6_test", true
        );
        assertTrue(sharedLowered.isSupported(), "Shared 6-channel climate lowering must succeed");
        assertTrue(
            sharedLowered.deduplicatedNodes() >= 10,
            "Expected shiftX/shiftZ/continents/erosion to deduplicate across 6 channels, got " + sharedLowered.deduplicatedNodes()
        );

        try (IrOpenClTerrainAccelerator acc = new IrOpenClTerrainAccelerator(tempDir.resolve("shared-climate6-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram prog = acc.lowerFusedBiomeClimateProgram(
                climate6, compileCtx, "shared_climate6_", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertNotNull(prog, "Shared climate6 OpenCL program must emit cleanly");
            assertZeroFp64(prog.sourceCode(), "shared_climate6_test");
            assertTrue(prog.sourceCode().contains("static void gl_eval_biome_climate6("));
            final long perlinDefs = prog.sourceCode().lines()
                .filter(l -> l.contains("gl_perlin_3d(") && l.trim().endsWith("{"))
                .count();
            assertEquals(1L, perlinDefs, "Shared climate6 kernel must emit exactly 1 gl_perlin_3d helper");

            if (!acc.isGpuReady()) {
                return;
            }
            final IrOpenClTerrainAccelerator.CompiledDensityKernel compiled = acc.compileProgramBlocking(
                prog, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertTrue(compiled.hasLiveGpuKernel(), "Shared climate6 kernel must compile on live GPU");
            final DensityFunctionCompiler mojangCompiler = new DensityFunctionCompiler(compileCtx);
            final DensitySampler[] cpuSamplers = new DensitySampler[6];
            for (int c = 0; c < 6; c++) {
                cpuSamplers[c] = mojangCompiler.getSampler(climate6.get(c));
            }
            final SamplerContext cpuCtx = SamplerContext.builder().enableCaches().build();
            final int sizeX = 4, sizeY = 8, sizeZ = 4;
            final int volSize = sizeX * sizeY * sizeZ;
            final int[] origins = new int[] { 0, -64, 0, 0 };
            final float[] gpuOut = new float[volSize * 6];
            acc.executeBiomeClimate6Batch(compiled, origins, 1, sizeX, sizeY, sizeZ, 4, 16, 4, gpuOut);
            final DensityVolume vol = new DensityVolume(sizeX, sizeY, sizeZ, 0, -64, 0, 4, 16, 4);
            try (
                ScopedDensityBuffer b0 = cpuCtx.acquireBuffer(vol);
                ScopedDensityBuffer b1 = cpuCtx.acquireBuffer(vol);
                ScopedDensityBuffer b2 = cpuCtx.acquireBuffer(vol);
                ScopedDensityBuffer b3 = cpuCtx.acquireBuffer(vol);
                ScopedDensityBuffer b4 = cpuCtx.acquireBuffer(vol);
                ScopedDensityBuffer b5 = cpuCtx.acquireBuffer(vol)
            ) {
                final ScopedDensityBuffer[] bufs = { b0, b1, b2, b3, b4, b5 };
                for (int c = 0; c < 6; c++) {
                    cpuSamplers[c].sampleVolume(cpuCtx, bufs[c], vol);
                }
                for (int i = 0; i < volSize; i++) {
                    for (int c = 0; c < 6; c++) {
                        assertEquals(bufs[c].get(i), gpuOut[i * 6 + c], 1e-4f,
                            "Shared climate6 channel " + c + " parity mismatch at idx=" + i);
                    }
                }
            }
        }
    }

    @Test
    void nonUniformPerBiomeCarverMaskMatchesCpuParityAndFailsClosed(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final DensityFunction shiftX = new ShiftNoiseFunction.ShiftA(noiseGetter.getOrThrow(Noises.SHIFT));
        final DensityFunction shiftZ = new ShiftNoiseFunction.ShiftB(noiseGetter.getOrThrow(Noises.SHIFT));
        // Use high-frequency noise so every test chunk's 17x17 neighborhood exercises all 3 biome carver lists
        final DensityFunction temperature = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.73, noiseGetter.getOrThrow(Noises.RIDGE)
        );
        final DensityFunction vegetation = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.VEGETATION)
        );
        final DensityFunction continents = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.CONTINENTALNESS)
        );
        final DensityFunction erosion = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.EROSION)
        );
        final DensityFunction depth = new GradientFunction(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -64, 320, 1.5f, -1.5f);
        final DensityFunction ridges = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseGetter.getOrThrow(Noises.RIDGE)
        );
        final List<DensityFunction> climate6 = List.of(temperature, vegetation, continents, erosion, depth, ridges);
        final NoiseRouter router = new NoiseRouter(
            temperature, vegetation, continents, erosion, depth, ridges,
            DensityFunctions.constant(64.0f), depth
        );
        final long worldSeed = 918273645L;
        final RandomState randomState = RandomState.create(
            noiseGetter, worldSeed, false, Blocks.STONE.defaultBlockState(), 63, router
        );
        final DensityFunction.CompileContext compileCtx = randomState.paper$compileContext();

        // 3 biomes with distinct carver lists: [cave, canyon], [canyon] (shifted slot=0!), and [] (carverless)
        final Holder.Reference<Biome> biomeCaveAndCanyon = makeKeyedBiome("perbiome_both", 0.5f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> biomeCanyonOnly = makeKeyedBiome("perbiome_canyon", 0.7f, Biome.TemperatureModifier.NONE);
        final Holder.Reference<Biome> biomeNoCarvers = makeKeyedBiome("perbiome_empty", 0.1f, Biome.TemperatureModifier.NONE);
        final var paramList = new Climate.ParameterList<>(List.of(
            com.mojang.datafixers.util.Pair.of(
                Climate.parameters(
                    Climate.Parameter.span(-1.0f, -0.1f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f), 0.0f
                ),
                (Holder<Biome>) biomeCaveAndCanyon
            ),
            com.mojang.datafixers.util.Pair.of(
                Climate.parameters(
                    Climate.Parameter.span(-0.1f, 0.2f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f), 0.0f
                ),
                (Holder<Biome>) biomeCanyonOnly
            ),
            com.mojang.datafixers.util.Pair.of(
                Climate.parameters(
                    Climate.Parameter.span(0.2f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f),
                    Climate.Parameter.span(-1.0f, 1.0f), Climate.Parameter.span(-1.0f, 1.0f), 0.0f
                ),
                (Holder<Biome>) biomeNoCarvers
            )
        ));
        final net.minecraft.world.level.biome.MultiNoiseBiomeSource biomeSource =
            net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromList(paramList);

        final net.minecraft.world.level.levelgen.carver.WorldCarver caveCarver =
            new net.minecraft.world.level.levelgen.carver.CaveWorldCarver(
                0.15F,
                net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(
                    VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(180)
                ),
                net.minecraft.util.valueproviders.VeryBiasedToBottomInt.of(0, 15),
                net.minecraft.util.valueproviders.UniformFloat.of(0.1F, 0.9F),
                false,
                net.minecraft.util.valueproviders.ConstantFloat.of(1.0F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.7F, 1.4F),
                net.minecraft.util.valueproviders.UniformFloat.of(0.8F, 1.3F),
                net.minecraft.util.valueproviders.ConstantFloat.of(1.0F),
                net.minecraft.util.valueproviders.UniformFloat.of(-1.0F, -0.4F)
            );
        final net.minecraft.world.level.levelgen.carver.WorldCarver canyonCarver =
            new net.minecraft.world.level.levelgen.carver.CanyonWorldCarver(
                0.02F,
                net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(
                    VerticalAnchor.absolute(10), VerticalAnchor.absolute(67)
                ),
                net.minecraft.util.valueproviders.UniformFloat.of(-0.125F, 0.125F),
                new net.minecraft.world.level.levelgen.carver.CanyonWorldCarver.Shape(
                    net.minecraft.util.valueproviders.UniformFloat.of(0.75F, 1.25F),
                    net.minecraft.util.valueproviders.TrapezoidFloat.of(0.0F, 6.0F, 2.0F),
                    3,
                    net.minecraft.util.valueproviders.UniformFloat.of(0.75F, 1.0F),
                    1.0F,
                    0.0F,
                    net.minecraft.util.valueproviders.ClampedNormalFloat.of(3.5F, 2.5F, 2.5F, 13.5F)
                )
            );

        final java.util.Map<Holder<Biome>, List<Holder<net.minecraft.world.level.levelgen.carver.WorldCarver>>> biomeCarvers =
            new java.util.IdentityHashMap<>();
        biomeCarvers.put(biomeCaveAndCanyon, List.of(Holder.direct(caveCarver), Holder.direct(canyonCarver)));
        biomeCarvers.put(biomeCanyonOnly, List.of(Holder.direct(canyonCarver)));
        biomeCarvers.put(biomeNoCarvers, List.of());

        final GpuBiomeRTree.FlattenedRTree flattened = GpuBiomeRTree.flatten(biomeSource);
        assertNotNull(flattened);
        try (IrOpenClTerrainAccelerator acc = new IrOpenClTerrainAccelerator(tempDir.resolve("perbiome-carver-cache"))) {
            final OpenClBackendEmitter.DensityVolumeProgram climate6Prog = acc.lowerFusedBiomeClimateProgram(
                climate6, compileCtx, "perbiome_cm_", TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT
            );
            assertNotNull(climate6Prog);
            final GpuBiomeRTree.FusedBiomeRTreeProgram fused = GpuBiomeRTree.buildFusedProgram(climate6Prog, flattened);
            assertNotNull(fused);

            final int minY = -64;
            final int genDepth = 384;
            final int minCarveY = minY + 1;
            final int maxCarveY = minY + genDepth - 1 - 7;
            final int wordsPerCol = (genDepth + 31) >>> 5;
            final WorldGenerationContext wgCtx = makeWgContext(minY, genDepth, 63);

            // Fail-closed S-004 check: custom unsupported carver inside any biome must return null
            final net.minecraft.world.level.levelgen.carver.WorldCarver unsupportedCarver =
                new net.minecraft.world.level.levelgen.carver.WorldCarver() {
                    @Override public com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.levelgen.carver.WorldCarver> codec() { return null; }
                    @Override public boolean isStartChunk(final net.minecraft.util.RandomSource r) { return false; }
                    @Override public boolean carve(final WorldGenerationContext c, final net.minecraft.util.RandomSource r, final net.minecraft.world.level.ChunkPos p, final net.minecraft.world.level.ChunkPos s, final net.minecraft.world.level.chunk.CarverOutput m) { return false; }
                };
            assertNull(
                GpuCarverMaskCompiler.compilePerBiome(
                    fused,
                    b -> b == biomeNoCarvers ? List.of(Holder.direct(unsupportedCarver)) : biomeCarvers.getOrDefault(b, List.of()),
                    worldSeed,
                    wgCtx,
                    "perbiome_unsup"
                ),
                "Unsupported carver in any biome must fail closed (S-004)"
            );

            final OpenClBackendEmitter.DensityVolumeProgram prog = GpuCarverMaskCompiler.compilePerBiome(
                fused,
                b -> biomeCarvers.getOrDefault(b, List.of()),
                worldSeed,
                wgCtx,
                "perbiome_cm_test"
            );
            assertNotNull(prog, "Per-biome carver mask must compile cleanly");
            assertZeroFp64(prog.sourceCode(), "perbiome_cm_test");
            assertTrue(prog.sourceCode().contains("gl_eval_biome_climate6("));
            assertTrue(prog.sourceCode().contains("gl_rtree_lookup_biome("));
            assertTrue(prog.sourceCode().contains("CM_SLOT_BIOME_CIDX["));
            assertTrue(prog.sourceCode().contains("if (bid < 0)"));

            if (!acc.isGpuReady()) {
                return;
            }
            final int[][] coords = {
                {0, 0}, {3, -2}, {-5, 4}, {12, 9}, {-16, -11}, {25, -18}, {-32, 20}, {40, 40}
            };
            final int batchCount = coords.length;
            final int[] origins = new int[batchCount * 4];
            for (int i = 0; i < batchCount; i++) {
                origins[i * 4] = coords[i][0] << 4;
                origins[i * 4 + 1] = minY;
                origins[i * 4 + 2] = coords[i][1] << 4;
            }
            final int[] gpuMeta = new int[batchCount];
            final int[] gpuMasks = new int[batchCount * 256 * wordsPerCol];
            try (GoldLeafOpenClBridge.CompiledKernel kernel = GoldLeafOpenClBridge.compileFromSource(
                0, prog.sourceCode(), prog.kernelName(), "-cl-std=CL1.2", prog.spilledLutWords()
            )) {
                final int err = kernel.dispatchCarverMaskBatch(
                    origins, batchCount, wordsPerCol, minY, minCarveY, maxCarveY, gpuMeta, gpuMasks
                );
                assertEquals(0, err, "Per-biome dispatchCarverMaskBatch failed");
            }

            final var biomeResolver = biomeSource.createUncachedResolver(randomState);
            // Match vanilla NoiseBasedChunkGenerator: 1 WorldgenRandom per chunk (note: check both reset & vanilla)
            long mismatchesReset = 0L;
            long totalCpuCarvedReset = 0L;
            long totalVoxels = 0L;
            long totalCpuCarved = 0L;
            long mismatches = 0L;
            final java.util.Set<Holder<Biome>> seenBiomes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int b = 0; b < batchCount; b++) {
                final net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(coords[b][0], coords[b][1]);
                final net.minecraft.world.level.levelgen.WorldgenRandom cpuRand =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(0L)
                    );
                final net.minecraft.world.level.chunk.CarvingMask cpuMask =
                    new net.minecraft.world.level.chunk.CarvingMask(minCarveY, maxCarveY);
                final net.minecraft.world.level.chunk.CarvingMask cpuMaskReset =
                    new net.minecraft.world.level.chunk.CarvingMask(minCarveY, maxCarveY);
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        final net.minecraft.world.level.ChunkPos srcPos = new net.minecraft.world.level.ChunkPos(pos.x() + dx, pos.z() + dz);
                        final Holder<Biome> srcBiome = biomeResolver.getNoiseBiome(
                            net.minecraft.core.QuartPos.fromBlock(srcPos.getMinBlockX()),
                            0,
                            net.minecraft.core.QuartPos.fromBlock(srcPos.getMinBlockZ())
                        );
                        seenBiomes.add(srcBiome);
                        final List<Holder<net.minecraft.world.level.levelgen.carver.WorldCarver>> list =
                            biomeCarvers.getOrDefault(srcBiome, List.of());
                        for (int slot = 0; slot < list.size(); slot++) {
                            final var carver = list.get(slot).value();
                            cpuRand.setLargeFeatureSeed(worldSeed + slot, srcPos.x(), srcPos.z());
                            if (carver.isStartChunk(cpuRand)) {
                                carver.carve(wgCtx, cpuRand, pos, srcPos, cpuMask);
                            }
                            final var freshRand = new net.minecraft.world.level.levelgen.WorldgenRandom(
                                new net.minecraft.world.level.levelgen.LegacyRandomSource(0L)
                            );
                            freshRand.setLargeFeatureSeed(worldSeed + slot, srcPos.x(), srcPos.z());
                            if (carver.isStartChunk(freshRand)) {
                                carver.carve(wgCtx, freshRand, pos, srcPos, cpuMaskReset);
                            }
                        }
                    }
                }
                final int[] cpuWords = cpuMask.isEmpty()
                    ? new int[256 * wordsPerCol]
                    : cpuMask.goldleaf$packColumnMasks(minY, wordsPerCol);
                final int[] cpuWordsReset = cpuMaskReset.isEmpty()
                    ? new int[256 * wordsPerCol]
                    : cpuMaskReset.goldleaf$packColumnMasks(minY, wordsPerCol);
                final int gpuBase = b * 256 * wordsPerCol;
                for (int w = 0; w < 256 * wordsPerCol; w++) {
                    final int cw = cpuWords[w];
                    final int cwr = cpuWordsReset[w];
                    final int gw = gpuMeta[b] >= 0 ? gpuMasks[gpuBase + w] : 0;
                    totalVoxels += 32L;
                    totalCpuCarved += Integer.bitCount(cw);
                    totalCpuCarvedReset += Integer.bitCount(cwr);
                    mismatches += Integer.bitCount(cw ^ gw);
                    mismatchesReset += Integer.bitCount(cwr ^ gw);
                }
            }
            final var diagSampler = randomState.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
            float minT = Float.POSITIVE_INFINITY, maxT = Float.NEGATIVE_INFINITY;
            for (int b = 0; b < batchCount; b++) {
                final float tv = diagSampler.temperature().sampleValue(coords[b][0] << 4, 0, coords[b][1] << 4);
                minT = Math.min(minT, tv);
                maxT = Math.max(maxT, tv);
            }
            assertEquals(3, seenBiomes.size(),
                "Test chunks must exercise all 3 distinct biome carver lists; seen=" + seenBiomes
                    + " minT=" + minT + " maxT=" + maxT + " totalCpuCarved=" + totalCpuCarved);
            assertTrue(totalCpuCarved > 200, "Per-biome test chunks must contain carved caves/canyons, got " + totalCpuCarved);
            final double matchPct = 100.0 * (1.0 - (double) mismatches / (double) totalVoxels);
            final double matchResetPct = 100.0 * (1.0 - (double) mismatchesReset / (double) totalVoxels);
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU Per-Biome Carver Mask parity: vanilla=%.4f%% (mismatches=%d, carved=%d) vs resetGaussian=%.4f%% (mismatches=%d, carved=%d) of %d%n",
                matchPct, mismatches, totalCpuCarved, matchResetPct, mismatchesReset, totalCpuCarvedReset, totalVoxels
            );
            assertTrue(
                matchPct >= 99.95,
                "Per-biome GPU carver mask parity must be >= 99.95% (got " + String.format(Locale.ROOT, "%.4f%%", matchPct) + ")"
            );
        }
    }

    private static net.minecraft.world.level.chunk.ProtoChunk createEmptyProtoChunk(
        final net.minecraft.world.level.ChunkPos pos,
        final int minY,
        final int height
    ) {
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(minY, height);
        final int sectionCount = height >> 4;
        final net.minecraft.world.level.chunk.LevelChunkSection[] sections =
            new net.minecraft.world.level.chunk.LevelChunkSection[sectionCount];
        final BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < sectionCount; i++) {
            sections[i] = new net.minecraft.world.level.chunk.LevelChunkSection(
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                    air,
                    net.minecraft.world.level.chunk.Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)
                ),
                null
            );
        }
        final net.minecraft.world.level.chunk.ProtoChunk chunk = new net.minecraft.world.level.chunk.ProtoChunk(
            pos,
            net.minecraft.world.level.chunk.UpgradeData.EMPTY,
            sections,
            new net.minecraft.world.ticks.ProtoChunkTicks<>(),
            new net.minecraft.world.ticks.ProtoChunkTicks<>(),
            heightAccessor,
            null,
            null
        );
        chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        return chunk;
    }

    private static void runCpuTerrainAndSurface(
        final net.minecraft.world.level.chunk.ProtoChunk chunk,
        final NoiseGeneratorSettings settings,
        final RandomState randomState,
        final WorldGenerationContext wgCtx,
        final net.minecraft.world.level.biome.BiomeManager biomeManager,
        final List<net.minecraft.world.level.levelgen.carver.WorldCarver> carvers,
        final long worldSeed
    ) {
        final net.minecraft.world.level.ChunkPos pos = chunk.getPos();
        final int minY = wgCtx.getMinGenY();
        final int height = wgCtx.getGenDepth();
        final int seaLevel = settings.seaLevel();
        final BlockState defaultBlock = settings.defaultBlock();
        final BlockState air = Blocks.AIR.defaultBlockState();
        final var lavaStatus = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        final var seaStatus = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        final net.minecraft.world.level.levelgen.Aquifer.FluidPicker fluidPicker =
            (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaStatus : seaStatus;

        final DensityVolume volume = new DensityVolume(16, height, 16, pos.getMinBlockX(), minY, pos.getMinBlockZ());
        try (net.minecraft.world.level.levelgen.NoiseChunk noiseChunk = new net.minecraft.world.level.levelgen.NoiseChunk(
            randomState,
            null,
            settings,
            fluidPicker,
            net.minecraft.world.level.levelgen.blending.Blender.empty(),
            volume
        )) {
            final var oceanFloor = chunk.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG);
            final var worldSurface = chunk.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG);
            final var aquifer = noiseChunk.aquifer();
            final DensitySampler.Bound finalDensity = noiseChunk.cachingSamplers().get(settings.noiseRouter().finalDensity());
            try (ScopedDensityBuffer buf = finalDensity.sampleVolume(volume)) {
                final int minSecY = chunk.getMinSectionY();
                for (int z = 0; z < 16; z++) {
                    final int bx = pos.getMinBlockX();
                    final int bz = pos.getMinBlockZ() + z;
                    for (int x = 0; x < 16; x++) {
                        final int wx = bx + x;
                        for (int y = height - 1; y >= 0; y--) {
                            final int wy = minY + y;
                            final double d = buf.get(volume.indexUnchecked(x, y, z));
                            BlockState st = d > 0.0 ? defaultBlock : aquifer.computeSubstance(wx, wy, bz, d);
                            if (st == null) {
                                st = defaultBlock;
                            }
                            if (!st.isAir()) {
                                chunk.getSection((wy >> 4) - minSecY).setBlockState(x, wy & 15, z, st, false);
                                oceanFloor.update(x, wy, z, st);
                                worldSurface.update(x, wy, z, st);
                            }
                        }
                    }
                }
            }

            randomState.surfaceSystem().buildSurface(
                randomState,
                biomeManager,
                wgCtx,
                chunk,
                noiseChunk,
                settings.materialRule().value(),
                null
            );

            if (!carvers.isEmpty()) {
                final int minCarveY = minY + 1;
                final int maxCarveY = minY + height - 1 - 7;
                final net.minecraft.world.level.chunk.CarvingMask mask =
                    new net.minecraft.world.level.chunk.CarvingMask(minCarveY, maxCarveY);
                final net.minecraft.world.level.levelgen.WorldgenRandom rand =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(
                        new net.minecraft.world.level.levelgen.LegacyRandomSource(0L)
                    );
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        final net.minecraft.world.level.ChunkPos srcPos = new net.minecraft.world.level.ChunkPos(pos.x() + dx, pos.z() + dz);
                        for (int ci = 0; ci < carvers.size(); ci++) {
                            final var carver = carvers.get(ci);
                            rand.setLargeFeatureSeed(worldSeed + ci, srcPos.x(), srcPos.z());
                            if (carver.isStartChunk(rand)) {
                                carver.carve(wgCtx, rand, pos, srcPos, mask);
                            }
                        }
                    }
                }
                if (!mask.isEmpty()) {
                    final int minSecY = chunk.getMinSectionY();
                    mask.visit((lx, lz, bottomY, topY) -> {
                        final int wx = pos.getBlockX(lx);
                        final int wz = pos.getBlockZ(lz);
                        for (int wy = topY; wy >= bottomY; wy--) {
                            final var sec = chunk.getSection((wy >> 4) - minSecY);
                            final BlockState old = sec.getBlockState(lx, wy & 15, lz);
                            if (old.isAir() && wy >= seaLevel) {
                                continue;
                            }
                            if (old.is(net.minecraft.tags.BlockTags.UNCARVABLE)) {
                                continue;
                            }
                            final BlockState carved = aquifer.computeSubstance(wx, wy, wz, 0.0);
                            if (carved != null && carved != old) {
                                sec.setBlockState(lx, wy & 15, lz, carved, false);
                                oceanFloor.update(lx, wy, lz, carved);
                                worldSurface.update(lx, wy, lz, carved);
                            }
                        }
                    });
                }
            }
        }
    }

    @Test
    void vanillaNetherLegacyRngSurfaceAndNonAquiferCarverLiveGpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final var noiseSettingsLookup = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        final var biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        final var carverLookup = registries.lookupOrThrow(Registries.CARVER);

        final NoiseGeneratorSettings nether = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.NETHER).value();
        assertTrue(nether.useLegacyRandomSource(), "Vanilla Nether must use LegacyRandomSource");
        assertTrue(nether.aquifers().isEmpty(), "Vanilla Nether must disable aquifers");

        final long worldSeed = 20261126L;
        final RandomState netherRs = RandomState.create(noiseGetter, worldSeed, nether);
        final int minY = 0;
        final int height = 128;
        final int sectionCount = height >> 4;
        final int seaLevel = nether.seaLevel();
        final WorldGenerationContext wgCtx = makeWgContext(minY, height, seaLevel);
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        final var netherSurf = GpuVanillaSurfaceCompiler.compile(
            nether, netherRs, wgCtx, false, false, false, null, emitter, exactMode, "live_nether"
        );
        assertNotNull(netherSurf, "Vanilla Nether surface compiler must succeed with LegacyPositionalRandomFactory");
        assertZeroFp64(netherSurf.terrainProgram().sourceCode(), "live_nether terrain");
        assertZeroFp64(netherSurf.surfaceProgram().sourceCode(), "live_nether surface");
        assertTrue(netherSurf.surfaceProgram().sourceCode().contains("gl_legacy_init("));
        assertTrue(netherSurf.surfaceProgram().sourceCode().contains("gl_vertical_gradient_legacy("));

        final var netherCarverProg = GpuCarverAquiferCompiler.compile(
            nether, netherRs, netherSurf.blockPalette(), emitter, exactMode, "live_nether"
        );
        assertNotNull(netherCarverProg, "Non-aquifer Nether carver program must compile cleanly");
        assertZeroFp64(netherCarverProg.sourceCode(), "live_nether carver");
        assertTrue(netherCarverProg.sourceCode().contains(GpuCarverAquiferCompiler.KERNEL_NAME));
        assertFalse(
            netherCarverProg.sourceCode().contains(GpuCarverAquiferCompiler.TERRAIN_AQUIFER_KERNEL_NAME),
            "Non-aquifer carver program must omit apply_terrain_aquifer_u8 so native arg_use_aquifer remains 0"
        );

        final var netherCave = carverLookup.getOrThrow(net.minecraft.data.worldgen.Carvers.NETHER_CAVE).value();
        final List<net.minecraft.world.level.levelgen.carver.WorldCarver> carvers = List.of(netherCave);
        final var netherMaskProg = GpuCarverMaskCompiler.compile(carvers, worldSeed, wgCtx, "live_nether");
        assertNotNull(netherMaskProg, "Vanilla Carvers.NETHER_CAVE mask program must compile cleanly");
        assertZeroFp64(netherMaskProg.sourceCode(), "live_nether mask");

        final List<Holder<Biome>> chunkBiomes = List.of(
            biomeLookup.getOrThrow(Biomes.NETHER_WASTES),
            biomeLookup.getOrThrow(Biomes.SOUL_SAND_VALLEY),
            biomeLookup.getOrThrow(Biomes.CRIMSON_FOREST),
            biomeLookup.getOrThrow(Biomes.BASALT_DELTAS)
        );
        final int[][] coords = {{0, 0}, {1, 0}, {0, 1}, {-2, 3}};
        final int batchCount = coords.length;

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("nether_clbin"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final var tKernel = accel.compileProgramBlocking(netherSurf.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var sKernel = accel.compileProgramBlocking(netherSurf.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var pKernel = accel.compileProgramBlocking(netherSurf.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var mKernel = accel.compileProgramBlocking(netherMaskProg, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var cKernel = accel.compileProgramBlocking(netherCarverProg, TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(
                tKernel.hasLiveGpuKernel() && sKernel.hasLiveGpuKernel() && pKernel.hasLiveGpuKernel()
                    && mKernel.hasLiveGpuKernel() && cKernel.hasLiveGpuKernel(),
                "All 5 Nether GPU kernels must compile live on OpenCL device"
            );

            final int qSizeY = sectionCount * 4;
            final int haloPerChunk = 36 * qSizeY;
            final int[] origins = new int[batchCount * 4];
            final byte[] biomesHalo = new byte[batchCount * haloPerChunk];
            for (int c = 0; c < batchCount; c++) {
                origins[c * 4] = coords[c][0] << 4;
                origins[c * 4 + 1] = minY;
                origins[c * 4 + 2] = coords[c][1] << 4;
                final Integer bid = netherSurf.biomeKeyToId().get(chunkBiomes.get(c).unwrapKey().orElseThrow());
                Arrays.fill(biomesHalo, c * haloPerChunk, (c + 1) * haloPerChunk, (byte) (bid != null ? bid.intValue() : 255));
            }
            final float[] prelim = new float[batchCount * 4];
            Arrays.fill(prelim, 64.0f);
            final int[] beardMeta = new int[batchCount * 2];
            final int[] beardItems = new int[8];
            final int wordsPerCol = (height + 31) >> 5;
            final int[] carverMeta = new int[batchCount];
            for (int c = 0; c < batchCount; c++) {
                carverMeta[c] = c;
            }
            final byte[] chunkFlags = new byte[batchCount];
            final int[] outCarverEvents = new int[batchCount * GpuCarverAquiferCompiler.MAX_CARVER_EVENTS];
            final int[] outCarverCounts = new int[batchCount];
            Arrays.fill(outCarverCounts, 999); // sentinel to verify non-aquifer kernel zeroes stale DMA slots
            final CompactVanillaSurfaceWritebackBuffer outBuf = new CompactVanillaSurfaceWritebackBuffer(batchCount * sectionCount);
            final long biomeZoomSeed = BiomeManager.obfuscateSeed(worldSeed);

            accel.executeVanillaSurfaceU8Batch(
                tKernel, sKernel, pKernel, mKernel, cKernel, null,
                origins, biomesHalo, prelim, beardMeta, beardItems, 0,
                carverMeta, null, batchCount, wordsPerCol, minY + 1, minY + height - 1 - 7,
                batchCount, sectionCount, seaLevel, Math.min(seaLevel, -54), minY,
                biomeZoomSeed, outBuf, chunkFlags, outCarverEvents, outCarverCounts
            );

            for (int c = 0; c < batchCount; c++) {
                assertEquals(0, outCarverCounts[c], "Non-aquifer Nether carver must zero outCarverCounts[" + c + "]");
                assertEquals(0, chunkFlags[c] & GpuCarverAquiferCompiler.FLAG_CARVER_EVENT_OVERFLOW);
            }

            long totalVoxels = 0L;
            long mismatches = 0L;
            long bedrockFloorChecked = 0L;
            long bedrockFloorMismatches = 0L;
            long bedrockRoofChecked = 0L;
            long bedrockRoofMismatches = 0L;
            long lavaBlocks = 0L;
            long nonDefaultSurfaceBlocks = 0L;
            final java.util.Map<String, Integer> diffHist = new java.util.TreeMap<>();
            final java.util.List<String> firstDiffs = new java.util.ArrayList<>();

            for (int c = 0; c < batchCount; c++) {
                final Holder<Biome> fixedBiome = chunkBiomes.get(c);
                final var cpuChunk = createEmptyProtoChunk(new net.minecraft.world.level.ChunkPos(coords[c][0], coords[c][1]), minY, height);
                final var cpuBiomeMgr = new BiomeManager((qx, qy, qz) -> fixedBiome, biomeZoomSeed);
                runCpuTerrainAndSurface(cpuChunk, nether, netherRs, wgCtx, cpuBiomeMgr, carvers, worldSeed);

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        for (int y = minY; y < minY + height; y++) {
                            final BlockState cpuState = cpuChunk.getBlockState(new BlockPos(lx, y, lz));
                            final BlockState gpuState = decodeGpuSectionVoxel(
                                outBuf, c * sectionCount + ((y - minY) >> 4), lx, (y - minY) & 15, lz, netherSurf.blockPalette()
                            );
                            totalVoxels++;
                            if (gpuState.is(Blocks.LAVA)) {
                                lavaBlocks++;
                            }
                            if (!gpuState.isAir() && !gpuState.is(Blocks.NETHERRACK) && !gpuState.is(Blocks.LAVA) && !gpuState.is(Blocks.BEDROCK)) {
                                nonDefaultSurfaceBlocks++;
                            }
                            final boolean diff = !cpuState.equals(gpuState);
                            if (diff) {
                                mismatches++;
                                final String key = cpuState.getBlock().getDescriptionId() + "->" + gpuState.getBlock().getDescriptionId();
                                diffHist.merge(key, 1, Integer::sum);
                                if (firstDiffs.size() < 12) {
                                    firstDiffs.add(String.format(Locale.ROOT, "c=%d(%d,%d,%d) cpu=%s gpu=%s", c, (coords[c][0]<<4)+lx, y, (coords[c][1]<<4)+lz, cpuState.getBlock().getDescriptionId(), gpuState.getBlock().getDescriptionId()));
                                }
                            }
                            if (y <= 4 && (cpuState.is(Blocks.BEDROCK) || gpuState.is(Blocks.BEDROCK))) {
                                bedrockFloorChecked++;
                                if (diff) bedrockFloorMismatches++;
                            } else if (y >= 123 && (cpuState.is(Blocks.BEDROCK) || gpuState.is(Blocks.BEDROCK))) {
                                bedrockRoofChecked++;
                                if (diff) bedrockRoofMismatches++;
                            }
                        }
                    }
                }
            }

            final double matchPct = 100.0 * (1.0 - (double) mismatches / (double) totalVoxels);
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU Vanilla Nether (Legacy RNG + NETHER_CAVE) parity: %.4f%% (mismatches=%d/%d, bedrockFloorErr=%d/%d, bedrockRoofErr=%d/%d, lava=%d, surfaceBlocks=%d) hist=%s sample=%s%n",
                matchPct, mismatches, totalVoxels, bedrockFloorMismatches, bedrockFloorChecked,
                bedrockRoofMismatches, bedrockRoofChecked, lavaBlocks, nonDefaultSurfaceBlocks, diffHist, firstDiffs
            );
            assertEquals(0L, bedrockFloorMismatches, "Legacy RNG Nether BEDROCK_FLOOR must match CPU 100.0%");
            assertEquals(0L, bedrockRoofMismatches, "Legacy RNG Nether BEDROCK_ROOF must match CPU 100.0%");
            assertTrue(lavaBlocks > 500, "Nether chunks must contain lava ocean/carved lava below Y=32, got " + lavaBlocks);
            assertTrue(nonDefaultSurfaceBlocks > 500, "Nether surface rules must place Soul Sand / Nylium / Basalt / Gravel, got " + nonDefaultSurfaceBlocks);
            assertTrue(
                matchPct >= 99.90,
                "Vanilla Nether live-GPU full pipeline parity must be >= 99.90% (got " + String.format(Locale.ROOT, "%.4f%%", matchPct) + ")"
            );
        }
    }

    @Test
    void vanillaEndLegacyRngSurfaceAndCustomRulesLiveGpuParity(@TempDir final Path tempDir) throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final var noiseSettingsLookup = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        final var biomeLookup = registries.lookupOrThrow(Registries.BIOME);

        final NoiseGeneratorSettings vanillaEnd = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.END).value();
        assertTrue(vanillaEnd.useLegacyRandomSource(), "Vanilla End must use LegacyRandomSource");
        assertTrue(vanillaEnd.aquifers().isEmpty(), "Vanilla End must disable aquifers");
        assertTrue(vanillaEnd.defaultFluid().isAir(), "Vanilla End defaultFluid must be AIR");

        // Custom Legacy-RNG End datapack rule exercising:
        // 1) Normal VerticalGradientCondition with LegacyPositionalRandomFactory
        // 2) Degenerate VerticalGradientCondition (trueY >= falseY) guard against 1/0 Infinity
        // 3) Custom OreVeinRule with LegacyPositionalRandomFactory
        final MaterialRule customEndRule = MaterialRules.sequence(
            MaterialRules.ifTrue(
                MaterialRules.verticalGradient("end_degenerate", VerticalAnchor.absolute(8), VerticalAnchor.absolute(8)),
                MaterialRules.state(Blocks.OBSIDIAN.defaultBlockState())
            ),
            MaterialRules.ifTrue(
                MaterialRules.verticalGradient("end_crust", VerticalAnchor.absolute(45), VerticalAnchor.absolute(58)),
                MaterialRules.state(Blocks.PURPUR_BLOCK.defaultBlockState())
            ),
            new OreVeinRule(
                Blocks.GOLD_ORE.defaultBlockState(),
                Blocks.RAW_GOLD_BLOCK.defaultBlockState(),
                Blocks.END_STONE_BRICKS.defaultBlockState(),
                0.2f,
                DensityFunctions.constant(0.6f),
                DensityFunctions.constant(0.4f),
                DensityFunctions.constant(-0.5f)
            ),
            vanillaEnd.materialRule().value()
        );
        final NoiseGeneratorSettings endDatapack = new NoiseGeneratorSettings(
            vanillaEnd.noiseSettings(),
            vanillaEnd.defaultBlock(),
            vanillaEnd.defaultFluid(),
            vanillaEnd.noiseRouter(),
            Holder.direct(customEndRule),
            vanillaEnd.spawnTarget(),
            vanillaEnd.seaLevel(),
            vanillaEnd.disableMobGeneration(),
            vanillaEnd.aquifers(),
            vanillaEnd.useLegacyRandomSource(),
            vanillaEnd.debugFunctions()
        );

        final long worldSeed = 777654321L;
        final RandomState endRs = RandomState.create(noiseGetter, worldSeed, endDatapack);
        final int minY = 0;
        final int height = 128;
        final int sectionCount = height >> 4;
        final WorldGenerationContext wgCtx = makeWgContext(minY, height, endDatapack.seaLevel());
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        final var endSurf = GpuVanillaSurfaceCompiler.compile(
            endDatapack, endRs, wgCtx, false, false, false, null, emitter, exactMode, "live_end_legacy"
        );
        assertNotNull(endSurf, "Legacy-RNG End datapack surface must compile cleanly on GPU");
        assertZeroFp64(endSurf.terrainProgram().sourceCode(), "live_end_legacy terrain");
        assertZeroFp64(endSurf.surfaceProgram().sourceCode(), "live_end_legacy surface");
        assertTrue(endSurf.surfaceProgram().sourceCode().contains("gl_legacy_init("));
        assertTrue(endSurf.surfaceProgram().sourceCode().contains("gl_vertical_gradient_legacy("));
        assertTrue(endSurf.surfaceProgram().sourceCode().contains("gl_legacy_next_float(&os)"));

        // Chunks at (0,0) and (1,0) sit on the central End island where EndIslandFunction is strongly positive
        final int[][] coords = {{0, 0}, {1, 0}, {-1, 0}, {0, -1}};
        final int batchCount = coords.length;
        final Holder<Biome> theEndBiome = biomeLookup.getOrThrow(Biomes.THE_END);

        try (IrOpenClTerrainAccelerator accel = new IrOpenClTerrainAccelerator(tempDir.resolve("end_clbin"))) {
            if (!accel.isGpuReady()) {
                return;
            }
            final var tKernel = accel.compileProgramBlocking(endSurf.terrainProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var sKernel = accel.compileProgramBlocking(endSurf.surfaceProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            final var pKernel = accel.compileProgramBlocking(endSurf.packProgram(), TerrainGenerationAccelerator.ParityMode.VANILLA_EXACT);
            assertTrue(tKernel.hasLiveGpuKernel() && sKernel.hasLiveGpuKernel() && pKernel.hasLiveGpuKernel());

            final int qSizeY = sectionCount * 4;
            final int haloPerChunk = 36 * qSizeY;
            final int[] origins = new int[batchCount * 4];
            for (int c = 0; c < batchCount; c++) {
                origins[c * 4] = coords[c][0] << 4;
                origins[c * 4 + 1] = minY;
                origins[c * 4 + 2] = coords[c][1] << 4;
            }
            final byte[] biomesHalo = new byte[batchCount * haloPerChunk];
            Arrays.fill(biomesHalo, (byte) 255);
            final float[] prelim = new float[batchCount * 4];
            Arrays.fill(prelim, 64.0f);
            final byte[] chunkFlags = new byte[batchCount];
            final CompactVanillaSurfaceWritebackBuffer outBuf = new CompactVanillaSurfaceWritebackBuffer(batchCount * sectionCount);
            final long biomeZoomSeed = BiomeManager.obfuscateSeed(worldSeed);

            // Pure 2-pass End dispatch (carverMaskKernel=null, carverKernel=null)
            accel.executeVanillaSurfaceU8Batch(
                tKernel, sKernel, pKernel, null, null, null,
                origins, biomesHalo, prelim, new int[batchCount * 2], new int[8], 0,
                null, null, 0, 1, minY + 1, minY + height - 8,
                batchCount, sectionCount, endDatapack.seaLevel(), Math.min(endDatapack.seaLevel(), -54), minY,
                biomeZoomSeed, outBuf, chunkFlags, null, null
            );

            long totalVoxels = 0L;
            long mismatches = 0L;
            long purpurCount = 0L;
            long oreCount = 0L;
            long endStoneCount = 0L;
            for (int c = 0; c < batchCount; c++) {
                final var cpuChunk = createEmptyProtoChunk(new net.minecraft.world.level.ChunkPos(coords[c][0], coords[c][1]), minY, height);
                final var cpuBiomeMgr = new BiomeManager((qx, qy, qz) -> theEndBiome, biomeZoomSeed);
                runCpuTerrainAndSurface(cpuChunk, endDatapack, endRs, wgCtx, cpuBiomeMgr, List.of(), worldSeed);

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        for (int y = minY; y < minY + height; y++) {
                            final BlockState cpuState = cpuChunk.getBlockState(new BlockPos(lx, y, lz));
                            final BlockState gpuState = decodeGpuSectionVoxel(
                                outBuf, c * sectionCount + ((y - minY) >> 4), lx, (y - minY) & 15, lz, endSurf.blockPalette()
                            );
                            assertFalse(gpuState.is(Blocks.LAVA), "End chunk must never leak LAVA");
                            totalVoxels++;
                            if (gpuState.is(Blocks.PURPUR_BLOCK)) purpurCount++;
                            if (gpuState.is(Blocks.GOLD_ORE) || gpuState.is(Blocks.RAW_GOLD_BLOCK) || gpuState.is(Blocks.END_STONE_BRICKS)) oreCount++;
                            if (gpuState.is(Blocks.END_STONE)) endStoneCount++;
                            if (!cpuState.equals(gpuState)) {
                                mismatches++;
                            }
                        }
                    }
                }
            }

            final double matchPct = 100.0 * (1.0 - (double) mismatches / (double) totalVoxels);
            System.out.printf(
                Locale.ROOT,
                "[DatapackWorldgenParityTest] LIVE-GPU Legacy-RNG End (EndIsland + VerticalGradient + OreVein) parity: %.4f%% (mismatches=%d/%d, purpur=%d, ore=%d, endStone=%d)%n",
                matchPct, mismatches, totalVoxels, purpurCount, oreCount, endStoneCount
            );
            assertTrue(purpurCount > 100 && oreCount > 100 && endStoneCount > 100,
                "End island must exercise Legacy RNG gradient (" + purpurCount + "), ore vein (" + oreCount + "), and end stone (" + endStoneCount + ")");
            assertTrue(
                matchPct >= 99.95,
                "Legacy-RNG End live-GPU parity must be >= 99.95% (got " + String.format(Locale.ROOT, "%.4f%%", matchPct) + ")"
            );
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyRngUnknownPositionalFactoryFailsClosedCleanly() throws Exception {
        final var noiseGetter = registries.lookupOrThrow(Registries.NOISE);
        final var noiseSettingsLookup = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        final NoiseGeneratorSettings nether = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.NETHER).value();
        final OpenClBackendEmitter emitter = new OpenClBackendEmitter();
        final var exactMode = ConceptBackendEmitter.GpuExactArithmeticMode.INT64_Q32_FP32;

        // 1. Unknown custom PositionalRandomFactory (neither Xoroshiro nor Legacy) must fail closed (S-004)
        final Identifier unknownId = Identifier.fromNamespaceAndPath("goldleaf", "unknown_custom_rng");
        final MaterialRule ruleWithUnknownRng = MaterialRules.ifTrue(
            MaterialRules.verticalGradient(unknownId.toString(), VerticalAnchor.absolute(0), VerticalAnchor.absolute(10)),
            MaterialRules.state(Blocks.BEDROCK.defaultBlockState())
        );
        final NoiseGeneratorSettings customSettings = new NoiseGeneratorSettings(
            nether.noiseSettings(),
            nether.defaultBlock(),
            nether.defaultFluid(),
            nether.noiseRouter(),
            Holder.direct(ruleWithUnknownRng),
            nether.spawnTarget(),
            nether.seaLevel(),
            nether.disableMobGeneration(),
            nether.aquifers(),
            true,
            nether.debugFunctions()
        );
        final RandomState rs = RandomState.create(noiseGetter, 42L, customSettings);
        final var field = RandomState.class.getDeclaredField("positionalRandoms");
        field.setAccessible(true);
        final var map = (java.util.Map<Identifier, net.minecraft.world.level.levelgen.PositionalRandomFactory>) field.get(rs);
        map.put(unknownId, new net.minecraft.world.level.levelgen.PositionalRandomFactory() {
            @Override
            public RandomSource at(final int x, final int y, final int z) {
                return new net.minecraft.world.level.levelgen.LegacyRandomSource(0L);
            }

            @Override
            public RandomSource fromHashOf(final String name) {
                return new net.minecraft.world.level.levelgen.LegacyRandomSource(0L);
            }

            @Override
            public RandomSource fromSeed(final long seed) {
                return new net.minecraft.world.level.levelgen.LegacyRandomSource(seed);
            }

            @Override
            public void parityConfigString(final StringBuilder sb) {
                sb.append("UnknownCustomFactory{0}");
            }
        });

        final var compiledUnknown = GpuVanillaSurfaceCompiler.compile(
            customSettings, rs, makeWgContext(0, 128, nether.seaLevel()), false, false, false, null, emitter, exactMode, "fail_closed_unknown"
        );
        assertNull(compiledUnknown, "S-004: Unknown PositionalRandomFactory must fail closed (return null)");

        // 2. Malformed LegacyPositionalRandomFactory parityConfigString must also fail closed (S-004)
        map.put(unknownId, new net.minecraft.world.level.levelgen.LegacyRandomSource.LegacyPositionalRandomFactory(123L) {
            @Override
            public void parityConfigString(final StringBuilder sb) {
                sb.append("MalformedLegacy{not_a_number}");
            }
        });
        final var compiledMalformed = GpuVanillaSurfaceCompiler.compile(
            customSettings, rs, makeWgContext(0, 128, nether.seaLevel()), false, false, false, null, emitter, exactMode, "fail_closed_malformed"
        );
        assertNull(compiledMalformed, "S-004: Malformed LegacyPositionalRandomFactory parityConfigString must fail closed (return null)");
    }
}
