package sodaviewer;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read genes from SODA assay HDF5.
 * Supports iCellR2 CSC ({@code i,p,x,rownames,colnames}) and 10x {@code /matrix}.
 * <p>
 * Large chunked assays (e.g. ~320K cells, ~1B nnz) cannot be loaded with a single
 * {@code getData()} — byte length overflows {@code int}. Those use hyperslab
 * slices via jhdf ≥ 0.9.3. Multi-gene requests share one matrix pass.
 */
public final class ExpressionStore implements AutoCloseable {
    /** Max nnz elements per hyperslab (keeps arrays well under Integer.MAX_VALUE bytes). */
    private static final int SLICE_NNZ = 5_000_000;
    /** If index array storage exceeds this, always use sliced reads. */
    private static final long FULL_LOAD_BYTE_LIMIT = 1_500_000_000L; // ~1.5 GiB

    /** Optional progress: {@code completed}/{@code total} over matrix columns (or 0/0 + detail). */
    @FunctionalInterface
    public interface Progress {
        void report(int completed, int total, String detail);
    }

    private final HdfFile h5;
    private final String[] features;
    private final String[] barcodes;
    private final Map<String, Integer> featureIndex;
    private final Map<String, Integer> barcodeIndex;
    private final Layout layout;
    private final Dataset indicesDs;
    private final Dataset dataDs;
    private final long[] indptr;
    private final boolean sliced;

    /** Soft cache: last single-gene vector for the same plot cell list. */
    private String cachedGene;
    private String[] cachedCells;
    private float[] cachedValues;

    private enum Layout { CSC_V1, TENX, SCIPY }

    private ExpressionStore(
            HdfFile h5,
            String[] features,
            String[] barcodes,
            Layout layout,
            Dataset indicesDs,
            Dataset dataDs,
            long[] indptr,
            boolean sliced
    ) {
        this.h5 = h5;
        this.features = features;
        this.barcodes = barcodes;
        this.layout = layout;
        this.indicesDs = indicesDs;
        this.dataDs = dataDs;
        this.indptr = indptr;
        this.sliced = sliced;
        this.featureIndex = indexMap(features);
        this.barcodeIndex = indexMap(barcodes);
    }

    public static ExpressionStore open(Path assayH5) {
        HdfFile h5 = new HdfFile(assayH5);
        try {
            if (h5.getChild("counts") instanceof Group counts) {
                if (counts.getChild("i") != null && counts.getChild("p") != null && counts.getChild("x") != null) {
                    String[] feats = readStrings(ds(counts, "rownames"));
                    String[] cells = readStrings(ds(counts, "colnames"));
                    Dataset iDs = ds(counts, "i");
                    Dataset xDs = ds(counts, "x");
                    long[] p = readLongish(ds(counts, "p"));
                    boolean sliced = needsSliced(iDs);
                    return new ExpressionStore(h5, feats, cells, Layout.CSC_V1, iDs, xDs, p, sliced);
                }
                if (counts.getChild("data") != null) {
                    String[] feats = readStrings(ds(counts, "features"));
                    String[] cells = readStrings(ds(counts, "barcodes"));
                    Dataset iDs = ds(counts, "indices");
                    Dataset xDs = ds(counts, "data");
                    long[] p = readLongish(ds(counts, "indptr"));
                    boolean sliced = needsSliced(iDs);
                    return new ExpressionStore(h5, feats, cells, Layout.SCIPY, iDs, xDs, p, sliced);
                }
            }
            if (h5.getChild("matrix") instanceof Group matrix) {
                Group featsG = (Group) matrix.getChild("features");
                Dataset nameOrId = featsG != null && featsG.getChild("name") != null
                        ? ds(featsG, "name")
                        : (featsG != null ? ds(featsG, "id") : null);
                String[] feats = nameOrId != null ? readStrings(nameOrId) : new String[0];
                String[] cells = readStrings(ds(matrix, "barcodes"));
                Dataset iDs = ds(matrix, "indices");
                Dataset xDs = ds(matrix, "data");
                long[] p = readLongish(ds(matrix, "indptr"));
                boolean sliced = needsSliced(iDs);
                return new ExpressionStore(h5, feats, cells, Layout.TENX, iDs, xDs, p, sliced);
            }
            h5.close();
            throw new IllegalArgumentException("Unrecognized assay HDF5 layout: " + assayH5);
        } catch (RuntimeException e) {
            h5.close();
            throw e;
        }
    }

