package sodaviewer;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Main window: welcome · archive overview · feature / box / violin / bubble / heatmap / gene–gene. */
public final class MainFrame extends JFrame {
    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_HOME = "home";
    private static final String CARD_SCATTER = "scatter";
    private static final String CARD_BOX = "box";
    private static final String CARD_VIOLIN = "violin";
    private static final String CARD_BUBBLE = "bubble";
    private static final String CARD_HEATMAP = "heatmap";
    private static final String CARD_CELL_HEATMAP = "cell_heatmap";
    private static final String CARD_COR = "cor";
    private static final String CARD_MULTI_FEATURE = "multi_feature";
    private static final String CARD_MULTI_CLUSTER = "multi_cluster";
    /** Max genes for faceted feature / box / violin on one page. */
    private static final int MAX_FEATURE_GENES = 9;
    private static final int MAX_CLUSTER_EXPR_GENES = 6;

    /** Expression matrix: norm.data (assay "data") or imputed. */
    private enum AssayChoice {
        NORM("norm.data", "data"),
        IMPUTED("imputed data", "imputed");

        final String label;
        final String folder;

        AssayChoice(String label, String folder) {
            this.label = label;
            this.folder = folder;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Gene / expression plot kinds shown in the toolbar. */
    private enum GenePlotType {
        FEATURE("Feature plot", "Color embedding by gene (log2p1)"),
        BOX("Box plot", "Expression by cluster"),
        VIOLIN("Violin plot", "Distribution by cluster"),
        BUBBLE("Bubble plot", "Genes × clusters · mean & % expressing"),
        HEATMAP_CELLS("Cell heatmap", "Genes × cells · ordered by cluster (heatmap.gg.plot)"),
        HEATMAP("Cluster heatmap", "Genes × clusters · row-scaled mean"),
        GENE_GENE("Gene–gene", "Two genes · Pearson correlation");

        final String label;
        final String tip;

        GenePlotType(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Plot reductions shown in the toolbar (exclude pseudotime / ptumap / …).
     * Includes current iCellR2 2.0.5 / iCellPy 0.2.x names (aligned_*, knetl, cgpca).
     */
    private static final List<String> PLOT_REDUCTIONS = List.of(
            "pca", "umap", "cgumap", "cgpca", "knetl",
            "aligned_pca", "aligned_umap", "aligned_knetl");

    private final WelcomePanel welcome = new WelcomePanel(this::chooseAndOpen, this::openSoda, this::openLastSoda);
    private final SodaHomePanel home = new SodaHomePanel();
    private final PlotPanel scatter = new PlotPanel();
    private final MultiFeaturePlotPanel multiFeaturePlot = new MultiFeaturePlotPanel();
    private final MultiClusterExprPlotPanel multiClusterPlot = new MultiClusterExprPlotPanel();
    private final BoxPlotPanel boxPlot = new BoxPlotPanel();
    private final ViolinPlotPanel violinPlot = new ViolinPlotPanel();
    private final BubblePlotPanel bubblePlot = new BubblePlotPanel();
    private final HeatmapPlotPanel heatmapPlot = new HeatmapPlotPanel();
    private final CellHeatmapPlotPanel cellHeatmapPlot = new CellHeatmapPlotPanel();
    private final CorPlotPanel corPlot = new CorPlotPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private JComponent activePlot;
    private String activeCard = CARD_WELCOME;

    private final JLabel status = new JLabel("Open or drop a .soda folder to begin");
    private final JProgressBar progress = new JProgressBar(0, 100);
    /** Wall-clock start of the current busy operation (nanoTime). */
    private long busyStartNanos;
    /** Last completed busy duration, e.g. {@code "38.2s"} — for status / light benchmark. */
    private String lastBusyElapsed = "";
    private final JComboBox<String> reductionBox = new JComboBox<>();
    /** Example list for Edit → Paste genes… (comma or newline). */
    private static final String EXAMPLE_PASTE_GENES = """
            MS4A1
            GNLY
            CD3E
            CD14
            FCER1A
            FCGR3A
            LYZ
            PPBP
            CD8A
            """.strip();

    private final JTextField geneField = new JTextField(42);
    private final JButton overviewBtn = new JButton("Overview");
    private final JComboBox<GenePlotType> plotTypeBox = new JComboBox<>(GenePlotType.values());
    private final JButton plotBtn = new JButton("Plot");
    private final JComboBox<Object> colorByBox = new JComboBox<>();
    private final JButton clearBtn = new JButton("Clear color");
    private final JButton exportBtn = new JButton("Export…");
    private final JComboBox<PlotTheme.ColorScale> colorScaleBox =
            new JComboBox<>(PlotTheme.ColorScale.values());
    private final JComboBox<PlotTheme.CatPalette> catPaletteBox =
            new JComboBox<>(PlotTheme.CatPalette.values());
    private final javax.swing.JSpinner dotSizeSpinner =
            new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(3, 1, 14, 1));
    private final javax.swing.JSpinner opacitySpinner =
            new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(85, 5, 100, 5));
    private final javax.swing.JCheckBox annotateBox = new javax.swing.JCheckBox("Annotate", true);
    private final JButton lassoBtn = new JButton("Lasso");
    private final JButton zoomInBtn = new JButton("Zoom in");
    private final JButton zoomOutBtn = new JButton("Zoom out");
    private final JButton zoomHomeBtn = new JButton("Home");
    private final JButton panLeftBtn = new JButton("←");
    private final JButton panRightBtn = new JButton("→");
    private final JButton panUpBtn = new JButton("↑");
    private final JButton panDownBtn = new JButton("↓");
    private final JButton lassoToolsBtn = new JButton("Lasso/cluster tools ▾");
    private final JComboBox<AssayChoice> dataTypeBox = new JComboBox<>(AssayChoice.values());

    private SodaArchive soda;
    private EmbeddingData embedding;
    private PlotSample sample;
    private ExpressionStore expression;
    private CellMetadata metadata;
    /** Guard against Color-by ↔ Scale listener recursion. */
    private boolean applyingColorBy;
    private boolean suppressReductionListener;

    public MainFrame() {
        super("SODAviewer");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1200, 720));

