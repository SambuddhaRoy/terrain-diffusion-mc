package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        /** Vanilla multi-noise parameters per biome quart, see {@link BiomeClassifier#CLIMATE_PARAMS}; row stride is BiomeClassifier.quarts(width). */
        public final short[] climate;
        /** Surface water Y per block, row-major (rivers and lakes); NO_WATER where dry; null if the tile has none. */
        public final short[] waterLevel;
        /** Highest value in waterLevel, for a cheap early-out. */
        public final int maxWaterLevel;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, short[] climate, short[] waterLevel, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIds  = biomeIds;
            this.climate   = climate;
            this.waterLevel = waterLevel;
            int max = NO_WATER;
            if (waterLevel != null) for (short w : waterLevel) max = Math.max(max, w);
            this.maxWaterLevel = max;
            this.width     = width;
            this.height    = height;
        }
    }

    public static final short NO_WATER = Short.MIN_VALUE;

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    private static final int MAX_CACHE_SIZE = 64;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            CACHE.clear();
            PENDING.clear();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static void clearCache() {
        CACHE.clear();
        PENDING.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            CACHE.clear();
            PENDING.clear();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(MAX_CACHE_SIZE);
            PENDING.remove(key);
            return data;
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            INFERENCE_EXECUTOR.submit(toRun);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        float[] elevPadded = pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, false)[0];
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = out[1];

        short[] climateParams = newClimateParams(H, W);
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION, climateParams);
        short[] water = applyHydrology(elevFlat, i1, j1, H, W, 1);
        return buildHeightmapData(elevFlat, biomeFlat, climateParams, water, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 2-pixel native padding (1 for bilinear + 1 for slope)
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        short[] climateParams = newClimateParams(H, W);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM, climateParams);
        short[] water = applyHydrology(elevOut, i1, j1, H, W, scale);
        return buildHeightmapData(elevOut, biomeFlat, climateParams, water, H, W);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private static short[] newClimateParams(int H, int W) {
        return new short[BiomeClassifier.quarts(H) * BiomeClassifier.quarts(W) * BiomeClassifier.CLIMATE_PARAMS];
    }

    // channel half-width and depth (blocks) per Hydrology tier: stream, river, big river
    private static final int[] RIVER_RADIUS = {0, 1, 2, 3};
    private static final int[] RIVER_DEPTH = {0, 1, 2, 3};

    /**
     * Carves river channels and fills lakes for a block-space tile. Lowers {@code elevBlocks} (metres) in place and
     * returns the water surface Y per block ({@link #NO_WATER} where dry), or null if the tile has no surface water.
     */
    private short[] applyHydrology(float[] elevBlocks, int i1, int j1, int H, int W, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled()) return null;
        int i1n = Math.floorDiv(i1, scale), j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-(i1 + H), scale), j2n = -Math.floorDiv(-(j1 + W), scale);
        int nH = i2n - i1n, nW = j2n - j1n, pad = Hydrology.PAD;
        int m = RIVER_RADIUS[RIVER_RADIUS.length - 1] / scale + 1; // channels whose discs reach into this tile
        int mH = nH + 2 * m, mW = nW + 2 * m, pW = nW + 2 * pad;
        float[] nativeElev = pipeline.get(i1n - pad, j1n - pad, i2n + pad, j2n + pad, false)[0];
        float blockMetres = NATIVE_RESOLUTION / scale;
        Hydrology.Result hydro = Hydrology.compute(nativeElev, nH, nW, pad, m, Math.max(3f, 1.5f * blockMetres));

        int n = H * W, sea = HeightConverter.seaLevel();
        int[] groundY = new int[n];
        for (int k = 0; k < n; k++) groundY[k] = HeightConverter.convertToMinecraftHeight(toShort(elevBlocks[k]), scale);
        int[] bedY = groundY.clone();
        short[] water = new short[n];
        Arrays.fill(water, NO_WATER);
        boolean any = false;

        for (int r = 0; r < mH; r++) {
            for (int c = 0; c < mW; c++) {
                int t = hydro.tier()[r * mW + c];
                if (t == 0) continue;
                int nr = r - m, nc = c - m; // native pixel relative to the tile's native origin
                int cr = (i1n + nr) * scale + scale / 2 - i1, cc = (j1n + nc) * scale + scale / 2 - j1;
                // water sits one block below the lowest native ground around the channel centre; native elevation is
                // global, so neighbouring tiles agree on the level
                float low = Float.MAX_VALUE;
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++)
                        low = Math.min(low, nativeElev[(nr + pad + dr) * pW + (nc + pad + dc)]);
                int surface = HeightConverter.convertToMinecraftHeight(toShort(low), scale) - 1;
                if (surface <= sea) continue; // the sea takes over at the coast
                int bed = Math.max(sea, surface - RIVER_DEPTH[t]);
                int rad = RIVER_RADIUS[t];
                for (int dr = -rad; dr <= rad; dr++)
                    for (int dc = -rad; dc <= rad; dc++) {
                        int rr = cr + dr, c2 = cc + dc;
                        if (rr < 0 || rr >= H || c2 < 0 || c2 >= W || dr * dr + dc * dc > rad * rad + rad) continue;
                        int k = rr * W + c2;
                        if (water[k] == NO_WATER || water[k] > surface) water[k] = (short) surface;
                        bedY[k] = Math.min(bedY[k], bed);
                        any = true;
                    }
            }
        }

        // lakes fill depressions up to their spill level and win over channels running through them
        for (int r = 0; r < mH; r++) {
            for (int c = 0; c < mW; c++) {
                float level = hydro.lakeLevel()[r * mW + c];
                if (Float.isNaN(level)) continue;
                int lakeY = HeightConverter.convertToMinecraftHeight(toShort(level), scale);
                if (lakeY <= sea) continue;
                for (int dr = 0; dr < scale; dr++)
                    for (int dc = 0; dc < scale; dc++) {
                        int rr = (i1n + r - m) * scale + dr - i1, c2 = (j1n + c - m) * scale + dc - j1;
                        if (rr < 0 || rr >= H || c2 < 0 || c2 >= W) continue;
                        int k = rr * W + c2;
                        if (bedY[k] < lakeY && (water[k] == NO_WATER || water[k] < lakeY)) {
                            water[k] = (short) lakeY;
                            any = true;
                        }
                    }
            }
        }

        if (!any) return null;
        holdBanks(water, bedY, H, W, sea);
        for (int k = 0; k < n; k++) {
            if (bedY[k] < groundY[k]) elevBlocks[k] = HeightConverter.metersForBlockY(bedY[k], scale);
        }
        return water;
    }

    /**
     * Keeps water from spilling sideways: a water column may not stand higher than a dry neighbour's ground.
     * Over-high columns are lowered (and their bed deepened); steps between water columns are fine, they become rapids.
     */
    private static void holdBanks(short[] water, int[] bedY, int H, int W, int sea) {
        int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int k = r * W + c;
                    if (water[k] == NO_WATER) continue;
                    int w = water[k];
                    for (int d = 0; d < 4; d++) {
                        int rr = r + dr[d], cc = c + dc[d];
                        if (rr < 0 || rr >= H || cc < 0 || cc >= W) continue;
                        int j = rr * W + cc;
                        if (water[j] == NO_WATER && bedY[j] < w) w = bedY[j];
                    }
                    if (w == water[k]) continue;
                    changed = true;
                    if (w <= sea) { water[k] = NO_WATER; continue; }
                    water[k] = (short) w;
                    if (bedY[k] >= w) bedY[k] = Math.max(sea, w - 1);
                }
            }
            if (!changed) break;
        }
    }

    private static short toShort(float metres) {
        return (short) Math.max(-32768, Math.min(32767, (int) Math.floor(metres)));
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, short[] climateParams, short[] water, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat[r * W + c];
            }
        return new HeightmapData(heightmap, biomeIds, climateParams, water, W, H);
    }
}
