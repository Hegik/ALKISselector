// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.Utils;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.config.AlkisSettings;
import de.alkisselector.config.TagProposal;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.ApplyAction;
import de.alkisselector.decision.Candidate;
import de.alkisselector.decision.DecisionLog;
import de.alkisselector.decision.Recommendation;
import de.alkisselector.ortho.OrthoResult;

/**
 * Seitenfenster für das hybride Entscheidungsmodell: Das Plugin empfiehlt, der Nutzer entscheidet
 * per Tastendruck.
 * <ul>
 * <li>Enter – übernehmen (nur bei Empfehlung „Übernahme empfohlen“)</li>
 * <li>Umschalt+Enter – trotz Diskrepanz/ohne Luftbildprüfung übernehmen</li>
 * <li>Entf – verwerfen</li>
 * <li>Leertaste – überspringen</li>
 * </ul>
 */
public final class ReviewDialog extends ToggleDialog {

    private static final String KEYS_HELP = "<small>Enter = übernehmen · Umschalt+Enter = trotz Diskrepanz übernehmen · "
            + "Entf = verwerfen · Leertaste = überspringen</small>";

    private final CandidateTableModel listModel = new CandidateTableModel();
    private final JTable list = new JTable(listModel);
    private final JComboBox<CandidateTableModel.Filter> filter = new JComboBox<>(CandidateTableModel.Filter.values());
    private final JLabel header = new JLabel(" ");
    private final JLabel details = new JLabel();
    private final TagTableModel tagModel = new TagTableModel();
    private final JTable tagTable = new JTable(tagModel);

    private final DecisionAction acceptAction = new DecisionAction("Übernehmen", "ok", Decision.ACCEPT);
    private final DecisionAction forceAction = new DecisionAction("Trotzdem übernehmen", "ok", Decision.FORCE);
    private final DecisionAction rejectAction = new DecisionAction("Verwerfen", "cancel", Decision.REJECT);
    private final DecisionAction skipAction = new DecisionAction("Überspringen", "dialogs/next", Decision.SKIP);
    private final DecisionAction undoAction = new DecisionAction("Zurücksetzen", "undo", Decision.UNDO);

    private AnalysisSession session;

    /** Erzeugt das Seitenfenster. */
    public ReviewDialog() {
        super("ALKIS-Abgleich", "alkisselector", "ALKIS-Kandidaten prüfen und übernehmen",
                Shortcut.registerShortcut("subwindow:alkisselector", "Fenster: ALKIS-Abgleich", KeyEvent.VK_K, Shortcut.ALT_SHIFT),
                300);
        buildList();
        buildTagTable();
        JPanel top = new JPanel(new BorderLayout(4, 2));
        top.add(filter, BorderLayout.NORTH);
        top.add(new JScrollPane(list), BorderLayout.CENTER);
        top.add(header, BorderLayout.SOUTH);

        details.setVerticalAlignment(SwingConstants.TOP);
        details.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel bottom = new JPanel(new BorderLayout(2, 2));
        bottom.add(details, BorderLayout.NORTH);
        JScrollPane tagScroll = new JScrollPane(tagTable);
        tagScroll.setPreferredSize(new Dimension(200, 120));
        bottom.add(tagScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(bottom));
        split.setResizeWeight(0.45);
        split.setBorder(null);

        createLayout(split, false, Arrays.asList(
                new SideButton(acceptAction), new SideButton(forceAction), new SideButton(rejectAction),
                new SideButton(skipAction), new SideButton(undoAction)));
        filter.addActionListener(e -> {
            Candidate current = getSelectedCandidate();
            listModel.setFilter((CandidateTableModel.Filter) filter.getSelectedItem());
            select(current != null && listModel.indexOf(current) >= 0 ? current : listModel.get(0), false);
            AlkisController.getInstance().repaint();
        });
        updateDetails();
    }

