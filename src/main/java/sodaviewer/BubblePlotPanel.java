package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * Multi-gene × cluster bubble plot (bubble.gg.plot / lineage_bubble style):
 * color = scaled mean expression, size = percent expressing.
 */
public final class BubblePlotPanel extends JPanel {
    private static final Color GRID = new Color(0xEE, 0xEE, 0xEE);
    private static final Color BOX = Color.BLACK;

    private String[] genes = new String[0];
    private String[] clusters = new String[0];
    /** mean[gene][cluster] after log2p1 */
    private float[][] mean;
    /** pct expressing 0..100 */
    private float[][] pct;
    /** per-gene z-scaled means clipped to [-2.5, 2.5] for color */
    private float[][] scaled;
    private PlotTheme.ColorScale colorScale = PlotTheme.ColorScale.BLUE_WHITE_RED;

    public BubblePlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 580));
    }

    public void setColorScale(PlotTheme.ColorScale scale) {
        this.colorScale = scale == null ? PlotTheme.ColorScale.BLUE_WHITE_RED : scale;
        repaint();
    }

    public PlotTheme.ColorScale getColorScale() {
        return colorScale;
    }

    /**
     * @param genes     gene names (rows)
     * @param clusters  cluster labels (columns)
     * @param meanExpr  mean[gene][cluster] (already log-transformed)
     * @param pctExpr   percent expressing 0..100
     */
    public void setData(String[] genes, String[] clusters, float[][] meanExpr, float[][] pctExpr) {
        this.genes = genes == null ? new String[0] : genes.clone();
        this.clusters = clusters == null ? new String[0] : clusters.clone();
        int ng = this.genes.length;
        int nc = this.clusters.length;
        mean = new float[ng][nc];
        pct = new float[ng][nc];
        scaled = new float[ng][nc];
        for (int g = 0; g < ng; g++) {
            double sum = 0;
            double sum2 = 0;
            int n = 0;
            for (int c = 0; c < nc; c++) {
                float m = meanExpr[g][c];
                float p = pctExpr[g][c];
                mean[g][c] = m;
                pct[g][c] = p;
                if (Float.isFinite(m)) {
                    sum += m;
                    sum2 += (double) m * m;
                    n++;
                }
            }
            double avg = n > 0 ? sum / n : 0;
            double var = n > 1 ? (sum2 - sum * sum / n) / (n - 1) : 0;
            double sd = Math.sqrt(Math.max(var, 1e-12));
            for (int c = 0; c < nc; c++) {
                float z = (float) ((mean[g][c] - avg) / sd);
                if (z < -2.5f) {
                    z = -2.5f;
                }
                if (z > 2.5f) {
                    z = 2.5f;
                }
                scaled[g][c] = z;
            }
        }
        repaint();
    }

    public void clear() {
        genes = new String[0];
        clusters = new String[0];
        mean = new float[0][];
        pct = new float[0][];
        scaled = new float[0][];
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            int w = getWidth();
            int h = getHeight();
            PlotTheme.clearPaper(g, w, h);
            PlotTheme.qualityHints(g, false);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setFont(PlotTheme.titleFont(getFont()));
            g.setColor(Color.BLACK);
            g.drawString("Gene × cluster", 28, 28);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.drawString("Scaled mean (color) · % expressing (size)", 28, 44);

            if (genes.length == 0 || clusters.length == 0) {
                g.setFont(PlotTheme.subtitleFont(getFont()));
                g.setColor(Color.BLACK);
                g.drawString("Choose multiple genes (comma-separated) for a bubble / heatmap view.",
                        28, 88);
                return;
            }

            int ng = genes.length;
            int nc = clusters.length;

            g.setFont(PlotTheme.tickFont(getFont()));
            FontMetrics tickFm = g.getFontMetrics();
            int maxGeneW = 0;
            for (String gene : genes) {
                maxGeneW = Math.max(maxGeneW, tickFm.stringWidth(gene));
            }
            int maxClusterW = 0;
            for (String cl : clusters) {
                maxClusterW = Math.max(maxClusterW, tickFm.stringWidth(cl));
            }

            boolean rotateClusters = nc > 8 || maxClusterW > 48;
            int legendW = 118;
            int left = Math.max(88, 24 + maxGeneW + 14);
            int right = legendW + 28;
            int top = 58;
            int bottom = rotateClusters
                    ? Math.max(70, 28 + (int) (maxClusterW * 0.72) + 18)
                    : 52;
            int pw = Math.max(40, w - left - right);
            int ph = Math.max(40, h - top - bottom);

            double cellW = pw / (double) nc;
            double cellH = ph / (double) ng;
            double maxR = Math.min(cellW, cellH) * 0.28;
            maxR = Math.min(maxR, 11.0);

            // panel fill + subtle cell grid
            g.setColor(Color.WHITE);
            g.fillRect(left, top, pw, ph);
            g.setStroke(new BasicStroke(0.6f));
            g.setColor(GRID);
            for (int ci = 1; ci < nc; ci++) {
                int x = left + (int) Math.round(ci * cellW);
                g.draw(new Line2D.Double(x, top, x, top + ph));
            }
            for (int gi = 1; gi < ng; gi++) {
                int y = top + (int) Math.round(gi * cellH);
                g.draw(new Line2D.Double(left, y, left + pw, y));
            }

            // bubbles (clip to panel)
            var oldClip = g.getClip();
            g.setClip(left, top, pw, ph);
            for (int gi = 0; gi < ng; gi++) {
                for (int ci = 0; ci < nc; ci++) {
                    float z = scaled[gi][ci];
                    float t = (z + 2.5f) / 5f;
                    Color fill = PlotTheme.scaleColor(colorScale, t, 1f);
                    float p = Math.max(0f, Math.min(100f, pct[gi][ci]));
                    double r = maxR * Math.sqrt(p / 100.0);
                    if (p <= 0.01f) {
                        continue;
                    }
                    if (r < 0.9) {
                        r = 0.9;
                    }
                    double cx = left + (ci + 0.5) * cellW;
                    double cy = top + (gi + 0.5) * cellH;
                    g.setColor(fill);
                    g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                    g.setStroke(new BasicStroke(0.65f));
                    g.setColor(new Color(0, 0, 0, 70));
                    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                }
            }
            g.setClip(oldClip);

            // outer box
            g.setColor(BOX);
            g.setStroke(new BasicStroke(1.15f));
            g.drawRect(left, top, pw, ph);

            // gene labels (right-aligned)
            g.setFont(PlotTheme.tickFont(getFont()));
            tickFm = g.getFontMetrics();
            g.setColor(Color.BLACK);
            for (int gi = 0; gi < ng; gi++) {
                String lab = genes[gi];
                int tw = tickFm.stringWidth(lab);
                float y = (float) (top + (gi + 0.5) * cellH + tickFm.getAscent() / 2.0 - 2);
                g.drawString(lab, left - 10 - tw, y);
            }

            // cluster labels
            if (rotateClusters) {
                AffineTransform saved = g.getTransform();
                for (int ci = 0; ci < nc; ci++) {
                    String lab = clusters[ci];
                    double cx = left + (ci + 0.5) * cellW;
                    double cy = top + ph + 8;
                    g.setTransform(saved);
                    g.rotate(-Math.PI / 4, cx, cy);
                    g.drawString(lab, (float) cx, (float) (cy + tickFm.getAscent()));
                }
                g.setTransform(saved);
            } else {
                for (int ci = 0; ci < nc; ci++) {
                    String lab = clusters[ci];
                    int tw = tickFm.stringWidth(lab);
                    float x = (float) (left + (ci + 0.5) * cellW - tw / 2.0);
                    float y = top + ph + 16;
                    g.drawString(lab, x, y);
                }
            }

            // axis titles
            g.setFont(PlotTheme.axisTitleFont(getFont()));
            FontMetrics axFm = g.getFontMetrics();
            String xlab = "Cluster";
            g.drawString(xlab, left + (pw - axFm.stringWidth(xlab)) / 2, h - 12);
            AffineTransform saved = g.getTransform();
            String ylab = "Gene";
            g.rotate(-Math.PI / 2);
            g.drawString(ylab, -(top + ph / 2 + axFm.stringWidth(ylab) / 2), 18);
            g.setTransform(saved);

            // legends — single right column, top-aligned with panel
            int lx = left + pw + 22;
            int ly = top;
            int colorBh = Math.min(120, Math.max(72, ph / 3));
            drawColorLegend(g, lx, ly, 14, colorBh);
            int sizeTop = ly + colorBh + 36;
            drawSizeLegend(g, lx, sizeTop, Math.min(maxR, 9.0));
        } finally {
            g.dispose();
        }
    }

    private void drawColorLegend(Graphics2D g, int x, int y, int bw, int bh) {
        g.setFont(PlotTheme.legendFont(getFont()));
        g.setColor(Color.BLACK);
        g.drawString("Scaled mean", x, y - 8);

        for (int i = 0; i < bh; i++) {
            float t = 1f - i / (float) Math.max(1, bh - 1);
            g.setColor(PlotTheme.scaleColor(colorScale, t, 1f));
            g.fillRect(x, y + i, bw, 1);
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(Color.BLACK);
        g.drawRect(x, y, bw, bh);

        // row z-score, clipped to [-2.5, 2.5]
        int tx = x + bw + 6;
        g.setFont(PlotTheme.tickFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("+2.5", tx, y + fm.getAscent() - 1);
        g.drawString("0", tx, y + bh / 2 + fm.getAscent() / 2 - 1);
        g.drawString("−2.5", tx, y + bh);
    }

    private void drawSizeLegend(Graphics2D g, int x, int y, double maxR) {
        g.setFont(PlotTheme.legendFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.BLACK);
        g.drawString("% expressing", x, y - 8);

        int[] pcts = {25, 50, 75, 100};
        int row = Math.max(18, (int) Math.ceil(maxR * 2) + 6);
        int cx = x + (int) Math.ceil(maxR) + 2;
        int yy = y + row / 2;
        g.setStroke(new BasicStroke(1.0f));
        g.setFont(PlotTheme.tickFont(getFont()));
        fm = g.getFontMetrics();
        for (int p : pcts) {
            double r = maxR * Math.sqrt(p / 100.0);
            if (r < 1.0) {
                r = 1.0;
            }
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(cx - r, yy - r, 2 * r, 2 * r));
            g.setColor(Color.BLACK);
            g.draw(new Ellipse2D.Double(cx - r, yy - r, 2 * r, 2 * r));
            String lab = p + "%";
            g.drawString(lab, cx + (int) Math.ceil(maxR) + 8,
                    yy + (fm.getAscent() - fm.getDescent()) / 2);
            yy += row;
        }
    }
}
