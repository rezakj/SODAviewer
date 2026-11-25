package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Locale;

/**
 * Gene × cluster mean-expression heatmap (row-scaled).
 */
public final class HeatmapPlotPanel extends JPanel {
    private String[] genes = new String[0];
    private String[] clusters = new String[0];
    private float[][] mean;
    private float[][] scaled;
    private PlotTheme.ColorScale colorScale = PlotTheme.ColorScale.BLUE_WHITE_RED;

    public HeatmapPlotPanel() {
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

    public void setData(String[] genes, String[] clusters, float[][] meanExpr) {
        this.genes = genes == null ? new String[0] : genes.clone();
        this.clusters = clusters == null ? new String[0] : clusters.clone();
        int ng = this.genes.length;
        int nc = this.clusters.length;
        mean = new float[ng][nc];
        scaled = new float[ng][nc];
        for (int g = 0; g < ng; g++) {
            double sum = 0;
            double sum2 = 0;
            int n = 0;
            for (int c = 0; c < nc; c++) {
                float m = meanExpr[g][c];
                mean[g][c] = m;
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
        scaled = new float[0][];
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        PlotTheme.clearPaper(g, w, h);
        PlotTheme.qualityHints(g, false);

        int left = 110;
        int right = 90;
        int top = 48;
        int bottom = 48;
        int pw = Math.max(10, w - left - right);
        int ph = Math.max(10, h - top - bottom);

        PlotTheme.drawTitle(g, getFont(), "Expression heatmap",
                "Row-scaled mean (z) · genes × clusters", left, 26);

        if (genes.length == 0 || clusters.length == 0 || scaled == null) {
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            g.drawString("Enter one or more genes for a cluster heatmap.", left, top + 40);
            return;
        }

        int ng = genes.length;
        int nc = clusters.length;
        double cellW = pw / (double) nc;
        double cellH = ph / (double) ng;

        for (int r = 0; r < ng; r++) {
            for (int c = 0; c < nc; c++) {
                float z = scaled[r][c];
                float tt = (z + 2.5f) / 5f;
                g.setColor(PlotTheme.scaleColor(colorScale, tt, 1f));
                int x0 = left + (int) Math.floor(c * cellW);
                int x1 = left + (int) Math.floor((c + 1) * cellW);
                int y0 = top + (int) Math.floor(r * cellH);
                int y1 = top + (int) Math.floor((r + 1) * cellH);
                g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
            }
        }

        g.setColor(PlotTheme.INK);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRect(left, top, (int) Math.round(nc * cellW), (int) Math.round(ng * cellH));

        g.setFont(PlotTheme.tickFont(getFont()));
        g.setColor(PlotTheme.INK);
        FontMetrics fm = g.getFontMetrics();
        for (int r = 0; r < ng; r++) {
            String lab = genes[r];
            int tw = fm.stringWidth(lab);
            int y = top + (int) Math.round((r + 0.5) * cellH) + fm.getAscent() / 2 - 1;
            g.drawString(lab, left - 8 - tw, y);
        }
        for (int c = 0; c < nc; c++) {
            String lab = clusters[c];
            int tw = fm.stringWidth(lab);
            int x = left + (int) Math.round((c + 0.5) * cellW) - tw / 2;
            g.drawString(lab, x, top + (int) Math.round(ng * cellH) + 16);
        }

        // color bar
        int bx = w - right + 18;
        int by = top;
        int bh = Math.min(160, ph);
        int bw = 14;
        for (int i = 0; i < bh; i++) {
            float t = 1f - i / (float) Math.max(1, bh - 1);
            g.setColor(PlotTheme.scaleColor(colorScale, t, 1f));
            g.fillRect(bx, by + i, bw, 1);
        }
        g.setColor(PlotTheme.INK);
        g.drawRect(bx, by, bw, bh);
        g.setFont(PlotTheme.tickFont(getFont()));
        g.setColor(PlotTheme.INK);
        g.drawString("+2.5", bx + bw + 4, by + 10);
        g.drawString("0", bx + bw + 4, by + bh / 2 + 4);
        g.drawString(String.format(Locale.ROOT, "−2.5"), bx + bw + 4, by + bh);
    }
}
