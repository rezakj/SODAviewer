package sodaviewer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Columns from {@code metadata/cell_data.tsv} for color-by (clusters,
 * conditions, pseudotime, QC metrics, …).
 */
public final class CellMetadata {
    public enum Kind { CATEGORICAL, CONTINUOUS }

    /** One colorable metadata field. */
    public static final class Field {
        public final String column;   // raw TSV header
        public final String label;    // UI label
        public final Kind kind;

        Field(String column, String label, Kind kind) {
            this.column = column;
            this.label = label;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Map<String, Integer> cellRow;           // cell_id → row
    private final List<String> cellOrder;
    private final Map<String, String[]> catCols;          // column → values[row]
    private final Map<String, float[]> numCols;           // column → values[row]
    private final List<Field> fields;

    private CellMetadata(
            Map<String, Integer> cellRow,
            List<String> cellOrder,
            Map<String, String[]> catCols,
            Map<String, float[]> numCols,
            List<Field> fields
    ) {
        this.cellRow = cellRow;
        this.cellOrder = List.copyOf(cellOrder);
        this.catCols = catCols;
        this.numCols = numCols;
        this.fields = List.copyOf(fields);
    }

    public static CellMetadata load(Path sodaRoot) throws IOException {
        Path cd = sodaRoot.resolve("metadata").resolve("cell_data.tsv");
        if (!Files.isRegularFile(cd)) {
            return new CellMetadata(Map.of(), List.of(), Map.of(), Map.of(), List.of());
        }

        List<String[]> rows = new ArrayList<>();
        String[] header;
        try (BufferedReader br = Files.newBufferedReader(cd, StandardCharsets.UTF_8)) {
            String h = br.readLine();
            if (h == null) {
                return new CellMetadata(Map.of(), List.of(), Map.of(), Map.of(), List.of());
            }
            header = h.split("\t", -1);
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) {
                    rows.add(line.split("\t", -1));
                }
            }
        }

        int cellCol = indexOf(header, "cell_id");
        if (cellCol < 0) {
            cellCol = 0;
        }

        Map<String, Integer> cellRow = new LinkedHashMap<>();
        List<String> cellOrder = new ArrayList<>();
        for (String[] p : rows) {
            if (cellCol >= p.length) {
                continue;
            }
            String id = p[cellCol];
            if (!cellRow.containsKey(id)) {
                cellRow.put(id, cellOrder.size());
                cellOrder.add(id);
            }
        }
        int n = cellOrder.size();

        // Preferred color-by columns (label, continuous?)
        String[][] preferred = {
                {"cluster_id", "Clusters", "cat"},
                {"condition_id", "Conditions", "cat"},
                {"sample_id", "Samples", "cat"},
                {"Pseudotime", "Pseudotime", "num"},
                {"branch_id", "Branches", "cat"},
                {"mito.percent", "Mito %", "num"},
                {"nGenes", "nGenes", "num"},
                {"UMIs", "UMIs", "num"},
                {"S.phase.probability", "S phase", "num"},
                {"g2m.phase.probability", "G2M phase", "num"},
                {"donor_id", "Donors", "cat"},
                {"batch_id", "Batches", "cat"},
                {"entity_type", "Entity type", "cat"}
        };

        Map<String, String[]> catCols = new LinkedHashMap<>();
        Map<String, float[]> numCols = new LinkedHashMap<>();
        List<Field> fields = new ArrayList<>();

