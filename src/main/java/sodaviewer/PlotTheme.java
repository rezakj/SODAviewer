package sodaviewer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared publication styling — mirrors iCellR2 open-axis cluster.plot theme
 * ({@code #2C2C2C} ink, muted ticks, L-shaped axes, no panel box).
 */
public final class PlotTheme {
    public static final Color INK = new Color(0x2C, 0x2C, 0x2C);
    public static final Color MUTE = new Color(0x6B, 0x6B, 0x6B);
    public static final Color FAINT = new Color(0xE8, 0xE8, 0xE8);
    public static final Color PAPER = Color.WHITE;
    public static final Color GENE_LO = new Color(0xE6, 0xE6, 0xE6); // grey90-ish
    public static final Color GENE_HI = new Color(0xB2, 0x18, 0x2B);

    public static final float AXIS_STROKE = 1.1f;
    public static final float TICK_STROKE = 0.9f;
    public static final int TICK_LEN = 5;

    private PlotTheme() {}

    public static void qualityHints(Graphics2D g, boolean heavy) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                heavy ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    public static Font titleFont(Font base) {
        return prefer(base, Font.BOLD, 15f);
    }

    public static Font subtitleFont(Font base) {
        return prefer(base, Font.PLAIN, 11f);
    }

    public static Font axisTitleFont(Font base) {
        return prefer(base, Font.PLAIN, 12f);
    }

    public static Font tickFont(Font base) {
        return prefer(base, Font.PLAIN, 10.5f);
    }

    public static Font legendFont(Font base) {
        return prefer(base, Font.PLAIN, 11f);
    }

    public static Font annoFont(Font base) {
        return prefer(base, Font.BOLD, 12f);
    }

    private static Font prefer(Font base, int style, float size) {
        String[] names = {
                "Avenir Next", "Helvetica Neue", "Segoe UI", "Arial", Font.SANS_SERIF
        };
        for (String n : names) {
            Font f = new Font(n, style, Math.round(size));
            // If the requested family is available, Java keeps it in getFamily()/getFontName()
            if (f.getFamily().toLowerCase().contains(n.toLowerCase().split(" ")[0].toLowerCase())
                    || n.equals(Font.SANS_SERIF)
                    || f.getFontName().toLowerCase().contains(n.toLowerCase().split(" ")[0].toLowerCase())) {
                return f.deriveFont(style, size);
            }
        }
        return base.deriveFont(style, size);
    }

    public static void clearPaper(Graphics2D g, int w, int h) {
        g.setColor(PAPER);
        g.fillRect(0, 0, w, h);
    }

    /** Open L-shaped axes with ticks + numeric labels (ggplot classic style). */
    public static void drawOpenAxes(
            Graphics2D g, Font base,
            int left, int top, int pw, int ph,
            double minX, double maxX, double minY, double maxY,
            String xlab, String ylab
    ) {
        g.setColor(INK);
        g.setStroke(new BasicStroke(AXIS_STROKE, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        // L spine
        g.draw(new Line2D.Double(left, top + ph, left + pw, top + ph));
        g.draw(new Line2D.Double(left, top, left, top + ph));

        g.setStroke(new BasicStroke(TICK_STROKE, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setFont(tickFont(base));
        FontMetrics fm = g.getFontMetrics();

        for (double xv : niceTicks(minX, maxX, 5)) {
            int px = left + (int) Math.round((xv - minX) / (maxX - minX) * pw);
            g.setColor(INK);
            g.drawLine(px, top + ph, px, top + ph + TICK_LEN);
            String lab = formatTick(xv);
            g.setColor(MUTE);
            g.drawString(lab, px - fm.stringWidth(lab) / 2, top + ph + TICK_LEN + fm.getAscent() + 1);
        }
        for (double yv : niceTicks(minY, maxY, 5)) {
            int py = top + ph - (int) Math.round((yv - minY) / (maxY - minY) * ph);
            g.setColor(INK);
            g.drawLine(left - TICK_LEN, py, left, py);
            String lab = formatTick(yv);
            g.setColor(MUTE);
            g.drawString(lab, left - TICK_LEN - 4 - fm.stringWidth(lab), py + fm.getAscent() / 2 - 1);
        }

        g.setFont(axisTitleFont(base));
        g.setColor(INK);
        FontMetrics fm2 = g.getFontMetrics();
        g.drawString(xlab, left + (pw - fm2.stringWidth(xlab)) / 2, top + ph + 36);

        // rotated ylab
        var old = g.getTransform();
        g.rotate(-Math.PI / 2);
        g.drawString(ylab, -(top + ph / 2 + fm2.stringWidth(ylab) / 2), 22);
        g.setTransform(old);
    }

    public static void drawTitle(Graphics2D g, Font base, String title, String subtitle, int left, int y) {
        g.setFont(titleFont(base));
        g.setColor(INK);
        g.drawString(title, left, y);
        if (subtitle != null && !subtitle.isEmpty()) {
            g.setFont(subtitleFont(base));
            g.setColor(MUTE);
            g.drawString(subtitle, left, y + 16);
        }
    }

    public static void drawColorBar(
            Graphics2D g, Font base,
            int x, int y, int bw, int bh,
            float lo, float hi, String name
    ) {
        drawColorBar(g, base, x, y, bw, bh, lo, hi, name, ColorScale.GREY_RED);
    }

    public static void drawColorBar(
            Graphics2D g, Font base,
            int x, int y, int bw, int bh,
            float lo, float hi, String name, ColorScale scale
    ) {
        for (int i = 0; i < bh; i++) {
            float t = 1f - (float) i / Math.max(1, bh - 1);
            g.setColor(scaleColor(scale, t, 1f));
            g.fillRect(x, y + i, bw, 1);
        }
        g.setColor(INK);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRect(x, y, bw, bh);
        g.setFont(tickFont(base));
        FontMetrics fm = g.getFontMetrics();
        String hiS = formatTick(hi);
        String loS = formatTick(lo);
        g.setColor(MUTE);
        g.drawString(hiS, x + bw + 5, y + fm.getAscent());
        g.drawString(loS, x + bw + 5, y + bh);
        if (name != null) {
            g.setFont(legendFont(base));
            g.setColor(INK);
            g.drawString(name, x - 2, y - 6);
        }
    }

    /** Continuous scales for gene expression (t in 0..1). */
    public enum ColorScale {
        GREY_RED("Gray → White → Red"),
        BLUE_WHITE_RED("Blue → White → Red"),
        BLUE_RED("Blue → Red"),
        VIRIDIS("Viridis"),
        MAGMA("Magma"),
        INFERNO("Inferno"),
        PLASMA("Plasma"),
        CIVIDIS("Cividis"),
        TURBO("Turbo"),
        YL_OR_RD("Yellow → Orange → Red"),
        GREY_BLUE("Grey → Blue");

        public final String label;

        ColorScale(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Discrete palettes for clusters / conditions / samples. */
    public enum CatPalette {
        DEFAULT("Default"),
        TABLEAU("Tableau"),
        PASTEL("Pastel"),
        VIVID("Vivid"),
        OKABE("Okabe–Ito");

        public final String label;

        CatPalette(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static CatPalette catPalette = CatPalette.DEFAULT;

    public static void setCatPalette(CatPalette p) {
        catPalette = p == null ? CatPalette.DEFAULT : p;
    }

    public static CatPalette getCatPalette() {
        return catPalette;
    }

    public static Color scaleColor(ColorScale scale, float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        if (scale == null) {
            scale = ColorScale.GREY_RED;
        }
        return switch (scale) {
            case BLUE_WHITE_RED -> blueWhiteRed(t, alpha);
            case BLUE_RED -> blueToRed(t, alpha);
            case VIRIDIS -> viridis(t, alpha);
            case MAGMA -> magma(t, alpha);
            case INFERNO -> inferno(t, alpha);
            case PLASMA -> plasma(t, alpha);
            case CIVIDIS -> cividis(t, alpha);
            case TURBO -> turbo(t, alpha);
            case YL_OR_RD -> ylOrRd(t, alpha);
            case GREY_BLUE -> greyBlue(t, alpha);
            default -> greyRed(t, alpha);
        };
    }

    /** Classic RdBu-style diverging (blue → white → red). */
    public static Color blueWhiteRed(float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float r, gv, b;
        if (t < 0.5f) {
            float u = t * 2f;
            // #2166AC → #F7F7F7
            r = 0.129f + (0.969f - 0.129f) * u;
            gv = 0.400f + (0.969f - 0.400f) * u;
            b = 0.675f + (0.969f - 0.675f) * u;
        } else {
            float u = (t - 0.5f) * 2f;
            // #F7F7F7 → #B2182B
            r = 0.969f + (0.698f - 0.969f) * u;
            gv = 0.969f + (0.094f - 0.969f) * u;
            b = 0.969f + (0.169f - 0.969f) * u;
        }
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    /** Alias for {@link #blueWhiteRed}. */
    public static Color blueRed(float t, float alpha) {
        return blueWhiteRed(t, alpha);
    }

    /** Sequential blue → pale → red (no hard white mid). */
    public static Color blueToRed(float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float r = 0.13f + (0.70f - 0.13f) * t;
        float gv = 0.34f + (0.09f - 0.34f) * t;
        float b = 0.79f + (0.17f - 0.79f) * t;
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    public static Color inferno(float t, float alpha) {
        float[][] stops = {
                {0.001f, 0.000f, 0.014f},
                {0.232f, 0.060f, 0.358f},
                {0.550f, 0.161f, 0.505f},
                {0.867f, 0.318f, 0.330f},
                {0.988f, 0.648f, 0.090f},
                {0.988f, 0.998f, 0.645f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color plasma(float t, float alpha) {
        float[][] stops = {
                {0.050f, 0.030f, 0.528f},
                {0.404f, 0.000f, 0.656f},
                {0.702f, 0.184f, 0.553f},
                {0.906f, 0.378f, 0.391f},
                {0.993f, 0.703f, 0.186f},
                {0.940f, 0.975f, 0.131f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color cividis(float t, float alpha) {
        float[][] stops = {
                {0.000f, 0.135f, 0.305f},
                {0.165f, 0.251f, 0.416f},
                {0.353f, 0.384f, 0.416f},
                {0.565f, 0.537f, 0.369f},
                {0.796f, 0.718f, 0.278f},
                {0.996f, 0.910f, 0.208f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color turbo(float t, float alpha) {
        float[][] stops = {
                {0.189f, 0.071f, 0.232f},
                {0.185f, 0.359f, 0.921f},
                {0.128f, 0.666f, 0.886f},
                {0.208f, 0.859f, 0.553f},
                {0.645f, 0.953f, 0.209f},
                {0.976f, 0.723f, 0.196f},
                {0.906f, 0.329f, 0.243f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color ylOrRd(float t, float alpha) {
        float[][] stops = {
                {1.000f, 1.000f, 0.800f},
                {0.996f, 0.851f, 0.463f},
                {0.996f, 0.600f, 0.161f},
                {0.890f, 0.290f, 0.125f},
                {0.600f, 0.000f, 0.149f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color greyBlue(float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float r = 0.90f + (0.12f - 0.90f) * t;
        float gv = 0.90f + (0.47f - 0.90f) * t;
        float b = 0.90f + (0.71f - 0.90f) * t;
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    public static Color viridis(float t, float alpha) {
        // sampled viridis stops
        float[][] stops = {
                {0.267f, 0.005f, 0.329f},
                {0.283f, 0.141f, 0.458f},
                {0.254f, 0.265f, 0.530f},
                {0.164f, 0.471f, 0.558f},
                {0.128f, 0.567f, 0.551f},
                {0.208f, 0.719f, 0.473f},
                {0.478f, 0.821f, 0.318f},
                {0.741f, 0.873f, 0.150f},
                {0.993f, 0.906f, 0.144f}
        };
        return lerpStops(stops, t, alpha);
    }

    public static Color magma(float t, float alpha) {
        float[][] stops = {
                {0.001f, 0.000f, 0.014f},
                {0.157f, 0.045f, 0.254f},
                {0.390f, 0.100f, 0.402f},
                {0.624f, 0.163f, 0.388f},
                {0.853f, 0.273f, 0.298f},
                {0.961f, 0.484f, 0.282f},
                {0.987f, 0.715f, 0.424f},
                {0.987f, 0.930f, 0.698f},
                {0.988f, 0.998f, 0.645f}
        };
        return lerpStops(stops, t, alpha);
    }

    private static Color lerpStops(float[][] stops, float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float x = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) Math.floor(x));
        float u = x - i;
        float r = stops[i][0] + (stops[i + 1][0] - stops[i][0]) * u;
        float gv = stops[i][1] + (stops[i + 1][1] - stops[i][1]) * u;
        float b = stops[i][2] + (stops[i + 1][2] - stops[i][2]) * u;
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    public static void drawDiscreteLegend(
            Graphics2D g, Font base,
            int x, int y, String[] labels, int maxRows
    ) {
        g.setFont(legendFont(base));
        FontMetrics fm = g.getFontMetrics();
        int row = Math.max(14, fm.getHeight());
        int n = Math.min(labels.length, maxRows);
        for (int i = 0; i < n; i++) {
            g.setColor(categorical(i, 1f));
            g.fillOval(x, y + i * row + 2, 9, 9);
            g.setColor(INK);
            g.drawOval(x, y + i * row + 2, 9, 9);
            g.setColor(MUTE);
            g.drawString(labels[i], x + 14, y + i * row + fm.getAscent());
        }
        if (labels.length > n) {
            g.setColor(MUTE);
            g.drawString("+" + (labels.length - n) + " more", x, y + n * row + fm.getAscent());
        }
    }

    public static void drawClusterLabel(Graphics2D g, Font base, String lab, int px, int py) {
        g.setFont(annoFont(base));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(lab);
        int x = px - tw / 2;
        int y = py + fm.getAscent() / 2 - 1;
        // soft white halo
        g.setColor(new Color(255, 255, 255, 220));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx * dx + dy * dy <= 5) {
                    g.drawString(lab, x + dx, y + dy);
                }
            }
        }
        g.setColor(INK);
        g.drawString(lab, x, y);
    }

    /** Active categorical palette (clusters, conditions, …). */
    public static Color categorical(int i, float alpha) {
        return categorical(i, alpha, catPalette);
    }

    public static Color categorical(int i, float alpha, CatPalette palette) {
        if (palette == null) {
            palette = CatPalette.DEFAULT;
        }
        if (i < 0) {
            return new Color(0.75f, 0.75f, 0.75f, Math.max(0f, Math.min(1f, alpha)));
        }
        float[][] pts = switch (palette) {
            case TABLEAU -> TABLEAU;
            case PASTEL -> PASTEL;
            case VIVID -> VIVID;
            case OKABE -> OKABE;
            default -> DEFAULT_CAT;
        };
        float[] c = pts[Math.floorMod(i, pts.length)];
        float r = c[0], gv = c[1], b = c[2];
        if (r + gv + b < 0.15f) {
            r = gv = b = 0.25f;
        }
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    private static final float[][] DEFAULT_CAT = {
            {0.90f, 0.29f, 0.235f},
            {0.00f, 0.45f, 0.70f},
            {0.00f, 0.62f, 0.45f},
            {0.94f, 0.65f, 0.10f},
            {0.80f, 0.47f, 0.65f},
            {0.35f, 0.70f, 0.90f},
            {0.00f, 0.00f, 0.00f},
            {0.94f, 0.89f, 0.26f},
            {0.55f, 0.25f, 0.55f},
            {0.40f, 0.65f, 0.12f},
            {0.70f, 0.20f, 0.40f},
            {0.20f, 0.55f, 0.70f}
    };
    private static final float[][] OKABE = {
            {0.90f, 0.60f, 0.00f},
            {0.35f, 0.70f, 0.90f},
            {0.00f, 0.60f, 0.50f},
            {0.95f, 0.90f, 0.25f},
            {0.00f, 0.45f, 0.70f},
            {0.80f, 0.40f, 0.00f},
            {0.80f, 0.60f, 0.70f},
            {0.20f, 0.20f, 0.20f}
    };
    private static final float[][] TABLEAU = {
            {0.121f, 0.466f, 0.705f},
            {1.000f, 0.498f, 0.054f},
            {0.172f, 0.627f, 0.172f},
            {0.839f, 0.152f, 0.156f},
            {0.580f, 0.403f, 0.741f},
            {0.549f, 0.337f, 0.294f},
            {0.890f, 0.466f, 0.760f},
            {0.498f, 0.498f, 0.498f},
            {0.737f, 0.741f, 0.133f},
            {0.090f, 0.745f, 0.811f}
    };
    private static final float[][] PASTEL = {
            {0.702f, 0.886f, 0.804f},
            {0.992f, 0.803f, 0.675f},
            {0.796f, 0.835f, 0.906f},
            {0.957f, 0.792f, 0.894f},
            {0.902f, 0.961f, 0.788f},
            {1.000f, 0.929f, 0.635f},
            {0.870f, 0.796f, 0.894f},
            {0.992f, 0.859f, 0.780f}
    };
    private static final float[][] VIVID = {
            {0.90f, 0.10f, 0.29f},
            {0.00f, 0.45f, 0.74f},
            {0.00f, 0.62f, 0.45f},
            {0.94f, 0.89f, 0.26f},
            {0.80f, 0.25f, 0.75f},
            {0.95f, 0.45f, 0.05f},
            {0.20f, 0.80f, 0.90f},
            {0.55f, 0.15f, 0.90f}
    };

    /** Diverging grey → white → red (low / mid / high). */
    public static Color greyRed(float t, float alpha) {
        t = Math.max(0f, Math.min(1f, t));
        float r, gv, b;
        if (t < 0.5f) {
            float u = t * 2f;
            // grey (#BEBEBE) → white
            r = 0.745f + (1.000f - 0.745f) * u;
            gv = 0.745f + (1.000f - 0.745f) * u;
            b = 0.745f + (1.000f - 0.745f) * u;
        } else {
            float u = (t - 0.5f) * 2f;
            // white → red (#B2182B)
            r = 1.000f + (0.698f - 1.000f) * u;
            gv = 1.000f + (0.094f - 1.000f) * u;
            b = 1.000f + (0.169f - 1.000f) * u;
        }
        return new Color(r, gv, b, Math.max(0f, Math.min(1f, alpha)));
    }

    public static List<Double> niceTicks(double lo, double hi, int target) {
        List<Double> out = new ArrayList<>();
        if (!(hi > lo) || target < 2) {
            out.add(lo);
            out.add(hi);
            return out;
        }
        double span = hi - lo;
        double raw = span / (target - 1.0);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double residual = raw / mag;
        double nice;
        if (residual <= 1.5) {
            nice = 1;
        } else if (residual <= 3) {
            nice = 2;
        } else if (residual <= 7) {
            nice = 5;
        } else {
            nice = 10;
        }
        double step = nice * mag;
        double start = Math.ceil(lo / step) * step;
        for (double v = start; v <= hi + step * 0.01; v += step) {
            if (v >= lo - step * 0.01 && v <= hi + step * 0.01) {
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            out.add(lo);
            out.add(hi);
        }
        return out;
    }

    public static String formatTick(double v) {
        double a = Math.abs(v);
        if (a == 0) {
            return "0";
        }
        if (a >= 1000 || (a < 0.01 && a > 0)) {
            return String.format("%.2g", v);
        }
        if (a >= 100) {
            return String.format("%.0f", v);
        }
        if (a >= 10) {
            return String.format("%.1f", v);
        }
        return String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static int[] pad(int legendExtra, boolean subtitle) {
        // left, right, top, bottom
        return new int[]{
                72,
                24 + legendExtra,
                subtitle ? 56 : 44,
                52
        };
    }
}
