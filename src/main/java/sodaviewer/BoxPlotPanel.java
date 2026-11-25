package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** gene.plot box-by-cluster — publication open-axis style. */
public final class BoxPlotPanel extends JPanel {
    private String title = "";
    private String ylab = "expression (log2p1)";
    private String[] clusterLabels = new String[0];
    private float[][] valuesByCluster = new float[0][];

    public BoxPlotPanel() {
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
            if (c >= 0 && c < k) {
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
        PlotTheme.drawTitle(g, getFont(), ttl, "Expression by cluster", left, 26);

        if (clusterLabels.length == 0) {
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            g.drawString("Plot a gene as box (needs cluster_id).", left, top + 40);
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

        // faint horizontal guides at nice ticks
        g.setStroke(new BasicStroke(0.7f));
        for (double yv : PlotTheme.niceTicks(ymin, ymax, 5)) {
            int py = top + ph - (int) Math.round(((yv - ymin) / (ymax - ymin)) * ph);
            g.setColor(PlotTheme.FAINT);
            g.drawLine(left, py, left + pw, py);
        }

        int k = clusterLabels.length;
        double slot = pw / (double) k;
        double boxW = Math.min(36, slot * 0.5);

        for (int i = 0; i < k; i++) {
            float[] a = valuesByCluster[i];
            double cx = left + (i + 0.5) * slot;
            Color fill = PlotTheme.categorical(i, 0.45f);
            Color stroke = PlotTheme.INK;

            if (a.length > 0) {
                int step = Math.max(1, a.length / 350);
                g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 70));
                for (int j = 0; j < a.length; j += step) {
                    double jx = cx + (((j * 31) % 13) - 6) * (boxW / 16.0);
                    int py = top + ph - (int) Math.round(((a[j] - ymin) / (ymax - ymin)) * ph);
                    g.fillOval((int) jx, py, 2, 2);
                }
            }

            if (a.length == 0) {
                continue;
            }

            Stats s = stats(a);
            int yMin = yPix(s.wLo, ymin, ymax, top, ph);
            int yMax = yPix(s.wHi, ymin, ymax, top, ph);
            int yQ1 = yPix(s.q1, ymin, ymax, top, ph);
            int yQ3 = yPix(s.q3, ymin, ymax, top, ph);
            int yMed = yPix(s.med, ymin, ymax, top, ph);

            g.setColor(stroke);
            g.setStroke(new BasicStroke(1.0f));
            g.drawLine((int) cx, yMin, (int) cx, Math.max(yQ1, yQ3));
            g.drawLine((int) cx, Math.min(yQ1, yQ3), (int) cx, yMax);
            g.drawLine((int) (cx - boxW / 5), yMin, (int) (cx + boxW / 5), yMin);
            g.drawLine((int) (cx - boxW / 5), yMax, (int) (cx + boxW / 5), yMax);

            int boxTop = Math.min(yQ1, yQ3);
            int boxH = Math.max(2, Math.abs(yQ3 - yQ1));
            g.setColor(fill);
            g.fillRoundRect((int) (cx - boxW / 2), boxTop, (int) boxW, boxH, 4, 4);
            g.setColor(stroke);
            g.drawRoundRect((int) (cx - boxW / 2), boxTop, (int) boxW, boxH, 4, 4);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine((int) (cx - boxW / 2 + 1), yMed, (int) (cx + boxW / 2 - 1), yMed);
            g.setStroke(new BasicStroke(1.0f));
        }

        // axes on top of guides
        PlotTheme.drawOpenAxes(g, getFont(), left, top, pw, ph, 0.5, k + 0.5, ymin, ymax, "cluster", ylab);

        // replace numeric x ticks with cluster labels
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
        // redraw x spine + title
        g.setColor(PlotTheme.INK);
        g.setStroke(new BasicStroke(PlotTheme.AXIS_STROKE));
        g.drawLine(left, top + ph, left + pw, top + ph);
        g.setFont(PlotTheme.axisTitleFont(getFont()));
        String xlab = "cluster";
        g.drawString(xlab, left + (pw - g.getFontMetrics().stringWidth(xlab)) / 2, top + ph + 36);
    }

    private static int yPix(float v, float ymin, float ymax, int top, int ph) {
        return top + ph - (int) Math.round(((v - ymin) / (ymax - ymin)) * ph);
    }

    private static Stats stats(float[] sorted) {
        Stats s = new Stats();
        int n = sorted.length;
        s.q1 = quantile(sorted, 0.25f);
        s.med = quantile(sorted, 0.5f);
        s.q3 = quantile(sorted, 0.75f);
        float iqr = s.q3 - s.q1;
        float lo = s.q1 - 1.5f * iqr;
        float hi = s.q3 + 1.5f * iqr;
        s.wLo = sorted[0];
        s.wHi = sorted[n - 1];
        for (float v : sorted) {
            if (v >= lo) {
                s.wLo = v;
                break;
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            if (sorted[i] <= hi) {
                s.wHi = sorted[i];
                break;
            }
        }
        return s;
    }

    private static float quantile(float[] sorted, float p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double idx = p * (sorted.length - 1);
        int i = (int) Math.floor(idx);
        double f = idx - i;
        if (i + 1 >= sorted.length) {
            return sorted[sorted.length - 1];
        }
        return (float) (sorted[i] * (1 - f) + sorted[i + 1] * f);
    }

    private static final class Stats {
        float q1, med, q3, wLo, wHi;
    }
}
