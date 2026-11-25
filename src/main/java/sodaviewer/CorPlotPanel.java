package sodaviewer;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** gg.cor — publication gene–gene scatter with Pearson stats. */
public final class CorPlotPanel extends JPanel {
    private float[] x;
    private float[] y;
    private int[] cluster;
    private String[] clusterLabels;
    private String gene1 = "";
    private String gene2 = "";
    private double r = Double.NaN;
    private double pValue = Double.NaN;

    public CorPlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 580));
    }

    public void setData(String g1, String g2, float[] x, float[] y, int[] cluster, String[] labels) {
        this.gene1 = g1;
        this.gene2 = g2;
        this.x = x;
        this.y = y;
        this.cluster = cluster;
        this.clusterLabels = labels;
        StatsPearson sp = pearson(x, y);
        this.r = sp.r;
        this.pValue = sp.p;
        repaint();
    }

    public void clear() {
        x = y = null;
        cluster = null;
        gene1 = gene2 = "";
        r = pValue = Double.NaN;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        PlotTheme.clearPaper(g, w, h);

        boolean hasLegend = clusterLabels != null && clusterLabels.length > 0;
        int[] pad = PlotTheme.pad(hasLegend ? 100 : 16, true);
        int left = pad[0], right = pad[1], top = pad[2], bottom = pad[3];
        int pw = Math.max(10, w - left - right);
        int ph = Math.max(10, h - top - bottom);

        String sub = String.format("P value: %s   ·   r = %s",
                formatP(pValue),
                Double.isFinite(r) ? String.format("%.3f", r) : "NA");
        PlotTheme.drawTitle(g, getFont(), "Gene–gene correlation", sub, left, 24);

        if (x == null || y == null || x.length == 0) {
            PlotTheme.qualityHints(g, false);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            g.drawString("Enter two genes (e.g. CD3E, MS4A1) and choose Gene–gene.", left, top + 40);
            return;
        }

        int n = Math.min(x.length, y.length);
        PlotTheme.qualityHints(g, n > 40_000);

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        if (maxX <= minX) {
            maxX = minX + 1e-3f;
        }
        if (maxY <= minY) {
            maxY = minY + 1e-3f;
        }
        float mx = (maxX - minX) * 0.04f;
        float my = (maxY - minY) * 0.04f;
        minX -= mx;
        maxX += mx;
        minY -= my;
        maxY += my;

        int diam = n > 80_000 ? 1 : (n > 20_000 ? 2 : 3);
        for (int i = 0; i < n; i++) {
            int sx = left + (int) Math.round(((x[i] - minX) / (maxX - minX)) * pw);
            int sy = top + ph - (int) Math.round(((y[i] - minY) / (maxY - minY)) * ph);
            Color fill;
            if (cluster != null && i < cluster.length && cluster[i] >= 0) {
                fill = PlotTheme.categorical(cluster[i], 0.72f);
            } else {
                fill = new Color(0.35f, 0.35f, 0.35f, 0.55f);
            }
            g.setColor(fill);
            if (diam <= 1) {
                g.fillRect(sx, sy, 1, 1);
            } else {
                g.fillOval(sx - diam / 2, sy - diam / 2, diam, diam);
            }
        }

        PlotTheme.drawOpenAxes(g, getFont(), left, top, pw, ph,
                minX, maxX, minY, maxY,
                gene1 + " (log2p1)", gene2 + " (log2p1)");

        if (hasLegend) {
            PlotTheme.drawDiscreteLegend(g, getFont(), w - right + 6, top, clusterLabels, 20);
        }
    }

    private static String formatP(double p) {
        if (!Double.isFinite(p)) {
            return "NA";
        }
        if (p == 0 || p < 2.2e-16) {
            return "< 2.2e-16";
        }
        return String.format("%.3g", p);
    }

    static final class StatsPearson {
        final double r;
        final double p;

        StatsPearson(double r, double p) {
            this.r = r;
            this.p = p;
        }
    }

    static StatsPearson pearson(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 3) {
            return new StatsPearson(Double.NaN, Double.NaN);
        }
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            sx += a[i];
            sy += b[i];
        }
        double mx = sx / n;
        double my = sy / n;
        double sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double dx = a[i] - mx;
            double dy = b[i] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        if (sxx <= 0 || syy <= 0) {
            return new StatsPearson(Double.NaN, Double.NaN);
        }
        double r = sxy / Math.sqrt(sxx * syy);
        r = Math.max(-1, Math.min(1, r));
        double p;
        if (Math.abs(r) >= 1.0 - 1e-15) {
            p = 0;
        } else {
            double t = r * Math.sqrt((n - 2) / (1 - r * r));
            p = 2 * studentTCdfComplement(Math.abs(t), n - 2);
        }
        return new StatsPearson(r, p);
    }

    private static double studentTCdfComplement(double t, int df) {
        double x = df / (df + t * t);
        return regularizedIncompleteBeta(df / 2.0, 0.5, x);
    }

    private static double regularizedIncompleteBeta(double a, double b, double x) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double bt = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return bt * betaCf(a, b, x) / a;
        }
        return 1 - bt * betaCf(b, a, 1 - x) / b;
    }

    private static double betaCf(double a, double b, double x) {
        final int maxIt = 200;
        final double eps = 3e-7;
        double am = 1, bm = 1, az = 1;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double bz = 1 - qab * x / qap;
        for (int m = 1; m <= maxIt; m++) {
            double tem = m + m;
            double d = m * (b - m) * x / ((qam + tem) * (a + tem));
            double ap = az + d * am;
            double bp = bz + d * bm;
            d = -(a + m) * (qab + m) * x / ((a + tem) * (qap + tem));
            double app = ap + d * az;
            double bpp = bp + d * bz;
            double aold = az;
            am = ap / bpp;
            bm = bp / bpp;
            az = app / bpp;
            bz = 1;
            if (Math.abs(az - aold) < eps * Math.abs(az)) {
                return az;
            }
        }
        return az;
    }

    private static double logGamma(double z) {
        double[] c = {
                76.18009172947146, -86.50532032941677,
                24.01409824083091, -1.231739572450155,
                0.1208650973866179e-2, -0.5398356897051425e-5
        };
        double x = z;
        double y = z;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += c[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
