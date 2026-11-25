package sodaviewer;

import java.util.Random;

/** Fixed-seed random downsample for plotting large embeddings. */
public final class PlotSample {
    /** Default when prefs are unset — denser plots; gene fetch is dominated by matrix I/O. */
    public static final int DEFAULT_MAX_CELLS = 1_000_000;
    public static final long SEED = 1L;

    public final int[] indices; // into full embedding
    public final double[][] xy;
    public final String[] cellIds;
    public final int totalCells;
    public final int maxCells;

    private PlotSample(int[] indices, double[][] xy, String[] cellIds, int totalCells, int maxCells) {
        this.indices = indices;
        this.xy = xy;
        this.cellIds = cellIds;
        this.totalCells = totalCells;
        this.maxCells = maxCells;
    }

    /** Uses {@link AppPrefs#maxPlotCells()}. */
    public static PlotSample of(EmbeddingData emb) {
        return of(emb, AppPrefs.maxPlotCells());
    }

    /**
     * @param maxCells plot cap; {@code <= 0} means all cells
     */
    public static PlotSample of(EmbeddingData emb, int maxCells) {
        int n = emb.xy.length;
        int cap = maxCells <= 0 ? n : maxCells;
        if (n <= cap) {
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            return new PlotSample(idx, emb.xy, emb.cellIds, n, cap);
        }
        int[] idx = sampleIndices(n, cap, SEED);
        double[][] xy = new double[idx.length][2];
        String[] ids = new String[idx.length];
        for (int i = 0; i < idx.length; i++) {
            int j = idx[i];
            xy[i][0] = emb.xy[j][0];
            xy[i][1] = emb.xy[j][1];
            ids[i] = emb.cellIds[j];
        }
        return new PlotSample(idx, xy, ids, n, cap);
    }

    public boolean downsampled() {
        return indices.length < totalCells;
    }

    /** Fisher–Yates partial shuffle — first {@code k} of a random permutation. */
    public static int[] sampleIndices(int n, int k, long seed) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        Random rnd = new Random(seed);
        for (int i = 0; i < k; i++) {
            int j = i + rnd.nextInt(n - i);
            int tmp = a[i];
            a[i] = a[j];
            a[j] = tmp;
        }
        int[] out = new int[k];
        System.arraycopy(a, 0, out, 0, k);
        return out;
    }
}
