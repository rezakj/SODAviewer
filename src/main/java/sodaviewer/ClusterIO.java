package sodaviewer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read / remap / write {@code cluster_id} the way iCellR2 and iCellPy expect:
 * update {@code metadata/cell_data.tsv}, and mirror into
 * {@code metadata/clustering/<modality>_cluster_id.tsv} ({@code cell_id} + {@code clusters}).
 */
public final class ClusterIO {
    private ClusterIO() {}

    public static final class WriteResult {
        public final Path cellDataPath;
        public final Path clusteringPath;
        public final int nCells;
        public final int nClusters;

        WriteResult(Path cellDataPath, Path clusteringPath, int nCells, int nClusters) {
            this.cellDataPath = cellDataPath;
            this.clusteringPath = clusteringPath;
            this.nCells = nCells;
            this.nClusters = nClusters;
        }
    }

    /**
     * Apply {@code oldLabel → newLabel} to every value in {@code cellIdToLabel}.
     * Labels not present in the map are left unchanged. Empty / null new labels are skipped.
     */
    public static Map<String, String> applyLabelRemap(
            Map<String, String> cellIdToLabel,
            Map<String, String> oldToNew
    ) {
        Objects.requireNonNull(cellIdToLabel, "cellIdToLabel");
        if (oldToNew == null || oldToNew.isEmpty()) {
            return new LinkedHashMap<>(cellIdToLabel);
        }
        Map<String, String> out = new LinkedHashMap<>(cellIdToLabel.size());
        for (Map.Entry<String, String> e : cellIdToLabel.entrySet()) {
            String cur = e.getValue();
            String mapped = oldToNew.get(cur);
            if (mapped != null && !mapped.isBlank()) {
                out.put(e.getKey(), mapped.trim());
            } else {
                out.put(e.getKey(), cur);
            }
        }
        return out;
    }