        for (String[] pref : preferred) {
            String col = pref[0];
            String label = pref[1];
            boolean wantNum = "num".equals(pref[2]);
            int ci = indexOf(header, col);
            if (ci < 0) {
                continue;
            }
            if (wantNum) {
                float[] vals = new float[n];
                int finite = 0;
                for (String[] p : rows) {
                    if (cellCol >= p.length) {
                        continue;
                    }
                    Integer ri = cellRow.get(p[cellCol]);
                    if (ri == null || ci >= p.length) {
                        continue;
                    }
                    float v = parseFloat(p[ci]);
                    vals[ri] = v;
                    if (Float.isFinite(v)) {
                        finite++;
                    }
                }
                if (finite < n * 0.05) {
                    continue; // almost all missing
                }
                // skip if essentially constant
                float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
                for (float v : vals) {
                    if (Float.isFinite(v)) {
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
                if (!(hi > lo)) {
                    continue;
                }
                numCols.put(col, vals);
                fields.add(new Field(col, label, Kind.CONTINUOUS));
            } else {
                String[] vals = new String[n];
                Set<String> uniq = new LinkedHashSet<>();
                int nonNa = 0;
                for (String[] p : rows) {
                    if (cellCol >= p.length) {
                        continue;
                    }
                    Integer ri = cellRow.get(p[cellCol]);
                    if (ri == null || ci >= p.length) {
                        continue;
                    }
                    String v = normalizeCat(p[ci]);
                    vals[ri] = v;
                    if (!"NA".equals(v)) {
                        uniq.add(v);
                        nonNa++;
                    }
                }
                if (nonNa < 1 || uniq.isEmpty()) {
                    continue;
                }
                // skip if only one real level
                if (uniq.size() < 1) {
                    continue;
                }
                catCols.put(col, vals);
                fields.add(new Field(col, label, Kind.CATEGORICAL));
            }
        }

        return new CellMetadata(cellRow, cellOrder, catCols, numCols, fields);
    }

    public List<Field> fields() {
        return fields;
    }

    public boolean hasClusters() {
        return catCols.containsKey("cluster_id");
    }

    public List<String> clusterLevels() {
        return categoricalLevels("cluster_id");
    }

    public int[] clusterIndices(String[] plotCellIds) {
        return categoricalIndices(plotCellIds, "cluster_id");
    }

    public List<String> categoricalLevels(String column) {
        String[] vals = catCols.get(column);
        if (vals == null) {
            return List.of();
        }
        Set<String> raw = new LinkedHashSet<>();
        boolean hasNa = false;
        for (String v : vals) {
            if (v == null || "NA".equals(v)) {
                hasNa = true;
            } else {
                raw.add(v);
            }
        }
        List<String> levels = sortLevels(new ArrayList<>(raw));
        if (hasNa) {
            levels.add("NA");
        }
        return levels;
    }

    public int[] categoricalIndices(String[] plotCellIds, String column) {
        String[] vals = catCols.get(column);
        List<String> levels = categoricalLevels(column);
        Map<String, Integer> levelIndex = new LinkedHashMap<>();
        for (int i = 0; i < levels.size(); i++) {
            levelIndex.put(levels.get(i), i);
        }
        int[] out = new int[plotCellIds.length];
        for (int i = 0; i < plotCellIds.length; i++) {
            out[i] = -1;
            Integer ri = cellRow.get(plotCellIds[i]);
            if (ri == null || vals == null || ri >= vals.length) {
                continue;
            }
            String v = vals[ri] == null ? "NA" : vals[ri];
            Integer ix = levelIndex.get(v);
            out[i] = ix == null ? -1 : ix;
        }
        return out;
    }

    /** Continuous values aligned to plot cell ids (NaN if missing). */
    public float[] continuousValues(String[] plotCellIds, String column) {
        float[] src = numCols.get(column);
        float[] out = new float[plotCellIds.length];
        for (int i = 0; i < plotCellIds.length; i++) {
            Integer ri = cellRow.get(plotCellIds[i]);
            if (ri == null || src == null || ri >= src.length) {
                out[i] = Float.NaN;
            } else {
                out[i] = src[ri];
            }
        }
        return out;
    }

    private static List<String> sortLevels(List<String> raw) {
        boolean allInt = !raw.isEmpty();
        for (String v : raw) {
            try {
                Integer.parseInt(v);
            } catch (NumberFormatException e) {
                allInt = false;
                break;
            }
        }
        if (allInt) {
            List<Integer> nums = new ArrayList<>();
            for (String v : raw) {
                nums.add(Integer.parseInt(v));
            }
            Collections.sort(nums);
            List<String> out = new ArrayList<>();
            for (Integer n : nums) {
                out.add(String.valueOf(n));
            }
            return out;
        }
        boolean allNum = !raw.isEmpty();
        for (String v : raw) {
            try {
                Double.parseDouble(v);
            } catch (NumberFormatException e) {
                allNum = false;
                break;
            }
        }
        if (allNum) {
            raw.sort((a, b) -> Double.compare(Double.parseDouble(a), Double.parseDouble(b)));
            return raw;
        }
        List<String> out = new ArrayList<>(raw);
        Collections.sort(out);
        return out;
    }

    private static String normalizeCat(String s) {
        if (s == null) {
            return "NA";
        }
        String v = s.trim();
        if (v.isEmpty() || "NA".equalsIgnoreCase(v) || "nan".equalsIgnoreCase(v)
                || "null".equalsIgnoreCase(v)) {
            return "NA";
        }
        return v;
    }

    private static float parseFloat(String s) {
        if (s == null) {
            return Float.NaN;
        }
        String v = s.trim();
        if (v.isEmpty() || "NA".equalsIgnoreCase(v) || "nan".equalsIgnoreCase(v)) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static int indexOf(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) {
            if (name.equalsIgnoreCase(cols[i].trim())) {
                return i;
            }
        }
        // also try case-insensitive without dots/underscores
        String key = name.toLowerCase(Locale.ROOT).replace(".", "").replace("_", "");
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toLowerCase(Locale.ROOT).replace(".", "").replace("_", "");
            if (c.equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
