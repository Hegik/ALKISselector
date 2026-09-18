// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import de.alkisselector.config.TagProposal;

/**
 * Tabelle der Tag-Vorschläge eines Kandidaten: Auswahl, Key, Wert (editierbar), vorhandener OSM-Wert, Herkunft.
 */
class TagTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"", "Key", "Wert", "OSM-Bestand", "Herkunft (ALKIS)"};

    private List<TagProposal> tags = Collections.emptyList();
    private boolean editable;

    void setTags(List<TagProposal> tags, boolean editable) {
        this.tags = tags != null ? new ArrayList<>(tags) : Collections.emptyList();
        this.editable = editable;
        fireTableDataChanged();
    }

    TagProposal getTag(int row) {
        return tags.get(row);
    }

    @Override
    public int getRowCount() {
        return tags.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return editable && (column == 0 || column == 2);
    }

    @Override
    public Object getValueAt(int row, int column) {
        TagProposal t = tags.get(row);
        switch (column) {
        case 0:
            return t.isSelected();
        case 1:
            return t.getKey();
        case 2:
            return t.getValue();
        case 3:
            return t.getExistingValue() == null ? "" : t.getExistingValue();
        default:
            return t.getOrigin();
        }
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        TagProposal t = tags.get(row);
        if (column == 0) {
            t.setSelected(Boolean.TRUE.equals(value));
        } else if (column == 2) {
            String v = value == null ? "" : value.toString().strip();
            t.setValue(v);
            if (!v.isEmpty()) {
                t.setSelected(true);
            }
        }
        fireTableRowsUpdated(row, row);
    }
}