    /** Read {@code cell_id → cluster_id} from {@code metadata/cell_data.tsv}. */
    public static Map<String, String> readClusterLabels(Path sodaRoot) throws IOException {
        Path cd = sodaRoot.resolve("metadata").resolve("cell_data.tsv");
        if (!Files.isRegularFile(cd)) {
            throw new IOException("Missing metadata/cell_data.tsv under " + sodaRoot);
        }
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(cd, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                return out;
            }
            String[] header = headerLine.split("\t", -1);
            int cellCol = indexOf(header, "cell_id");
            int clustCol = indexOf(header, "cluster_id");
            if (cellCol < 0) {
                cellCol = 0;
            }
            if (clustCol < 0) {
                throw new IOException("cell_data.tsv has no cluster_id column");
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (cellCol >= parts.length || clustCol >= parts.length) {
                    continue;
                }
                String id = parts[cellCol];
                if (!out.containsKey(id)) {
                    out.put(id, normalize(parts[clustCol]));
                }
            }
        }
        return out;
    }

    /**
     * Overlay session scatter assignments onto a full-archive label map.
     * {@code categories[i]} indexes {@code labels}; cell ids come from {@code sampleCellIds}.
     */
    public static Map<String, String> overlaySampleClusters(
            Map<String, String> base,
            String[] sampleCellIds,
            int[] categories,
            String[] labels
    ) {
        Map<String, String> out = new LinkedHashMap<>(base);
        if (sampleCellIds == null || categories == null || labels == null) {
            return out;
        }
        int n = Math.min(sampleCellIds.length, categories.length);
        for (int i = 0; i < n; i++) {
            String id = sampleCellIds[i];
            if (id == null || id.isBlank()) {
                continue;
            }
            int c = categories[i];
            if (c < 0 || c >= labels.length || labels[c] == null) {
                continue;
            }
            out.put(id, labels[c].trim());
        }
        return out;
    }

    /**
     * Rewrite {@code metadata/cell_data.tsv} {@code cluster_id} and the clustering sidecar.
     * Atomic replace for cell_data (temp + move).
     */
    public static WriteResult writeClusters(
            Path sodaRoot,
            Map<String, String> cellIdToCluster,
            String modality
    ) throws IOException {
        Objects.requireNonNull(sodaRoot, "sodaRoot");
        Objects.requireNonNull(cellIdToCluster, "cellIdToCluster");
        String mod = (modality == null || modality.isBlank())
                ? "rna"
                : modality.trim().toLowerCase(Locale.ROOT);

        Path meta = sodaRoot.resolve("metadata");
        Path cellData = meta.resolve("cell_data.tsv");
        if (!Files.isRegularFile(cellData)) {
            throw new IOException("Missing metadata/cell_data.tsv under " + sodaRoot);
        }

        int nWritten = rewriteCellDataClusterId(cellData, cellIdToCluster);
        Path sidecar = writeClusteringSidecar(meta, mod, cellIdToCluster);

        Set<String> uniq = new LinkedHashSet<>();
        for (String v : cellIdToCluster.values()) {
            if (v != null && !v.isBlank() && !"NA".equalsIgnoreCase(v)) {
                uniq.add(v);
            }
        }
        return new WriteResult(cellData, sidecar, nWritten, uniq.size());
    }

    private static int rewriteCellDataClusterId(Path cellData, Map<String, String> cellIdToCluster)
            throws IOException {
        List<String> lines = Files.readAllLines(cellData, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("Empty cell_data.tsv");
        }
        String[] header = lines.get(0).split("\t", -1);
        int cellCol = indexOf(header, "cell_id");
        int clustCol = indexOf(header, "cluster_id");
        if (cellCol < 0) {
            cellCol = 0;
        }
        if (clustCol < 0) {
            throw new IOException("cell_data.tsv has no cluster_id column");
        }

        Path tmp = cellData.resolveSibling("cell_data.tsv.tmp." + ProcessHandle.current().pid());
        int n = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            bw.write(lines.get(0));
            bw.newLine();
            for (int li = 1; li < lines.size(); li++) {
                String line = lines.get(li);
                if (line.isEmpty()) {
                    bw.newLine();
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (cellCol < parts.length) {
                    String id = parts[cellCol];
                    String neu = cellIdToCluster.get(id);
                    if (neu != null) {
                        if (clustCol >= parts.length) {
                            String[] grow = new String[clustCol + 1];
                            System.arraycopy(parts, 0, grow, 0, parts.length);
                            for (int i = parts.length; i < grow.length; i++) {
                                grow[i] = "";
                            }
                            parts = grow;
                        }
                        parts[clustCol] = neu;
                        n++;
                    }
                }
                bw.write(String.join("\t", parts));
                bw.newLine();
            }
        }
        atomicReplace(tmp, cellData);
        // Drop legacy twins iCellR2/iCellPy refuse to keep alongside .tsv
        Files.deleteIfExists(cellData.resolveSibling("cell_data.rds"));
        Files.deleteIfExists(cellData.resolveSibling("cell_data.parquet"));
        return n;
    }

    /**
     * Write {@code metadata/clustering/<mod>_cluster_id.tsv} with {@code cell_id} + {@code clusters}
     * (same columns as iCellR2 / iCellPy {@code run.clustering}). Preserves an existing
     * {@code cluster_id} column name if that file already uses it (R {@code change.clust}).
     */
    private static Path writeClusteringSidecar(
            Path meta,
            String modality,
            Map<String, String> cellIdToCluster
    ) throws IOException {
        Path clDir = meta.resolve("clustering");
        Files.createDirectories(clDir);
        Path path = clDir.resolve(modality + "_cluster_id.tsv");
        String valueCol = "clusters";
        if (Files.isRegularFile(path)) {
            try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String h = br.readLine();
                if (h != null) {
                    String[] cols = h.split("\t", -1);
                    if (indexOf(cols, "cluster_id") >= 0 && indexOf(cols, "clusters") < 0) {
                        valueCol = "cluster_id";
                    }
                }
            }
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + ProcessHandle.current().pid());
        try (BufferedWriter bw = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            bw.write("cell_id\t" + valueCol);
            bw.newLine();
            for (Map.Entry<String, String> e : cellIdToCluster.entrySet()) {
                bw.write(e.getKey());
                bw.write('\t');
                bw.write(e.getValue() == null ? "NA" : e.getValue());
                bw.newLine();
            }
        }
        atomicReplace(tmp, path);
        Files.deleteIfExists(clDir.resolve(modality + "_cluster_id.rds"));
        return path;
    }

    private static void atomicReplace(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Prefer {@code rna}; else first {@code *_cluster_id.tsv} stem modality. */
    public static String detectModality(Path sodaRoot) {
        Path clDir = sodaRoot.resolve("metadata").resolve("clustering");
        if (!Files.isDirectory(clDir)) {
            return "rna";
        }
        Path rna = clDir.resolve("rna_cluster_id.tsv");
        if (Files.isRegularFile(rna)) {
            return "rna";
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(clDir, "*_cluster_id.tsv")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.endsWith("_cluster_id.tsv")) {
                    return name.substring(0, name.length() - "_cluster_id.tsv".length());
                }
            }
        } catch (IOException ignored) {
        }
        return "rna";
    }

    public static List<String> uniqueSortedLabels(Map<String, String> cellIdToLabel) {
        Set<String> raw = new LinkedHashSet<>();
        for (String v : cellIdToLabel.values()) {
            if (v != null && !v.isBlank() && !"NA".equalsIgnoreCase(v)) {
                raw.add(v);
            }
        }
        List<String> levels = new ArrayList<>(raw);
        boolean allInt = !levels.isEmpty();
        for (String v : levels) {
            try {
                Integer.parseInt(v);
            } catch (NumberFormatException e) {
                allInt = false;
                break;
            }
        }
        if (allInt) {
            levels.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
        } else {
            levels.sort(String::compareTo);
        }
        return levels;
    }

    private static String normalize(String s) {
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

    private static int indexOf(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) {
            if (name.equalsIgnoreCase(cols[i].trim())) {
                return i;
            }
        }
        return -1;
    }
}
