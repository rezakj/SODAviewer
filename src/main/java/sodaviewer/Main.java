package sodaviewer;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Portable SODA viewer — run with: {@code java -jar SODAviewer.jar} */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length > 0) {
            String a0 = args[0];
            if ("-v".equals(a0) || "--version".equals(a0) || "version".equals(a0)) {
                System.out.println("SODAviewer " + AppVersion.VERSION
                        + " (SODA " + AppVersion.COMPATIBLE_SODA_VERSION
                        + " / schema " + AppVersion.COMPATIBLE_SCHEMA_VERSION + ")");
                return;
            }
            if ("-h".equals(a0) || "--help".equals(a0) || "help".equals(a0)) {
                System.out.println("""
                        SODAviewer %s — portable SODA archive viewer
                        Usage: java -jar SODAviewer.jar [path/to/archive.soda]
                               java -jar SODAviewer.jar --version
                        Compatible: SODA %s / schema %s (iCellR2 %s, iCellPy %s)
                        """.formatted(
                        AppVersion.VERSION,
                        AppVersion.COMPATIBLE_SODA_VERSION,
                        AppVersion.COMPATIBLE_SCHEMA_VERSION,
                        AppVersion.COMPATIBLE_ICELLR2,
                        AppVersion.COMPATIBLE_ICELLPY));
                return;
            }
        }

        // Must run before AWT/Swing init so File/Edit/View/Help join the macOS menu bar
        // (same pattern as IntelliJ, IGV, etc.). Harmless on Windows/Linux.
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "SODAviewer");
        System.setProperty("apple.awt.application.appearance", "system");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // keep default L&F
            }
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                frame.openSoda(new java.io.File(args[0]));
            }
        });
    }
}
