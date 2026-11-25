package sodaviewer;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.embed.swing.JFXPanel;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interactive SODA {@code index.html} home page (JavaFX WebView in Swing).
 */
public final class SodaHomePanel extends JPanel {
    private static final AtomicBoolean FX_READY = new AtomicBoolean(false);

    private final JFXPanel fxPanel = new JFXPanel();
    private final JLabel placeholder = new JLabel(
            "<html><div style='text-align:center;color:#64748b;line-height:1.6'>"
                    + "<div style='font-size:15px;margin-bottom:8px'>Drop a <b>.soda</b> folder here</div>"
                    + "<div style='font-size:12px'>or use File → Open SODA…</div>"
                    + "</div></html>",
            JLabel.CENTER);
    private WebView webView;
    private WebEngine engine;
    private Path currentIndex;

    public SodaHomePanel() {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        placeholder.setFont(placeholder.getFont().deriveFont(Font.PLAIN, 14f));
        placeholder.setForeground(new Color(0x64, 0x74, 0x8B));
        add(placeholder, BorderLayout.CENTER);
        ensureFx();
        Platform.runLater(this::initWeb);
    }

    /** Install AWT drop target on this panel and the embedded WebView surface. */
    public void installDropTarget(java.util.function.Consumer<java.io.File> onSoda) {
        SodaDropTargets.install(this, onSoda);
        SodaDropTargets.install(placeholder, onSoda);
        SodaDropTargets.install(fxPanel, onSoda);
    }

    private static void ensureFx() {
        if (FX_READY.get()) {
            return;
        }
        synchronized (FX_READY) {
            if (FX_READY.get()) {
                return;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    FX_READY.set(true);
                    latch.countDown();
                });
            } catch (IllegalStateException already) {
                Platform.setImplicitExit(false);
                FX_READY.set(true);
                latch.countDown();
            }
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void initWeb() {
        webView = new WebView();
        webView.setContextMenuEnabled(true);
        engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.FAILED) {
                SwingUtilities.invokeLater(() ->
                        placeholder.setText("Failed to load index.html: " + engine.getLoadWorker().getException()));
            }
        });
        fxPanel.setScene(new Scene(webView));
        SwingUtilities.invokeLater(() -> {
            remove(placeholder);
            add(fxPanel, BorderLayout.CENTER);
            revalidate();
            repaint();
            if (currentIndex != null) {
                loadSoda(currentIndex.getParent());
            }
        });
    }

    /** Load {@code sodaRoot/index.html} (interactive). */
    public void loadSoda(Path sodaRoot) {
        if (sodaRoot == null) {
            clear();
            return;
        }
        Path index = sodaRoot.resolve("index.html");
        currentIndex = index;
        if (!Files.isRegularFile(index)) {
            SwingUtilities.invokeLater(() -> {
                placeholder.setText("No index.html in " + sodaRoot.getFileName());
                removeAll();
                add(placeholder, BorderLayout.CENTER);
                revalidate();
                repaint();
            });
            return;
        }
        Platform.runLater(() -> {
            if (engine == null) {
                return;
            }
            engine.load(index.toUri().toString());
        });
        SwingUtilities.invokeLater(() -> {
            if (fxPanel.getParent() == null) {
                removeAll();
                add(fxPanel, BorderLayout.CENTER);
                revalidate();
                repaint();
            }
        });
    }

    public void reload() {
        if (currentIndex != null && Files.isRegularFile(currentIndex)) {
            Platform.runLater(() -> {
                if (engine != null) {
                    engine.reload();
                }
            });
        }
    }

    public void clear() {
        currentIndex = null;
        Platform.runLater(() -> {
            if (engine != null) {
                engine.loadContent("""
                        <html><body style="font-family:system-ui;color:#64748b;padding:2rem">
                        <p>Open a <code>.soda</code> archive to view its home page.</p>
                        </body></html>
                        """);
            }
        });
    }

    public void goHome() {
        Platform.runLater(() -> {
            if (engine != null && currentIndex != null) {
                engine.load(currentIndex.toUri().toString());
            }
        });
    }
}
