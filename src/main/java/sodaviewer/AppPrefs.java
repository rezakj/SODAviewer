package sodaviewer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;

/** Persistent UI prefs: last SODA, plot type, recent genes, max plot cells. */
public final class AppPrefs {
    private static final Preferences P = Preferences.userRoot().node("sodaviewer");
    private static final String LAST_SODA = "lastSoda";
    private static final String PLOT_TYPE = "plotType";
    private static final String RECENT_GENES = "recentGenes";
    private static final String MAX_PLOT_CELLS = "maxPlotCells";
    private static final int MAX_RECENT = 8;

    /** Preset caps for View → Max cells to plot (0 = all). */
    public static final int[] MAX_PLOT_CELL_OPTIONS = {
            10_000, 50_000, 100_000, 250_000, 350_000, 500_000, 750_000, 1_000_000, 0
    };

    private AppPrefs() {}

    public static String lastSoda() {
        return P.get(LAST_SODA, "");
    }

    public static void setLastSoda(File dir) {
        if (dir != null) {
            P.put(LAST_SODA, dir.getAbsolutePath());
        }
    }

    public static String plotType() {
        return P.get(PLOT_TYPE, "FEATURE");
    }

    public static void setPlotType(String name) {
        if (name != null && !name.isBlank()) {
            P.put(PLOT_TYPE, name);
        }
    }

    /**
     * Max cells drawn / used in gene plots. {@code <= 0} means no cap (all cells).
     * Default {@link PlotSample#DEFAULT_MAX_CELLS}.
     */
    public static int maxPlotCells() {
        return P.getInt(MAX_PLOT_CELLS, PlotSample.DEFAULT_MAX_CELLS);
    }

    public static void setMaxPlotCells(int n) {
        P.putInt(MAX_PLOT_CELLS, n);
    }

    public static String maxPlotCellsLabel(int n) {
        if (n <= 0) {
            return "All cells";
        }
        if (n >= 1_000) {
            return String.format("%,d", n);
        }
        return Integer.toString(n);
    }

    public static List<String> recentGenes() {
        String raw = P.get(RECENT_GENES, "");
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : raw.split("\\|")) {
            String g = p.trim();
            if (!g.isEmpty()) {
                out.add(g);
            }
        }
        return out;
    }

    public static void rememberGenes(List<String> genes) {
        if (genes == null || genes.isEmpty()) {
            return;
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String g : genes) {
            if (g != null && !g.isBlank()) {
                ordered.add(g.trim());
            }
        }
        for (String g : recentGenes()) {
            ordered.add(g);
        }
        List<String> keep = new ArrayList<>();
        for (String g : ordered) {
            keep.add(g);
            if (keep.size() >= MAX_RECENT) {
                break;
            }
        }
        P.put(RECENT_GENES, String.join("|", keep));
    }

    public static File lastSodaDir() {
        String p = lastSoda();
        if (p.isBlank()) {
            return null;
        }
        File f = new File(p);
        return f.isDirectory() ? f : null;
    }
}
