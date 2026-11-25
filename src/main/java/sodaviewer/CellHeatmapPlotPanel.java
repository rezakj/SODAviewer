package sodaviewer;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Classic {@code heatmap.gg.plot(..., group.by = "cells")} style:
 * genes × cells, row-scaled, columns ordered by cluster with equal-width panels.
 */
public final class CellHeatmapPlotPanel extends JPanel {
    private static final int STRIP_H = 10;

    private String[] genes = new String[0];
    /** scaled[gene][cell] already z-scored and clipped to [-2.5, 2.5] */
    private float[][] scaled;
    /** cluster index per column (aligned with scaled[0].length) */
    private int[] colCluster;
    private String[] clusterLevels = new String[0];
    private int nCells;
    /** Consecutive cluster blocks: [start, end) into cell columns. */
    private int[] blockStart = new int[0];
    private int[] blockEnd = new int[0];
    private int[] blockCluster = new int[0];
    private PlotTheme.ColorScale colorScale = PlotTheme.ColorScale.BLUE_WHITE_RED;
    private BufferedImage cache;
    private int cacheW = -1;
    private int cacheH = -1;

    public CellHeatmapPlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 580));
    }

    public void setColorScale(PlotTheme.ColorScale scale) {
        this.colorScale = scale == null ? PlotTheme.ColorScale.BLUE_WHITE_RED : scale;
        cache = null;
        repaint();
    }

    public PlotTheme.ColorScale getColorScale() {
        return colorScale;
    }

    /**
     * @param genes          gene names (rows)
     * @param scaledExpr     genes × cells, already row-scaled & clipped
     * @param colCluster     cluster index per cell column
     * @param clusterLevels  cluster label for each index
     */
    public void setCellData(
            String[] genes, float[][] scaledExpr, int[] colCluster, String[] clusterLevels
    ) {
        this.genes = genes == null ? new String[0] : genes.clone();
        this.clusterLevels = clusterLevels == null ? new String[0] : clusterLevels.clone();
        if (scaledExpr == null || this.genes.length == 0) {
            this.scaled = new float[0][];
            this.colCluster = new int[0];
            this.nCells = 0;
        } else {
            int ng = this.genes.length;
            this.nCells = scaledExpr[0] == null ? 0 : scaledExpr[0].length;
            this.scaled = new float[ng][nCells];
            for (int g = 0; g < ng; g++) {
                System.arraycopy(scaledExpr[g], 0, this.scaled[g], 0, nCells);
            }
            this.colCluster = colCluster == null ? new int[nCells] : colCluster.clone();
        }
        rebuildBlocks();
        cache = null;
        repaint();
    }

    private void rebuildBlocks() {
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<Integer> cats = new ArrayList<>();
        if (nCells > 0 && colCluster != null && colCluster.length == nCells) {
            int start = 0;
            while (start < nCells) {
                int cat = colCluster[start];
                int end = start + 1;
                while (end < nCells && colCluster[end] == cat) {
                    end++;
                }
                starts.add(start);
                ends.add(end);
                cats.add(cat);
                start = end;
            }
        }
        blockStart = starts.stream().mapToInt(Integer::intValue).toArray();
        blockEnd = ends.stream().mapToInt(Integer::intValue).toArray();
        blockCluster = cats.stream().mapToInt(Integer::intValue).toArray();
    }

    public void clear() {
        genes = new String[0];
        scaled = new float[0][];
        colCluster = new int[0];
        clusterLevels = new String[0];
        nCells = 0;
        blockStart = new int[0];
        blockEnd = new int[0];
        blockCluster = new int[0];
        cache = null;
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
            g.drawString("Gene × cell heatmap", 28, 28);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.drawString("Row-scaled (z) · equal-width clusters  (heatmap.gg.plot)", 28, 44);

            if (genes.length == 0 || nCells == 0 || scaled == null || blockStart.length == 0) {
                g.setColor(Color.BLACK);
                g.drawString("Enter genes for a cell-level heatmap (classic heatmap.gg.plot).", 28, 88);
                return;
            }

            g.setFont(PlotTheme.tickFont(getFont()));
            FontMetrics fm = g.getFontMetrics();
            int maxGeneW = 0;
            for (String gene : genes) {
                maxGeneW = Math.max(maxGeneW, fm.stringWidth(gene));
            }

            int left = Math.max(88, 24 + maxGeneW + 14);
            int right = 100;
            int top = 58;
            // room for cluster labels + axis title (no overlap)
            int bottom = 58;
            int pw = Math.max(40, w - left - right);
            int ph = Math.max(40, h - top - bottom);
            int heatTop = top + STRIP_H + 4;
            int heatH = Math.max(20, ph - STRIP_H - 4);
            int nBlocks = blockStart.length;

            // equal-width cluster color strip
            for (int b = 0; b < nBlocks; b++) {
                int x0 = left + (int) Math.floor(b * (double) pw / nBlocks);
                int x1 = left + (int) Math.floor((b + 1) * (double) pw / nBlocks);
                g.setColor(PlotTheme.categorical(blockCluster[b], 1f));
                g.fillRect(x0, top, Math.max(1, x1 - x0), STRIP_H);
            }
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(0.8f));
            g.drawRect(left, top, pw, STRIP_H);

            BufferedImage img = raster(pw, heatH);
            g.drawImage(img, left, heatTop, null);

            // equal-width panel boundaries
            g.setStroke(new BasicStroke(1.0f));
            g.setColor(new Color(0, 0, 0, 160));
            for (int b = 1; b < nBlocks; b++) {
                int x = left + (int) Math.round(b * (double) pw / nBlocks);
                g.drawLine(x, top, x, heatTop + heatH);
            }

            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.1f));
            g.drawRect(left, heatTop, pw, heatH);

            // gene labels
            g.setFont(PlotTheme.tickFont(getFont()));
            fm = g.getFontMetrics();
            int ng = genes.length;
            for (int r = 0; r < ng; r++) {
                String lab = genes[r];
                int tw = fm.stringWidth(lab);
                int y = heatTop + (int) Math.round((r + 0.5) * heatH / (double) ng)
                        + fm.getAscent() / 2 - 1;
                g.setColor(Color.BLACK);
                g.drawString(lab, left - 10 - tw, y);
            }

            // cluster labels — one line under the panel, clear of axis title
            g.setFont(PlotTheme.tickFont(getFont()));
            fm = g.getFontMetrics();
            int clusterLabelY = heatTop + heatH + fm.getAscent() + 3;
            for (int b = 0; b < nBlocks; b++) {
                int cat = blockCluster[b];
                String lab = (cat >= 0 && cat < clusterLevels.length)
                        ? clusterLevels[cat]
                        : String.valueOf(cat);
                int x0 = left + (int) Math.round(b * (double) pw / nBlocks);
                int x1 = left + (int) Math.round((b + 1) * (double) pw / nBlocks);
                int tw = fm.stringWidth(lab);
                if (tw <= (x1 - x0) - 2 || nBlocks <= 24) {
                    g.setColor(Color.BLACK);
                    g.drawString(lab, x0 + Math.max(0, (x1 - x0 - tw) / 2), clusterLabelY);
                }
            }

            g.setFont(PlotTheme.axisTitleFont(getFont()));
            g.setColor(Color.BLACK);
            String xlab = nBlocks + " clusters · " + nCells + " cells";
            int xlabY = Math.min(h - 10, clusterLabelY + fm.getHeight() + 14);
            g.drawString(xlab, left + (pw - g.getFontMetrics().stringWidth(xlab)) / 2, xlabY);

            // color bar
            int bx = left + pw + 18;
            int by = heatTop;
            int bh = Math.min(160, heatH);
            int bw = 14;
            for (int i = 0; i < bh; i++) {
                float t = 1f - i / (float) Math.max(1, bh - 1);
                g.setColor(PlotTheme.scaleColor(colorScale, t, 1f));
                g.fillRect(bx, by + i, bw, 1);
            }
            g.setColor(Color.BLACK);
            g.drawRect(bx, by, bw, bh);
            g.setFont(PlotTheme.tickFont(getFont()));
            g.drawString("+2.5", bx + bw + 4, by + 10);
            g.drawString("0", bx + bw + 4, by + bh / 2 + 4);
            g.drawString(String.format(Locale.ROOT, "−2.5"), bx + bw + 4, by + bh);
            g.setFont(PlotTheme.legendFont(getFont()));
            g.drawString("z", bx, by - 6);
        } finally {
            g.dispose();
        }
    }


    /**
     * Map each equal-width cluster panel independently onto its cells
     * (same visual width per cluster, like facet_wrap).
     */
    private BufferedImage raster(int pw, int ph) {
        if (cache != null && cacheW == pw && cacheH == ph) {
            return cache;
        }
        int ng = genes.length;
        int nBlocks = blockStart.length;
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ph; y++) {
            int gene = Math.min(ng - 1, (int) ((long) y * ng / ph));
            for (int x = 0; x < pw; x++) {
                int b = Math.min(nBlocks - 1, (int) ((long) x * nBlocks / pw));
                int x0 = (int) ((long) b * pw / nBlocks);
                int x1 = (int) ((long) (b + 1) * pw / nBlocks);
                int panelW = Math.max(1, x1 - x0);
                int local = x - x0;
                int start = blockStart[b];
                int end = blockEnd[b];
                int nIn = Math.max(1, end - start);
                int cell = start + Math.min(nIn - 1, (int) ((long) local * nIn / panelW));
                float z = scaled[gene][cell];
                float t = (z + 2.5f) / 5f;
                Color c = PlotTheme.scaleColor(colorScale, t, 1f);
                img.setRGB(x, y, c.getRGB() & 0xFFFFFF);
            }
        }
        cache = img;
        cacheW = pw;
        cacheH = ph;
        return img;
    }
}
