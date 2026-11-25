package sodaviewer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight handle for an on-disk SODA archive (schema {@link AppVersion#COMPATIBLE_SCHEMA_VERSION}).
 * Discovers reductions under {@code reductions/} and {@code modalities/<mod>/reductions/}
 * (same as iCellR2 {@code open.soda}).
 */
public final class SodaArchive {
    private static final Pattern JSON_STRING =
            Pattern.compile("\"(soda_version|schema_version|package_version)\"\\s*:\\s*\"([^\"]*)\"");

    private final Path root;
    private final List<String> reductions;
    private final Map<String, Path> reductionDirs;
    private final Map<String, Integer> cellIndex;
    private final List<String> cellIds;
    private final String sodaVersion;
    private final String schemaVersion;
    private final String packageVersion;

    private SodaArchive(
            Path root,
            List<String> reductions,
            Map<String, Path> reductionDirs,
            List<String> cellIds,
            String sodaVersion,
            String schemaVersion,
            String packageVersion
    ) {
        this.root = root;
        this.reductions = List.copyOf(reductions);
        this.reductionDirs = Collections.unmodifiableMap(new LinkedHashMap<>(reductionDirs));
        this.cellIds = List.copyOf(cellIds);
        this.cellIndex = indexCells(this.cellIds);
        this.sodaVersion = sodaVersion;
        this.schemaVersion = schemaVersion;
        this.packageVersion = packageVersion;
    }

    private static Map<String, Integer> indexCells(List<String> cellIds) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < cellIds.size(); i++) {
            idx.put(cellIds.get(i), i);
        }
        return Collections.unmodifiableMap(idx);
    }

    public static SodaArchive open(Path path) throws IOException {
        Path root = resolveRoot(path);
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a SODA directory: " + path);
        }
        Map<String, Path> redDirs = discoverReductions(root);
        if (redDirs.isEmpty()) {
            throw new IOException(
                    "No reductions/*/cell_embeddings.h5 found under "
                            + root
                            + " (checked reductions/ and modalities/*/reductions/)");
        }
        List<String> reds = new ArrayList<>(redDirs.keySet());
        Collections.sort(reds);
        List<String> cells = readCellIds(root);
        ManifestVersions ver = readManifestVersions(root);
        return new SodaArchive(
                root, reds, redDirs, cells, ver.sodaVersion, ver.schemaVersion, ver.packageVersion);
    }

    /**
     * Root {@code reductions/} first, then {@code modalities/<mod>/reductions/}.
     * Root wins on name collision (matches iCellR2 open.soda preference for RNA).
     */
    private static Map<String, Path> discoverReductions(Path root) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        collectReductions(root.resolve("reductions"), out, false);
        Path mods = root.resolve("modalities");
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                for (Path mod : ds) {
                    if (!Files.isDirectory(mod)) {
                        continue;
                    }
                    collectReductions(mod.resolve("reductions"), out, true);
                }
            }
        }
        return out;
    }

    private static void collectReductions(Path redRoot, Map<String, Path> out, boolean skipExisting)
            throws IOException {
        if (!Files.isDirectory(redRoot)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(redRoot)) {
            for (Path d : ds) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                if (!Files.isRegularFile(d.resolve("cell_embeddings.h5"))) {
                    continue;
                }
                String name = d.getFileName().toString();
                if (skipExisting && out.containsKey(name)) {
                    continue;
                }
                out.put(name, d);
            }
        }
    }

    private static Path resolveRoot(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (Files.isDirectory(p) && p.getFileName() != null
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".soda")) {
            return p;
        }
        Path sibling = Path.of(p.toString() + ".soda");
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        return p;
    }

    private static List<String> readCellIds(Path root) throws IOException {
        Path cd = root.resolve("metadata").resolve("cell_data.tsv");
        if (!Files.isRegularFile(cd)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(cd, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                return ids;
            }
            String[] cols = header.split("\t", -1);
            int cellCol = 0;
            for (int i = 0; i < cols.length; i++) {
                if ("cell_id".equalsIgnoreCase(cols[i])) {
                    cellCol = i;
                    break;
                }
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (cellCol < parts.length) {
                    ids.add(parts[cellCol]);
                }
            }
        }
        return ids;
    }

    private static final class ManifestVersions {
        final String sodaVersion;
        final String schemaVersion;
        final String packageVersion;

        ManifestVersions(String sodaVersion, String schemaVersion, String packageVersion) {
            this.sodaVersion = sodaVersion;
            this.schemaVersion = schemaVersion;
            this.packageVersion = packageVersion;
        }
    }

    /** Best-effort parse of {@code soda.json} version fields (no JSON dependency). */
    private static ManifestVersions readManifestVersions(Path root) {
        String soda = null;
        String schema = null;
        String pkg = null;
        Path man = root.resolve("soda.json");
        if (Files.isRegularFile(man)) {
            try {
                String text = Files.readString(man, StandardCharsets.UTF_8);
                Matcher m = JSON_STRING.matcher(text);
                while (m.find()) {
                    switch (m.group(1)) {
                        case "soda_version" -> {
                            if (soda == null) {
                                soda = m.group(2);
                            }
                        }
                        case "schema_version" -> {
                            if (schema == null) {
                                schema = m.group(2);
                            }
                        }
                        case "package_version" -> {
                            if (pkg == null) {
                                pkg = m.group(2);
                            }
                        }
                        default -> {
                        }
                    }
                }
            } catch (IOException ignored) {
                // leave nulls
            }
        }
        if (soda == null || schema == null || pkg == null) {
            Path ver = root.resolve("metadata").resolve("version.json");
            if (Files.isRegularFile(ver)) {
                try {
                    String text = Files.readString(ver, StandardCharsets.UTF_8);
                    Matcher m = JSON_STRING.matcher(text);
                    while (m.find()) {
                        switch (m.group(1)) {
                            case "soda_version" -> {
                                if (soda == null) {
                                    soda = m.group(2);
                                }
                            }
                            case "schema_version" -> {
                                if (schema == null) {
                                    schema = m.group(2);
                                }
                            }
                            case "package_version" -> {
                                if (pkg == null) {
                                    pkg = m.group(2);
                                }
                            }
                            default -> {
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // leave nulls
                }
            }
        }
        return new ManifestVersions(
                soda != null ? soda : AppVersion.COMPATIBLE_SODA_VERSION,
                schema != null ? schema : AppVersion.COMPATIBLE_SCHEMA_VERSION,
                pkg);
    }

    public Path root() {
        return root;
    }

    public List<String> reductions() {
        return reductions;
    }

    public List<String> cellIds() {
        return cellIds;
    }

    public Map<String, Integer> cellIndex() {
        return cellIndex;
    }

    public String sodaVersion() {
        return sodaVersion;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    /** Producer package version from the archive, or {@code null} if unknown. */
    public String packageVersion() {
        return packageVersion;
    }

    public Path embeddingPath(String reduction) {
        Path dir = reductionDirs.get(reduction);
        if (dir != null) {
            return dir.resolve("cell_embeddings.h5");
        }
        return root.resolve("reductions").resolve(reduction).resolve("cell_embeddings.h5");
    }

    /**
     * Assay folders under {@code modalities/rna/assays/} that look loadable
     * ({@code assay_source.h5}, top-level {@code *.h5}, or nested sample {@code *.h5}).
     */
    public List<String> assayNames() {
        List<String> out = new ArrayList<>();
        Path assays = root.resolve("modalities").resolve("rna").resolve("assays");
        if (!Files.isDirectory(assays)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(assays)) {
            for (Path d : ds) {
                if (!Files.isDirectory(d)) {
                    continue;
                }
                if (resolveAssayH5(d) != null) {
                    out.add(d.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(out);
        return out;
    }

    /** Prefer {@code assay_source.h5}, else top-level or nested sample {@code *.h5}. */
    public Path assayPath(String assayName) {
        if (assayName == null || assayName.isBlank()) {
            return assayPath();
        }
        return resolveAssayH5(
                root.resolve("modalities").resolve("rna").resolve("assays").resolve(assayName));
    }

    /** Default / legacy: normalized {@code data} assay, then counts fallback. */
    public Path assayPath() {
        Path data = resolveAssayH5(
                root.resolve("modalities").resolve("rna").resolve("assays").resolve("data"));
        if (data != null) {
            return data;
        }
        // fallback: first counts sample h5
        Path counts = root.resolve("modalities").resolve("rna").resolve("assays").resolve("counts");
        if (Files.isDirectory(counts)) {
            try (DirectoryStream<Path> samples = Files.newDirectoryStream(counts)) {
                for (Path sample : samples) {
                    if (!Files.isDirectory(sample)) {
                        continue;
                    }
                    Path named = sample.resolve(sample.getFileName().toString() + ".h5");
                    if (Files.isRegularFile(named)) {
                        return named;
                    }
                    try (DirectoryStream<Path> h5s = Files.newDirectoryStream(sample, "*.h5")) {
                        for (Path h : h5s) {
                            return h;
                        }
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        return root.resolve("modalities").resolve("rna").resolve("assays").resolve("data")
                .resolve("assay_source.h5");
    }

    /**
     * Resolve a loadable matrix under an assay folder.
     * Layouts supported:
     * <ul>
     *   <li>{@code assay_source.h5} (materialized norm / imputed)</li>
     *   <li>any {@code *.h5} directly in the assay folder</li>
     *   <li>per-sample dirs, e.g. {@code data/my340k/my340k.h5} (large demos)</li>
     * </ul>
     */
    private static Path resolveAssayH5(Path assayDir) {
        if (assayDir == null || !Files.isDirectory(assayDir)) {
            return null;
        }
        Path source = assayDir.resolve("assay_source.h5");
        if (Files.isRegularFile(source)) {
            return source;
        }
        try (DirectoryStream<Path> h5s = Files.newDirectoryStream(assayDir, "*.h5")) {
            for (Path h : h5s) {
                return h;
            }
        } catch (IOException ignored) {
        }
        // Nested sample folders (counts / data without assay_source.h5)
        try (DirectoryStream<Path> samples = Files.newDirectoryStream(assayDir)) {
            for (Path sample : samples) {
                if (!Files.isDirectory(sample)) {
                    continue;
                }
                Path named = sample.resolve(sample.getFileName().toString() + ".h5");
                if (Files.isRegularFile(named)) {
                    return named;
                }
                try (DirectoryStream<Path> h5s = Files.newDirectoryStream(sample, "*.h5")) {
                    for (Path h : h5s) {
                        return h;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    @Override
    public String toString() {
        return Objects.toString(root.getFileName(), root.toString());
    }
}
