package sodaviewer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Non-blocking gene typeahead. Uses a non-focusable window (not JPopupMenu)
 * so the text field keeps keyboard focus while suggestions appear.
 */
public final class GeneSuggestSupport {
    private static final int MIN_CHARS = 2;
    private static final int DEBOUNCE_MS = 140;

    private final JTextField field;
    private final Supplier<ExpressionStore> expression;
    private final JWindow popup;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final AtomicInteger gen = new AtomicInteger();
    private boolean applying;
    private javax.swing.Timer debounce;

    public GeneSuggestSupport(JTextField field, Supplier<ExpressionStore> expression) {
        this.field = field;
        this.expression = expression;

        list.setVisibleRowCount(8);
        list.setFocusable(false);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setFocusable(false);
        scroll.getVerticalScrollBar().setFocusable(false);
        scroll.setPreferredSize(new Dimension(300, 168));

        popup = new JWindow(SwingUtilities.getWindowAncestor(field));
        popup.setFocusableWindowState(false);
        popup.setType(java.awt.Window.Type.POPUP);
        popup.getContentPane().add(scroll);
        popup.setAlwaysOnTop(false);

        debounce = new javax.swing.Timer(DEBOUNCE_MS, e -> refresh());
        debounce.setRepeats(false);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { schedule(); }
            @Override public void removeUpdate(DocumentEvent e) { schedule(); }
            @Override public void changedUpdate(DocumentEvent e) { schedule(); }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // Hide when leaving the field (not when interacting with list — list isn't focusable)
                SwingUtilities.invokeLater(() -> {
                    if (!field.isFocusOwner()) {
                        hide();
                    }
                });
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!popup.isVisible() || model.isEmpty()) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        int i = Math.min(Math.max(list.getSelectedIndex(), -1) + 1, model.size() - 1);
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        int i = Math.max(list.getSelectedIndex() - 1, 0);
                        list.setSelectedIndex(i);
                        list.ensureIndexIsVisible(i);
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        if (list.getSelectedIndex() >= 0) {
                            apply(list.getSelectedValue());
                            e.consume();
                        }
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        hide();
                        e.consume();
                    }
                    default -> { }
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (list.getSelectedValue() != null) {
                    apply(list.getSelectedValue());
                }
            }
        });
    }

    private void schedule() {
        if (applying) {
            return;
        }
        debounce.restart();
    }

    private void refresh() {
        ExpressionStore store = expression.get();
        if (store == null || !field.isShowing() || !field.isEnabled()) {
            hide();
            return;
        }
        String text = field.getText();
        int caret = Math.max(0, Math.min(field.getCaretPosition(), text.length()));
        Token tok = tokenAt(text, caret);
        if (tok.query.length() < MIN_CHARS) {
            hide();
            return;
        }

        final int ticket = gen.incrementAndGet();
        final String query = tok.query;
        // Off-EDT so large assays don't freeze typing
        new Thread(() -> {
            List<String> hits = store.suggest(query, 12);
            SwingUtilities.invokeLater(() -> {
                if (ticket != gen.get() || applying) {
                    return;
                }
                // Caret/query may have changed while searching
                Token now = tokenAt(field.getText(), field.getCaretPosition());
                if (!now.query.equalsIgnoreCase(query)) {
                    return;
                }
                model.clear();
                for (String h : hits) {
                    model.addElement(h);
                }
                if (model.isEmpty()) {
                    hide();
                    return;
                }
                list.setSelectedIndex(0);
                showUnderField();
            });
        }, "gene-suggest").start();
    }

    private void showUnderField() {
        if (!field.isShowing() || !field.isDisplayable()) {
            return;
        }
        // Re-parent if window ancestor changed (first open)
        java.awt.Window owner = SwingUtilities.getWindowAncestor(field);
        if (owner != null && popup.getOwner() != owner) {
            popup.setVisible(false);
            // Can't change owner easily; position relative to screen instead
        }
        popup.pack();
        Point p = field.getLocationOnScreen();
        popup.setLocation(p.x, p.y + field.getHeight() + 2);
        if (!popup.isVisible()) {
            popup.setVisible(true);
        }
    }

    private void hide() {
        if (popup.isVisible()) {
            popup.setVisible(false);
        }
        model.clear();
    }

    private void apply(String gene) {
        if (gene == null) {
            return;
        }
        applying = true;
        try {
            String text = field.getText();
            int caret = field.getCaretPosition();
            Token tok = tokenAt(text, caret);
            String next = text.substring(0, tok.start) + gene + text.substring(tok.end);
            field.setText(next);
            int pos = tok.start + gene.length();
            field.setCaretPosition(Math.min(pos, next.length()));
            hide();
        } finally {
            applying = false;
        }
        field.requestFocusInWindow();
    }

    private static Token tokenAt(String text, int caret) {
        caret = Math.max(0, Math.min(caret, text.length()));
        int start = caret;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == ',' || c == ';') {
                break;
            }
            start--;
        }
        int end = caret;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == ',' || c == ';') {
                break;
            }
            end++;
        }
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        String q = text.substring(start, Math.min(caret, end)).trim();
        return new Token(start, end, q);
    }

    private record Token(int start, int end, String query) {}
}
