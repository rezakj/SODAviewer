package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Gene expression violin by cluster (KDE mirrored). */
public final class ViolinPlotPanel extends JPanel {
    private String title = "";
    private String ylab = "expression (log2p1)";
    private String[] clusterLabels = new String[0];
    private float[][] valuesByCluster = new float[0][];

    public ViolinPlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 580));
    }

    public void setData(String gene, String[] labels, int[] clusterIdx, float[] expr) {
        this.title = gene == null ? "" : gene;
        this.clusterLabels = labels == null ? new String[0] : labels.clone();
        int k = clusterLabels.length;
        List<List<Float>> buckets = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int i = 0; i < expr.length; i++) {
            int c = clusterIdx[i];
            if (c >= 0 && c < k && Float.isFinite(expr[i])) {
                buckets.get(c).add(expr[i]);
            }
        }
        valuesByCluster = new float[k][];
        for (int i = 0; i < k; i++) {
            List<Float> b = buckets.get(i);
            float[] a = new float[b.size()];
            for (int j = 0; j < b.size(); j++) {
                a[j] = b.get(j);
            }
            Arrays.sort(a);
            valuesByCluster[i] = a;
        }
        repaint();
    }

    public void clear() {
        clusterLabels = new String[0];
        valuesByCluster = new float[0][];
        title = "";
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        PlotTheme.clearPaper(g, w, h);
        PlotTheme.qualityHints(g, false);

        int left = 72;
        int right = 28;
        int top = 48;
        int bottom = 52;
        int pw = Math.max(10, w - left - right);
        int ph = Math.max(10, h - top - bottom);

        String ttl = title.isEmpty() ? "Gene by cluster" : title;
        PlotTheme.drawTitle(g, getFont(), ttl, "Violin · expression by cluster", left, 26);

        if (clusterLabels.length == 0) {
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            g.drawString("Plot a gene as violin (needs cluster_id).", left, top + 40);
            return;
        }

        float ymin = Float.POSITIVE_INFINITY;
        float ymax = Float.NEGATIVE_INFINITY;
        for (float[] a : valuesByCluster) {
            for (float v : a) {
                ymin = Math.min(ymin, v);
                ymax = Math.max(ymax, v);
            }
        }
        if (!Float.isFinite(ymin)) {
            ymin = 0;
            ymax = 1;
        }
        if (ymax <= ymin) {
            ymax = ymin + 1e-3f;
        }
        float pad = 0.06f * (ymax - ymin);
        ymin -= pad;
        ymax += pad;

        g.setStroke(new BasicStroke(0.7f));
        for (double yv : PlotTheme.niceTicks(ymin, ymax, 5)) {
            int py = top + ph - (int) Math.round(((yv - ymin) / (ymax - ymin)) * ph);
            g.setColor(PlotTheme.FAINT);
            g.drawLine(left, py, left + pw, py);
        }

        int k = clusterLabels.length;
        double slot = pw / (double) k;
        double maxHalf = Math.min(42, slot * 0.42);

        for (int i = 0; i < k; i++) {
            float[] a = valuesByCluster[i];
            if (a.length < 2) {
                continue;
            }
            double cx = left + (i + 0.5) * slot;
            float[] dens = kde(a, ymin, ymax, 64);
            float dmax = 1e-6f;
            for (float d : dens) {
                dmax = Math.max(dmax, d);
            }

            Path2D.Double path = new Path2D.Double();
            for (int t = 0; t < dens.length; t++) {
                double yv = ymin + (ymax - ymin) * t / (double) (dens.length - 1);
                int py = top + ph - (int) Math.round(((yv - ymin) / (ymax - ymin)) * ph);
                double half = maxHalf * (dens[t] / dmax);
                double px = cx + half;
                if (t == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            for (int t = dens.length - 1; t >= 0; t--) {
                double yv = ymin + (ymax - ymin) * t / (double) (dens.length - 1);
                int py = top + ph - (int) Math.round(((yv - ymin) / (ymax - ymin)) * ph);
                double half = maxHalf * (dens[t] / dmax);
                path.lineTo(cx - half, py);
            }
            path.closePath();

            Color fill = PlotTheme.categorical(i, 0.55f);
            g.setColor(fill);
            g.fill(path);
            g.setColor(PlotTheme.INK);
            g.setStroke(new BasicStroke(1.0f));
            g.draw(path);

            // median line
            float med = a[a.length / 2];
            int yMed = top + ph - (int) Math.round(((med - ymin) / (ymax - ymin)) * ph);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine((int) (cx - maxHalf * 0.55), yMed, (int) (cx + maxHalf * 0.55), yMed);
            g.setStroke(new BasicStroke(1.0f));
        }

        PlotTheme.drawOpenAxes(g, getFont(), left, top, pw, ph, 0.5, k + 0.5, ymin, ymax, "cluster", ylab);

        g.setColor(PlotTheme.PAPER);
        g.fillRect(left - 2, top + ph + 6, pw + 4, 28);
        g.setFont(PlotTheme.tickFont(getFont()));
        g.setColor(PlotTheme.MUTE);
        for (int i = 0; i < k; i++) {
            double cx = left + (i + 0.5) * slot;
            String lab = clusterLabels[i];
            int tw = g.getFontMetrics().stringWidth(lab);
            g.drawString(lab, (int) (cx - tw / 2.0), top + ph + 18);
        }
        g.setColor(PlotTheme.INK);
        g.setStroke(new BasicStroke(PlotTheme.AXIS_STROKE));
        g.drawLine(left, top + ph, left + pw, top + ph);
        g.setFont(PlotTheme.axisTitleFont(getFont()));
        g.setColor(PlotTheme.MUTE);
        String xlab = "cluster";
        int xtw = g.getFontMetrics().stringWidth(xlab);
        g.drawString(xlab, left + (pw - xtw) / 2, top + ph + 36);
    }

    /** Gaussian KDE evaluated on a uniform grid between ymin and ymax. */
    private static float[] kde(float[] sorted, float ymin, float ymax, int bins) {
        float[] dens = new float[bins];
        int n = sorted.length;
        float iqr = sorted[Math.min(n - 1, (3 * n) / 4)] - sorted[n / 4];
        float sd = 0;
        float mean = 0;
        for (float v : sorted) {
            mean += v;
        }
        mean /= n;
        for (float v : sorted) {
            float d = v - mean;
            sd += d * d;
        }
        sd = (float) Math.sqrt(sd / Math.max(1, n - 1));
        float bw = 1.06f * Math.max(sd, iqr / 1.34f) * (float) Math.pow(n, -0.2);
        if (!(bw > 1e-6f)) {
            bw = Math.max(1e-3f, (ymax - ymin) * 0.05f);
        }
        for (int t = 0; t < bins; t++) {
            float x = ymin + (ymax - ymin) * t / (float) (bins - 1);
            double sum = 0;
            for (float v : sorted) {
                double u = (x - v) / bw;
                sum += Math.exp(-0.5 * u * u);
            }
            dens[t] = (float) (sum / (n * bw * Math.sqrt(2 * Math.PI)));
        }
        return dens;
    }
}