        buildMenu();
        enableDragAndDrop();

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(new Color(0xF7, 0xF8, 0xFA));
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE2, 0xE8, 0xF0)));

        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(new EmptyBorder(10, 12, 4, 12));
        top.setBackground(new Color(0xF7, 0xF8, 0xFA));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.gridy = 0;
        c.gridx = 0;
        JButton openBtn = new JButton("Open SODA…");
        openBtn.addActionListener(e -> chooseAndOpen());
        top.add(openBtn, c);

        c.gridx++;
        overviewBtn.addActionListener(e -> showHome());
        top.add(overviewBtn, c);

        c.gridx++;
        top.add(new JLabel("Reduction"), c);
        c.gridx++;
        reductionBox.setPrototypeDisplayValue("cgumapXXXX");
        reductionBox.addActionListener(e -> {
            if (suppressReductionListener || soda == null) {
                return;
            }
            String sel = String.valueOf(reductionBox.getSelectedItem());
            if (!isRealReduction(sel)) {
                return;
            }
            loadReductionAsync(sel, true);
        });
        top.add(reductionBox, c);

        c.gridx++;
        top.add(new JLabel("Gene(s)"), c);
        c.gridx++;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        geneField.setToolTipText("Type to autocomplete · commas for multi-gene · Enter to plot");
        geneField.setColumns(42);
        geneField.setFont(geneField.getFont().deriveFont(14f));
        geneField.setMinimumSize(new Dimension(380, 34));
        geneField.setPreferredSize(new Dimension(560, 34));
        geneField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCB, 0xD5, 0xE1)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        geneField.addActionListener(e -> runGenePlot());
        // Keep native text copy/paste (parent drop TransferHandler must not steal it)
        geneField.setTransferHandler(new TransferHandler("text") {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    String s = (String) support.getTransferable()
                            .getTransferData(DataFlavor.stringFlavor);
                    if (s == null) {
                        return false;
                    }
                    String norm = normalizeGeneText(s);
                    if (norm.isEmpty()) {
                        return false;
                    }
                    geneField.replaceSelection(norm);
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
        new GeneSuggestSupport(geneField, () -> expression);
        top.add(geneField, c);
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;

        c.gridx++;
        top.add(new JLabel("Plot"), c);
        c.gridx++;
        restorePlotType();
        plotTypeBox.setToolTipText("Feature · box · violin · bubble · cell/cluster heatmap · gene–gene");
        plotTypeBox.setPrototypeDisplayValue(GenePlotType.GENE_GENE);
        plotTypeBox.addActionListener(e -> {
            GenePlotType pt = (GenePlotType) plotTypeBox.getSelectedItem();
            if (pt != null) {
                AppPrefs.setPlotType(pt.name());
                tipForPlotType(pt);
            }
        });
        top.add(plotTypeBox, c);
        c.gridx++;
        plotBtn.setToolTipText("Run the selected plot type");
        plotBtn.addActionListener(e -> runGenePlot());
        top.add(plotBtn, c);

        c.gridx++;
        top.add(new JLabel("Data"), c);
        c.gridx++;
        dataTypeBox.setToolTipText("Expression matrix: norm.data or imputed data");
        dataTypeBox.setPrototypeDisplayValue(AssayChoice.IMPUTED);
        dataTypeBox.addActionListener(e -> onDataTypeChanged());
        top.add(dataTypeBox, c);

        JPanel tools = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        tools.setBorder(new EmptyBorder(2, 10, 8, 10));
        tools.setBackground(new Color(0xF7, 0xF8, 0xFA));
        // Keep toolbar above the card; WrapLayout reports full wrapped height
        tools.setOpaque(true);
        tools.add(new JLabel("Color by"));
        colorByBox.setPrototypeDisplayValue("PseudotimeXXXX");
        colorByBox.setToolTipText("Color scatter by metadata (clusters, conditions, pseudotime, …)");
        colorByBox.addActionListener(e -> applyColorBy());
        tools.add(colorByBox);
        clearBtn.addActionListener(e -> {
            if (colorByBox.getItemCount() > 0) {
                colorByBox.setSelectedIndex(0);
            }
            showCard(CARD_SCATTER);
            scatter.clearColors();
            multiFeaturePlot.clear();
            multiClusterPlot.clear();
            updateScalePaletteEnabled();
            status.setText(sampleStatus());
        });
        tools.add(clearBtn);
        exportBtn.addActionListener(e -> exportPlot());
        tools.add(exportBtn);
        tools.add(new JLabel("  Scale"));
        colorScaleBox.setSelectedItem(PlotTheme.ColorScale.BLUE_WHITE_RED);
        colorScaleBox.setToolTipText("Continuous color scale (genes / pseudotime / bubble / heatmap)");
        scatter.setColorScale(PlotTheme.ColorScale.BLUE_WHITE_RED);
        colorScaleBox.addActionListener(e -> {
            if (applyingColorBy) {
                return;
            }
            PlotTheme.ColorScale cs = (PlotTheme.ColorScale) colorScaleBox.getSelectedItem();
            scatter.setColorScale(cs);
            multiFeaturePlot.setColorScale(cs);
            bubblePlot.setColorScale(cs);
            heatmapPlot.setColorScale(cs);
            cellHeatmapPlot.setColorScale(cs);
            // Refresh continuous color-by with the new scale (do not reset Scale choice)
            Object sel = colorByBox.getSelectedItem();
            if (sel instanceof CellMetadata.Field f && f.kind == CellMetadata.Kind.CONTINUOUS) {
                refreshContinuousColorBy(f);
            }
        });
        tools.add(colorScaleBox);
        tools.add(new JLabel("  Palette"));
        catPaletteBox.setSelectedItem(PlotTheme.CatPalette.DEFAULT);
        catPaletteBox.setToolTipText("Colors for clusters / conditions / samples");
        PlotTheme.setCatPalette(PlotTheme.CatPalette.DEFAULT);
        catPaletteBox.addActionListener(e -> {
            PlotTheme.CatPalette pal = (PlotTheme.CatPalette) catPaletteBox.getSelectedItem();
            PlotTheme.setCatPalette(pal);
            scatter.repaint();
            boxPlot.repaint();
            violinPlot.repaint();
            corPlot.repaint();
            Object sel = colorByBox.getSelectedItem();
            if (sel instanceof CellMetadata.Field f && f.kind == CellMetadata.Kind.CATEGORICAL) {
                applyColorBy();
            }
        });
        tools.add(catPaletteBox);
        tools.add(new JLabel("  Dot"));
        dotSizeSpinner.setToolTipText("Scatter point size (pixels)");
        ((javax.swing.JSpinner.DefaultEditor) dotSizeSpinner.getEditor()).getTextField().setColumns(2);
        scatter.setPointSize(3);
        dotSizeSpinner.addChangeListener(e -> {
            int v = ((Number) dotSizeSpinner.getValue()).intValue();
            scatter.setPointSize(v);
        });
        tools.add(dotSizeSpinner);
        tools.add(new JLabel("  Opacity"));
        opacitySpinner.setToolTipText("Point transparency (%)");
        ((javax.swing.JSpinner.DefaultEditor) opacitySpinner.getEditor()).getTextField().setColumns(3);
        scatter.setPointOpacity(0.85f);
        opacitySpinner.addChangeListener(e -> {
            int v = ((Number) opacitySpinner.getValue()).intValue();
            scatter.setPointOpacity(v / 100f);
        });
        tools.add(opacitySpinner);
        annotateBox.setToolTipText("Show cluster / category labels on the plot");
        annotateBox.setOpaque(false);
        annotateBox.addActionListener(e -> {
            scatter.setAnnotateClusters(annotateBox.isSelected());
        });
        tools.add(annotateBox);
        lassoBtn.setToolTipText("Lasso-select cells on the scatter plot");
        lassoBtn.addActionListener(e -> toggleLasso());
        tools.add(lassoBtn);
        zoomInBtn.setToolTipText("Zoom in");
        zoomInBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.zoomIn(); });
        tools.add(zoomInBtn);
        zoomOutBtn.setToolTipText("Zoom out");
        zoomOutBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.zoomOut(); });
        tools.add(zoomOutBtn);
        zoomHomeBtn.setToolTipText("Reset zoom, selection, hidden clusters, and custom colors");
        zoomHomeBtn.addActionListener(e -> {
            showCard(CARD_SCATTER);
            scatter.zoomHome();
            status.setText(sampleStatus() + "  ·  home — zoom, selection, clusters & colors reset");
        });
        tools.add(zoomHomeBtn);
        tools.add(new JLabel("  Pan"));
        panLeftBtn.setToolTipText("Pan left (or drag plot / ← key)");
        panRightBtn.setToolTipText("Pan right (or drag plot / → key)");
        panUpBtn.setToolTipText("Pan up (or drag plot / ↑ key)");
        panDownBtn.setToolTipText("Pan down (or drag plot / ↓ key)");
        panLeftBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.requestFocusInWindow(); scatter.panLeft(); });
        panRightBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.requestFocusInWindow(); scatter.panRight(); });
        panUpBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.requestFocusInWindow(); scatter.panUp(); });
        panDownBtn.addActionListener(e -> { showCard(CARD_SCATTER); scatter.requestFocusInWindow(); scatter.panDown(); });
        tools.add(panLeftBtn);
        tools.add(panRightBtn);
        tools.add(panUpBtn);
        tools.add(panDownBtn);
        lassoToolsBtn.setToolTipText("Session-only exploration (does not modify SODA): save IDs, assign/merge/rename clusters, hide cells");
        lassoToolsBtn.addActionListener(e -> showLassoToolsMenu());
        tools.add(lassoToolsBtn);

        scatter.addPropertyChangeListener("hover", evt -> {
            int idx = scatter.hoverIndex();
            if (idx >= 0 && sample != null && idx < sample.cellIds.length) {
                String id = sample.cellIds[idx];
                status.setText(sampleStatus() + "  ·  " + id);
            }
        });
        scatter.addPropertyChangeListener("selection", evt -> {
            int n = scatter.selectedCount();
            if (n > 0) {
                status.setText(sampleStatus() + "  ·  " + n + " cells selected (Lasso/cluster tools ▾)");
            } else if (scatter.isLassoMode()) {
                status.setText(sampleStatus()
                        + "  ·  lasso on — no cells in loop (draw a larger closed loop)");
            }
        });

        north.add(top, BorderLayout.NORTH);
        north.add(tools, BorderLayout.SOUTH);
        // Recalc wrapped toolbar height when the window width changes
        tools.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                tools.revalidate();
                north.revalidate();
            }
        });

        status.setBorder(new EmptyBorder(0, 0, 0, 8));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
        status.setForeground(new Color(0x6B, 0x6B, 0x6B));
        progress.setVisible(false);
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(300, 18));
        progress.setMaximumSize(new Dimension(400, 18));
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBackground(Color.WHITE);
        south.setBorder(new EmptyBorder(4, 12, 8, 12));
        south.add(status, BorderLayout.CENTER);
        south.add(progress, BorderLayout.EAST);

        home.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        scatter.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        multiFeaturePlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        multiClusterPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        boxPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        violinPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bubblePlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        heatmapPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        cellHeatmapPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        corPlot.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        cardPanel.setBackground(Color.WHITE);
        getContentPane().setBackground(Color.WHITE);
        cardPanel.add(welcome, CARD_WELCOME);
        cardPanel.add(home, CARD_HOME);
        cardPanel.add(scatter, CARD_SCATTER);
        cardPanel.add(multiFeaturePlot, CARD_MULTI_FEATURE);
        cardPanel.add(multiClusterPlot, CARD_MULTI_CLUSTER);
        cardPanel.add(boxPlot, CARD_BOX);
        cardPanel.add(violinPlot, CARD_VIOLIN);
        cardPanel.add(bubblePlot, CARD_BUBBLE);
        cardPanel.add(heatmapPlot, CARD_HEATMAP);
        cardPanel.add(cellHeatmapPlot, CARD_CELL_HEATMAP);
        cardPanel.add(corPlot, CARD_COR);
        activePlot = welcome;

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(cardPanel, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);

        setControlsEnabled(false);
        showCard(CARD_WELCOME);
        pack();
        setSize(1200, 780);
        setLocationRelativeTo(null);
    }

    private void buildMenu() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open SODA…");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, mask));
        open.addActionListener(e -> chooseAndOpen());
        JMenuItem openLast = new JMenuItem("Reopen last SODA");
        openLast.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
                mask | InputEvent.SHIFT_DOWN_MASK));
        openLast.addActionListener(e -> openLastSoda());
        JMenuItem close = new JMenuItem("Close");
        close.addActionListener(e -> closeSoda());
        JMenuItem exp = new JMenuItem("Export plot…");
        exp.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, mask));
        exp.addActionListener(e -> exportPlot());
        JMenuItem saveIds = new JMenuItem("Save selected IDs…");
        saveIds.addActionListener(e -> saveSelectedIds());
        JMenuItem openInBrowser = new JMenuItem("Open archive in browser…");
        openInBrowser.addActionListener(e -> openIndexInBrowser());
        JMenuItem quit = new JMenuItem("Quit");
        quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask));
        quit.addActionListener(e -> dispose());
        file.add(open);
        file.add(openLast);
        file.add(close);
        file.addSeparator();
        file.add(exp);
        file.add(saveIds);
        file.add(openInBrowser);
        file.addSeparator();
        file.add(quit);
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        // No ⌘C / ⌘V accelerators — they steal paste/copy from the Gene field
        JMenuItem copyGene = new JMenuItem("Copy genes");
        copyGene.addActionListener(e -> copyGenesFromField());
        JMenuItem pasteGene = new JMenuItem("Paste genes");
        pasteGene.addActionListener(e -> pasteGenesFromClipboard());
        JMenuItem pasteGenesDialog = new JMenuItem("Paste genes…");
        pasteGenesDialog.addActionListener(e -> pasteGenesDialog());
        JMenuItem loadGenes = new JMenuItem("Load genes from file…");
        loadGenes.addActionListener(e -> loadGenesFromFile());
        JMenuItem clearColor = new JMenuItem("Clear plot color");
        clearColor.addActionListener(e -> {
            if (sample != null) {
                showCard(CARD_SCATTER);
                scatter.clearColors();
                status.setText(sampleStatus());
            }
        });
        JMenuItem clearSel = new JMenuItem("Clear selection");
        clearSel.addActionListener(e -> {
            scatter.clearSelection();
            status.setText(sample == null ? "SODAviewer" : sampleStatus());
        });
        edit.add(copyGene);
        edit.add(pasteGene);
        edit.add(pasteGenesDialog);
        edit.add(loadGenes);
        edit.addSeparator();
        edit.add(clearColor);
        edit.add(clearSel);
        bar.add(edit);

        JMenu view = new JMenu("View");
        JMenuItem viewHome = new JMenuItem("Archive overview");
        viewHome.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, mask));
        viewHome.addActionListener(e -> showHome());
        JMenuItem viewPlot = new JMenuItem("Scatter plot");
        viewPlot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, mask));
        viewPlot.addActionListener(e -> {
            if (sample != null) {
                showCard(CARD_SCATTER);
            }
        });
        JMenuItem plotNow = new JMenuItem("Run plot");
        plotNow.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, mask));
        plotNow.addActionListener(e -> runGenePlot());
        JMenuItem reloadHome = new JMenuItem("Reload overview");
        reloadHome.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, mask));
        reloadHome.addActionListener(e -> home.reload());
        JMenuItem goTop = new JMenuItem("Scroll overview to top");
        goTop.addActionListener(e -> home.goHome());
        view.add(viewHome);
        view.add(viewPlot);
        view.add(plotNow);
        view.addSeparator();

        JMenu maxCellsMenu = new JMenu("Max cells to plot");
        javax.swing.ButtonGroup maxCellsGroup = new javax.swing.ButtonGroup();
        int curMax = AppPrefs.maxPlotCells();
        for (int opt : AppPrefs.MAX_PLOT_CELL_OPTIONS) {
            String label = opt <= 0
                    ? "All cells"
                    : AppPrefs.maxPlotCellsLabel(opt)
                    + (opt == PlotSample.DEFAULT_MAX_CELLS ? " (default)" : "");
            javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(label);
            boolean selected = (opt <= 0 && curMax <= 0) || (opt > 0 && curMax == opt);
            item.setSelected(selected);
            final int chosen = opt;
            item.addActionListener(e -> applyMaxPlotCells(chosen));
            maxCellsGroup.add(item);
            maxCellsMenu.add(item);
        }
        view.add(maxCellsMenu);
        view.addSeparator();
        view.add(reloadHome);
        view.add(goTop);
        bar.add(view);

        JMenu help = new JMenu("Help");
        JMenuItem howto = new JMenuItem("How to use");
        howto.addActionListener(e -> JOptionPane.showMessageDialog(this,
                """
                1. Drag & drop a .soda folder (or File → Open / Reopen last ⌘⇧O)
                2. Overview shows index.html · then choose pca / umap / cgumap
                3. Gene(s) typeahead · Plot / Color by · Data · Dot size · Palette
                4. View → Max cells to plot (default 1M) · ⌘P runs the selected plot
                5. Zoom in/out/Home · Color scale for genes / continuous metadata
                """,
                "How to use", JOptionPane.INFORMATION_MESSAGE));
        JMenuItem shortcuts = new JMenuItem("Keyboard shortcuts");
        shortcuts.addActionListener(e -> showKeyboardShortcuts());
        JMenuItem about = new JMenuItem("About SODAviewer");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                AppVersion.aboutText(),
                "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(howto);
        help.add(shortcuts);
        help.add(about);
        bar.add(help);

        setJMenuBar(bar);
    }

    private void showKeyboardShortcuts() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        String mod = mac ? "⌘" : "Ctrl+";
        String shift = mac ? "⇧" : "Shift+";
        JOptionPane.showMessageDialog(this,
                """
                %sO          Open SODA
                %s%sO     Reopen last SODA
                %sE          Export plot
                %sP          Run plot
                %s1          Archive overview
                %s2          Scatter plot
                %sR          Reload overview

                Toolbar: Zoom · Pan ←→↑↓ · Lasso · Lasso/cluster tools ▾
                Legend: click hide · Shift-click select · double-click recolor
                """.formatted(mod, mod, shift, mod, mod, mod, mod, mod),
                "Keyboard shortcuts", JOptionPane.INFORMATION_MESSAGE);
    }

    private void enableDragAndDrop() {
        java.util.function.Consumer<File> open = this::openSoda;
        // AWT DropTarget works on the window body and over JavaFX WebView;
        // TransferHandler alone is often ignored on nested / FX surfaces.
        SodaDropTargets.install(this, open);
        SodaDropTargets.install((JComponent) getContentPane(), open);
        SodaDropTargets.install(cardPanel, open);
        SodaDropTargets.install(scatter, open);
        SodaDropTargets.install(multiFeaturePlot, open);
        SodaDropTargets.install(multiClusterPlot, open);
        SodaDropTargets.install(boxPlot, open);
        SodaDropTargets.install(violinPlot, open);
        SodaDropTargets.install(bubblePlot, open);
        SodaDropTargets.install(heatmapPlot, open);
        SodaDropTargets.install(cellHeatmapPlot, open);
        SodaDropTargets.install(corPlot, open);
        SodaDropTargets.install(welcome, open);
        home.installDropTarget(open);

        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Transferable t = support.getTransferable();
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    File sodaDir = resolveSodaDrop(files);
                    if (sodaDir == null) {
                        JOptionPane.showMessageDialog(MainFrame.this,
                                "Drop a .soda folder (or a file inside one).",
                                "Drag & drop", JOptionPane.WARNING_MESSAGE);
                        return false;
                    }
                    openSoda(sodaDir);
                    return true;
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MainFrame.this, ex.getMessage(),
                            "Drag & drop failed", JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            }
        };
        setTransferHandler(handler);
        ((JComponent) getContentPane()).setTransferHandler(handler);
        cardPanel.setTransferHandler(handler);
        home.setTransferHandler(handler);
        scatter.setTransferHandler(handler);
    }

    public static File resolveSodaDrop(List<File> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        for (File f : files) {
            if (f == null) {
                continue;
            }
            File dir = f.isDirectory() ? f : f.getParentFile();
            while (dir != null) {
                String name = dir.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".soda") && dir.isDirectory()) {
                    return dir;
                }
                // soda.json + reductions/ or modalities/*/reductions/
                if (Files.isRegularFile(dir.toPath().resolve("soda.json"))
                        && looksLikeSodaRoot(dir.toPath())) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        }
        return null;
    }

    /** True if root or modality-local reductions exist (iCellR2 / iCellPy layout). */
    static boolean looksLikeSodaRoot(Path root) {
        if (Files.isDirectory(root.resolve("reductions"))) {
            return true;
        }
        Path mods = root.resolve("modalities");
        if (!Files.isDirectory(mods)) {
            return false;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path mod : ds) {
                if (Files.isDirectory(mod.resolve("reductions"))) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private void showCard(String name) {
        cards.show(cardPanel, name);
        activeCard = name;
        switch (name) {
            case CARD_WELCOME -> activePlot = welcome;
            case CARD_HOME -> activePlot = home;
            case CARD_BOX -> activePlot = boxPlot;
            case CARD_VIOLIN -> activePlot = violinPlot;
            case CARD_BUBBLE -> activePlot = bubblePlot;
            case CARD_HEATMAP -> activePlot = heatmapPlot;
            case CARD_CELL_HEATMAP -> activePlot = cellHeatmapPlot;
            case CARD_COR -> activePlot = corPlot;
            case CARD_MULTI_FEATURE -> activePlot = multiFeaturePlot;
            case CARD_MULTI_CLUSTER -> activePlot = multiClusterPlot;
            default -> activePlot = scatter;
        }
        updateScalePaletteEnabled();
    }

    private void showHome() {
        if (soda == null) {
            showCard(CARD_WELCOME);
            status.setText("Open or drop a .soda folder to begin");
            return;
        }
        showCard(CARD_HOME);
        String pkg = soda.packageVersion() != null ? soda.packageVersion() : "?";
        status.setText(soda.root() + "  ·  overview  ·  SODA " + soda.sodaVersion()
                + " / schema " + soda.schemaVersion()
                + "  ·  producer " + pkg);
    }

    public void openSoda(File dir) {
        if (dir == null) {
            return;
        }
        status.setText("Opening " + dir.getName() + "…");
        setControlsEnabled(false);
        new SwingWorker<OpenResult, Void>() {
            @Override
            protected OpenResult doInBackground() throws Exception {
                SodaArchive s = SodaArchive.open(dir.toPath());
                CellMetadata meta = CellMetadata.load(s.root());
                return new OpenResult(s, meta);
            }

            @Override
            protected void done() {
                try {
                    closeExpression();
                    OpenResult r = get();
                    soda = r.soda;
                    metadata = r.meta;
                    List<String> reds = plotReductions(soda);
                    if (reds.isEmpty()) {
                        throw new IllegalStateException(
                                "No pca / umap / cgumap reductions found under " + soda.root());
                    }
                    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                    for (String red : reds) {
                        model.addElement(red);
                    }
                    suppressReductionListener = true;
                    reductionBox.setModel(model);
                    preferReduction("umap");
                    if (reductionBox.getSelectedItem() == null && model.getSize() > 0) {
                        reductionBox.setSelectedIndex(0);
                    }
                    suppressReductionListener = false;
                    refreshColorByOptions();
                    refreshDataTypeOptions();
                    home.loadSoda(soda.root());
                    AppPrefs.setLastSoda(dir);
                    welcome.refreshOpenLast();
                    showHome();
                    String red0 = String.valueOf(reductionBox.getSelectedItem());
                    status.setText(soda.root() + "  ·  overview  ·  " + red0);
                    setControlsEnabled(true);
                    openExpressionAsync();
                    if (red0 != null && !"null".equals(red0)) {
                        loadReductionAsync(red0, false);
                    }
                } catch (Exception ex) {
                    soda = null;
                    metadata = null;
                    JOptionPane.showMessageDialog(MainFrame.this,
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage(),
                            "Open SODA failed", JOptionPane.ERROR_MESSAGE);
                    status.setText("Open failed");
                    setControlsEnabled(false);
                }
            }
        }.execute();
    }

    private void closeSoda() {
        closeExpression();
        soda = null;
        metadata = null;
        embedding = null;
        sample = null;
        reductionBox.setModel(new DefaultComboBoxModel<>());
        colorByBox.setModel(new DefaultComboBoxModel<>(new Object[]{COLOR_BY_NONE}));
        dataTypeBox.setSelectedItem(AssayChoice.NORM);
        home.clear();
        scatter.clearColors();
        boxPlot.clear();
        violinPlot.clear();
        bubblePlot.clear();
        heatmapPlot.clear();
        cellHeatmapPlot.clear();
        multiFeaturePlot.clear();
        multiClusterPlot.clear();
        corPlot.clear();
        showCard(CARD_WELCOME);
        welcome.refreshOpenLast();
        setControlsEnabled(false);
        status.setText("Open or drop a .soda folder to begin");
    }

    /** Keep plottable reductions (case-insensitive); prefer umap / aligned_umap / knetl. */
    public static List<String> plotReductions(SodaArchive archive) {
        List<String> out = new ArrayList<>();
        for (String r : archive.reductions()) {
            String low = r.toLowerCase(Locale.ROOT);
            if (PLOT_REDUCTIONS.contains(low)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt(r -> {
            String x = r.toLowerCase(Locale.ROOT);
            return switch (x) {
                case "umap" -> 0;
                case "aligned_umap" -> 1;
                case "knetl" -> 2;
                case "aligned_knetl" -> 3;
                case "pca" -> 4;
                case "aligned_pca" -> 5;
                case "cgumap" -> 6;
                case "cgpca" -> 7;
                default -> 9;
            };
        }));
        return out;
    }

    private static final class OpenResult {
        final SodaArchive soda;
        final CellMetadata meta;

        OpenResult(SodaArchive soda, CellMetadata meta) {
            this.soda = soda;
            this.meta = meta;
        }
    }

    private void preferReduction(String name) {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) reductionBox.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            String el = model.getElementAt(i);
            if (el != null && name.equalsIgnoreCase(el)) {
                reductionBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private static boolean isRealReduction(String sel) {
        return sel != null && !sel.isBlank();
    }

    private boolean ensureReductionReady(String action) {
        if (sample == null || !isRealReduction(String.valueOf(reductionBox.getSelectedItem()))) {
            JOptionPane.showMessageDialog(this,
                    "Open a SODA and wait for the reduction to load.",
                    action, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    /** Split Gene field on commas (and optional whitespace / semicolons). */
    public static List<String> parseGenes(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        // commas, semicolons, tabs, newlines, or runs of spaces
        for (String part : text.split("[,;\t\r\n]+|\s{2,}")) {
            for (String tok : part.trim().split("\s+")) {
                String g = tok.trim();
                if (g.length() >= 2
                        && ((g.startsWith("\"") && g.endsWith("\""))
                        || (g.startsWith("'") && g.endsWith("'")))) {
                    g = g.substring(1, g.length() - 1).trim();
                }
                if (g.isEmpty() || g.startsWith("#")) {
                    continue;
                }
                if (!out.contains(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    /** Join parsed genes for the Gene field (comma-separated). */
    public static String formatGenes(List<String> genes) {
        if (genes == null || genes.isEmpty()) {
            return "";
        }
        return String.join(", ", genes);
    }

    public static String normalizeGeneText(String text) {
        return formatGenes(parseGenes(text));
    }

    private void chooseAndOpen() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select a .soda folder");
        File last = AppPrefs.lastSodaDir();
        if (last != null && last.getParentFile() != null) {
            fc.setCurrentDirectory(last.getParentFile());
            fc.setSelectedFile(last);
        } else {
            File hint = new File(System.getProperty("user.home") + "/Downloads/Git/iCellR2/demo_iCellR2");
            if (hint.isDirectory()) {
                fc.setCurrentDirectory(hint);
            }
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openSoda(fc.getSelectedFile());
        }
    }

    private void openLastSoda() {
        File last = AppPrefs.lastSodaDir();
        if (last == null) {
            JOptionPane.showMessageDialog(this,
                    "No previous .soda folder yet — open one once to remember it.",
                    "Reopen last SODA", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openSoda(last);
    }

    private void loadReductionAsync(String reduction, boolean revealPlot) {
        if (soda == null || reduction == null) {
            return;
        }
        status.setText("Loading " + reduction + "…");
        Path path = soda.embeddingPath(reduction);
        new SwingWorker<EmbeddingData, Void>() {
            @Override
            protected EmbeddingData doInBackground() {
                return EmbeddingData.load(path);
            }

            @Override
            protected void done() {
                try {
                    embedding = get();
                    sample = PlotSample.of(embedding);
                    String xlab = embedding.dimNames.length > 0 ? embedding.dimNames[0] : "V1";
                    String ylab = embedding.dimNames.length > 1 ? embedding.dimNames[1] : "V2";
                    scatter.setEmbedding(sample.xy, sample.cellIds,
                            reduction.toUpperCase(Locale.ROOT), xlab, ylab);
                    if (revealPlot) {
                        showCard(CARD_SCATTER);
                        Object colorSel = colorByBox.getSelectedItem();
                        if (colorSel instanceof CellMetadata.Field) {
                            applyColorBy();
                        } else {
                            status.setText(sampleStatus());
                        }
                    } else {
                        status.setText(sampleStatus() + "  ·  overview");
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MainFrame.this,
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage(),
                            "Load embedding failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private String sampleStatus() {
        if (sample == null || !isRealReduction(String.valueOf(reductionBox.getSelectedItem()))) {
            return soda == null ? "Open or drop a .soda folder to begin"
                    : "Overview  ·  " + reductionBox.getSelectedItem();
        }
        String red = String.valueOf(reductionBox.getSelectedItem());
        if (sample.downsampled()) {
            return red + "  ·  showing " + sample.xy.length + " / " + sample.totalCells
                    + " cells (max " + AppPrefs.maxPlotCellsLabel(sample.maxCells)
                    + ", seed=" + PlotSample.SEED + ")";
        }
        return red + "  ·  " + sample.totalCells + " cells";
    }

    private void beginBusy(String message) {
        busyStartNanos = System.nanoTime();
        lastBusyElapsed = "";
        status.setText(message);
        progress.setVisible(true);
        progress.setIndeterminate(true);
        progress.setString("Working… · 0.0s");
        progress.setValue(0);
    }

    private void setBusyProgress(int completed, int total, String detail) {
        String el = formatElapsed((System.nanoTime() - busyStartNanos) / 1_000_000_000.0);
        if (total <= 0) {
            progress.setIndeterminate(true);
            String base = detail == null || detail.isBlank() ? "Working…" : detail;
            progress.setString(base + " · " + el);
            status.setText(base + " · " + el);
            return;
        }
        progress.setIndeterminate(false);
        int pct = (int) Math.min(100L, Math.round(100.0 * completed / (double) total));
        progress.setValue(pct);
        String eta = "";
        if (completed > 0 && completed < total) {
            double sec = (System.nanoTime() - busyStartNanos) / 1_000_000_000.0;
            if (sec > 0.25) {
                double remain = sec * (total - completed) / (double) completed;
                eta = " · ETA " + formatElapsed(remain);
            }
        }
        String head = detail == null || detail.isBlank() ? (pct + "%") : detail;
        progress.setString(head + " · " + pct + "% · " + el + eta);
        status.setText(head + "  (" + completed + " / " + total + " cells) · " + el + eta);
    }

    /** Hide progress; returns wall time of the busy op (also stored in {@link #lastBusyElapsed}). */
    private String endBusy() {
        lastBusyElapsed = formatElapsed((System.nanoTime() - busyStartNanos) / 1_000_000_000.0);
        progress.setIndeterminate(false);
        progress.setValue(0);
        progress.setString("");
        progress.setVisible(false);
        return lastBusyElapsed;
    }

    private static String formatElapsed(double seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        if (seconds < 60) {
            return String.format(Locale.US, "%.1fs", seconds);
        }
        int s = (int) Math.round(seconds);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Append light timing benchmark to a finished-plot status line. */
    private String withTiming(String base) {
        if (lastBusyElapsed == null || lastBusyElapsed.isBlank()) {
            return base;
        }
        int nPlot = sample == null ? 0 : sample.xy.length;
        int nAssay = sample == null ? 0 : sample.totalCells;
        String cells = nPlot > 0 && nPlot < nAssay
                ? (nPlot + "/" + nAssay + " cells")
                : (nAssay > 0 ? nAssay + " cells" : null);
        if (cells != null) {
            return base + " · " + lastBusyElapsed + " · " + cells;
        }
        return base + " · " + lastBusyElapsed;
    }

    /** Progress callback safe to call from a worker thread. */
    private ExpressionStore.Progress uiProgress() {
        return (done, total, detail) -> javax.swing.SwingUtilities.invokeLater(
                () -> setBusyProgress(done, total, detail));
    }

    /** Rebuild plot sample after View → Max cells to plot changes. */
    private void applyMaxPlotCells(int maxCells) {
        AppPrefs.setMaxPlotCells(maxCells);
        if (embedding == null) {
            status.setText("Max plot cells → " + AppPrefs.maxPlotCellsLabel(maxCells)
                    + " (applies when a reduction is loaded)");
            return;
        }
        sample = PlotSample.of(embedding, maxCells);
        String xlab = embedding.dimNames.length > 0 ? embedding.dimNames[0] : "V1";
        String ylab = embedding.dimNames.length > 1 ? embedding.dimNames[1] : "V2";
        String red = String.valueOf(reductionBox.getSelectedItem());
        scatter.setEmbedding(sample.xy, sample.cellIds,
                red == null ? "Embedding" : red.toUpperCase(Locale.ROOT), xlab, ylab);
        Object colorSel = colorByBox.getSelectedItem();
        if (colorSel instanceof CellMetadata.Field) {
            applyColorBy();
        }
        status.setText(sampleStatus() + "  ·  max plot cells updated");
        String g = geneField.getText().trim();
        if (!g.isEmpty() && expression != null
                && plotTypeBox.getSelectedItem() == GenePlotType.FEATURE) {
            runGenePlot();
        }
    }

    private void openExpressionAsync() {
        if (soda == null) {
            return;
        }
        AssayChoice choice = (AssayChoice) dataTypeBox.getSelectedItem();
        if (choice == null) {
            choice = AssayChoice.NORM;
        }
        Path assay = soda.assayPath(choice.folder);
        if (assay == null || !java.nio.file.Files.isRegularFile(assay)) {
            expression = null;
            status.setText(sampleStatus() + "  ·  " + choice.label + " not found in this SODA");
            JOptionPane.showMessageDialog(this,
                    choice.label + " was not found in this SODA archive.\n\n"
                            + "Looked for: modalities/rna/assays/" + choice.folder + "/",
                    "Data type unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final AssayChoice used = choice;
        final Path path = assay;
        status.setText("Loading " + used.label + "…");
        new SwingWorker<ExpressionStore, Void>() {
            @Override
            protected ExpressionStore doInBackground() {
                return ExpressionStore.open(path);
            }

            @Override
            protected void done() {
                try {
                    closeExpression();
                    expression = get();
                    status.setText(sampleStatus()
                            + (CARD_HOME.equals(activeCard) ? "  ·  overview" : "")
                            + "  ·  " + used.label
                            + " ready (" + expression.featureNames().size() + " genes)");
                } catch (Exception ex) {
                    expression = null;
                    status.setText(sampleStatus() + "  ·  " + used.label + " failed to load");
                }
            }
        }.execute();
    }

    private void refreshDataTypeOptions() {
        DefaultComboBoxModel<AssayChoice> model = new DefaultComboBoxModel<>();
        java.util.Set<String> have = new java.util.LinkedHashSet<>();
        if (soda != null) {
            have.addAll(soda.assayNames());
        }
        // Always offer the two main choices; enable based on presence
        for (AssayChoice ac : AssayChoice.values()) {
            model.addElement(ac);
        }
        dataTypeBox.setModel(model);
        // Prefer norm.data if present, else first available mapping
        if (have.contains("data")) {
            dataTypeBox.setSelectedItem(AssayChoice.NORM);
        } else if (have.contains("imputed")) {
            dataTypeBox.setSelectedItem(AssayChoice.IMPUTED);
        } else {
            dataTypeBox.setSelectedItem(AssayChoice.NORM);
        }
        // Grey out missing via tooltip; still selectable to show a clear message
        dataTypeBox.setToolTipText(dataTypeTooltip(have));
    }

    private static String dataTypeTooltip(java.util.Set<String> have) {
        StringBuilder sb = new StringBuilder("Expression matrix — ");
        sb.append(have.contains("data") ? "norm.data ✓" : "norm.data ✗");
        sb.append(" · ");
        sb.append(have.contains("imputed") ? "imputed ✓" : "imputed ✗");
        return sb.toString();
    }

    private void onDataTypeChanged() {
        if (soda == null) {
            return;
        }
        AssayChoice choice = (AssayChoice) dataTypeBox.getSelectedItem();
        if (choice == AssayChoice.IMPUTED) {
            java.util.Set<String> have = new java.util.LinkedHashSet<>(soda.assayNames());
            if (!have.contains("imputed")) {
                JOptionPane.showMessageDialog(this,
                        "This SODA has no imputed assay.\n\n"
                                + "Expected folder:\n"
                                + "  modalities/rna/assays/imputed/\n\n"
                                + "Run run.impute() in iCellR2 first, or switch Data back to norm.data.",
                        "Imputed data not found", JOptionPane.WARNING_MESSAGE);
                // Revert to norm.data if available
                if (have.contains("data")) {
                    dataTypeBox.setSelectedItem(AssayChoice.NORM);
                }
                return;
            }
        }
        openExpressionAsync();
    }

    private static final String COLOR_BY_NONE = "Choose…";

    private void refreshColorByOptions() {
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement(COLOR_BY_NONE);
        if (metadata != null) {
            for (CellMetadata.Field f : metadata.fields()) {
                model.addElement(f);
            }
        }
        colorByBox.setModel(model);
        colorByBox.setSelectedIndex(0);
    }

    private void applyColorBy() {
        if (soda == null || sample == null || applyingColorBy) {
            return;
        }
        Object sel = colorByBox.getSelectedItem();
        if (sel == null || COLOR_BY_NONE.equals(sel) || !(sel instanceof CellMetadata.Field)) {
            return;
        }
        if (!ensureReductionReady("Color by")) {
            return;
        }
        CellMetadata.Field field = (CellMetadata.Field) sel;
        showCard(CARD_SCATTER);
        applyingColorBy = true;
        try {
            if (field.kind == CellMetadata.Kind.CATEGORICAL) {
                String[] labels = metadata.categoricalLevels(field.column).toArray(String[]::new);
                int[] cats = metadata.categoricalIndices(sample.cellIds, field.column);
                scatter.setClusterColors(cats, labels, annotateBox.isSelected());
                status.setText(sampleStatus() + "  ·  colored by " + field.label
                        + " (" + labels.length + " levels)"
                        + "  ·  use Modify help under the legend");
            } else {
                // Default Scale for pseudotime / phase when Color by changes
                if (isPseudotimeLike(field)) {
                    colorScaleBox.setSelectedItem(PlotTheme.ColorScale.VIRIDIS);
                    scatter.setColorScale(PlotTheme.ColorScale.VIRIDIS);
                }
                refreshContinuousColorBy(field);
            }
        } finally {
            applyingColorBy = false;
            updateScalePaletteEnabled();
        }
    }

    private static boolean isPseudotimeLike(CellMetadata.Field field) {
        if (field == null) {
            return false;
        }
        String col = field.column == null ? "" : field.column.toLowerCase(Locale.ROOT);
        String lab = field.label == null ? "" : field.label.toLowerCase(Locale.ROOT);
        return col.contains("pseudotime") || col.contains("ptime")
                || lab.contains("pseudotime") || lab.contains("ptime")
                || lab.contains("phase");
    }

    private void refreshContinuousColorBy(CellMetadata.Field field) {
        if (metadata == null || sample == null || field == null) {
            return;
        }
        float[] vals = metadata.continuousValues(sample.cellIds, field.column);
        PlotTheme.ColorScale cs = (PlotTheme.ColorScale) colorScaleBox.getSelectedItem();
        if (cs != null) {
            scatter.setColorScale(cs);
        }
        scatter.setContinuousColors(vals, field.label);
        status.setText(sampleStatus() + "  ·  colored by " + field.label
                + (cs == null ? "" : "  ·  " + cs.label));
    }

    private void runGenePlot() {
        GenePlotType type = (GenePlotType) plotTypeBox.getSelectedItem();
        if (type == null) {
            type = GenePlotType.FEATURE;
        }
        AppPrefs.setPlotType(type.name());
        switch (type) {
            case FEATURE -> plotFeature();
            case BOX -> plotByCluster(false);
            case VIOLIN -> plotByCluster(true);
            case BUBBLE -> plotBubbleOrHeatmap(false);
            case HEATMAP -> plotBubbleOrHeatmap(true);
            case HEATMAP_CELLS -> plotCellHeatmap();
            case GENE_GENE -> plotGeneGene();
        }
    }

    private void plotFeature() {
        colorScaleBox.setSelectedItem(PlotTheme.ColorScale.GREY_RED);
        scatter.setColorScale(PlotTheme.ColorScale.GREY_RED);
        multiFeaturePlot.setColorScale(PlotTheme.ColorScale.GREY_RED);
        if (!ensureReductionReady("Feature plot")) {
            return;
        }
        if (expression == null) {
            JOptionPane.showMessageDialog(this, "Expression assay not loaded.", "Feature plot",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> genes = parseGenes(geneField.getText());
        if (genes.isEmpty()) {
            tipGenes();
            return;
        }
        if (genes.size() > MAX_FEATURE_GENES) {
            JOptionPane.showMessageDialog(this,
                    "Feature plot allows at most " + MAX_FEATURE_GENES + " genes on one page\n"
                            + "(got " + genes.size() + ").\n\n"
                            + "Use Bubble / Cell heatmap / Cluster heatmap for larger sets.",
                    "Too many genes", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final List<String> geneList = genes;
        final String[] cells = sample.cellIds;
        beginBusy("Feature plot: " + genes.size() + " gene"
                + (genes.size() == 1 ? "" : "s") + "…");
        new SwingWorker<float[][], Void>() {
            @Override
            protected float[][] doInBackground() {
                float[][] raw = expression.valuesForCells(geneList, cells, uiProgress());
                float[][] out = new float[raw.length][];
                for (int i = 0; i < raw.length; i++) {
                    out[i] = ExprTransform.log2p1(raw[i]);
                }
                return out;
            }

            @Override
            protected void done() {
                endBusy();
                try {
                    float[][] vals = get();
                    PlotTheme.ColorScale cs = (PlotTheme.ColorScale) colorScaleBox.getSelectedItem();
                    if (cs == null) {
                        cs = PlotTheme.ColorScale.GREY_RED;
                    }
                    if (geneList.size() == 1) {
                        scatter.setColorScale(cs);
                        scatter.setGeneColors(vals[0], geneList.get(0));
                        showCard(CARD_SCATTER);
                    } else {
                        multiFeaturePlot.setColorScale(cs);
                        multiFeaturePlot.setData(sample.xy,
                                scatterAxes()[0], scatterAxes()[1],
                                geneList.toArray(String[]::new), vals);
                        showCard(CARD_MULTI_FEATURE);
                    }
                    status.setText(withTiming(sampleStatus() + "  ·  feature ("
                            + geneList.size() + " gene"
                            + (geneList.size() == 1 ? "" : "s")
                            + ")  ·  " + cs.label));
                } catch (Exception ex) {
                    geneError(geneList.get(0), ex);
                }
            }
        }.execute();
    }

    private String[] scatterAxes() {
        String red = String.valueOf(reductionBox.getSelectedItem());
        if (red != null && red.toLowerCase(Locale.ROOT).contains("umap")) {
            return new String[]{"UMAP_1", "UMAP_2"};
        }
        if (red != null && red.toLowerCase(Locale.ROOT).contains("pca")) {
            return new String[]{"PC1", "PC2"};
        }
        return new String[]{"Dim1", "Dim2"};
    }

    private void plotByCluster(boolean violin) {
        String title = violin ? "Violin plot" : "Box plot";
        if (!ensureReductionReady(title)) {
            return;
        }
        if (expression == null) {
            JOptionPane.showMessageDialog(this, "Expression assay not loaded.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (metadata == null || !metadata.hasClusters()) {
            JOptionPane.showMessageDialog(this, title + " needs cluster_id.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> genes = parseGenes(geneField.getText());
        if (genes.isEmpty()) {
            tipGenes();
            return;
        }
        if (genes.size() > MAX_CLUSTER_EXPR_GENES) {
            JOptionPane.showMessageDialog(this,
                    title + " allows at most " + MAX_CLUSTER_EXPR_GENES
                            + " genes on one page\n"
                            + "(got " + genes.size() + ").\n\n"
                            + "Use Bubble / Heatmap for larger gene sets.",
                    "Too many genes", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final List<String> geneList = genes;
        final String[] cells = sample.cellIds;
        final int[] cats = metadata.clusterIndices(cells);
        final String[] labels = metadata.clusterLevels().toArray(String[]::new);
        status.setText(title + " for " + genes.size() + " gene"
                + (genes.size() == 1 ? "" : "s") + "…");
        beginBusy(title + "…");
        new SwingWorker<float[][], Void>() {
            @Override
            protected float[][] doInBackground() {
                float[][] raw = expression.valuesForCells(geneList, cells, uiProgress());
                float[][] out = new float[raw.length][];
                for (int i = 0; i < raw.length; i++) {
                    out[i] = ExprTransform.log2p1(raw[i]);
                }
                return out;
            }

            @Override
            protected void done() {
                endBusy();
                try {
                    float[][] vals = get();
                    if (geneList.size() == 1) {
                        String g = geneList.get(0);
                        if (violin) {
                            violinPlot.setData(g, labels, cats, vals[0]);
                            showCard(CARD_VIOLIN);
                        } else {
                            boxPlot.setData(g, labels, cats, vals[0]);
                            showCard(CARD_BOX);
                        }
                    } else {
                        multiClusterPlot.setData(
                                violin ? MultiClusterExprPlotPanel.Kind.VIOLIN
                                        : MultiClusterExprPlotPanel.Kind.BOX,
                                geneList.toArray(String[]::new), labels, cats, vals);
                        showCard(CARD_MULTI_CLUSTER);
                    }
                    status.setText(withTiming(sampleStatus() + "  ·  "
                            + (violin ? "violin" : "box") + " ("
                            + geneList.size() + " gene"
                            + (geneList.size() == 1 ? "" : "s") + ")"));
                } catch (Exception ex) {
                    geneError(geneList.get(0), ex);
                }
            }
        }.execute();
    }



    /** Classic heatmap.gg.plot(group.by = "cells"): genes × cells, ordered by cluster. */
    private void plotCellHeatmap() {
        String title = "Cell heatmap";
        if (!ensureReductionReady(title)) {
            return;
        }
        if (expression == null) {
            JOptionPane.showMessageDialog(this, "Expression assay not loaded.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (metadata == null || !metadata.hasClusters()) {
            JOptionPane.showMessageDialog(this, title + " needs cluster_id.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> genes = parseGenes(geneField.getText());
        if (genes.isEmpty()) {
            tipGenes();
            return;
        }
        final String[] cells = sample.cellIds;
        final int[] cats = metadata.clusterIndices(cells);
        final String[] labels = metadata.clusterLevels().toArray(String[]::new);
        status.setText(title + " for " + genes.size() + " gene"
                + (genes.size() == 1 ? "" : "s") + "…");
        final List<String> geneList = genes;
        final int maxCells = 8_000;
        beginBusy(title + "…");
        new SwingWorker<CellHeatData, Void>() {
            @Override
            protected CellHeatData doInBackground() {
                int nAll = cells.length;
                Integer[] boxed = new Integer[nAll];
                for (int i = 0; i < nAll; i++) {
                    boxed[i] = i;
                }
                java.util.Arrays.sort(boxed, (a, b) -> {
                    int ca = cats[a];
                    int cb = cats[b];
                    if (ca != cb) {
                        return Integer.compare(ca, cb);
                    }
                    return Integer.compare(a, b);
                });
                int[] order = new int[nAll];
                for (int i = 0; i < nAll; i++) {
                    order[i] = boxed[i];
                }

                if (nAll > maxCells) {
                    java.util.Random rnd = new java.util.Random(PlotSample.SEED);
                    java.util.List<Integer> keep = new java.util.ArrayList<>(maxCells);
                    int start = 0;
                    while (start < nAll) {
                        int cat = cats[order[start]];
                        int end = start + 1;
                        while (end < nAll && cats[order[end]] == cat) {
                            end++;
                        }
                        int nGrp = end - start;
                        int take = Math.max(1, (int) Math.round(maxCells * (double) nGrp / nAll));
                        take = Math.min(take, nGrp);
                        if (take >= nGrp) {
                            for (int i = start; i < end; i++) {
                                keep.add(order[i]);
                            }
                        } else {
                            int[] pick = PlotSample.sampleIndices(nGrp, take, rnd.nextLong());
                            for (int p : pick) {
                                keep.add(order[start + p]);
                            }
                        }
                        start = end;
                    }
                    while (keep.size() > maxCells) {
                        keep.remove(keep.size() - 1);
                    }
                    order = keep.stream().mapToInt(Integer::intValue).toArray();
                }

                int n = order.length;
                String[] orderedCells = new String[n];
                int[] orderedCats = new int[n];
                for (int i = 0; i < n; i++) {
                    orderedCells[i] = cells[order[i]];
                    orderedCats[i] = cats[order[i]];
                }

                int ng = geneList.size();
                float[][] scaled = new float[ng][n];
                String[] resolved = geneList.toArray(String[]::new);
                float[][] rawAll = expression.valuesForCells(geneList, orderedCells, uiProgress());
                for (int gi = 0; gi < ng; gi++) {
                    float[] raw = rawAll[gi];
                    float[] vals = ExprTransform.log2p1(raw);
                    double sum = 0;
                    double sum2 = 0;
                    int cnt = 0;
                    for (float v : vals) {
                        if (Float.isFinite(v)) {
                            sum += v;
                            sum2 += (double) v * v;
                            cnt++;
                        }
                    }
                    double avg = cnt > 0 ? sum / cnt : 0;
                    double var = cnt > 1 ? (sum2 - sum * sum / cnt) / (cnt - 1) : 0;
                    double sd = Math.sqrt(Math.max(var, 1e-12));
                    for (int c = 0; c < n; c++) {
                        float z = (float) ((vals[c] - avg) / sd);
                        if (z < -2.5f) {
                            z = -2.5f;
                        }
                        if (z > 2.5f) {
                            z = 2.5f;
                        }
                        scaled[gi][c] = z;
                    }
                }
                return new CellHeatData(resolved, scaled, orderedCats, labels, nAll);
            }

            @Override
            protected void done() {
                endBusy();
                try {
                    CellHeatData hd = get();
                    PlotTheme.ColorScale cs = (PlotTheme.ColorScale) colorScaleBox.getSelectedItem();
                    if (cs == null) {
                        cs = PlotTheme.ColorScale.BLUE_WHITE_RED;
                    }
                    cellHeatmapPlot.setColorScale(cs);
                    cellHeatmapPlot.setCellData(hd.genes, hd.scaled, hd.colCluster, hd.clusterLevels);
                    showCard(CARD_CELL_HEATMAP);
                    String ds = hd.nCellsShown < hd.nCellsTotal
                            ? " · showing " + hd.nCellsShown + "/" + hd.nCellsTotal
                            : " · " + hd.nCellsShown + " cells";
                    status.setText(withTiming(sampleStatus() + "  ·  cell heatmap ("
                            + hd.genes.length + " genes" + ds + ")"));
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                    List<String> sug = expression.suggest(geneList.get(0), 8);
                    if (!sug.isEmpty()) {
                        msg += "\nDid you mean: " + String.join(", ", sug);
                    }
                    JOptionPane.showMessageDialog(MainFrame.this, msg, title + " failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void plotBubbleOrHeatmap(boolean heatmap) {
        String title = heatmap ? "Heatmap" : "Bubble plot";
        if (!ensureReductionReady(title)) {
            return;
        }
        if (expression == null) {
            JOptionPane.showMessageDialog(this, "Expression assay not loaded.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (metadata == null || !metadata.hasClusters()) {
            JOptionPane.showMessageDialog(this, title + " needs cluster_id.", title,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> genes = parseGenes(geneField.getText());
        if (genes.isEmpty()) {
            tipGenes();
            return;
        }
        final String[] cells = sample.cellIds;
        final int[] cats = metadata.clusterIndices(cells);
        final String[] labels = metadata.clusterLevels().toArray(String[]::new);
        status.setText(title + " for " + genes.size() + " gene" + (genes.size() == 1 ? "" : "s") + "…");
        final List<String> geneList = genes;
        beginBusy(title + ": reading " + genes.size() + " gene"
                + (genes.size() == 1 ? "" : "s") + " (one pass)…");
        new SwingWorker<BubbleData, Void>() {
            @Override
            protected BubbleData doInBackground() {
                int ng = geneList.size();
                int nc = labels.length;
                float[][] means = new float[ng][nc];
                float[][] pcts = new float[ng][nc];
                String[] resolved = geneList.toArray(String[]::new);
                float[][] rawAll = expression.valuesForCells(geneList, cells, uiProgress());
                javax.swing.SwingUtilities.invokeLater(
                        () -> setBusyProgress(0, 0, "Aggregating by cluster…"));
                for (int gi = 0; gi < ng; gi++) {
                    float[] raw = rawAll[gi];
                    float[] vals = ExprTransform.log2p1(raw);
                    int[] n = new int[nc];
                    int[] pos = new int[nc];
                    double[] sum = new double[nc];
                    for (int i = 0; i < vals.length; i++) {
                        int c = cats[i];
                        if (c < 0 || c >= nc) {
                            continue;
                        }
                        n[c]++;
                        sum[c] += vals[i];
                        if (raw[i] > 0f) {
                            pos[c]++;
                        }
                    }
                    for (int c = 0; c < nc; c++) {
                        means[gi][c] = n[c] > 0 ? (float) (sum[c] / n[c]) : 0f;
                        pcts[gi][c] = n[c] > 0 ? (100f * pos[c] / n[c]) : 0f;
                    }
                }
                return new BubbleData(resolved, labels, means, pcts);
            }

            @Override
            protected void done() {
                endBusy();
                try {
                    BubbleData bd = get();
                    PlotTheme.ColorScale cs = (PlotTheme.ColorScale) colorScaleBox.getSelectedItem();
                    if (cs == null) {
                        cs = PlotTheme.ColorScale.BLUE_WHITE_RED;
                    }
                    if (heatmap) {
                        heatmapPlot.setColorScale(cs);
                        heatmapPlot.setData(bd.genes, bd.clusters, bd.means);
                        showCard(CARD_HEATMAP);
                        status.setText(withTiming(sampleStatus() + "  ·  heatmap ("
                                + bd.genes.length + " genes × " + bd.clusters.length + " clusters)"));
                    } else {
                        bubblePlot.setColorScale(cs);
                        bubblePlot.setData(bd.genes, bd.clusters, bd.means, bd.pcts);
                        showCard(CARD_BUBBLE);
                        status.setText(withTiming(sampleStatus() + "  ·  bubble ("
                                + bd.genes.length + " genes × " + bd.clusters.length + " clusters)"));
                    }
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                    List<String> sug = expression.suggest(geneList.get(0), 8);
                    if (!sug.isEmpty()) {
                        msg += "\nDid you mean: " + String.join(", ", sug);
                    }
                    JOptionPane.showMessageDialog(MainFrame.this, msg, title + " failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void plotGeneGene() {
        if (!ensureReductionReady("Gene–gene")) {
            return;
        }
        if (expression == null) {
            JOptionPane.showMessageDialog(this, "Expression assay not loaded.", "Gene–gene",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> genes = parseGenes(geneField.getText());
        if (genes.size() < 2) {
            JOptionPane.showMessageDialog(this,
                    "Gene–gene needs two genes, e.g. CD3E, MS4A1",
                    "Gene–gene", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final String g1 = genes.get(0);
        final String g2 = genes.get(1);
        final String[] cells = sample.cellIds;
        final int[] cats;
        final String[] labels;
        if (metadata != null && metadata.hasClusters()) {
            cats = metadata.clusterIndices(cells);
            labels = metadata.clusterLevels().toArray(String[]::new);
        } else {
            cats = null;
            labels = null;
        }
        status.setText("Gene–gene: " + g1 + " vs " + g2 + "…");
        beginBusy("Gene–gene: " + g1 + " vs " + g2 + "…");
        new SwingWorker<float[][], Void>() {
            @Override
            protected float[][] doInBackground() {
                float[][] raw = expression.valuesForCells(List.of(g1, g2), cells, uiProgress());
                float[] x = ExprTransform.log2p1(raw[0]);
                float[] y = ExprTransform.log2p1(raw[1]);
                return new float[][]{x, y};
            }

            @Override
            protected void done() {
                endBusy();
                try {
                    float[][] xy = get();
                    corPlot.setData(g1, g2, xy[0], xy[1], cats, labels);
                    showCard(CARD_COR);
                    status.setText(withTiming(sampleStatus() + "  ·  " + g1 + " vs " + g2));
                } catch (Exception ex) {
                    geneError(g1 + "/" + g2, ex);
                }
            }
        }.execute();
    }

    private static final class CellHeatData {
        final String[] genes;
        final float[][] scaled;
        final int[] colCluster;
        final String[] clusterLevels;
        final int nCellsShown;
        final int nCellsTotal;

        CellHeatData(String[] genes, float[][] scaled, int[] colCluster,
                     String[] clusterLevels, int nCellsTotal) {
            this.genes = genes;
            this.scaled = scaled;
            this.colCluster = colCluster;
            this.clusterLevels = clusterLevels;
            this.nCellsShown = scaled.length == 0 || scaled[0] == null ? 0 : scaled[0].length;
            this.nCellsTotal = nCellsTotal;
        }
    }

    private static final class BubbleData {
        final String[] genes;
        final String[] clusters;
        final float[][] means;
        final float[][] pcts;

        BubbleData(String[] genes, String[] clusters, float[][] means, float[][] pcts) {
            this.genes = genes;
            this.clusters = clusters;
            this.means = means;
            this.pcts = pcts;
        }
    }

    private void exportPlot() {
        if (activePlot == null || CARD_HOME.equals(activeCard) || CARD_WELCOME.equals(activeCard)) {
            JOptionPane.showMessageDialog(this,
                    "Switch to a plot view before exporting.",
                    "Export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export plot");
        fc.setAcceptAllFileFilterUsed(false);
        FileNameExtensionFilter png = new FileNameExtensionFilter("PNG image (*.png)", "png");
        FileNameExtensionFilter jpg = new FileNameExtensionFilter("JPEG image (*.jpg)", "jpg", "jpeg");
        FileNameExtensionFilter pdf = new FileNameExtensionFilter("PDF (*.pdf)", "pdf");
        fc.addChoosableFileFilter(png);
        fc.addChoosableFileFilter(jpg);
        fc.addChoosableFileFilter(pdf);
        fc.setFileFilter(png);

        String baseHint = "SODAviewer_plot";
        String g = geneField.getText().trim();
        if (!g.isEmpty()) {
            baseHint = g.replaceAll("[^A-Za-z0-9._-]", "_");
            String lower = baseHint.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".pdf")) {
                baseHint = stripKnownExportExt(baseHint);
            }
        }
        final String defaultStem = baseHint;
        fc.setSelectedFile(new File(defaultStem + ".png"));
        fc.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, evt -> {
            PlotExporter.Format fmt = formatFromFilter(fc.getFileFilter());
            if (fmt == null) {
                fmt = PlotExporter.Format.PNG;
            }
            File cur = fc.getSelectedFile();
            String stem = defaultStem;
            if (cur != null && cur.getName() != null && !cur.getName().isBlank()) {
                stem = stripKnownExportExt(cur.getName());
            }
            File parent = cur != null ? cur.getParentFile() : null;
            File next = parent == null
                    ? new File(stem + "." + extensionFor(fmt))
                    : new File(parent, stem + "." + extensionFor(fmt));
            fc.setSelectedFile(next);
        });

        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        PlotExporter.Format fmt = formatFromFilter(fc.getFileFilter());
        if (fmt == null) {
            fmt = formatFromName(f.getName());
        }
        if (fmt == null) {
            fmt = PlotExporter.Format.PNG;
        }
        f = withExportExtension(f, fmt);
        try {
            int defW = Math.max(900, activePlot.getWidth());
            int defH = Math.max(700, activePlot.getHeight());
            javax.swing.JSpinner wSpin = new javax.swing.JSpinner(
                    new javax.swing.SpinnerNumberModel(defW, 200, 8000, 50));
            javax.swing.JSpinner hSpin = new javax.swing.JSpinner(
                    new javax.swing.SpinnerNumberModel(defH, 200, 8000, 50));
            javax.swing.JSpinner scaleSpin = new javax.swing.JSpinner(
                    new javax.swing.SpinnerNumberModel(2, 1, 4, 1));
            JPanel sizePanel = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
            sizePanel.add(new JLabel("Width (px)"));
            sizePanel.add(wSpin);
            sizePanel.add(new JLabel("Height (px)"));
            sizePanel.add(hSpin);
            sizePanel.add(new JLabel("Scale (1–4)"));
            sizePanel.add(scaleSpin);
            int ok = JOptionPane.showConfirmDialog(this, sizePanel,
                    "Export image size", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return;
            }
            int ew = ((Number) wSpin.getValue()).intValue();
            int eh = ((Number) hSpin.getValue()).intValue();
            int sc = ((Number) scaleSpin.getValue()).intValue();
            PlotExporter.export(activePlot, f.toPath(), fmt, sc, ew, eh);
            status.setText("Exported " + fmt + " " + ew + "×" + eh + "@" + sc + "x → "
                    + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static PlotExporter.Format formatFromFilter(javax.swing.filechooser.FileFilter filter) {
        if (!(filter instanceof FileNameExtensionFilter f)) {
            return null;
        }
        for (String ext : f.getExtensions()) {
            String e = ext.toLowerCase(Locale.ROOT);
            if (e.equals("png")) {
                return PlotExporter.Format.PNG;
            }
            if (e.equals("jpg") || e.equals("jpeg")) {
                return PlotExporter.Format.JPG;
            }
            if (e.equals("pdf")) {
                return PlotExporter.Format.PDF;
            }
        }
        return null;
    }

    private static PlotExporter.Format formatFromName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return PlotExporter.fromExtension(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String extensionFor(PlotExporter.Format fmt) {
        return switch (fmt) {
            case JPG -> "jpg";
            case PDF -> "pdf";
            default -> "png";
        };
    }

    private static String stripKnownExportExt(String name) {
        String n = name;
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpeg")) {
            return n.substring(0, n.length() - 5);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".pdf")) {
            return n.substring(0, n.length() - 4);
        }
        return n;
    }

    private static File withExportExtension(File f, PlotExporter.Format fmt) {
        String stem = stripKnownExportExt(f.getName());
        String ext = extensionFor(fmt);
        File parent = f.getParentFile();
        return parent == null ? new File(stem + "." + ext) : new File(parent, stem + "." + ext);
    }

    private void openIndexInBrowser() {
        if (soda == null) {
            return;
        }
        Path index = soda.root().resolve("index.html");
        if (!Files.isRegularFile(index)) {
            JOptionPane.showMessageDialog(this, "No index.html in this archive.",
                    "Open in browser", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().browse(index.toUri());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Open in browser",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void tipGenes() {
        List<String> hints = expression != null ? expression.suggest("CD", 8) : List.of();
        JOptionPane.showMessageDialog(this,
                "Type a gene (e.g. " + String.join(", ", hints) + ")\n"
                        + "Several genes → bubble / heatmap. Two genes → Gene–gene.",
                "Gene", JOptionPane.INFORMATION_MESSAGE);
    }

    private void geneError(String g, Exception ex) {
        endBusy();
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String raw = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        boolean missing = raw.toLowerCase(Locale.ROOT).contains("gene not found");
        String msg = raw;
        if (missing && expression != null) {
            List<String> sug = expression.suggest(g, 12);
            if (!sug.isEmpty()) {
                msg += "\nDid you mean: " + String.join(", ", sug);
            }
        } else if (!missing) {
            msg = "Could not load expression for " + g + ".\n\n" + raw;
        }
        JOptionPane.showMessageDialog(this, msg,
                missing ? "Gene not found" : "Expression load failed",
                JOptionPane.ERROR_MESSAGE);
        status.setText((missing ? "Gene not found: " : "Expression failed: ") + g);
    }


    private void restorePlotType() {
        try {
            plotTypeBox.setSelectedItem(GenePlotType.valueOf(AppPrefs.plotType()));
        } catch (Exception ex) {
            plotTypeBox.setSelectedItem(GenePlotType.FEATURE);
        }
    }


    private void tipForPlotType(GenePlotType pt) {
        if (pt == null) {
            return;
        }
        String tip = switch (pt) {
            case FEATURE -> "tip: 1–9 genes · faceted feature plots";
            case BOX, VIOLIN -> "tip: 1–6 genes · needs cluster_id";
            case BUBBLE, HEATMAP -> "tip: comma-separated genes × clusters";
            case HEATMAP_CELLS -> "tip: genes × cells · ordered by cluster";
            case GENE_GENE -> "tip: exactly two genes, e.g. CD3E, MS4A1";
        };
        // Do not change Scale / repaint here — only apply defaults when Plot is pressed.
        status.setText((soda == null ? "SODAviewer" : sampleStatus()) + "  ·  " + tip);
    }


    /**
     * Scale = continuous (genes / pseudotime / heatmaps).
     * Palette = categorical (clusters / conditions / samples).
     * Grey out the one that does not apply to the current coloring / plot.
     */
    private void updateScalePaletteEnabled() {
        boolean base = soda != null;
        if (!base) {
            colorScaleBox.setEnabled(false);
            catPaletteBox.setEnabled(false);
            return;
        }
        Object sel = colorByBox.getSelectedItem();
        boolean categorical = sel instanceof CellMetadata.Field f
                && f.kind == CellMetadata.Kind.CATEGORICAL;
        boolean continuous = sel instanceof CellMetadata.Field f
                && f.kind == CellMetadata.Kind.CONTINUOUS;
        boolean continuousPlot = switch (activeCard == null ? "" : activeCard) {
            case CARD_MULTI_FEATURE, CARD_BUBBLE, CARD_HEATMAP, CARD_CELL_HEATMAP -> true;
            case CARD_SCATTER -> continuous || scatter.isGeneColored();
            default -> false;
        };
        boolean categoricalPlot = switch (activeCard == null ? "" : activeCard) {
            case CARD_BOX, CARD_VIOLIN, CARD_BUBBLE, CARD_MULTI_CLUSTER,
                    CARD_CELL_HEATMAP, CARD_HEATMAP -> true;
            case CARD_SCATTER -> categorical || scatter.isClusterColored();
            default -> false;
        };
        boolean scaleOn = continuous || continuousPlot || (!categorical && !continuous);
        boolean paletteOn = categorical || categoricalPlot || (!categorical && !continuous);
        // Exclusive when Color by itself drives the scatter
        if (continuous && CARD_SCATTER.equals(activeCard) && !scatter.isGeneColored()) {
            paletteOn = false;
        }
        if (categorical && CARD_SCATTER.equals(activeCard) && !scatter.isGeneColored()) {
            scaleOn = false;
        }
        colorScaleBox.setEnabled(scaleOn);
        catPaletteBox.setEnabled(paletteOn);
        colorScaleBox.setToolTipText(scaleOn
                ? "Continuous color scale (genes / pseudotime / bubble / heatmap)"
                : "Scale applies to continuous Color by and gene plots");
        catPaletteBox.setToolTipText(paletteOn
                ? "Colors for clusters / conditions / samples"
                : "Palette applies to categorical Color by and cluster plots");
    }

    private void setControlsEnabled(boolean on) {
        reductionBox.setEnabled(on);
        geneField.setEnabled(on);
        overviewBtn.setEnabled(on);
        plotTypeBox.setEnabled(on);
        plotBtn.setEnabled(on);
        dataTypeBox.setEnabled(on);
        colorByBox.setEnabled(on && metadata != null && !metadata.fields().isEmpty());
        clearBtn.setEnabled(on);
        exportBtn.setEnabled(on);
        if (on) {
            updateScalePaletteEnabled();
        } else {
            colorScaleBox.setEnabled(false);
            catPaletteBox.setEnabled(false);
        }
        dotSizeSpinner.setEnabled(on);
        opacitySpinner.setEnabled(on);
        annotateBox.setEnabled(on);
        lassoBtn.setEnabled(on);
        zoomInBtn.setEnabled(on);
        zoomOutBtn.setEnabled(on);
        zoomHomeBtn.setEnabled(on);
        panLeftBtn.setEnabled(on);
        panRightBtn.setEnabled(on);
        panUpBtn.setEnabled(on);
        panDownBtn.setEnabled(on);
        lassoToolsBtn.setEnabled(on);
    }

    private void toggleLasso() {
        showCard(CARD_SCATTER);
        boolean on = !scatter.isLassoMode();
        scatter.setLassoMode(on); // turning off also clears selection
        lassoBtn.setText(on ? "Lasso ●" : "Lasso");
        if (on) {
            status.setText(sampleStatus() + "  ·  lasso on — drag to select cells");
        } else {
            status.setText(sampleStatus() + "  ·  lasso off · selection cleared");
        }
    }


    private void showLassoToolsMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem save = new JMenuItem("Save selected IDs…");
        save.addActionListener(e -> saveSelectedIds());
        JMenuItem assign = new JMenuItem("Assign selection to cluster…");
        assign.addActionListener(e -> assignSelectionToCluster());
        JMenuItem remove = new JMenuItem("Remove selection from plot");
        remove.addActionListener(e -> removeSelectionFromPlot());
        JMenuItem showAll = new JMenuItem("Show all cells");
        showAll.addActionListener(e -> {
            showCard(CARD_SCATTER);
            scatter.showAllCells();
            status.setText(sampleStatus() + "  ·  all cells visible");
        });
        JMenuItem merge = new JMenuItem("Merge clusters…");
        merge.addActionListener(e -> mergeClustersDialog());
        JMenuItem rename = new JMenuItem("Rename cluster…");
        rename.addActionListener(e -> renameClusterDialog());
        menu.add(save);
        menu.addSeparator();
        menu.add(assign);
        menu.add(remove);
        menu.add(showAll);
        menu.addSeparator();
        menu.add(merge);
        menu.add(rename);
        menu.show(lassoToolsBtn, 0, lassoToolsBtn.getHeight());
    }

    private boolean ensureClusterModeForEdit(String action) {
        showCard(CARD_SCATTER);
        if (scatter.hasClusterColors()) {
            return true;
        }
        if (sample == null || metadata == null || !metadata.hasClusters()) {
            JOptionPane.showMessageDialog(this,
                    action + " needs cluster_id metadata.",
                    action, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String[] labels = metadata.clusterLevels().toArray(String[]::new);
        int[] cats = metadata.clusterIndices(sample.cellIds);
        scatter.setClusterColors(cats, labels, annotateBox.isSelected());
        for (int i = 0; i < colorByBox.getItemCount(); i++) {
            Object o = colorByBox.getItemAt(i);
            if (o instanceof CellMetadata.Field f && "cluster_id".equals(f.column)) {
                colorByBox.setSelectedItem(o);
                break;
            }
        }
        return true;
    }

    private void assignSelectionToCluster() {
        if (!ensureClusterModeForEdit("Assign to cluster")) {
            return;
        }
        if (scatter.selectedCount() < 1) {
            JOptionPane.showMessageDialog(this,
                    "Select cells with Lasso first.",
                    "Assign to cluster", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] labels = scatter.getClusterLabels();
        java.util.List<String> choices = new java.util.ArrayList<>();
        if (labels != null) {
            for (String l : labels) {
                choices.add(l);
            }
        }
        choices.add("+ New cluster…");
        String pick = (String) JOptionPane.showInputDialog(
                this,
                "Assign " + scatter.selectedCount() + " selected cells to:",
                "Assign to cluster",
                JOptionPane.QUESTION_MESSAGE,
                null,
                choices.toArray(),
                choices.get(0));
        if (pick == null) {
            return;
        }
        int n;
        if (pick.startsWith("+")) {
            String name = JOptionPane.showInputDialog(this,
                    "New cluster name (e.g. B cell):", "New cluster",
                    JOptionPane.QUESTION_MESSAGE);
            if (name == null || name.isBlank()) {
                return;
            }
            n = scatter.assignSelectedToCluster(-1, name.trim());
            status.setText(sampleStatus() + "  ·  assigned " + n + " cells → " + name.trim());
        } else {
            int cat = -1;
            for (int i = 0; i < labels.length; i++) {
                if (pick.equals(labels[i])) {
                    cat = i;
                    break;
                }
            }
            n = scatter.assignSelectedToCluster(cat, null);
            status.setText(sampleStatus() + "  ·  assigned " + n + " cells → " + pick);
        }
    }

    private void removeSelectionFromPlot() {
        showCard(CARD_SCATTER);
        if (scatter.selectedCount() < 1) {
            JOptionPane.showMessageDialog(this,
                    "Select cells with Lasso first.",
                    "Remove from plot", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int n = scatter.hideSelectedCells();
        status.setText(sampleStatus() + "  ·  hid " + n
                + " cells (Lasso/cluster tools → Show all cells to restore)");
    }

    private void renameClusterDialog() {
        if (!ensureClusterModeForEdit("Rename cluster")) {
            return;
        }
        String[] labels = scatter.getClusterLabels();
        if (labels == null || labels.length == 0) {
            return;
        }
        String pick = (String) JOptionPane.showInputDialog(
                this, "Cluster to rename:", "Rename cluster",
                JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
        if (pick == null) {
            return;
        }
        int cat = -1;
        for (int i = 0; i < labels.length; i++) {
            if (pick.equals(labels[i])) {
                cat = i;
                break;
            }
        }
        String name = JOptionPane.showInputDialog(this,
                "New name for cluster \"" + pick + "\"\n(e.g. B cell):",
                pick);
        if (name == null || name.isBlank()) {
            return;
        }
        scatter.renameCluster(cat, name.trim());
        status.setText(sampleStatus() + "  ·  renamed \"" + pick + "\" → \"" + name.trim() + "\"");
    }

    private void mergeClustersDialog() {
        if (!ensureClusterModeForEdit("Merge clusters")) {
            return;
        }
        String[] labels = scatter.getClusterLabels();
        if (labels == null || labels.length < 2) {
            JOptionPane.showMessageDialog(this, "Need at least two clusters to merge.",
                    "Merge clusters", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        javax.swing.JList<String> list = new javax.swing.JList<>(labels);
        list.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(280, 180));
        int ok = JOptionPane.showConfirmDialog(this, scroll,
                "Select clusters to merge (2+)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        int[] sel = list.getSelectedIndices();
        if (sel.length < 2) {
            JOptionPane.showMessageDialog(this, "Select at least two clusters.",
                    "Merge clusters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String[] selLabs = new String[sel.length];
        for (int i = 0; i < sel.length; i++) {
            selLabs[i] = labels[sel[i]];
        }
        String targetLab = (String) JOptionPane.showInputDialog(
                this,
                "Merge into (keep this name):",
                "Merge clusters",
                JOptionPane.QUESTION_MESSAGE,
                null,
                selLabs,
                selLabs[0]);
        if (targetLab == null) {
            return;
        }
        int target = sel[0];
        for (int s : sel) {
            if (labels[s].equals(targetLab)) {
                target = s;
                break;
            }
        }
        int n = scatter.mergeClusters(sel, target);
        status.setText(sampleStatus() + "  ·  merged " + sel.length
                + " clusters → \"" + targetLab + "\" (" + n + " cells remapped)");
    }

    private void saveSelectedIds() {
        showCard(CARD_SCATTER);
        int nSel = scatter.selectedCount();
        String[] ids = scatter.selectedCellIds();
        if (nSel < 1 || ids.length < 1) {
            JOptionPane.showMessageDialog(this,
                    "No cells selected.\n\n"
                            + "1. Click Lasso (button shows Lasso ●)\n"
                            + "2. Drag a loop around cells on the scatter plot\n"
                            + "3. Release — status should show “N cells selected”\n"
                            + "4. Then Lasso/cluster tools ▾ → Save selected IDs…",
                    "Save IDs", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save selected cell IDs (" + ids.length + " cells)");
        File startDir = AppPrefs.lastSodaDir();
        if (startDir == null) {
            startDir = new File(System.getProperty("user.home"), "Desktop");
        }
        if (startDir.isDirectory()) {
            fc.setCurrentDirectory(startDir);
        }
        String red = String.valueOf(reductionBox.getSelectedItem());
        String hint = "selected_cells_" + (red == null ? "plot" : red) + ".txt";
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), hint));
        fc.setFileFilter(new FileNameExtensionFilter("Text (*.txt)", "txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        if (f == null) {
            return;
        }
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
            f = new File(f.getParentFile(), f.getName() + ".txt");
        }
        try {
            if (f.getParentFile() != null) {
                Files.createDirectories(f.getParentFile().toPath());
            }
            StringBuilder sb = new StringBuilder(ids.length * 24 + 200);
            sb.append("# SODAviewer lasso selection\n");
            sb.append("# n=").append(ids.length).append('\n');
            if (soda != null) {
                sb.append("# soda=").append(soda.root()).append('\n');
            }
            sb.append("# reduction=").append(reductionBox.getSelectedItem()).append('\n');
            sb.append("cell_id\n");
            for (String id : ids) {
                sb.append(id).append('\n');
            }
            Files.writeString(f.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            status.setText("Wrote " + ids.length + " cell ids → " + f.getAbsolutePath());
            JOptionPane.showMessageDialog(this,
                    "Saved " + ids.length + " cell_id values to:\n" + f.getAbsolutePath(),
                    "Save IDs", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not write file:\n" + ex.getMessage(),
                    "Save IDs failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void closeExpression() {
        if (expression != null) {
            try {
                expression.close();
            } catch (Exception ignored) {
                // ignore
            }
            expression = null;
        }
    }

    private void copyGenesFromField() {
        String sel = geneField.getSelectedText();
        String text = (sel != null && !sel.isBlank()) ? sel : geneField.getText();
        text = normalizeGeneText(text);
        if (text.isBlank()) {
            status.setText("Nothing to copy — type or load genes first");
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            List<String> genes = parseGenes(text);
            status.setText("Copied " + genes.size() + " gene"
                    + (genes.size() == 1 ? "" : "s") + " to clipboard");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not copy to clipboard:\n" + ex.getMessage(),
                    "Copy genes", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void pasteGenesFromClipboard() {
        String raw = readClipboardText();
        if (raw == null) {
            JOptionPane.showMessageDialog(this,
                    "Clipboard is empty or not text.",
                    "Paste genes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        applyGenesToField(raw, "Pasted");
    }

    private void pasteGenesDialog() {
        javax.swing.JTextArea area = new javax.swing.JTextArea(12, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(geneField.getFont());
        String clip = readClipboardText();
        if (clip != null && !clip.isBlank()) {
            area.setText(clip.trim());
        } else {
            // PBMC-style marker example (comma or newline both OK)
            area.setText(EXAMPLE_PASTE_GENES);
        }
        area.selectAll();
        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.add(new JLabel(
                "<html>One gene per line, or comma-separated."
                        + " Quotes optional. Example markers shown below.</html>"),
                BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(480, 260));
        wrap.add(scroll, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, wrap,
                "Paste genes",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        applyGenesToField(area.getText(), "Loaded");
    }

    private void loadGenesFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load genes from file");
        fc.setAcceptAllFileFilterUsed(true);
        fc.addChoosableFileFilter(new FileNameExtensionFilter(
                "Gene lists (*.txt, *.csv, *.tsv, *.genes)", "txt", "csv", "tsv", "genes"));
        File start = AppPrefs.lastSodaDir();
        if (start != null && start.getParentFile() != null) {
            fc.setCurrentDirectory(start.getParentFile());
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        try {
            String raw = Files.readString(f.toPath());
            applyGenesToField(raw, "Loaded from " + f.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Load genes failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyGenesToField(String raw, String verb) {
        List<String> genes = parseGenes(raw);
        if (genes.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No gene names found.\nUse one gene per line, or commas / tabs.",
                    "Genes", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String joined = formatGenes(genes);
        geneField.setText(joined);
        geneField.requestFocusInWindow();
        geneField.setCaretPosition(joined.length());
        status.setText(verb + " " + genes.size() + " gene"
                + (genes.size() == 1 ? "" : "s"));
    }

    private static String readClipboardText() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t == null) {
                return null;
            }
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Object data = t.getTransferData(DataFlavor.stringFlavor);
                return data == null ? null : data.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static void copyText(String text) {
        if (text == null) {
            text = "";
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    @Override
    public void dispose() {
        closeExpression();
        super.dispose();
    }
}
