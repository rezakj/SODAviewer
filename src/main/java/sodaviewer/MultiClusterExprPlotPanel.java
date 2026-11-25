package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Faceted box or violin by cluster for multiple genes. */
public final class MultiClusterExprPlotPanel extends JPanel {
    public enum Kind { BOX, VIOLIN }

    private Kind kind = Kind.BOX;
    private String[] genes = new String[0];
    private String[] clusterLabels = new String[0];
    /** values[gene][cluster][obs] sorted */
    private float[][][] values = new float[0][][];

    public MultiClusterExprPlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(900, 720));
    }

    public void setData(Kind kind, String[] genes, String[] labels, int[] clusterIdx, float[][] exprByGene) {
        this.kind = kind == null ? Kind.BOX : kind;
        this.genes = genes == null ? new String[0] : genes.clone();
        this.clusterLabels = labels == null ? new String[0] : labels.clone();
        int ng = this.genes.length;
        int k = this.clusterLabels.length;
        this.values = new float[ng][k][];
        for (int g = 0; g < ng; g++) {
            List<List<Float>> buckets = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                buckets.add(new ArrayList<>());
            }
            float[] expr = exprByGene[g];
            for (int i = 0; i < expr.length; i++) {
                int c = clusterIdx[i];
                if (c >= 0 && c < k && Float.isFinite(expr[i])) {
                    buckets.get(c).add(expr[i]);
                }
            }
            for (int c = 0; c < k; c++) {
                List<Float> b = buckets.get(c);
                float[] a = new float[b.size()];
                for (int j = 0; j < b.size(); j++) {
                    a[j] = b.get(j);
                }
                Arrays.sort(a);
                values[g][c] = a;
            }
        }
        repaint();
    }

    public void clear() {
        genes = new String[0];
        clusterLabels = new String[0];
        values = new float[0][][];
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

            String kindLab = kind == Kind.VIOLIN ? "Violin" : "Box";
            g.setFont(PlotTheme.titleFont(getFont()));
            g.setColor(Color.BLACK);
            g.drawString(kindLab + " plots by cluster", 20, 24);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.drawString(genes.length + " genes · log2p1", 20, 40);

            if (genes.length == 0 || clusterLabels.length == 0) {
                g.drawString("Enter gene(s) for " + kindLab.toLowerCase() + " plots.", 20, 80);
                return;
            }

            int n = genes.length;
            int cols = (int) Math.ceil(Math.sqrt(n));
            int rows = (int) Math.ceil(n / (double) cols);
            int padL = 12;
            int padR = 12;
            int padT = 52;
            int padB = 8;
            int gap = 8;
            int gridW = Math.max(40, w - padL - padR);
            int gridH = Math.max(40, h - padT - padB);
            int cellW = (gridW - gap * (cols - 1)) / cols;
            int cellH = (gridH - gap * (rows - 1)) / rows;

            for (int i = 0; i < n; i++) {
                int r = i / cols;
                int c = i % cols;
                int x0 = padL + c * (cellW + gap);
                int y0 = padT + r * (cellH + gap);
                if (kind == Kind.VIOLIN) {
                    paintViolin(g, x0, y0, cellW, cellH, i);
                } else {
                    paintBox(g, x0, y0, cellW, cellH, i);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private float[] yRange(int gi) {
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        for (float[] a : values[gi]) {
            if (a == null || a.length == 0) {
                continue;
            }
            lo = Math.min(lo, a[0]);
            hi = Math.max(hi, a[a.length - 1]);
        }
        if (!(hi > lo)) {
            lo = 0;
            hi = 1;
        }
        float pad = (hi - lo) * 0.08f;
        return new float[]{lo - pad, hi + pad};
    }

    private void paintFrame(Graphics2D g, int left, int top, int pw, int ph, String title) {
        g.setColor(Color.WHITE);
        g.fillRect(left, top, pw, ph);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(left, top, pw, ph);
        g.setFont(PlotTheme.legendFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, left + (pw - fm.stringWidth(title)) / 2, top + 14);
    }

    private void paintBox(Graphics2D g, int left, int top, int pw, int ph, int gi) {
        paintFrame(g, left, top, pw, ph, genes[gi]);
        int mL = 32;
        int mR = 10;
        int mT = 22;
        int mB = 28;
        int ix = left + mL;
        int iy = top + mT;
        int iw = Math.max(10, pw - mL - mR);
        int ih = Math.max(10, ph - mT - mB);
        float[] yr = yRange(gi);
        int k = clusterLabels.length;
        double slot = iw / (double) k;

        g.setColor(Color.BLACK);
        g.drawLine(ix, iy + ih, ix + iw, iy + ih);
        g.drawLine(ix, iy, ix, iy + ih);

        for (int c = 0; c < k; c++) {
            float[] a = values[gi][c];
            if (a == null || a.length == 0) {
                continue;
            }
            double cx = ix + (c + 0.5) * slot;
            float q1 = percentile(a, 0.25f);
            float q2 = percentile(a, 0.50f);
            float q3 = percentile(a, 0.75f);
            float iqr = q3 - q1;
            float wLo = Math.max(a[0], q1 - 1.5f * iqr);
            float wHi = Math.min(a[a.length - 1], q3 + 1.5f * iqr);
            int y1 = yPix(q1, yr, iy, ih);
            int y2 = yPix(q2, yr, iy, ih);
            int y3 = yPix(q3, yr, iy, ih);
            int yw0 = yPix(wLo, yr, iy, ih);
            int yw1 = yPix(wHi, yr, iy, ih);
            int bw = Math.max(6, (int) (slot * 0.45));
            g.setColor(new Color(0x4C, 0x78, 0xA8, 180));
            g.fillRect((int) cx - bw / 2, y3, bw, Math.max(1, y1 - y3));
            g.setColor(Color.BLACK);
            g.drawRect((int) cx - bw / 2, y3, bw, Math.max(1, y1 - y3));
            g.drawLine((int) cx - bw / 2, y2, (int) cx + bw / 2, y2);
            g.drawLine((int) cx, yw0, (int) cx, y1);
            g.drawLine((int) cx, y3, (int) cx, yw1);
        }

        g.setFont(PlotTheme.tickFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        for (int c = 0; c < k; c++) {
            String lab = clusterLabels[c];
            int tw = fm.stringWidth(lab);
            if (tw > slot - 2) {
                continue;
            }
            int tx = (int) (ix + (c + 0.5) * slot - tw / 2.0);
            g.drawString(lab, tx, top + ph - 6);
        }
    }

    private void paintViolin(Graphics2D g, int left, int top, int pw, int ph, int gi) {
        paintFrame(g, left, top, pw, ph, genes[gi]);
        int mL = 32;
        int mR = 10;
        int mT = 22;
        int mB = 28;
        int ix = left + mL;
        int iy = top + mT;
        int iw = Math.max(10, pw - mL - mR);
        int ih = Math.max(10, ph - mT - mB);
        float[] yr = yRange(gi);
        int k = clusterLabels.length;
        double slot = iw / (double) k;

        g.setColor(Color.BLACK);
        g.drawLine(ix, iy + ih, ix + iw, iy + ih);
        g.drawLine(ix, iy, ix, iy + ih);

        for (int c = 0; c < k; c++) {
            float[] a = values[gi][c];
            if (a == null || a.length < 2) {
                continue;
            }
            double cx = ix + (c + 0.5) * slot;
            float[] dens = kde(a, yr[0], yr[1], 40);
            float maxD = 1e-6f;
            for (float d : dens) {
                maxD = Math.max(maxD, d);
            }
            double half = slot * 0.4;
            Path2D path = new Path2D.Double();
            for (int i = 0; i < dens.length; i++) {
                float yv = yr[0] + (yr[1] - yr[0]) * i / (float) (dens.length - 1);
                int py = yPix(yv, yr, iy, ih);
                double w = (dens[i] / maxD) * half;
                if (i == 0) {
                    path.moveTo(cx - w, py);
                } else {
                    path.lineTo(cx - w, py);
                }
            }
            for (int i = dens.length - 1; i >= 0; i--) {
                float yv = yr[0] + (yr[1] - yr[0]) * i / (float) (dens.length - 1);
                int py = yPix(yv, yr, iy, ih);
                double w = (dens[i] / maxD) * half;
                path.lineTo(cx + w, py);
            }
            path.closePath();
            g.setColor(new Color(0x4C, 0x78, 0xA8, 160));
            g.fill(path);
            g.setColor(Color.BLACK);
            g.draw(path);
        }

        g.setFont(PlotTheme.tickFont(getFont()));
        FontMetrics fm = g.getFontMetrics();
        for (int c = 0; c < k; c++) {
            String lab = clusterLabels[c];
            int tw = fm.stringWidth(lab);
            if (tw > slot - 2) {
                continue;
            }
            int tx = (int) (ix + (c + 0.5) * slot - tw / 2.0);
            g.drawString(lab, tx, top + ph - 6);
        }
    }

    private static int yPix(float v, float[] yr, int iy, int ih) {
        float t = (v - yr[0]) / (yr[1] - yr[0]);
        return iy + ih - (int) Math.round(t * ih);
    }

    private static float percentile(float[] sorted, float q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        float pos = q * (sorted.length - 1);
        int i = (int) Math.floor(pos);
        float f = pos - i;
        if (i >= sorted.length - 1) {
            return sorted[sorted.length - 1];
        }
        return sorted[i] * (1 - f) + sorted[i + 1] * f;
    }

    private static float[] kde(float[] a, float lo, float hi, int bins) {
        float[] dens = new float[bins];
        float bw = Math.max((hi - lo) / 20f, 1e-3f);
        for (float x : a) {
            for (int i = 0; i < bins; i++) {
                float y = lo + (hi - lo) * i / (float) (bins - 1);
                float u = (y - x) / bw;
                dens[i] += (float) Math.exp(-0.5 * u * u);
            }
        }
        return dens;
    }
}
