package sodaviewer;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * FlowLayout that wraps and reports the correct preferred height so a
 * BorderLayout.NORTH/SOUTH toolbar is not clipped by the center card.
 */
public final class WrapLayout extends FlowLayout {
    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension dim = layoutSize(target, false);
        dim.width -= (getHgap() + 1);
        return dim;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getWidth();
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }
            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int maxWidth = targetWidth - insets.left - insets.right;

            int x = 0;
            int y = insets.top + vgap;
            int rowHeight = 0;
            int reqWidth = 0;

            for (Component m : target.getComponents()) {
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (x == 0 || x + d.width <= maxWidth) {
                    if (x > 0) {
                        x += hgap;
                    }
                    x += d.width;
                    rowHeight = Math.max(rowHeight, d.height);
                } else {
                    y += vgap + rowHeight;
                    x = d.width;
                    rowHeight = d.height;
                }
                reqWidth = Math.max(reqWidth, x);
            }
            y += rowHeight + vgap + insets.bottom;
            return new Dimension(reqWidth + insets.left + insets.right, y);
        }
    }

    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        // After layout, force parent to respect new preferred height
        Container parent = target.getParent();
        if (parent != null) {
            parent.invalidate();
        }
    }
}
