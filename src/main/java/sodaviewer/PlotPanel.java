package sodaviewer;

import javax.swing.JColorChooser;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Embedding scatter with gene (log2p1 + z) / cluster colors, zoom, and lasso
 * selection (exportable cell ids).
 */
public final class PlotPanel extends JPanel {
    private double[][] xy;
    private String[] cellIds;
    private float[] geneColor;      // z-scored log2p1 for display
    private String geneLabel = "";   // drawn under the plot (feature plots)
    private int[] clusterColor;
    private String[] clusterLabels;
    private boolean annotateClusters;
    private float pointOpacity = 0.85f;
    private boolean[] catHidden;
    private Color[] catOverride;
    private final java.util.List<Rectangle> legendHits = new ArrayList<>();
    private final java.util.List<Integer> legendCats = new ArrayList<>();
    private Rectangle legendHelpHit;
    private boolean[] selected;
    private boolean[] cellHidden;
    private String title = "";
    private String titleSuffix = "";
    private String xlab = "V1";
    private String ylab = "V2";
    private float colorLo = 0f;
    private float colorHi = 1f;
    private Mode mode = Mode.NONE;
    private PlotTheme.ColorScale colorScale = PlotTheme.ColorScale.BLUE_WHITE_RED;
    /** User point size in px; 0 = auto from cell count. */
    private int pointSize = 0;
    private int hoverIndex = -1;

    /** Data bounds (full embedding). */
    private double dataMinX, dataMaxX, dataMinY, dataMaxY;
    /** Current view window. */
    private double viewMinX, viewMaxX, viewMinY, viewMaxY;
    private boolean viewValid;

    private boolean lassoMode;
    private final List<Point> lassoScreen = new ArrayList<>();
    private boolean lassoDragging;
    private boolean panDragging;
    private int panLastX, panLastY;

    /** Layout of last paint (for hit-testing). */
    private int plotLeft, plotTop, plotW, plotH;

    enum Mode { NONE, GENE, CLUSTER }

    public PlotPanel() {
        setBackground(PlotTheme.PAPER);
        setOpaque(true);
        setPreferredSize(new Dimension(760, 580));
        installMouse();
    }

