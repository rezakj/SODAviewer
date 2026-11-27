package sodaviewer;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Welcome: copy column (vertically centered) + smaller dashboard SODA sketch.
 */
public final class WelcomePanel extends JPanel {
    private static final Color PAPER = Color.WHITE;
    private static final Color INK = new Color(0x1A, 0x24, 0x32);
    private static final Color MUTE = new Color(0x5C, 0x6B, 0x7A);
    private static final Color ACCENT = new Color(0x2A, 0x5F, 0x8F);

    private static final BufferedImage ART = loadArt();

    private final DropZone dropZone = new DropZone();
    private final JButton openLastBtn = new JButton();

    public WelcomePanel(Runnable onOpen, Consumer<File> onDrop, Runnable onOpenLast) {
        setLayout(new BorderLayout());
        setBackground(PAPER);

        JPanel root = new JPanel(new GridBagLayout());
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(20, 36, 20, 28));

        GridBagConstraints rc = new GridBagConstraints();
        rc.gridy = 0;
        rc.weighty = 1;
        rc.fill = GridBagConstraints.BOTH;

        // Left copy — vertically centered block, capped width
        rc.gridx = 0;
        rc.weightx = 0.46;
        rc.insets = new Insets(0, 8, 0, 28);
        rc.anchor = GridBagConstraints.CENTER;
        JPanel copyWrap = new JPanel();
        copyWrap.setOpaque(false);
        copyWrap.setLayout(new BoxLayout(copyWrap, BoxLayout.Y_AXIS));
        copyWrap.add(Box.createVerticalGlue());
        JPanel copy = buildCopyColumn(onOpen, onOpenLast);
        copy.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.setMaximumSize(new Dimension(440, Integer.MAX_VALUE));
        copyWrap.add(copy);
        copyWrap.add(Box.createVerticalGlue());
        root.add(copyWrap, rc);

        // Right sketch — fills remaining space, stays smaller visually via padding
        rc.gridx = 1;
        rc.weightx = 0.54;
        rc.insets = new Insets(8, 0, 8, 8);
        rc.anchor = GridBagConstraints.CENTER;
        SketchPane sketch = new SketchPane();
        sketch.setPreferredSize(new Dimension(480, 400));
        sketch.setMinimumSize(new Dimension(300, 260));
        root.add(sketch, rc);

        if (onDrop != null) {
            SodaDropTargets.install(this, onDrop);
            SodaDropTargets.install(root, onDrop);
            SodaDropTargets.install(copy, onDrop);
            SodaDropTargets.install(dropZone, onDrop);
            SodaDropTargets.install(sketch, onDrop);
        }

