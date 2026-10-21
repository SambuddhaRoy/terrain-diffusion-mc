package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;

/**
 * Rivers and lakes from tile-local drainage on native-resolution elevation.
 *
 * <p>Each tile is computed from its own elevation plus {@link #PAD} pixels of margin, so results don't depend on
 * which tile generates first. Measured against exact drainage over a 30 km window, the stream network inside a
 * tile matches perfectly with this margin and capped river widths match 92-97%, so tiles join without seams.
 * Catchments larger than the margin are undercounted, which only caps river width at {@link #BIG_RIVER_ACC}.
 */
public final class Hydrology {
    /** Native pixels of margin around a tile (64 px = 1.9 km). */
    public static final int PAD = 64;

    /** Upstream area thresholds in native pixels (1 px = 30 m x 30 m). */
    static final int STREAM_ACC = 1000;     // ~0.9 km^2
    static final int RIVER_ACC = 2500;      // ~2.3 km^2
    static final int BIG_RIVER_ACC = 5000;  // ~4.5 km^2


    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final float[] DIST = {1.4142f, 1f, 1.4142f, 1f, 1f, 1.4142f, 1f, 1.4142f};

    private Hydrology() {
    }

    /**
     * Per pixel of the core plus {@code margin} on each side, row-major ((H + 2*margin) x (W + 2*margin)):
     * channel tier (0 none, 1 stream, 2 river, 3 big river) and lake surface in metres (NaN = none).
     */
    public record Result(byte[] tier, float[] lakeLevel) {}

    /**
     * @param elev   padded elevation in metres, row-major (H + 2*pad) x (W + 2*pad)
     * @param margin pixels beyond the core to report, so channels just across a tile edge can still be drawn
     * @param minLakeDepthM depressions shallower than this stay dry (keep it above one block, or lakes come out dotted)
     */
    public static Result compute(float[] elev, int H, int W, int pad, int margin, float minLakeDepthM) {
        int pH = H + 2 * pad, pW = W + 2 * pad, n = pH * pW;
        float[] filled = fill(elev, pH, pW);
        int[] down = flowDirections(filled, pH, pW);

        // accumulate in order of decreasing filled height: every cell is final before it drains on
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) boxed[i] = i;
        Arrays.sort(boxed, (a, b) -> Float.compare(filled[b], filled[a]));
        int[] acc = new int[n];
        Arrays.fill(acc, 1);
        for (Integer i : boxed) {
            int d = down[i];
            if (d >= 0) acc[d] += acc[i];
        }

        int oH = H + 2 * margin, oW = W + 2 * margin, off = pad - margin;
        byte[] tier = new byte[oH * oW];
        float[] lake = new float[oH * oW];
        for (int r = 0; r < oH; r++) {
            for (int c = 0; c < oW; c++) {
                int i = (r + off) * pW + (c + off), o = r * oW + c;
                lake[o] = Float.NaN;
                if (elev[i] <= 0f) continue; // ocean
                if (filled[i] - elev[i] >= minLakeDepthM) {
                    lake[o] = filled[i];
                } else {
                    int a = acc[i];
                    tier[o] = (byte) (a >= BIG_RIVER_ACC ? 3 : a >= RIVER_ACC ? 2 : a >= STREAM_ACC ? 1 : 0);
                }
            }
        }
        return new Result(tier, lake);
    }

    /** Priority-flood depression filling (Barnes et al. 2014, epsilon variant); edges and ocean are outlets. */
    static float[] fill(float[] elev, int H, int W) {
        int n = H * W;
        float[] f = elev.clone();
        boolean[] done = new boolean[n];
        Heap heap = new Heap(n);
        for (int i = 0; i < n; i++) {
            int r = i / W, c = i % W;
            if (r == 0 || c == 0 || r == H - 1 || c == W - 1 || elev[i] <= 0f) {
                done[i] = true;
                heap.push(i, f[i]);
            }
        }
        while (heap.size > 0) {
            int i = heap.pop();
            float z = f[i];
            int r = i / W, c = i % W;
            for (int k = 0; k < 8; k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                int j = rr * W + cc;
                if (done[j]) continue;
                done[j] = true;
                if (f[j] <= z) f[j] = Math.nextUp(z);
                heap.push(j, f[j]);
            }
        }
        return f;
    }

    /** D8 steepest descent on the filled surface; -1 for outlets (edges, ocean, no lower neighbour). */
    static int[] flowDirections(float[] f, int H, int W) {
        int[] down = new int[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c, best = -1;
                float bestDrop = 0f;
                for (int k = 0; k < 8; k++) {
                    int rr = r + DR[k], cc = c + DC[k];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    float drop = (f[i] - f[rr * W + cc]) / DIST[k];
                    if (drop > bestDrop) { bestDrop = drop; best = rr * W + cc; }
                }
                down[i] = best;
            }
        }
        return down;
    }

    /** Binary min-heap of cell indices keyed by height. */
    private static final class Heap {
        final int[] idx;
        final float[] key;
        int size;

        Heap(int capacity) {
            idx = new int[capacity];
            key = new float[capacity];
        }

        void push(int i, float k) {
            int p = size++;
            while (p > 0) {
                int parent = (p - 1) >> 1;
                if (key[parent] <= k) break;
                idx[p] = idx[parent]; key[p] = key[parent]; p = parent;
            }
            idx[p] = i; key[p] = k;
        }

        int pop() {
            int top = idx[0];
            int lastI = idx[--size];
            float lastK = key[size];
            int p = 0;
            while (true) {
                int ch = 2 * p + 1;
                if (ch >= size) break;
                if (ch + 1 < size && key[ch + 1] < key[ch]) ch++;
                if (key[ch] >= lastK) break;
                idx[p] = idx[ch]; key[p] = key[ch]; p = ch;
            }
            idx[p] = lastI; key[p] = lastK;
            return top;
        }
    }
}
