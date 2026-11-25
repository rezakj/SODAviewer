package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Faceted feature plot (genes × embedding), like gene.plot / feat with multiple genes.
 */
public final class MultiFeaturePlotPanel extends JPanel {
    private double[][] xy = new double[0][];
    private String[] genes = new String[0];
    /** expr[gene][cell] log2p1 */
    private float[][] expr = new float[0][];
    private float[] lo = new float[0];
    private float[] hi = new float[0];
    private String xlab = "Dim1";
    private String ylab = "Dim2";
    private PlotTheme.ColorScale colorScale = PlotTheme.ColorScale.GREY_RED;
    private double minX, maxX, minY, maxY;

    public MultiFeaturePlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(900, 720));
    }

    public void setColorScale(PlotTheme.ColorScale scale) {
        this.colorScale = scale == null ? PlotTheme.ColorScale.GREY_RED : scale;
        repaint();
    }

    public void setData(
            double[][] xy, String xlab, String ylab,
            String[] genes, float[][] exprLog2p1
    ) {
        this.xy = xy == null ? new double[0][] : xy;
        this.xlab = xlab == null ? "Dim1" : xlab;
        this.ylab = ylab == null ? "Dim2" : ylab;
        this.genes = genes == null ? new String[0] : genes.clone();
        int ng = this.genes.length;
        this.expr = new float[ng][];
        this.lo = new float[ng];
        this.hi = new float[ng];
        for (int g = 0; g < ng; g++) {
            this.expr[g] = exprLog2p1[g].clone();
            float[] qs = quantiles(this.expr[g], 0.01f, 0.99f);
            lo[g] = qs[0];
            hi[g] = qs[1];
            if (!(hi[g] > lo[g])) {
                hi[g] = lo[g] + 1e-6f;
            }
        }
        computeBounds();
        repaint();
    }

    public void clear() {
        xy = new double[0][];
        genes = new String[0];
        expr = new float[0][];
        lo = new float[0];
        hi = new float[0];
        repaint();
    }

    private void computeBounds() {
        if (xy.length == 0) {
            minX = minY = 0;
            maxX = maxY = 1;
            return;
        }
        minX = maxX = xy[0][0];
        minY = maxY = xy[0][1];
        for (double[] p : xy) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double dx = maxX - minX;
        double dy = maxY - minY;
        if (dx < 1e-9) {
            dx = 1;
        }
        if (dy < 1e-9) {
            dy = 1;
        }
        minX -= dx * 0.04;
        maxX += dx * 0.04;
        minY -= dy * 0.04;
        maxY += dy * 0.04;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            int w = getWidth();
            int h = getHeight();
            PlotTheme.clearPaper(g, w, h);
            PlotTheme.qualityHints(g, false);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g.setFont(PlotTheme.titleFont(getFont()));
            g.setColor(Color.BLACK);
            g.drawString("Feature plots", 20, 24);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.drawString(genes.length + " genes · log2p1 · Gray→White→Red / Scale", 20, 40);

            if (genes.length == 0 || xy.length == 0) {
                g.setColor(Color.BLACK);
                g.drawString("Enter one or more genes for faceted feature plots.", 20, 80);
                return;
            }

            int n = genes.length;
            int cols = (int) Math.ceil(Math.sqrt(n));
            int rows = (int) Math.ceil(n / (double) cols);
            int padL = 16;
            int padR = 16;
            int padT = 52;
            int padB = 12;
            int gap = 10;
            int gridW = Math.max(40, w - padL - padR);
            int gridH = Math.max(40, h - padT - padB);
            int cellW = (gridW - gap * (cols - 1)) / cols;
            int cellH = (gridH - gap * (rows - 1)) / rows;

            int diam = cellW < 220 ? 2 : 3;
            for (int i = 0; i < n; i++) {
                int r = i / cols;
                int c = i % cols;
                int x0 = padL + c * (cellW + gap);
                int y0 = padT + r * (cellH + gap);
                paintOne(g, x0, y0, cellW, cellH, i, diam);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintOne(Graphics2D g, int left, int top, int pw, int ph, int gi, int diam) {
        // Room for y tick labels · color bar + values · x ticks · gene name
        int mL = 42;
        int mR = 44;
        int mT = 6;
        int mB = 34;
        int ix = left + mL;
        int iy = top + mT;
        int iw = Math.max(10, pw - mL - mR);
        int ih = Math.max(10, ph - mT - mB);

        double dx = maxX - minX;
        double dy = maxY - minY;
        float[] vals = expr[gi];
        float vlo = lo[gi];
        float vhi = hi[gi];

        // low first, high on top
        Integer[] order = new Integer[xy.length];
        for (int i = 0; i < xy.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(
                a < vals.length ? vals[a] : 0f,
                b < vals.length ? vals[b] : 0f));

        for (int idx : order) {
            double[] p = xy[idx];
            int sx = ix + (int) Math.round(((p[0] - minX) / dx) * iw);
            int sy = iy + ih - (int) Math.round(((p[1] - minY) / dy) * ih);
            float tt = (vals[idx] - vlo) / (vhi - vlo);
            g.setColor(PlotTheme.scaleColor(colorScale, tt, 0.85f));
            if (diam <= 1) {
                g.fillRect(sx, sy, 1, 1);
            } else {
                g.fillOval(sx - diam / 2, sy - diam / 2, diam, diam);
            }
        }

        // Open L axes + numeric ticks (no box, no UMAP_1 label under the panel)
        g.setColor(PlotTheme.INK);
        g.setStroke(new BasicStroke(PlotTheme.AXIS_STROKE, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.drawLine(ix, iy + ih, ix + iw, iy + ih);
        g.drawLine(ix, iy, ix, iy + ih);

        g.setStroke(new BasicStroke(PlotTheme.TICK_STROKE, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.setFont(PlotTheme.tickFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        int tickN = Math.max(3, Math.min(5, iw / 55));
        for (double xv : PlotTheme.niceTicks(minX, maxX, tickN)) {
            int px = ix + (int) Math.round((xv - minX) / dx * iw);
            g.setColor(PlotTheme.INK);
            g.drawLine(px, iy + ih, px, iy + ih + PlotTheme.TICK_LEN);
            String lab = PlotTheme.formatTick(xv);
            g.setColor(PlotTheme.MUTE);
            g.drawString(lab, px - fm.stringWidth(lab) / 2, iy + ih + PlotTheme.TICK_LEN + fm.getAscent());
        }
        for (double yv : PlotTheme.niceTicks(minY, maxY, tickN)) {
            int py = iy + ih - (int) Math.round((yv - minY) / dy * ih);
            g.setColor(PlotTheme.INK);
            g.drawLine(ix - PlotTheme.TICK_LEN, py, ix, py);
            String lab = PlotTheme.formatTick(yv);
            g.setColor(PlotTheme.MUTE);
            g.drawString(lab, ix - PlotTheme.TICK_LEN - 3 - fm.stringWidth(lab),
                    py + fm.getAscent() / 2 - 1);
        }

        // Gene name under the plot (not the dim name)
        g.setFont(PlotTheme.legendFont(getFont()));
        fm = g.getFontMetrics();
        String title = genes[gi];
        g.setColor(PlotTheme.INK);
        g.drawString(title, ix + (iw - fm.stringWidth(title)) / 2, top + ph - 4);

        // Color bar with hi / lo values
        int bx = ix + iw + 8;
        int bh = ih;
        int bw = 8;
        for (int y = 0; y < bh; y++) {
            float t = 1f - y / (float) Math.max(1, bh - 1);
            g.setColor(PlotTheme.scaleColor(colorScale, t, 1f));
            g.fillRect(bx, iy + y, bw, 1);
        }
        g.setColor(PlotTheme.INK);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRect(bx, iy, bw, bh);
        g.setFont(PlotTheme.tickFont(getFont()));
        fm = g.getFontMetrics();
        String hiS = PlotTheme.formatTick(vhi);
        String loS = PlotTheme.formatTick(vlo);
        g.setColor(PlotTheme.MUTE);
        g.drawString(hiS, bx + bw + 3, iy + fm.getAscent());
        g.drawString(loS, bx + bw + 3, iy + bh);
    }

    private static float[] quantiles(float[] v, float qLo, float qHi) {
        float[] a = v.clone();
        java.util.Arrays.sort(a);
        int n = a.length;
        if (n == 0) {
            return new float[]{0f, 1f};
        }
        int i0 = Math.max(0, Math.min(n - 1, Math.round(qLo * (n - 1))));
        int i1 = Math.max(0, Math.min(n - 1, Math.round(qHi * (n - 1))));
        return new float[]{a[i0], a[i1]};
    }
}