    private void installMouse() {
        setFocusable(true);
        addMouseWheelListener(this::onWheel);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                ensurePlotLayout();
                // Lasso: left-drag in lasso mode
                if (lassoMode && SwingUtilities.isLeftMouseButton(e)) {
                    lassoDragging = true;
                    panDragging = false;
                    lassoScreen.clear();
                    lassoScreen.add(e.getPoint());
                    repaint();
                    return;
                }
                // Pan: left-drag when not in lasso mode, or middle-button drag anytime
                boolean startPan = SwingUtilities.isMiddleMouseButton(e)
                        || (SwingUtilities.isLeftMouseButton(e) && !lassoMode);
                if (startPan) {
                    if (legendCategoryAt(e.getX(), e.getY()) >= 0
                            || (legendHelpHit != null && legendHelpHit.contains(e.getX(), e.getY()))) {
                        return;
                    }
                    panDragging = true;
                    panLastX = e.getX();
                    panLastY = e.getY();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (panDragging) {
                    panDragging = false;
                    setCursor(lassoMode
                            ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                            : Cursor.getDefaultCursor());
                    return;
                }
                if (!lassoMode || !lassoDragging) {
                    return;
                }
                lassoDragging = false;
                Point p = e.getPoint();
                if (lassoScreen.isEmpty() || lassoScreen.get(lassoScreen.size() - 1).distance(p) >= 2) {
                    lassoScreen.add(p);
                }
                if (lassoScreen.size() >= 3) {
                    applyLassoSelection();
                }
                lassoScreen.clear();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (panDragging) {
                    return;
                }
                if (legendHelpHit != null && legendHelpHit.contains(e.getX(), e.getY())
                        && SwingUtilities.isLeftMouseButton(e)) {
                    showLegendHelp();
                    return;
                }
                int cat = legendCategoryAt(e.getX(), e.getY());
                if (cat >= 0) {
                    if (e.getClickCount() >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                        recolorCategory(cat);
                    } else if (SwingUtilities.isRightMouseButton(e)
                            || (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown())) {
                        selectCategory(cat, e.isControlDown() || e.isMetaDown());
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        toggleCategoryHidden(cat);
                    }
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e) && e.getClickCount() == 1) {
                    clearSelection();
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (panDragging) {
                    int dx = e.getX() - panLastX;
                    int dy = e.getY() - panLastY;
                    panLastX = e.getX();
                    panLastY = e.getY();
                    panByScreenPixels(dx, dy);
                    return;
                }
                if (!lassoMode || !lassoDragging) {
                    return;
                }
                Point p = e.getPoint();
                if (lassoScreen.isEmpty() || lassoScreen.get(lassoScreen.size() - 1).distance(p) >= 3) {
                    lassoScreen.add(p);
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (lassoDragging || panDragging) {
                    return;
                }
                updateHover(e.getX(), e.getY());
            }
        });
        installKeyPan();
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        javax.swing.ToolTipManager.sharedInstance().setInitialDelay(180);
    }

    private void installKeyPan() {
        javax.swing.InputMap im = getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
        javax.swing.ActionMap am = getActionMap();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0), "panLeft");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0), "panRight");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0), "panUp");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0), "panDown");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, 0), "panLeft");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, 0), "panRight");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_W, 0), "panUp");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, 0), "panDown");
        am.put("panLeft", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { panStep(-1, 0); }
        });
        am.put("panRight", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { panStep(1, 0); }
        });
        am.put("panUp", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { panStep(0, -1); }
        });
        am.put("panDown", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { panStep(0, 1); }
        });
    }

    /** Arrow / WASD step: fraction of current view. */
    public void panStep(int dirX, int dirY) {
        if (!viewValid || plotW < 1 || plotH < 1) {
            ensurePlotLayout();
        }
        double frac = 0.18;
        int dx = (int) Math.round(-dirX * plotW * frac);
        int dy = (int) Math.round(-dirY * plotH * frac);
        panByScreenPixels(dx, dy);
    }

    public void panLeft() { panStep(-1, 0); }
    public void panRight() { panStep(1, 0); }
    public void panUp() { panStep(0, -1); }
    public void panDown() { panStep(0, 1); }

    /**
     * Pan by screen-pixel drag delta. Positive dx = drag right → content follows.
     */
    public void panByScreenPixels(int dxPix, int dyPix) {
        if (!viewValid || plotW < 1 || plotH < 1 || xy == null) {
            return;
        }
        double spanX = viewMaxX - viewMinX;
        double spanY = viewMaxY - viewMinY;
        double ddx = -(dxPix / (double) plotW) * spanX;
        double ddy = (dyPix / (double) plotH) * spanY;
        viewMinX += ddx;
        viewMaxX += ddx;
        viewMinY += ddy;
        viewMaxY += ddy;
        clampView();
        repaint();
    }

    private void onWheel(MouseWheelEvent e) {
        if (xy == null || !viewValid) {
            return;
        }
        double factor = e.getPreciseWheelRotation() < 0 ? 0.85 : 1.18;
        zoomAt(e.getX(), e.getY(), factor);
    }

    public void setLassoMode(boolean on) {
        this.lassoMode = on;
        setCursor(on ? Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                : Cursor.getDefaultCursor());
        if (!on) {
            lassoScreen.clear();
            lassoDragging = false;
            clearSelection();
        }
        repaint();
    }

    public boolean isLassoMode() {
        return lassoMode;
    }

    public void setColorScale(PlotTheme.ColorScale scale) {
        this.colorScale = scale == null ? PlotTheme.ColorScale.BLUE_WHITE_RED : scale;
        repaint();
    }

    public PlotTheme.ColorScale getColorScale() {
        return colorScale;
    }

    /** {@code size <= 0} = automatic from cell count. */
    public void setPointSize(int size) {
        this.pointSize = size;
        repaint();
    }

    public int getPointSize() {
        return pointSize;
    }

    /** Hovered cell index, or -1. */
    public int hoverIndex() {
        return hoverIndex;
    }

    /** Point fill opacity 0..1. */
    public void setPointOpacity(float opacity) {
        this.pointOpacity = Math.max(0.05f, Math.min(1f, opacity));
        repaint();
    }

    public float getPointOpacity() {
        return pointOpacity;
    }

    public void setAnnotateClusters(boolean on) {
        this.annotateClusters = on;
        repaint();
    }

    public boolean isAnnotateClusters() {
        return annotateClusters;
    }

    public void showAllCategories() {
        if (catHidden != null) {
            Arrays.fill(catHidden, false);
            repaint();
        }
    }

    public void resetCategoryColors() {
        if (catOverride != null) {
            Arrays.fill(catOverride, null);
            repaint();
        }
    }

    public void zoomIn() {
        zoomCenter(0.8);
    }

    public void zoomOut() {
        zoomCenter(1.25);
    }

    public void zoomHome() {
        resetViewToData();
        clearSelection();
        showAllCells();
        showAllCategories();
        resetCategoryColors();
        setToolTipText(null);
        repaint();
    }

    /** Restore original category visibility and palette colors (keeps zoom). */
    public void resetLegendAppearance() {
        showAllCategories();
        resetCategoryColors();
        repaint();
    }

    private void zoomCenter(double factor) {
        if (!viewValid) {
            return;
        }
        double cx = (viewMinX + viewMaxX) / 2;
        double cy = (viewMinY + viewMaxY) / 2;
        zoomAroundData(cx, cy, factor);
    }

    private void zoomAt(int sx, int sy, double factor) {
        if (!viewValid || plotW < 1 || plotH < 1) {
            return;
        }
        double dx = dataX(sx);
        double dy = dataY(sy);
        zoomAroundData(dx, dy, factor);
    }

    private void zoomAroundData(double cx, double cy, double factor) {
        double vx = viewMaxX - viewMinX;
        double vy = viewMaxY - viewMinY;
        double nvx = vx * factor;
        double nvy = vy * factor;
        // keep cursor/center fixed
        double fx = (cx - viewMinX) / vx;
        double fy = (cy - viewMinY) / vy;
        viewMinX = cx - fx * nvx;
        viewMaxX = viewMinX + nvx;
        viewMinY = cy - fy * nvy;
        viewMaxY = viewMinY + nvy;
        clampView();
        repaint();
    }

    private void resetViewToData() {
        if (xy == null || xy.length == 0) {
            viewValid = false;
            return;
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
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
        dataMinX = minX - 0.04 * dx;
        dataMaxX = maxX + 0.04 * dx;
        dataMinY = minY - 0.04 * dy;
        dataMaxY = maxY + 0.04 * dy;
        viewMinX = dataMinX;
        viewMaxX = dataMaxX;
        viewMinY = dataMinY;
        viewMaxY = dataMaxY;
        viewValid = true;
    }

    private void clampView() {
        // don't zoom out past ~3x data span
        double maxSpanX = (dataMaxX - dataMinX) * 3;
        double maxSpanY = (dataMaxY - dataMinY) * 3;
        if (viewMaxX - viewMinX > maxSpanX) {
            double c = (viewMinX + viewMaxX) / 2;
            viewMinX = c - maxSpanX / 2;
            viewMaxX = c + maxSpanX / 2;
        }
        if (viewMaxY - viewMinY > maxSpanY) {
            double c = (viewMinY + viewMaxY) / 2;
            viewMinY = c - maxSpanY / 2;
            viewMaxY = c + maxSpanY / 2;
        }
        double minSpanX = (dataMaxX - dataMinX) * 0.02;
        double minSpanY = (dataMaxY - dataMinY) * 0.02;
        if (viewMaxX - viewMinX < minSpanX) {
            double c = (viewMinX + viewMaxX) / 2;
            viewMinX = c - minSpanX / 2;
            viewMaxX = c + minSpanX / 2;
        }
        if (viewMaxY - viewMinY < minSpanY) {
            double c = (viewMinY + viewMaxY) / 2;
            viewMinY = c - minSpanY / 2;
            viewMaxY = c + minSpanY / 2;
        }
        // keep view overlapping data (allow small margin outside)
        double spanX = viewMaxX - viewMinX;
        double spanY = viewMaxY - viewMinY;
        double marginX = spanX * 0.85;
        double marginY = spanY * 0.85;
        if (viewMaxX < dataMinX - marginX) {
            double s = (dataMinX - marginX) - viewMaxX;
            viewMinX += s;
            viewMaxX += s;
        }
        if (viewMinX > dataMaxX + marginX) {
            double s = (dataMaxX + marginX) - viewMinX;
            viewMinX += s;
            viewMaxX += s;
        }
        if (viewMaxY < dataMinY - marginY) {
            double s = (dataMinY - marginY) - viewMaxY;
            viewMinY += s;
            viewMaxY += s;
        }
        if (viewMinY > dataMaxY + marginY) {
            double s = (dataMaxY + marginY) - viewMinY;
            viewMinY += s;
            viewMaxY += s;
        }
    }

    public void setEmbedding(double[][] xy, String[] cellIds, String title, String xlab, String ylab) {
        this.xy = xy;
        this.cellIds = cellIds;
        this.title = title == null ? "" : title;
        this.xlab = xlab == null ? "V1" : xlab;
        this.ylab = ylab == null ? "V2" : ylab;
        this.geneColor = null;
        this.geneLabel = "";
        this.clusterColor = null;
        this.clusterLabels = null;
        this.mode = Mode.NONE;
        this.titleSuffix = "";
        this.selected = xy == null ? null : new boolean[xy.length];
        this.cellHidden = xy == null ? null : new boolean[xy.length];
        this.hoverIndex = -1;
        setToolTipText(null);
        resetViewToData();
        repaint();
    }

    /** Legacy overload without cell ids. */
    public void setEmbedding(double[][] xy, String title, String xlab, String ylab) {
        setEmbedding(xy, null, title, xlab, ylab);
    }

    /**
     * Color by gene using log2p1 values, scaled between the 1st–99th percentiles.
     * Gene name is drawn centered under the plot.
     */
    public void setGeneColors(float[] log2p1Values) {
        setGeneColors(log2p1Values, null);
    }

    public void setGeneColors(float[] log2p1Values, String geneName) {
        this.geneColor = log2p1Values;
        this.geneLabel = geneName == null ? "" : geneName.trim();
        this.clusterColor = null;
        this.clusterLabels = null;
        this.mode = Mode.GENE;
        this.titleSuffix = "log2p1";
        if (log2p1Values == null || log2p1Values.length == 0) {
            colorLo = 0f;
            colorHi = 1f;
        } else {
            float[] qs = quantiles(log2p1Values, 0.01f, 0.99f);
            colorLo = qs[0];
            colorHi = qs[1];
            if (colorHi <= colorLo) {
                colorHi = colorLo + 1e-6f;
            }
        }
        repaint();
    }

    /** Color by a continuous metadata column (pseudotime, QC, …). */
    public void setContinuousColors(float[] values, String legendName) {
        this.geneColor = values;
        this.geneLabel = "";
        this.clusterColor = null;
        this.clusterLabels = null;
        this.mode = Mode.GENE;
        this.titleSuffix = legendName == null ? "" : legendName;
        if (values == null || values.length == 0) {
            colorLo = 0f;
            colorHi = 1f;
        } else {
            float[] qs = quantiles(values, 0.01f, 0.99f);
            colorLo = qs[0];
            colorHi = qs[1];
            if (!Float.isFinite(colorLo) || !Float.isFinite(colorHi) || colorHi <= colorLo) {
                colorLo = 0f;
                colorHi = 1f;
            }
        }
        repaint();
    }

    public void setClusterColors(int[] categories, String[] labels, boolean annotate) {
        this.clusterColor = categories;
        this.clusterLabels = labels;
        this.annotateClusters = annotate;
        this.geneColor = null;
        this.geneLabel = "";
        this.mode = Mode.CLUSTER;
        this.titleSuffix = "";
        int k = labels == null ? 0 : labels.length;
        this.catHidden = new boolean[k];
        this.catOverride = new Color[k];
        legendHits.clear();
        legendCats.clear();
        repaint();
    }

    public void clearColors() {
        this.geneColor = null;
        this.geneLabel = "";
        this.clusterColor = null;
        this.clusterLabels = null;
        this.catHidden = null;
        this.catOverride = null;
        legendHits.clear();
        legendCats.clear();
        this.mode = Mode.NONE;
        this.titleSuffix = "";
        repaint();
    }

    /** True when scatter uses a continuous color scale (gene / pseudotime). */
    public boolean isGeneColored() {
        return mode == Mode.GENE;
    }

    /** True when scatter uses categorical / cluster colors. */
    public boolean isClusterColored() {
        return mode == Mode.CLUSTER;
    }

    public void clearSelection() {
        if (selected != null) {
            Arrays.fill(selected, false);
            firePropertyChange("selection", -1, 0);
        }
        repaint();
    }

    public int selectedCount() {
        if (selected == null) {
            return 0;
        }
        int n = 0;
        for (boolean b : selected) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    public String[] selectedCellIds() {
        if (selected == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            if (!selected[i]) {
                continue;
            }
            if (cellIds != null && i < cellIds.length && cellIds[i] != null && !cellIds[i].isBlank()) {
                out.add(cellIds[i]);
            } else {
                out.add("cell_" + i);
            }
        }
        return out.toArray(String[]::new);
    }


    public boolean[] selectedMask() {
        return selected == null ? new boolean[0] : selected.clone();
    }

    public int[] getClusterCategories() {
        return clusterColor == null ? null : clusterColor.clone();
    }

    public String[] getClusterLabels() {
        return clusterLabels == null ? null : clusterLabels.clone();
    }

    public boolean hasClusterColors() {
        return mode == Mode.CLUSTER && clusterColor != null && clusterLabels != null;
    }

    /** Hide currently selected cells from the scatter (session only). */
    public int hideSelectedCells() {
        if (selected == null || cellHidden == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                cellHidden[i] = true;
                selected[i] = false;
                n++;
            }
        }
        if (n > 0) {
            firePropertyChange("selection", -1, 0);
            repaint();
        }
        return n;
    }

    public void showAllCells() {
        if (cellHidden != null) {
            Arrays.fill(cellHidden, false);
            repaint();
        }
    }

    public void renameCluster(int cat, String newName) {
        if (clusterLabels == null || cat < 0 || cat >= clusterLabels.length) {
            return;
        }
        if (newName == null || newName.isBlank()) {
            return;
        }
        clusterLabels[cat] = newName.trim();
        repaint();
    }

    /**
     * Assign selected cells to an existing cluster index, or to a new label
     * when {@code cat < 0} (appends {@code newLabel}).
     * @return number of cells reassigned
     */
    public int assignSelectedToCluster(int cat, String newLabel) {
        if (selected == null || clusterColor == null) {
            return 0;
        }
        int target = cat;
        if (target < 0) {
            String lab = (newLabel == null || newLabel.isBlank()) ? "new" : newLabel.trim();
            int k = clusterLabels == null ? 0 : clusterLabels.length;
            String[] nl = new String[k + 1];
            if (clusterLabels != null) {
                System.arraycopy(clusterLabels, 0, nl, 0, k);
            }
            nl[k] = lab;
            clusterLabels = nl;
            boolean[] nh = new boolean[k + 1];
            if (catHidden != null) {
                System.arraycopy(catHidden, 0, nh, 0, k);
            }
            catHidden = nh;
            Color[] no = new Color[k + 1];
            if (catOverride != null) {
                System.arraycopy(catOverride, 0, no, 0, k);
            }
            catOverride = no;
            target = k;
        }
        if (target >= clusterLabels.length) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < selected.length && i < clusterColor.length; i++) {
            if (selected[i]) {
                clusterColor[i] = target;
                n++;
            }
        }
        if (n > 0) {
            mode = Mode.CLUSTER;
            repaint();
        }
        return n;
    }

    /**
     * Merge {@code sources} into {@code target} (existing index). Compacts labels.
     */
    public int mergeClusters(int[] sources, int target) {
        if (clusterColor == null || clusterLabels == null || sources == null || sources.length == 0) {
            return 0;
        }
        if (target < 0 || target >= clusterLabels.length) {
            return 0;
        }
        boolean[] isSrc = new boolean[clusterLabels.length];
        for (int s : sources) {
            if (s >= 0 && s < isSrc.length && s != target) {
                isSrc[s] = true;
            }
        }
        int n = 0;
        for (int i = 0; i < clusterColor.length; i++) {
            int c = clusterColor[i];
            if (c >= 0 && c < isSrc.length && isSrc[c]) {
                clusterColor[i] = target;
                n++;
            }
        }
        // compact: rebuild labels keeping order of first appearance
        java.util.LinkedHashMap<Integer, String> keep = new java.util.LinkedHashMap<>();
        for (int i = 0; i < clusterColor.length; i++) {
            int c = clusterColor[i];
            if (c < 0 || c >= clusterLabels.length) {
                continue;
            }
            keep.putIfAbsent(c, clusterLabels[c]);
        }
        java.util.List<Integer> oldIds = new java.util.ArrayList<>(keep.keySet());
        java.util.Map<Integer, Integer> remap = new java.util.HashMap<>();
        String[] nl = new String[oldIds.size()];
        for (int i = 0; i < oldIds.size(); i++) {
            int old = oldIds.get(i);
            remap.put(old, i);
            nl[i] = keep.get(old);
        }
        for (int i = 0; i < clusterColor.length; i++) {
            Integer m = remap.get(clusterColor[i]);
            clusterColor[i] = m == null ? -1 : m;
        }
        clusterLabels = nl;
        catHidden = new boolean[nl.length];
        catOverride = new Color[nl.length];
        mode = Mode.CLUSTER;
        repaint();
        return n;
    }


    private boolean insidePlot(int x, int y) {
        return x >= plotLeft && x <= plotLeft + plotW && y >= plotTop && y <= plotTop + plotH;
    }

    /** Recompute plot rect if paint hasn't run yet (needed for first lasso). */
    private void ensurePlotLayout() {
        if (plotW > 0 && plotH > 0) {
            return;
        }
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        int legendW = mode == Mode.GENE ? 78 : (mode == Mode.CLUSTER ? 108 : 16);
        int[] pad = PlotTheme.pad(legendW, false);
        plotLeft = pad[0];
        plotTop = pad[2];
        plotW = Math.max(10, w - pad[0] - pad[1]);
        plotH = Math.max(10, h - pad[2] - pad[3]);
    }

    private double dataX(int sx) {
        double dx = viewMaxX - viewMinX;
        return viewMinX + ((sx - plotLeft) / (double) plotW) * dx;
    }

    private double dataY(int sy) {
        double dy = viewMaxY - viewMinY;
        return viewMaxY - ((sy - plotTop) / (double) plotH) * dy;
    }

    private void updateHover(int mx, int my) {
        int prev = hoverIndex;
        hoverIndex = nearestPoint(mx, my, Math.max(6, effectiveDiam() + 3));
        if (hoverIndex != prev) {
            repaint();
            setToolTipText(hoverTip(hoverIndex));
            firePropertyChange("hover", prev, hoverIndex);
        }
    }

    private String hoverTip(int idx) {
        if (idx < 0 || xy == null || idx >= xy.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html>");
        if (cellIds != null && idx < cellIds.length && cellIds[idx] != null) {
            sb.append("<b>").append(cellIds[idx]).append("</b><br>");
        }
        sb.append(String.format("%s=%.3f &nbsp; %s=%.3f", xlab, xy[idx][0], ylab, xy[idx][1]));
        if (mode == Mode.CLUSTER && clusterColor != null && idx < clusterColor.length) {
            int c = clusterColor[idx];
            if (c >= 0 && clusterLabels != null && c < clusterLabels.length) {
                sb.append("<br>").append(clusterLabels[c]);
            }
        } else if (mode == Mode.GENE && geneColor != null && idx < geneColor.length) {
            sb.append("<br>")
                    .append(titleSuffix.isEmpty() ? "value" : titleSuffix)
                    .append("=")
                    .append(String.format("%.3f", geneColor[idx]));
        }
        sb.append("</html>");
        return sb.toString();
    }

    private int nearestPoint(int mx, int my, int maxDist) {
        if (xy == null || !viewValid || plotW < 1 || plotH < 1) {
            return -1;
        }
        double dx = viewMaxX - viewMinX;
        double dy = viewMaxY - viewMinY;
        if (dx < 1e-12) {
            dx = 1;
        }
        if (dy < 1e-12) {
            dy = 1;
        }
        int best = -1;
        double bestD = (double) maxDist * maxDist;
        int step = xy.length > 60_000 ? 2 : 1;
        for (int i = 0; i < xy.length; i += step) {
            if (xy[i][0] < viewMinX || xy[i][0] > viewMaxX
                    || xy[i][1] < viewMinY || xy[i][1] > viewMaxY) {
                continue;
            }
            int sx = plotLeft + (int) Math.round(((xy[i][0] - viewMinX) / dx) * plotW);
            int sy = plotTop + plotH - (int) Math.round(((xy[i][1] - viewMinY) / dy) * plotH);
            double d = (sx - mx) * (double) (sx - mx) + (sy - my) * (double) (sy - my);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private int effectiveDiam() {
        if (pointSize > 0) {
            return pointSize;
        }
        int n = xy == null ? 0 : xy.length;
        return n > 80_000 ? 1 : (n > 25_000 ? 2 : (n > 5_000 ? 2 : 3));
    }

    private int legendCategoryAt(int x, int y) {
        for (int i = 0; i < legendHits.size(); i++) {
            if (legendHits.get(i).contains(x, y)) {
                return legendCats.get(i);
            }
        }
        return -1;
    }

    private void toggleCategoryHidden(int cat) {
        if (catHidden == null || cat < 0 || cat >= catHidden.length) {
            return;
        }
        catHidden[cat] = !catHidden[cat];
        firePropertyChange("categoryFilter", !catHidden[cat], catHidden[cat]);
        String lab = clusterLabels != null && cat < clusterLabels.length ? clusterLabels[cat] : ("#" + cat);
        setToolTipText(catHidden[cat]
                ? "Hidden: " + lab + " (click legend to show)"
                : "Shown: " + lab);
        repaint();
    }

    private void selectCategory(int cat, boolean add) {
        if (selected == null || clusterColor == null || cat < 0) {
            return;
        }
        if (!add) {
            Arrays.fill(selected, false);
        }
        int n = 0;
        for (int i = 0; i < clusterColor.length && i < selected.length; i++) {
            if (clusterColor[i] == cat && categoryVisible(cat)) {
                selected[i] = true;
                n++;
            }
        }
        firePropertyChange("selection", -1, selectedCount());
        String lab = clusterLabels != null && cat < clusterLabels.length ? clusterLabels[cat] : ("#" + cat);
        setToolTipText("Selected " + n + " cells · " + lab + " (Save IDs… to export)");
        repaint();
    }

    private void recolorCategory(int cat) {
        if (catOverride == null || cat < 0 || cat >= catOverride.length) {
            return;
        }
        Color cur = catOverride[cat] != null
                ? catOverride[cat]
                : PlotTheme.categorical(cat, 1f);
        Color picked = JColorChooser.showDialog(this, "Cluster / category color", cur);
        if (picked != null) {
            catOverride[cat] = picked;
            repaint();
        }
    }

    private Color categoryColor(int cat, float alpha) {
        if (cat < 0) {
            return new Color(0.75f, 0.75f, 0.75f, alpha);
        }
        if (catOverride != null && cat < catOverride.length && catOverride[cat] != null) {
            Color c = catOverride[cat];
            float a = Math.max(0f, Math.min(1f, alpha));
            return new Color(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, a);
        }
        return PlotTheme.categorical(cat, alpha);
    }

    private boolean categoryVisible(int cat) {
        return cat < 0 || catHidden == null || cat >= catHidden.length || !catHidden[cat];
    }

    private void drawInteractiveLegend(Graphics2D g, int x, int y, int maxRows) {
        legendHits.clear();
        legendCats.clear();
        legendHelpHit = null;
        if (clusterLabels == null) {
            return;
        }
        g.setFont(PlotTheme.legendFont(getFont()));
        var fm = g.getFontMetrics();
        int row = Math.max(16, fm.getHeight() + 2);
        int n = Math.min(clusterLabels.length, maxRows);
        for (int i = 0; i < n; i++) {
            int yy = y + i * row;
            Rectangle hit = new Rectangle(x - 2, yy, 120, row);
            legendHits.add(hit);
            legendCats.add(i);
            boolean hid = catHidden != null && i < catHidden.length && catHidden[i];
            Color sw = categoryColor(i, hid ? 0.25f : 1f);
            g.setColor(sw);
            g.fillOval(x, yy + 3, 10, 10);
            g.setColor(PlotTheme.INK);
            g.drawOval(x, yy + 3, 10, 10);
            g.setColor(hid ? PlotTheme.MUTE : PlotTheme.INK);
            String lab = clusterLabels[i];
            g.drawString(lab, x + 16, yy + fm.getAscent() + 2);
            if (hid) {
                g.setColor(PlotTheme.MUTE);
                int tw = fm.stringWidth(lab);
                g.drawLine(x + 16, yy + fm.getAscent() / 2 + 4, x + 16 + tw, yy + fm.getAscent() / 2 + 4);
            }
        }
        if (clusterLabels.length > n) {
            g.setColor(PlotTheme.MUTE);
            int extra = clusterLabels.length - n;
            g.drawString("+" + extra + " cluster" + (extra == 1 ? "" : "s"),
                    x, y + n * row + fm.getAscent());
        }
        // Help affordance — interactive only, omitted from exports
        if (!isExporting()) {
            int hy = y + n * row + (clusterLabels.length > n ? row + 6 : 10);
            int hx = x;
            Font helpFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
            g.setFont(helpFont);
            FontMetrics hfm = g.getFontMetrics();
            String label = "Modify help";
            int padX = 8;
            int padY = 4;
            int boxW = padX * 2 + hfm.stringWidth(label);
            int boxH = hfm.getHeight() + padY * 2;
            legendHelpHit = new Rectangle(hx, hy, boxW, boxH);

            g.setColor(new Color(0xEE, 0xF4, 0xFA));
            g.fillRoundRect(hx, hy, boxW, boxH, 8, 8);
            g.setColor(new Color(0x2A, 0x5F, 0x8F));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(hx, hy, boxW, boxH, 8, 8);
            int ly = hy + (boxH + hfm.getAscent() - hfm.getDescent()) / 2;
            g.drawString(label, hx + padX, ly);
        }
    }

    private boolean isExporting() {
        return Boolean.TRUE.equals(getClientProperty("sodaviewer.exporting"));
    }

    private void showLegendHelp() {
        javax.swing.JOptionPane.showMessageDialog(this,
                """
                Modify legend

                • Click a color — hide / show that cluster (or condition)
                • Shift-click — select those cells (then Save IDs…)
                • ⌘/Ctrl-Shift-click — add to current selection
                • Double-click — pick a custom color for that category
                • Home — reset zoom, selection, visibility, and colors

                Tip: hide unwanted clusters before lasso, or Shift-click
                a legend swatch to select a whole cluster at once.
                """,
                "Modify help",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
    }

    private void applyLassoSelection() {
        if (xy == null || selected == null || lassoScreen.size() < 3) {
            return;
        }
        // Polygon.contains is more reliable than Path2D for freehand lassos
        java.awt.Polygon poly = new java.awt.Polygon();
        for (Point p : lassoScreen) {
            poly.addPoint(p.x, p.y);
        }
        // Ensure closed
        Point first = lassoScreen.get(0);
        Point last = lassoScreen.get(lassoScreen.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            poly.addPoint(first.x, first.y);
        }

        double dx = viewMaxX - viewMinX;
        double dy = viewMaxY - viewMinY;
        if (dx < 1e-12) {
            dx = 1;
        }
        if (dy < 1e-12) {
            dy = 1;
        }
        if (plotW < 1 || plotH < 1) {
            return;
        }

        int hitPad = 2; // catch near-edge points / small markers
        boolean any = false;
        for (int i = 0; i < xy.length; i++) {
            int sx = plotLeft + (int) Math.round(((xy[i][0] - viewMinX) / dx) * plotW);
            int sy = plotTop + plotH - (int) Math.round(((xy[i][1] - viewMinY) / dy) * plotH);
            boolean hit = poly.contains(sx, sy);
            if (!hit && hitPad > 0) {
                // Expand hit box slightly around the painted point
                hit = poly.contains(sx - hitPad, sy)
                        || poly.contains(sx + hitPad, sy)
                        || poly.contains(sx, sy - hitPad)
                        || poly.contains(sx, sy + hitPad)
                        || poly.intersects(sx - hitPad, sy - hitPad, hitPad * 2 + 1, hitPad * 2 + 1);
            }
            selected[i] = hit;
            if (hit) {
                any = true;
            }
        }
        int n = selectedCount();
        firePropertyChange("selection", -1, n);
        // Keep empty result visible to the user via property change (0)
        if (!any) {
            Arrays.fill(selected, false);
            firePropertyChange("selection", -1, 0);
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth();
        int h = getHeight();
        PlotTheme.clearPaper(g, w, h);

        int legendW = mode == Mode.GENE ? 78 : (mode == Mode.CLUSTER ? 108 : 16);
        boolean geneUnder = mode == Mode.GENE && geneLabel != null && !geneLabel.isEmpty();
        int[] pad = PlotTheme.pad(legendW, false);
        int left = pad[0], right = pad[1], top = pad[2];
        int bottom = pad[3] + (geneUnder ? 18 : 0);
        int pw = Math.max(10, w - left - right);
        int ph = Math.max(10, h - top - bottom);
        plotLeft = left;
        plotTop = top;
        plotW = pw;
        plotH = ph;

        String ttl = title.isEmpty() ? "Embedding" : title;
        PlotTheme.drawTitle(g, getFont(), ttl, null, left, 28);

        if (xy == null || xy.length == 0 || !viewValid) {
            PlotTheme.qualityHints(g, false);
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            g.drawString("Open a SODA folder and choose a reduction to plot.", left, top + 36);
            return;
        }

        int n = xy.length;
        PlotTheme.qualityHints(g, n > 40_000);

        double minX = viewMinX, maxX = viewMaxX, minY = viewMinY, maxY = viewMaxY;
        double dx = maxX - minX;
        double dy = maxY - minY;
        if (dx < 1e-12) {
            dx = 1;
        }
        if (dy < 1e-12) {
            dy = 1;
        }

        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (mode == Mode.GENE && geneColor != null) {
            final float[] c = geneColor;
            Integer[] boxed = new Integer[n];
            for (int i = 0; i < n; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> Float.compare(
                    a < c.length ? c[a] : 0f,
                    b < c.length ? c[b] : 0f));
            for (int i = 0; i < n; i++) {
                order[i] = boxed[i];
            }
        }

        int diam = effectiveDiam();
        float alpha = pointOpacity;
        boolean hasSel = selectedCount() > 0;

        // unselected first (dim), then selected on top
        for (int pass = 0; pass < 2; pass++) {
            for (int idx : order) {
                if (cellHidden != null && idx < cellHidden.length && cellHidden[idx]) {
                    continue;
                }
                boolean isSel = selected != null && idx < selected.length && selected[idx];
                if (hasSel) {
                    if (pass == 0 && isSel) {
                        continue;
                    }
                    if (pass == 1 && !isSel) {
                        continue;
                    }
                } else if (pass == 1) {
                    break;
                }
                if (mode == Mode.CLUSTER && clusterColor != null && idx < clusterColor.length
                        && !categoryVisible(clusterColor[idx])) {
                    continue;
                }
                double[] p = xy[idx];
                if (p[0] < minX || p[0] > maxX || p[1] < minY || p[1] > maxY) {
                    continue;
                }
                int sx = left + (int) Math.round(((p[0] - minX) / dx) * pw);
                int sy = top + ph - (int) Math.round(((p[1] - minY) / dy) * ph);
                Color fill;
                if (mode == Mode.GENE && geneColor != null && idx < geneColor.length) {
                    float tt = (geneColor[idx] - colorLo) / (colorHi - colorLo);
                    fill = PlotTheme.scaleColor(colorScale, tt, hasSel && !isSel ? 0.22f : alpha);
                } else if (mode == Mode.CLUSTER && clusterColor != null && idx < clusterColor.length) {
                    int cat = clusterColor[idx];
                    fill = categoryColor(cat, hasSel && !isSel ? Math.min(0.22f, alpha) : alpha);
                } else {
                    fill = new Color(0.35f, 0.35f, 0.35f, hasSel && !isSel ? 0.2f : alpha);
                }
                g.setColor(fill);
                if (diam <= 1) {
                    g.fillRect(sx, sy, 1, 1);
                } else {
                    g.fillOval(sx - diam / 2, sy - diam / 2, diam, diam);
                }
                if (isSel && diam >= 2) {
                    g.setColor(new Color(0x11, 0x11, 0x11));
                    g.setStroke(new BasicStroke(1.1f));
                    g.drawOval(sx - diam / 2 - 1, sy - diam / 2 - 1, diam + 2, diam + 2);
                }
                if (idx == hoverIndex) {
                    g.setColor(new Color(0x1E, 0x3A, 0x5F));
                    g.setStroke(new BasicStroke(1.6f));
                    int hr = Math.max(diam / 2 + 3, 5);
                    g.drawOval(sx - hr, sy - hr, hr * 2, hr * 2);
                }
            }
        }

        PlotTheme.drawOpenAxes(g, getFont(), left, top, pw, ph, minX, maxX, minY, maxY, xlab, ylab);

        if (geneUnder) {
            g.setFont(PlotTheme.titleFont(getFont()));
            g.setColor(PlotTheme.INK);
            FontMetrics gfm = g.getFontMetrics();
            int gx = left + (pw - gfm.stringWidth(geneLabel)) / 2;
            int gy = top + ph + 40;
            g.drawString(geneLabel, gx, gy);
        }

        if (mode == Mode.CLUSTER && annotateClusters && clusterLabels != null && clusterColor != null) {
            int k = clusterLabels.length;
            double[] sxSum = new double[k];
            double[] sySum = new double[k];
            int[] cnt = new int[k];
            for (int i = 0; i < xy.length; i++) {
                int c = clusterColor[i];
                if (c < 0 || c >= k || !categoryVisible(c)) {
                    continue;
                }
                if (xy[i][0] < minX || xy[i][0] > maxX || xy[i][1] < minY || xy[i][1] > maxY) {
                    continue;
                }
                sxSum[c] += xy[i][0];
                sySum[c] += xy[i][1];
                cnt[c]++;
            }
            for (int c = 0; c < k; c++) {
                if (cnt[c] == 0) {
                    continue;
                }
                int px = left + (int) Math.round(((sxSum[c] / cnt[c] - minX) / dx) * pw);
                int py = top + ph - (int) Math.round(((sySum[c] / cnt[c] - minY) / dy) * ph);
                PlotTheme.drawClusterLabel(g, getFont(), clusterLabels[c], px, py);
            }
        }

        if (mode == Mode.GENE) {
            PlotTheme.drawColorBar(g, getFont(), w - right + 10, top, 12, ph,
                    colorLo, colorHi,
                    titleSuffix.isEmpty() ? "value" : titleSuffix,
                    colorScale);
                } else if (mode == Mode.CLUSTER && clusterLabels != null) {
            // Fit as many as the panel allows, capped at 50; overflow as "+N clusters"
            g.setFont(PlotTheme.legendFont(getFont()));
            int rowH = Math.max(14, g.getFontMetrics().getHeight() + 1);
            int helpRoom = isExporting() ? 8 : 36;
            int overflowRoom = 18;
            int avail = Math.max(rowH, h - top - bottom - helpRoom - overflowRoom);
            int maxRows = Math.min(50, Math.max(1, avail / rowH));
            drawInteractiveLegend(g, w - right + 8, top, maxRows);
        }

        String sub = mode == Mode.GENE
                ? (titleSuffix.isEmpty() ? "continuous" : titleSuffix)
                : (mode == Mode.CLUSTER ? "Clusters" : null);
        if (sub != null) {
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.MUTE);
            int tw = g.getFontMetrics().stringWidth(sub);
            g.drawString(sub, left + pw - tw, 28);
        }

        int nSel = selectedCount();
        if (!isExporting() && (nSel > 0 || lassoMode)) {
            g.setFont(PlotTheme.subtitleFont(getFont()));
            g.setColor(PlotTheme.INK);
            String tip = lassoMode
                    ? "Lasso: drag to select  ·  right-click clears  ·  " + nSel + " selected"
                    : nSel + " cells selected";
            g.drawString(tip, left, h - 10);
        }

        // active lasso path
        if (lassoMode && lassoScreen.size() >= 2) {
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(new Color(0x57, 0x06, 0x8C, 200));
            Point prev = lassoScreen.get(0);
            for (int i = 1; i < lassoScreen.size(); i++) {
                Point p = lassoScreen.get(i);
                g.drawLine(prev.x, prev.y, p.x, p.y);
                prev = p;
            }
            if (lassoDragging) {
                g.setColor(new Color(0x57, 0x06, 0x8C, 80));
                Path2D fill = new Path2D.Double();
                fill.moveTo(lassoScreen.get(0).x, lassoScreen.get(0).y);
                for (int i = 1; i < lassoScreen.size(); i++) {
                    fill.lineTo(lassoScreen.get(i).x, lassoScreen.get(i).y);
                }
                fill.closePath();
                g.fill(fill);
            }
        }
    }

    static float[] quantiles(float[] values, float loP, float hiP) {
        int n = 0;
        for (float v : values) {
            if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                n++;
            }
        }
        if (n == 0) {
            return new float[]{0f, 1f};
        }
        float[] a = new float[n];
        int j = 0;
        for (float v : values) {
            if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                a[j++] = v;
            }
        }
        Arrays.sort(a);
        float lo = a[Math.min(a.length - 1, Math.max(0, Math.round(loP * (a.length - 1))))];
        float hi = a[Math.min(a.length - 1, Math.max(0, Math.round(hiP * (a.length - 1))))];
        return new float[]{lo, hi};
    }
}
