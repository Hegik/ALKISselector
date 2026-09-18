// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import javax.swing.table.AbstractTableModel;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.decision.Candidate;
import de.alkisselector.decision.Recommendation;
import de.alkisselector.ortho.OrthoResult;

/**
 * Tabelle der Kandidaten mit Filter.
 */
class CandidateTableModel extends AbstractTableModel {

    /** Vordefinierte Filter der Review-Liste. */
    enum Filter {
        OFFEN("Offene Einträge", c -> c.getStatus() == Candidate.Status.OFFEN
                && c.getRecommendation() != Recommendation.NICHTS_ZU_TUN),
        EMPFOHLEN("Übernahme empfohlen", c -> c.getStatus() == Candidate.Status.OFFEN
                && c.getRecommendation() == Recommendation.UEBERNAHME_EMPFOHLEN),
        DISKREPANZ("Diskrepanz / ungeprüft", c -> c.getStatus() == Candidate.Status.OFFEN
                && (c.getRecommendation() == Recommendation.DISKREPANZ || c.getRecommendation() == Recommendation.UNGEPRUEFT)),
        NEU("Nur neue Gebäude", c -> c.getMatchClass() == MatchClass.NEU),
        ABWEICHEND("Nur abweichende Gebäude", c -> c.getMatchClass() == MatchClass.ABWEICHEND),
        KOMPLEX("Nur komplexe Fälle", c -> c.getMatchClass() == MatchClass.KOMPLEX),
        NUR_OSM("Nur OSM (ohne ALKIS)", c -> c.getMatchClass() == MatchClass.NUR_OSM),
        ERLEDIGT("Erledigte Einträge", c -> c.getStatus() != Candidate.Status.OFFEN),
        ALLE("Alle Einträge (auch identische)", c -> true);

        private final String label;
        private final Predicate<Candidate> predicate;

        Filter(String label, Predicate<Candidate> predicate) {
            this.label = label;
            this.predicate = predicate;
        }

        boolean test(Candidate c) {
            return predicate.test(c);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String[] COLUMNS = {"Empfehlung", "Klasse", "Gebäude", "Luftbild", "IoU", "Status"};

    private List<Candidate> all = Collections.emptyList();
    private List<Candidate> visible = Collections.emptyList();
    private Filter filter = Filter.OFFEN;

    void setCandidates(List<Candidate> candidates) {
        this.all = candidates != null ? candidates : Collections.emptyList();
        refresh();
    }

    void setFilter(Filter filter) {
        this.filter = filter;
        refresh();
    }

    Filter getFilter() {
        return filter;
    }

    /** Wendet den Filter erneut an (z. B. nach einer Entscheidung). */
    void refresh() {
        List<Candidate> v = new ArrayList<>();
        for (Candidate c : all) {
            if (filter.test(c)) {
                v.add(c);
            }
        }
        visible = v;
        fireTableDataChanged();
    }

    List<Candidate> getVisible() {
        return visible;
    }

    Candidate get(int row) {
        return row >= 0 && row < visible.size() ? visible.get(row) : null;
    }

    int indexOf(Candidate c) {
        return visible.indexOf(c);
    }

    @Override
    public int getRowCount() {
        return visible.size();
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
    public Object getValueAt(int row, int column) {
        Candidate c = visible.get(row);
        switch (column) {
        case 0:
            return c.getRecommendation();
        case 1:
            return c.getMatchClass().getLabel();
        case 2:
            return c.getTitle();
        case 3:
            OrthoResult o = c.getOrtho();
            return o != null && o.isValid() ? String.format(Locale.GERMAN, "%.0f %%", o.getScore() * 100) : "–";
        case 4:
            double iou = c.getMatch().getIou();
            return Double.isNaN(iou) ? "–" : String.format(Locale.GERMAN, "%.2f", iou);
        default:
            return c.getStatus().toString();
        }
    }
}