        add(root, BorderLayout.CENTER);
        refreshOpenLast();
    }

    private JPanel buildCopyColumn(Runnable onOpen, Runnable onOpenLast) {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("SODAviewer");
        brand.setForeground(INK);
        brand.setFont(brandFont(40f));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(brand);
        col.add(Box.createVerticalStrut(6));

        JLabel tag = new JLabel("Single Omics Data Archive");
        tag.setForeground(ACCENT);
        tag.setFont(tag.getFont().deriveFont(Font.BOLD, 13f));
        tag.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(tag);
        col.add(Box.createVerticalStrut(14));

        JLabel lede = new JLabel(
                "<html><div style='width:400px;color:#5c6b7a;font-size:13px;line-height:1.55'>"
                        + "One portable <b>.soda</b> folder shared by "
                        + "<b>R</b>, <b>Python</b>, <b>Java</b> (this viewer), and the "
                        + "<b>bash CLI</b> — JSON · HDF5 · TSV · Parquet. "
                        + "Open it here for PCA, UMAP, KNetL, clusters, and genes."
                        + "</div></html>");
        lede.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(lede);
        col.add(Box.createVerticalStrut(22));

        dropZone.setAlignmentX(Component.LEFT_ALIGNMENT);
        dropZone.setMaximumSize(new Dimension(420, 92));
        dropZone.setPreferredSize(new Dimension(420, 92));
        col.add(dropZone);
        col.add(Box.createVerticalStrut(14));

        JPanel btnRow = new JPanel();
        btnRow.setOpaque(false);
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton openBtn = fancyButton("Open SODA…", true);
        openBtn.addActionListener(e -> {
            if (onOpen != null) {
                onOpen.run();
            }
        });
        btnRow.add(openBtn);
        btnRow.add(Box.createHorizontalStrut(10));
        styleOpenLast(onOpenLast);
        btnRow.add(openLastBtn);
        btnRow.add(Box.createHorizontalGlue());
        col.add(btnRow);
        col.add(Box.createVerticalStrut(28));

        JPanel qs = quickStartStrip();
        qs.setAlignmentX(Component.LEFT_ALIGNMENT);
        qs.setMaximumSize(new Dimension(420, Integer.MAX_VALUE));
        col.add(qs);

        return col;
    }

    public void refreshOpenLast() {
        File last = AppPrefs.lastSodaDir();
        if (last != null) {
            openLastBtn.setText("Reopen  " + last.getName());
            openLastBtn.setToolTipText(last.getAbsolutePath());
            openLastBtn.setVisible(true);
        } else {
            openLastBtn.setVisible(false);
        }
    }

    private void styleOpenLast(Runnable onOpenLast) {
        openLastBtn.setVisible(false);
        openLastBtn.setFont(openLastBtn.getFont().deriveFont(Font.PLAIN, 13f));
        openLastBtn.setFocusPainted(false);
        openLastBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        openLastBtn.setBackground(new Color(0xEE, 0xF4, 0xFA));
        openLastBtn.setForeground(ACCENT);
        openLastBtn.setOpaque(true);
        openLastBtn.setBorderPainted(false);
        openLastBtn.setPreferredSize(new Dimension(200, 34));
        openLastBtn.setMaximumSize(new Dimension(240, 34));
        openLastBtn.addActionListener(e -> {
            if (onOpenLast != null) {
                onOpenLast.run();
            }
        });
    }

    private static JButton fancyButton(String text, boolean primary) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setPreferredSize(new Dimension(168, 36));
        b.setMaximumSize(new Dimension(168, 36));
        if (primary) {
            b.setBackground(ACCENT);
            b.setForeground(Color.WHITE);
        }
        return b;
    }

    private static JPanel quickStartStrip() {
        JPanel strip = new JPanel();
        strip.setOpaque(false);
        strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));

        JLabel h = new JLabel("Quick start");
        h.setForeground(INK);
        h.setFont(h.getFont().deriveFont(Font.BOLD, 12.5f));
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        strip.add(h);
        strip.add(Box.createVerticalStrut(10));

        String[][] steps = {
                {"1", "Drag & drop", "Drop a .soda folder, or Open"},
                {"2", "Reduction", "Choose pca · umap · knetl"},
                {"3", "Explore", "Plot genes · Color by metadata"}
        };
        for (int i = 0; i < steps.length; i++) {
            JPanel row = quickStep(steps[i][0], steps[i][1], steps[i][2]);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(420, 40));
            strip.add(row);
            if (i < steps.length - 1) {
                strip.add(Box.createVerticalStrut(8));
            }
        }
        return strip;
    }

    private static JPanel quickStep(String num, String title, String detail) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel n = new JLabel(num, SwingConstants.CENTER);
        n.setOpaque(true);
        n.setBackground(new Color(0xE4, 0xEE, 0xF8));
        n.setForeground(ACCENT);
        n.setFont(n.getFont().deriveFont(Font.BOLD, 12f));
        n.setPreferredSize(new Dimension(24, 24));
        JLabel t = new JLabel("<html><b style='color:#1a2432;font-size:12px'>" + title
                + "</b><br><span style='color:#5c6b7a;font-size:11px'>" + detail + "</span></html>");
        row.add(n, BorderLayout.WEST);
        row.add(t, BorderLayout.CENTER);
        return row;
    }

    private static Font brandFont(float size) {
        String[] prefer = {"Iowan Old Style", "Palatino Linotype", "Palatino", "Georgia", Font.SERIF};
        for (String name : prefer) {
            Font f = new Font(name, Font.BOLD, Math.round(size));
            if (name.equals(Font.SERIF)
                    || f.getFamily().toLowerCase().contains(name.split(" ")[0].toLowerCase())) {
                return f.deriveFont(Font.BOLD, size);
            }
        }
        return new Font(Font.SERIF, Font.BOLD, Math.round(size));
    }

    private static BufferedImage loadArt() {
        for (String path : new String[]{
                "/sodaviewer/welcome-watercolor.png",
                "/sodaviewer/soda-arch.png"
        }) {
            try (InputStream in = WelcomePanel.class.getResourceAsStream(path)) {
                if (in != null) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        return img;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static final class SketchPane extends JPanel {
        SketchPane() {
            setOpaque(false);
            setToolTipText("R · Python · Java · bash → SODA → Viewer");
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int w = getWidth();
            int h = getHeight();
            float pad = 4f;
            RoundRectangle2D card = new RoundRectangle2D.Float(pad, pad, w - 2 * pad, h - 2 * pad, 16, 16);
            g.setColor(Color.WHITE);
            g.fill(card);
            g.setColor(new Color(0xE8, 0xE0, 0xD4));
            g.setStroke(new BasicStroke(1.1f));
            g.draw(card);

            int inner = 16;
            int iw = w - 2 * inner;
            int ih = h - 2 * inner - 20;
            if (ART != null && iw > 20 && ih > 20) {
                double s = Math.min(iw / (double) ART.getWidth(), ih / (double) ART.getHeight());
                int dw = (int) Math.round(ART.getWidth() * s);
                int dh = (int) Math.round(ART.getHeight() * s);
                int x = (w - dw) / 2;
                int y = inner + Math.max(0, (ih - dh) / 2);
                g.drawImage(ART, x, y, dw, dh, null);
            }

            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g.setColor(MUTE);
            String cap = "R · Python · Java · bash · Viewer";
            int tw = g.getFontMetrics().stringWidth(cap);
            g.drawString(cap, (w - tw) / 2, h - 10);
        }
    }

    private static final class DropZone extends JPanel {
        DropZone() {
            setOpaque(false);
            setLayout(new BorderLayout());
            JLabel lab = new JLabel(
                    "<html><div style='text-align:center;line-height:1.5'>"
                            + "<div style='font-size:14.5px;font-weight:600;color:#1a2432'>"
                            + "Drag &amp; drop a <span style='color:#2a5f8f'>.soda</span> folder</div>"
                            + "<div style='font-size:11px;color:#5c6b7a'>"
                            + "From Finder / Explorer · or Open SODA…</div>"
                            + "</div></html>",
                    SwingConstants.CENTER);
            add(lab, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float pad = 1.5f;
            RoundRectangle2D rr = new RoundRectangle2D.Float(
                    pad, pad, getWidth() - 2 * pad, getHeight() - 2 * pad, 12, 12);
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(rr);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    0, new float[]{7f, 5f}, 0));
            g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 200));
            g.draw(rr);
            super.paintComponent(g0);
        }
    }
}