    private void buildList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setAutoCreateRowSorter(false);
        list.setFillsViewportHeight(true);
        list.getColumnModel().getColumn(0).setCellRenderer(new RecommendationRenderer());
        list.getColumnModel().getColumn(0).setPreferredWidth(130);
        list.getColumnModel().getColumn(1).setPreferredWidth(70);
        list.getColumnModel().getColumn(2).setPreferredWidth(220);
        list.getColumnModel().getColumn(3).setPreferredWidth(55);
        list.getColumnModel().getColumn(4).setPreferredWidth(40);
        list.getColumnModel().getColumn(5).setPreferredWidth(70);
        list.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelectionChanged(true);
            }
        });
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "alkis-accept", acceptAction);
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "alkis-force", forceAction);
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "alkis-reject", rejectAction);
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "alkis-skip", skipAction);
    }

    private static void bind(JComponent c, KeyStroke ks, String name, AbstractAction action) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, name);
        c.getActionMap().put(name, action);
    }

    private void buildTagTable() {
        tagTable.setFillsViewportHeight(true);
        tagTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        tagTable.getColumnModel().getColumn(0).setMaxWidth(30);
        tagTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        tagTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        tagTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        tagTable.getColumnModel().getColumn(4).setPreferredWidth(160);
        DefaultTableCellRenderer conflictRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                TagProposal t = tagModel.getTag(row);
                if (!isSelected) {
                    c.setBackground(t.isConflict() ? new Color(0xff, 0xe0, 0xc0) : table.getBackground());
                }
                return c;
            }
        };
        for (int i = 1; i < 5; i++) {
            tagTable.getColumnModel().getColumn(i).setCellRenderer(conflictRenderer);
        }
    }

    // ------------------------------------------------------------------ Sitzung / Auswahl

    /**
     * Zeigt eine Sitzung an.
     * @param s Sitzung oder {@code null}
     * @param select vorzuwählender Kandidat oder {@code null} (dann der erste)
     */
    public void showSession(AnalysisSession s, Candidate select) {
        this.session = s;
        listModel.setCandidates(s != null ? s.getCandidates() : null);
        if (s != null) {
            // unfurlDialog baut das Seitenpanel neu auf (showDialog allein macht den Dialog nicht sichtbar)
            unfurlDialog();
        }
        if (select != null && listModel.indexOf(select) < 0) {
            filter.setSelectedItem(CandidateTableModel.Filter.ALLE);
        }
        select(select != null ? select : listModel.get(0), true);
        updateHeader();
    }

    /** @return ausgewählter Kandidat oder {@code null} */
    public Candidate getSelectedCandidate() {
        return listModel.get(list.getSelectedRow());
    }

    /** @return aktuell sichtbare (gefilterte) Kandidaten */
    public List<Candidate> getVisibleCandidates() {
        return listModel.getVisible();
    }

    private void select(Candidate c, boolean zoom) {
        int idx = c != null ? listModel.indexOf(c) : -1;
        if (idx >= 0) {
            list.getSelectionModel().setSelectionInterval(idx, idx);
            list.scrollRectToVisible(list.getCellRect(idx, 0, true));
        } else {
            list.clearSelection();
        }
        onSelectionChanged(zoom);
        list.requestFocusInWindow();
    }

    private void onSelectionChanged(boolean zoom) {
        Candidate c = getSelectedCandidate();
        if (c != null && c.getStatus() == Candidate.Status.OFFEN && c.getShownSince() == 0) {
            c.setShownSince(System.currentTimeMillis());
        }
        updateDetails();
        if (zoom && c != null) {
            zoomTo(c);
        }
        AlkisController.getInstance().repaint();
    }

    private void updateHeader() {
        if (session == null) {
            header.setText("Keine Analyse. Menü „ALKIS“ → „Ausschnitt analysieren“.");
            return;
        }
        long open = session.getCandidates().stream().filter(c -> c.getStatus() == Candidate.Status.OFFEN
                && c.getRecommendation().isApplicable()).count();
        long done = session.getCandidates().stream().filter(c -> c.getStatus() != Candidate.Status.OFFEN).count();
        header.setText("<html><small>" + session.getProfile().getName() + " · " + session.getCandidates().size()
                + " Einträge · " + open + " offen übernehmbar · " + done + " erledigt"
                + (session.getExcludedCount() > 0 ? " · " + session.getExcludedCount() + " Bauteile ausgeblendet" : "")
                + "</small></html>");
    }

    private void updateDetails() {
        Candidate c = getSelectedCandidate();
        if (c == null) {
            details.setText("<html>" + (session == null ? "Noch keine Analyse vorhanden." : "Kein Eintrag ausgewählt.")
                    + "<br><br>" + KEYS_HELP + "</html>");
            tagModel.setTags(null, false);
            setActionsEnabled(null);
            return;
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(c.getTitle())).append("</b><br>");
        if (c.getBuilding() != null) {
            sb.append("ALKIS-ID: ").append(esc(c.getId())).append("<br>");
        }
        sb.append("Vergleich: <b>").append(c.getMatchClass().getLabel()).append("</b> – ")
                .append(c.getMatchClass().getDescription());
        if (!Double.isNaN(c.getMatch().getIou())) {
            sb.append(String.format(Locale.GERMAN, " (IoU %.2f, max. Abweichung %.1f m)", c.getMatch().getIou(),
                    c.getMatch().getHausdorff()));
        }
        sb.append("<br>");
        OrthoResult o = c.getOrtho();
        if (o != null && o.isValid()) {
            sb.append(String.format(Locale.GERMAN, "Luftbild: %.0f %% Kantenübereinstimmung", o.getScore() * 100));
            if (o.getOffset() >= 0.2) {
                sb.append(String.format(Locale.GERMAN, " (Versatz %.1f m)", o.getOffset()));
            }
            OrthoResult oo = c.getOsmOrtho();
            if (oo != null && oo.isValid()) {
                sb.append(String.format(Locale.GERMAN, " · OSM-Geometrie: %.0f %%", oo.getScore() * 100));
            }
            sb.append("<br>");
        }
        Color col = AlkisPreviewLayer.COLORS.getOrDefault(c.getRecommendation(), Color.BLACK);
        sb.append("Empfehlung: <b><font color='").append(hex(col)).append("'>").append(c.getRecommendation().getLabel())
                .append("</font></b>");
        if (c.getStatus() != Candidate.Status.OFFEN) {
            sb.append(" · Status: <b>").append(c.getStatus()).append("</b>");
        }
        if (!c.getHints().isEmpty()) {
            sb.append("<ul style='margin-left:12px'>");
            c.getHints().forEach(h -> sb.append("<li>").append(esc(h)).append("</li>"));
            sb.append("</ul>");
        } else {
            sb.append("<br>");
        }
        sb.append(KEYS_HELP).append("</html>");
        details.setText(sb.toString());
        boolean editable = c.getStatus() == Candidate.Status.OFFEN && c.getRecommendation().isApplicable();
        tagModel.setTags(c.getTags(), editable);
        setActionsEnabled(c);
    }

    private void setActionsEnabled(Candidate c) {
        boolean open = c != null && c.getStatus() != Candidate.Status.UEBERNOMMEN && c.getStatus() != Candidate.Status.VERWORFEN;
        boolean applicable = open && c.getRecommendation().isApplicable();
        // Übernehmen bleibt auch bei Diskrepanz aktiv, damit Enter den Hinweis auf Umschalt+Enter zeigt
        acceptAction.setEnabled(applicable);
        forceAction.setEnabled(applicable && !c.getRecommendation().isDefaultAccept());
        rejectAction.setEnabled(open);
        skipAction.setEnabled(c != null);
        undoAction.setEnabled(c != null && c.getStatus() != Candidate.Status.OFFEN);
    }

    private static void zoomTo(Candidate c) {
        if (!MainApplication.isDisplayingMapView()) {
            return;
        }
        Bounds b = null;
        for (List<LatLon> ring : c.getOutlines()) {
            for (LatLon ll : ring) {
                if (b == null) {
                    b = new Bounds(ll);
                } else {
                    b.extend(ll);
                }
            }
        }
        // bei „abweichend“/„komplex“ auch die OSM-Gebäude einrahmen, damit beide Umrisse vergleichbar sind
        List<org.openstreetmap.josm.data.osm.OsmPrimitive> extra = new java.util.ArrayList<>();
        c.getMatch().getPartners().forEach(p -> extra.add(p.getPrimitive()));
        if (c.getOsmOnly() != null) {
            extra.add(c.getOsmOnly().getPrimitive());
        }
        for (org.openstreetmap.josm.data.osm.OsmPrimitive p : extra) {
            BBox bb = p.getBBox();
            if (p.isUsable() && bb.isValid()) {
                if (b == null) {
                    b = new Bounds(bb.getBottomRight());
                } else {
                    b.extend(bb.getBottomRight());
                }
                b.extend(bb.getTopLeft());
            }
        }
        if (b == null) {
            return;
        }
        MainApplication.getMap().mapView.zoomTo(withMargin(b));
    }

    /**
     * Fügt einen kleinen Rand hinzu, sodass das Gebäude den Ausschnitt möglichst füllt:
     * 15 % der Ausdehnung, mindestens 1,5 m.
     */
    private static Bounds withMargin(Bounds b) {
        double cos = Math.max(0.2, Math.cos(Math.toRadians(b.getCenter().lat())));
        double heightM = (b.getMaxLat() - b.getMinLat()) * 111_000.0;
        double widthM = (b.getMaxLon() - b.getMinLon()) * 111_000.0 * cos;
        double marginM = Math.max(1.5, 0.15 * Math.max(heightM, widthM));
        double dLat = marginM / 111_000.0;
        double dLon = dLat / cos;
        return new Bounds(b.getMinLat() - dLat, b.getMinLon() - dLon, b.getMaxLat() + dLat, b.getMaxLon() + dLon);
    }

    // ------------------------------------------------------------------ Entscheidungen

    private enum Decision { ACCEPT, FORCE, REJECT, SKIP, UNDO }

    private void decide(Decision d) {
        Candidate c = getSelectedCandidate();
        if (c == null || session == null) {
            return;
        }
        if (tagTable.isEditing()) {
            tagTable.getCellEditor().stopCellEditing();
        }
        switch (d) {
        case ACCEPT:
        case FORCE:
            if (!accept(c, d == Decision.FORCE)) {
                return;
            }
            break;
        case REJECT:
            if (c.getStatus() == Candidate.Status.UEBERNOMMEN || c.getStatus() == Candidate.Status.VERWORFEN) {
                return;
            }
            c.setStatus(Candidate.Status.VERWORFEN);
            DecisionLog.log(session, c, Candidate.Status.VERWORFEN);
            break;
        case SKIP:
            if (c.getStatus() == Candidate.Status.OFFEN) {
                c.setStatus(Candidate.Status.UEBERSPRUNGEN);
                DecisionLog.log(session, c, Candidate.Status.UEBERSPRUNGEN);
            }
            break;
        case UNDO:
            reset(c);
            listModel.refresh();
            select(c, false);
            updateHeader();
            return;
        default:
            return;
        }
        advance(c);
    }

    private boolean accept(Candidate c, boolean force) {
        if (c.getStatus() == Candidate.Status.UEBERNOMMEN || c.getStatus() == Candidate.Status.VERWORFEN) {
            return false;
        }
        Recommendation r = c.getRecommendation();
        if (!r.isApplicable()) {
            notify(c.getMatchClass() == MatchClass.KOMPLEX
                    ? "Komplexer Fall – bitte manuell bearbeiten (Entf = verwerfen, Leertaste = überspringen)."
                    : "Für diesen Eintrag gibt es nichts zu übernehmen.");
            return false;
        }
        if (!r.isDefaultAccept() && !force) {
            notify("„" + r.getLabel() + "“ – keine Standardempfehlung. Mit Umschalt+Enter trotzdem übernehmen.");
            return false;
        }
        if (!isDataLayerPresent()) {
            notify("Die Datenebene dieser Analyse ist nicht mehr geöffnet.");
            return false;
        }
        try {
            Command cmd = new ApplyAction(session).apply(c);
            if (cmd == null) {
                return false;
            }
            c.setAppliedCommand(cmd);
            c.setStatus(Candidate.Status.UEBERNOMMEN);
            DecisionLog.log(session, c, Candidate.Status.UEBERNOMMEN);
            return true;
        } catch (ApplyAction.ApplyException e) {
            notify(e.getMessage());
            return false;
        }
    }

    private void reset(Candidate c) {
        if (c.getStatus() == Candidate.Status.UEBERNOMMEN) {
            Command applied = c.getAppliedCommand();
            if (applied != null && UndoRedoHandler.getInstance().getLastCommand() == applied) {
                UndoRedoHandler.getInstance().undo();
            } else {
                notify("Seit der Übernahme wurden weitere Änderungen gemacht – bitte über Bearbeiten → Rückgängig zurücknehmen.");
                return;
            }
            c.setAppliedCommand(null);
        }
        c.setStatus(Candidate.Status.OFFEN);
        c.setShownSince(System.currentTimeMillis());
        DecisionLog.log(session, c, Candidate.Status.OFFEN);
    }

    /** Wählt nach einer Entscheidung den nächsten offenen Eintrag. */
    private void advance(Candidate decided) {
        int oldIdx = list.getSelectedRow();
        List<Candidate> before = listModel.getVisible();
        Candidate next = null;
        for (int i = oldIdx + 1; i < before.size() && next == null; i++) {
            if (before.get(i).getStatus() == Candidate.Status.OFFEN) {
                next = before.get(i);
            }
        }
        for (int i = 0; i < oldIdx && next == null; i++) {
            if (before.get(i).getStatus() == Candidate.Status.OFFEN) {
                next = before.get(i);
            }
        }
        listModel.refresh();
        updateHeader();
        if (next != null) {
            select(next, true);
        } else {
            select(listModel.indexOf(decided) >= 0 ? decided : null, false);
            notify("Alle Einträge dieser Ansicht sind bearbeitet.");
        }
    }

    private boolean isDataLayerPresent() {
        return MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class).stream()
                .anyMatch(l -> l.getDataSet() == session.getDataSet());
    }

    private static void notify(String text) {
        new Notification(text).setIcon(javax.swing.JOptionPane.WARNING_MESSAGE).show();
    }

    private static String esc(String s) {
        return Utils.escapeReservedCharactersHTML(s == null ? "" : s);
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public void destroy() {
        AlkisController.getInstance().setDialog(null);
        super.destroy();
    }

    /** Aktion für eine Entscheidung. */
    private final class DecisionAction extends AbstractAction {
        private final Decision decision;

        DecisionAction(String name, String icon, Decision decision) {
            super(name);
            this.decision = decision;
            new ImageProvider(icon).getResource().attachImageIcon(this, true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            decide(decision);
        }
    }

    /** Färbt die Empfehlungsspalte. */
    private static final class RecommendationRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Recommendation r = (Recommendation) value;
            Component comp = super.getTableCellRendererComponent(table, r != null ? r.getLabel() : "", isSelected, hasFocus,
                    row, column);
            if (!isSelected) {
                Color c = r != null ? AlkisPreviewLayer.COLORS.get(r) : null;
                comp.setBackground(c != null ? blend(c, table.getBackground()) : table.getBackground());
            }
            return comp;
        }

        private static Color blend(Color c, Color bg) {
            return new Color((c.getRed() + 2 * bg.getRed()) / 3, (c.getGreen() + 2 * bg.getGreen()) / 3,
                    (c.getBlue() + 2 * bg.getBlue()) / 3);
        }
    }
}
