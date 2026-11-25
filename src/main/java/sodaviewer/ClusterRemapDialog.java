package sodaviewer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UI to renumber / remap cluster labels (old → new). Apply stays in-session;
 * Export writes through {@link ClusterIO} after a confirm warning.
 */
public final class ClusterRemapDialog extends JDialog {
    public enum Action { CANCEL, APPLY, EXPORT }

    private final RemapModel model;
    private Action result = Action.CANCEL;

    private ClusterRemapDialog(Frame owner, List<String> currentLabels) {
        super(owner, "Remap / renumber clusters", true);
        this.model = new RemapModel(currentLabels);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 280));

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        north.add(new JLabel(
                "<html>Edit <b>New label</b> for each cluster. "
                        + "Use Move up/down then Fill 1…K to renumber in that order.<br>"
                        + "<b>Apply</b> updates the plot only. "
                        + "<b>Export to SODA…</b> writes the original archive (with confirm).</html>"),
                BorderLayout.CENTER);

        JPanel side = new JPanel(new GridLayout(0, 1, 0, 6));
        side.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JButton up = new JButton("Move up");
        up.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (model.moveUp(r)) {
                table.setRowSelectionInterval(r - 1, r - 1);
            }
        });
        JButton down = new JButton("Move down");
        down.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (model.moveDown(r)) {
                table.setRowSelectionInterval(r + 1, r + 1);
            }
        });
        JButton fill = new JButton("Fill 1…K");
        fill.setToolTipText("Set New label to 1, 2, … K in the current row order");
        fill.addActionListener(e -> model.fillSequential());
        JButton copy = new JButton("Copy current");
        copy.setToolTipText("Reset New label to match Current");
        copy.addActionListener(e -> model.copyCurrent());
        side.add(up);
        side.add(down);
        side.add(fill);
        side.add(copy);

        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        center.add(scroll, BorderLayout.CENTER);
        center.add(side, BorderLayout.EAST);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            result = Action.CANCEL;
            dispose();
        });
        JButton apply = new JButton("Apply in session");
        apply.setToolTipText("Remap labels on the scatter plot only (does not write SODA)");
        apply.addActionListener(e -> {
            if (!stopEditing(table) || !validateRows()) {
                return;
            }
            result = Action.APPLY;
            dispose();
        });
        JButton export = new JButton("Export to SODA…");
        export.setToolTipText("Write remapped clusters into the loaded SODA directory");
        export.addActionListener(e -> {
            if (!stopEditing(table) || !validateRows()) {
                return;
            }
            result = Action.EXPORT;
            dispose();
        });
        south.add(cancel);
        south.add(apply);
        south.add(export);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private boolean stopEditing(JTable table) {
        if (table.isEditing() && table.getCellEditor() != null) {
            return table.getCellEditor().stopCellEditing();
        }
        return true;
    }

    private boolean validateRows() {
        for (int i = 0; i < model.getRowCount(); i++) {
            String neu = model.newLabel(i);
            if (neu == null || neu.isBlank()) {
                JOptionPane.showMessageDialog(this,
                        "New label for cluster \"" + model.oldLabel(i) + "\" is empty.",
                        "Remap clusters", JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        return true;
    }

    /** {@code old label → new label} in table order (LinkedHashMap). */
    public Map<String, String> oldToNew() {
        return model.oldToNew();
    }

    public Action result() {
        return result;
    }

    public static ClusterRemapDialog show(Frame owner, List<String> currentLabels) {
        ClusterRemapDialog d = new ClusterRemapDialog(owner, currentLabels);
        d.setVisible(true);
        return d;
    }

    private static final class RemapModel extends AbstractTableModel {
        private final List<String> oldLabs = new ArrayList<>();
        private final List<String> newLabs = new ArrayList<>();

        RemapModel(List<String> current) {
            for (String s : current) {
                String v = s == null ? "" : s;
                oldLabs.add(v);
                newLabs.add(v);
            }
        }

        String oldLabel(int row) {
            return oldLabs.get(row);
        }

        String newLabel(int row) {
            return newLabs.get(row);
        }

        Map<String, String> oldToNew() {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < oldLabs.size(); i++) {
                m.put(oldLabs.get(i), newLabs.get(i).trim());
            }
            return m;
        }

        void fillSequential() {
            for (int i = 0; i < newLabs.size(); i++) {
                newLabs.set(i, String.valueOf(i + 1));
            }
            fireTableDataChanged();
        }

        void copyCurrent() {
            for (int i = 0; i < oldLabs.size(); i++) {
                newLabs.set(i, oldLabs.get(i));
            }
            fireTableDataChanged();
        }

        boolean moveUp(int row) {
            if (row <= 0 || row >= oldLabs.size()) {
                return false;
            }
            swap(row, row - 1);
            return true;
        }

        boolean moveDown(int row) {
            if (row < 0 || row >= oldLabs.size() - 1) {
                return false;
            }
            swap(row, row + 1);
            return true;
        }

        private void swap(int a, int b) {
            String o = oldLabs.get(a);
            oldLabs.set(a, oldLabs.get(b));
            oldLabs.set(b, o);
            String n = newLabs.get(a);
            newLabs.set(a, newLabs.get(b));
            newLabs.set(b, n);
            fireTableRowsUpdated(Math.min(a, b), Math.max(a, b));
        }

        @Override
        public int getRowCount() {
            return oldLabs.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Current" : "New label";
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return columnIndex == 0 ? oldLabs.get(rowIndex) : newLabs.get(rowIndex);
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 1) {
                return;
            }
            newLabs.set(rowIndex, aValue == null ? "" : String.valueOf(aValue));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