    private static boolean needsSliced(Dataset indicesDs) {
        try {
            long bytes = indicesDs.getSizeInBytes();
            if (bytes < 0 || bytes > FULL_LOAD_BYTE_LIMIT) {
                return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        // Probe: tiny slice must work for large chunked files; full load may overflow.
        try {
            long n = indicesDs.getSize();
            if (n > 50_000_000L) {
                return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return false;
    }

    public List<String> featureNames() {
        List<String> out = new ArrayList<>(features.length);
        for (String f : features) {
            out.add(f);
        }
        return out;
    }

    public List<String> suggest(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        if (q.isEmpty() || limit <= 0) {
            return hits;
        }
        for (String f : features) {
            if (f.regionMatches(true, 0, q, 0, q.length())) {
                hits.add(f);
                if (hits.size() >= limit) {
                    return hits;
                }
            }
        }
        for (String f : features) {
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.startsWith(q)) {
                continue;
            }
            if (lower.contains(q)) {
                hits.add(f);
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Expression for {@code gene} aligned to {@code plotCellIds} order.
     * Missing cells → 0.
     */
    public float[] valuesForCells(String gene, String[] plotCellIds) {
        return valuesForCells(gene, plotCellIds, null);
    }

    public float[] valuesForCells(String gene, String[] plotCellIds, Progress progress) {
        float[][] rows = valuesForCells(List.of(gene), plotCellIds, progress);
        return rows[0];
    }

    /**
     * Fetch many genes in <strong>one</strong> matrix pass (critical for bubble / heatmap).
     * Returns {@code [gene][cell]} aligned to {@code plotCellIds}; missing → 0.
     * Gene names that are not found throw {@link IllegalArgumentException}.
     */
    public float[][] valuesForCells(List<String> genes, String[] plotCellIds, Progress progress) {
        if (genes == null || genes.isEmpty()) {
            throw new IllegalArgumentException("No genes requested");
        }
        if (plotCellIds == null) {
            plotCellIds = new String[0];
        }

        // Fast path: single gene soft-cache
        if (genes.size() == 1
                && cachedGene != null
                && cachedGene.equals(genes.get(0))
                && Arrays.equals(cachedCells, plotCellIds)
                && cachedValues != null) {
            report(progress, 1, 1, "Cached " + genes.get(0));
            return new float[][]{cachedValues.clone()};
        }

        int ng = genes.size();
        int[] featureRows = new int[ng];
        String[] resolved = new String[ng];
        for (int gi = 0; gi < ng; gi++) {
            String g = genes.get(gi);
            int fi = resolveFeature(g);
            if (fi < 0) {
                throw new IllegalArgumentException("Gene not found: " + g);
            }
            featureRows[gi] = fi;
            // canonical name from feature table
            resolved[gi] = features[fi];
        }

        float[][] out = new float[ng][plotCellIds.length];
        if (plotCellIds.length == 0) {
            return out;
        }

        int[] plotOfCol = new int[barcodes.length];
        Arrays.fill(plotOfCol, -1);
        BitSet want = new BitSet(barcodes.length);
        int mapped = 0;
        for (int i = 0; i < plotCellIds.length; i++) {
            Integer col = barcodeIndex.get(plotCellIds[i]);
            if (col == null) {
                continue;
            }
            plotOfCol[col] = i;
            want.set(col);
            mapped++;
        }
        if (mapped == 0) {
            report(progress, 1, 1, "No matching cells");
            return out;
        }

        int[] slotOfFeature = new int[features.length];
        Arrays.fill(slotOfFeature, -1);
        for (int gi = 0; gi < ng; gi++) {
            slotOfFeature[featureRows[gi]] = gi;
        }

        String detail = ng == 1
                ? ("Reading " + resolved[0] + "…")
                : ("Reading " + ng + " genes (one pass)…");
        report(progress, 0, barcodes.length, detail);

        if (sliced) {
            fillMultiSliced(slotOfFeature, featureRows, want, plotOfCol, out, progress);
        } else {
            try {
                fillMultiFull(slotOfFeature, want, plotOfCol, out, progress);
            } catch (ArithmeticException | OutOfMemoryError ex) {
                fillMultiSliced(slotOfFeature, featureRows, want, plotOfCol, out, progress);
            }
        }

        report(progress, barcodes.length, barcodes.length, "Expression ready");

        if (ng == 1) {
            cachedGene = genes.get(0);
            cachedCells = plotCellIds.clone();
            cachedValues = out[0].clone();
        } else {
            cachedGene = null;
            cachedCells = null;
            cachedValues = null;
        }
        return out;
    }

    private static void report(Progress progress, int done, int total, String detail) {
        if (progress != null) {
            progress.report(done, total, detail);
        }
    }

    private int resolveFeature(String gene) {
        Integer fi = featureIndex.get(gene);
        if (fi != null) {
            return fi;
        }
        for (Map.Entry<String, Integer> e : featureIndex.entrySet()) {
            if (e.getKey().equalsIgnoreCase(gene)) {
                return e.getValue();
            }
        }
        return -1;
    }

    private void fillMultiFull(
            int[] slotOfFeature, BitSet want, int[] plotOfCol, float[][] out, Progress progress
    ) {
        int[] indices = readInt(indicesDs.getData());
        double[] data = readDouble(dataDs.getData());
        int nCells = barcodes.length;
        for (int j = 0; j < nCells; j++) {
            if (!want.get(j)) {
                continue;
            }
            int start = (int) indptr[j];
            int end = (int) indptr[j + 1];
            int plotIdx = plotOfCol[j];
            for (int k = start; k < end; k++) {
                int row = indices[k];
                if (row < 0 || row >= slotOfFeature.length) {
                    continue;
                }
                int slot = slotOfFeature[row];
                if (slot >= 0) {
                    out[slot][plotIdx] = (float) data[k];
                }
            }
            if (progress != null && (j & 4095) == 0) {
                report(progress, j, nCells, "Reading expression…");
            }
        }
    }

    /**
     * One hyperslab pass over CSC; fills every requested gene for plotted cells.
     */
    private void fillMultiSliced(
            int[] slotOfFeature, int[] featureRows,
            BitSet want, int[] plotOfCol, float[][] out, Progress progress
    ) {
        int nCells = barcodes.length;
        int nGenes = featureRows.length;
        int onlyRow = nGenes == 1 ? featureRows[0] : -1;
        int c0 = 0;
        while (c0 < nCells) {
            int c1 = c0 + 1;
            while (c1 < nCells && (indptr[c1] - indptr[c0]) < SLICE_NNZ) {
                c1++;
            }
            if (c1 <= c0) {
                c1 = Math.min(nCells, c0 + 1);
            }
            long s = indptr[c0];
            long e = indptr[c1];
            long span = e - s;
            if (span <= 0) {
                c0 = c1;
                continue;
            }
            if (span > Integer.MAX_VALUE) {
                c1 = c0 + 1;
                s = indptr[c0];
                e = indptr[c1];
                span = e - s;
            }
            int len = (int) span;
            int[] idx = readInt(indicesDs.getData(new long[]{s}, new int[]{len}));
            double[] dat = readDouble(dataDs.getData(new long[]{s}, new int[]{len}));
            for (int cell = c0; cell < c1; cell++) {
                if (!want.get(cell)) {
                    continue;
                }
                int a = (int) (indptr[cell] - s);
                int b = (int) (indptr[cell + 1] - s);
                int plotIdx = plotOfCol[cell];
                if (onlyRow >= 0) {
                    int hit = binarySearch(idx, a, b, onlyRow);
                    if (hit >= 0) {
                        out[0][plotIdx] = (float) dat[hit];
                    }
                } else {
                    for (int k = a; k < b; k++) {
                        int row = idx[k];
                        if (row < 0 || row >= slotOfFeature.length) {
                            continue;
                        }
                        int slot = slotOfFeature[row];
                        if (slot >= 0) {
                            out[slot][plotIdx] = (float) dat[k];
                        }
                    }
                }
            }
            report(progress, c1, nCells,
                    nGenes == 1 ? "Reading expression…" : ("Reading " + nGenes + " genes…"));
            c0 = c1;
        }
    }

    private static int binarySearch(int[] a, int from, int to, int key) {
        int lo = from;
        int hi = to - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = a[mid];
            if (v < key) {
                lo = mid + 1;
            } else if (v > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        for (int k = from; k < to; k++) {
            if (a[k] == key) {
                return k;
            }
        }
        return -1;
    }

    private static Map<String, Integer> indexMap(String[] ids) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            m.put(ids[i], i);
        }
        return m;
    }

    private static Dataset ds(Group g, String name) {
        Node n = g.getChild(name);
        if (!(n instanceof Dataset d)) {
            throw new IllegalArgumentException("Missing dataset: " + name);
        }
        return d;
    }

    private static String[] readStrings(Dataset ds) {
        Object raw = ds.getData();
        if (raw instanceof String[] s) {
            return s;
        }
        if (raw instanceof Object[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = String.valueOf(arr[i]);
            }
            return out;
        }
        throw new IllegalArgumentException("Bad string ds: " + raw.getClass());
    }

    private static int[] readInt(Object raw) {
        if (raw instanceof int[] a) {
            return a;
        }
        if (raw instanceof long[] a) {
            int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = (int) a[i];
            }
            return out;
        }
        if (raw instanceof short[] a) {
            int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        throw new IllegalArgumentException("Bad int ds: " + raw.getClass());
    }

    private static long[] readLongish(Dataset ds) {
        Object raw = ds.getData();
        if (raw instanceof long[] a) {
            return a;
        }
        if (raw instanceof int[] a) {
            long[] out = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        if (raw instanceof double[] a) {
            long[] out = new long[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = (long) a[i];
            }
            return out;
        }
        throw new IllegalArgumentException("Bad indptr ds: " + raw.getClass());
    }

    private static double[] readDouble(Object raw) {
        if (raw instanceof double[] a) {
            return a;
        }
        if (raw instanceof float[] a) {
            double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        if (raw instanceof int[] a) {
            double[] out = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                out[i] = a[i];
            }
            return out;
        }
        throw new IllegalArgumentException("Bad data ds: " + raw.getClass());
    }

    @Override
    public void close() {
        cachedGene = null;
        cachedCells = null;
        cachedValues = null;
        h5.close();
    }
}
