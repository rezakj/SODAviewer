package sodaviewer;

import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * AWT-level folder drops (works over Swing <em>and</em> JavaFX {@code JFXPanel},
 * where {@link javax.swing.TransferHandler} is often ignored).
 */
final class SodaDropTargets {
    private SodaDropTargets() {}

    static void install(Component component, Consumer<File> onSoda) {
        if (component == null || onSoda == null) {
            return;
        }
        new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = dtde.getTransferable();
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.dropComplete(false);
                        return;
                    }
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    File soda = MainFrame.resolveSodaDrop(files);
                    if (soda == null) {
                        dtde.dropComplete(false);
                        return;
                    }
                    dtde.dropComplete(true);
                    // Open on EDT
                    javax.swing.SwingUtilities.invokeLater(() -> onSoda.accept(soda));
                } catch (Exception ex) {
                    dtde.dropComplete(false);
                }
            }
        }, true);
    }
}
