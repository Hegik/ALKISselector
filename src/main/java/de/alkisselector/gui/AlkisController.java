// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.util.HashSet;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;

import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ProfileStore;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.Candidate;

/**
 * Verbindet Analyse, Vorschau-Layer und Review-Dialog. Hält die aktuelle Analyse-Sitzung.
 */
public final class AlkisController implements LayerChangeListener {

    private static final AlkisController INSTANCE = new AlkisController();

    private AnalysisSession session;
    private AlkisPreviewLayer layer;
    private ReviewDialog dialog;
    private boolean listening;
    private AlkisReviewMapMode reviewMode;
    /** Profile mit ungeprüfter Lizenz, deren Warnung in dieser Sitzung bestätigt wurde. */
    private final Set<String> confirmedUnverified = new HashSet<>();

    private AlkisController() {
        // Singleton
    }

    /** @return die globale Instanz */
    public static AlkisController getInstance() {
        return INSTANCE;
    }

    /** @param dialog Review-Dialog des aktuellen Kartenfensters (oder {@code null}) */
    public void setDialog(ReviewDialog dialog) {
        this.dialog = dialog;
    }

    /** @return Review-Dialog des aktuellen Kartenfensters oder {@code null} */
    public ReviewDialog getDialog() {
        return dialog;
    }

    /** @param reviewMode Prüfmodus, in den nach einer Ausschnitt-Analyse gewechselt wird */
    public void setReviewMode(AlkisReviewMapMode reviewMode) {
        this.reviewMode = reviewMode;
    }

    /** @return aktuelle Sitzung oder {@code null} */
    public AnalysisSession getSession() {
        return session;
    }

    /** @return Vorschau-Layer oder {@code null} */
    public AlkisPreviewLayer getLayer() {
        return layer;
    }

    /**
     * Startet die Analyse des aktuellen Kartenausschnitts.
     */
    public void analyzeView() {
        DataSet ds = requireEditDataSet();
        if (ds == null) {
            return;
        }
        Bounds view = MainApplication.getMap().mapView.getRealBounds();
        // Gebäude außerhalb der heruntergeladenen Bereiche überspringt die Analyse (LoadedArea).
        if (ds.getDataSourceBounds().stream().noneMatch(b -> b.intersects(view))) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "<html>Für den sichtbaren Ausschnitt sind keine OSM-Daten heruntergeladen.<br>"
                            + "Übernommen werden nur ALKIS-Gebäude, die vollständig im heruntergeladenen Bereich liegen.<br>"
                            + "Bitte zuerst OSM-Daten für diesen Bereich herunterladen.</html>",
                    "ALKISselector", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ServiceProfile p = ProfileStore.getInstance().getActiveProfile();
        if (!confirmUnverified(p)) {
            return;
        }
        MainApplication.worker.submit(new AnalysisTask(p, view, ds));
    }

    /**
     * Warnt einmal je Sitzung und Profil, wenn der Gebäude-Dienst nicht zu einem mitgelieferten,
     * rechtlich geprüften Profil gehört.
     * @param p aktives Profil
     * @return ob die Analyse fortgesetzt werden soll
     */
    private boolean confirmUnverified(ServiceProfile p) {
        if (DefaultProfiles.isVerified(p) || confirmedUnverified.contains(p.getName())) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(),
                "<html><b>Die Nutzung dieser Daten für OpenStreetMap ist unter Umständen nicht erlaubt.</b><br><br>"
                        + "Das Profil „" + escape(p.getName()) + "“ nutzt keinen der mitgelieferten Dienste, deren "
                        + "Lizenz geprüft ist.<br>Übernehmen Sie nur Daten, deren Lizenz mit der ODbL vereinbar ist "
                        + "oder für die<br>eine ausdrückliche Erlaubnis für OpenStreetMap vorliegt "
                        + "(siehe wiki.openstreetmap.org/wiki/Contributors).<br><br>Trotzdem fortfahren?</html>",
                "ALKISselector – Lizenz ungeprüft", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return false;
        }
        confirmedUnverified.add(p.getName());
        return true;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Startet die Analyse des Gebäudes an einem Punkt (Einzelklick-Modus).
     * @param point Klickpunkt
     */
    public void analyzePoint(LatLon point) {
        DataSet ds = requireEditDataSet();
        if (ds == null) {
            return;
        }
        ServiceProfile p = ProfileStore.getInstance().getActiveProfile();
        if (!confirmUnverified(p)) {
            return;
        }
        MainApplication.worker.submit(new AnalysisTask(p, point, ds));
    }

    private static DataSet requireEditDataSet() {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null || !MainApplication.isDisplayingMapView()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    "Bitte zuerst OSM-Daten für das Gebiet herunterladen oder eine Datenebene öffnen.",
                    "ALKISselector", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return ds;
    }

    /**
     * Zeigt das Ergebnis einer Analyse an (wird im EDT aufgerufen).
     * @param newSession Ergebnis
     * @param merge bei {@code true} (Einzelklick) werden die Kandidaten zur bestehenden Sitzung hinzugefügt
     */
    void showSession(AnalysisSession newSession, boolean merge) {
        Candidate select = null;
        if (merge && session != null && session.getDataSet() == newSession.getDataSet()
                && session.getProfile().getName().equals(newSession.getProfile().getName())) {
            Set<String> known = new HashSet<>();
            session.getCandidates().forEach(c -> known.add(c.getId()));
            for (Candidate c : newSession.getCandidates()) {
                if (known.add(c.getId())) {
                    session.getCandidates().add(0, c);
                    if (select == null) {
                        select = c;
                    }
                } else if (select == null) {
                    select = session.getCandidates().stream().filter(x -> x.getId().equals(c.getId())).findFirst().orElse(null);
                }
            }
        } else {
            session = newSession;
            if (merge && !newSession.getCandidates().isEmpty()) {
                select = newSession.getCandidates().get(0);
            }
        }
        ensureLayer();
        layer.invalidate();
        if (dialog != null) {
            dialog.showSession(session, select);
        }
        if (!merge) {
            notifyUser(summary(session));
            enterReviewMode();
        }
    }

    /** Wechselt nach einer Ausschnitt-Analyse in den Prüfmodus, damit die Tasten sofort gelten. */
    private void enterReviewMode() {
        org.openstreetmap.josm.gui.MapFrame map = MainApplication.getMap();
        if (reviewMode != null && map != null && map.mapMode != reviewMode && !session.getCandidates().isEmpty()) {
            map.selectMapMode(reviewMode);
        }
    }

    private void ensureLayer() {
        if (layer == null || !MainApplication.getLayerManager().containsLayer(layer)) {
            layer = new AlkisPreviewLayer();
            MainApplication.getLayerManager().addLayer(layer, false);
            if (!listening) {
                MainApplication.getLayerManager().addLayerChangeListener(this);
                listening = true;
            }
        }
    }

    /** Vorschau neu zeichnen (nach Änderungen an Kandidaten oder Auswahl). */
    public void repaint() {
        if (layer != null) {
            layer.invalidate();
        }
    }

    /** @return die im Review-Dialog sichtbaren Kandidaten (ohne Dialog: alle außer identischen) */
    public java.util.List<Candidate> getVisibleCandidates() {
        if (dialog != null) {
            return dialog.getVisibleCandidates();
        }
        if (session == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<Candidate> l = new java.util.ArrayList<>();
        for (Candidate c : session.getCandidates()) {
            if (c.getMatchClass() != de.alkisselector.compare.MatchClass.IDENTISCH) {
                l.add(c);
            }
        }
        return l;
    }

    /** Ansicht des ausgewählten Kandidaten in der Karte. */
    public enum ViewMode {
        /** Zustand nach der Übernahme (Vorschau) mit bisheriger Geometrie und Verschiebungspfeilen. */
        NEU("NEU – Zustand nach der Übernahme"),
        /** Heutiger Zustand in OSM. */
        ALT("ALT – heutiger Stand in OSM");

        private final String label;

        ViewMode(String label) {
            this.label = label;
        }

        /** @return Beschriftung für Karte und Dialog */
        public String getLabel() {
            return label;
        }
    }

    private ViewMode viewMode = ViewMode.NEU;

    /** @return aktuelle Ansicht */
    public ViewMode getViewMode() {
        return viewMode;
    }

    /** Wechselt zwischen alter und neuer Ansicht. */
    public void toggleViewMode() {
        setViewMode(viewMode == ViewMode.NEU ? ViewMode.ALT : ViewMode.NEU);
    }

    /** @param mode neue Ansicht */
    public void setViewMode(ViewMode mode) {
        viewMode = mode;
        repaint();
        if (dialog != null) {
            dialog.viewModeChanged();
        }
    }

    private Candidate previewCandidate;
    private Object previewToken;
    private de.alkisselector.decision.ChangePreview preview;

    /**
     * Liefert die Vorschau der Änderungen für einen Kandidaten. Die Anpassung an Nachbargebäude wird
     * dabei mit dem <em>aktuellen</em> Datenstand neu berechnet (wie bei der Übernahme), sobald der
     * Kandidat gewechselt oder die Daten verändert wurden – z. B. nachdem ein Nachbargebäude übernommen
     * wurde. Hinweise und Konfliktstatus des Kandidaten werden dabei aktualisiert.
     * @param c Kandidat
     * @return Vorschau oder {@code null}
     */
    public de.alkisselector.decision.ChangePreview getPreview(Candidate c) {
        if (c == null || session == null || c.getStatus() != Candidate.Status.OFFEN) {
            return null;
        }
        Object token = dataToken();
        if (c != previewCandidate || !token.equals(previewToken)) {
            de.alkisselector.decision.NeighbourFitter.Result fit =
                    de.alkisselector.decision.ApplyAction.computeFit(c, session);
            if (fit != null) {
                c.setFit(fit, session.getCrs());
            }
            preview = de.alkisselector.decision.ChangePreview.of(c, session);
            boolean dataChanged = c == previewCandidate;
            previewCandidate = c;
            previewToken = token;
            if (dataChanged && dialog != null) {
                // Daten wurden bei ausgewähltem Eintrag verändert: Hinweise im Dialog nachziehen
                javax.swing.SwingUtilities.invokeLater(dialog::viewModeChanged);
            }
        }
        return preview;
    }

    /** Merkmal des Datenstands: jede Bearbeitung in JOSM verändert den Undo-/Redo-Stapel. */
    private static Object dataToken() {
        org.openstreetmap.josm.data.UndoRedoHandler u = org.openstreetmap.josm.data.UndoRedoHandler.getInstance();
        return java.util.Arrays.asList(System.identityHashCode(u.getLastCommand()), u.getUndoCommands().size(),
                u.getRedoCommands().size());
    }

    /** @return aktuell im Review-Dialog ausgewählter Kandidat oder {@code null} */
    public Candidate getSelectedCandidate() {
        return dialog != null ? dialog.getSelectedCandidate() : null;
    }

    /**
     * Zeigt eine kurze Meldung unten rechts im Kartenfenster.
     * @param text Meldung
     */
    public void notifyUser(String text) {
        new Notification(text).setIcon(JOptionPane.INFORMATION_MESSAGE).setDuration(Notification.TIME_DEFAULT).show();
    }

    private static String summary(AnalysisSession s) {
        long total = s.getCandidates().size();
        long open = s.getCandidates().stream().filter(c -> c.getRecommendation() != null && c.getRecommendation().isApplicable()).count();
        StringBuilder sb = new StringBuilder("<html>ALKIS-Abgleich abgeschlossen: ").append(total).append(" Einträge, ")
                .append(open).append(" davon übernehmbar.");
        if (s.getExcludedCount() > 0) {
            sb.append("<br>").append(s.getExcludedCount()).append(" Bauteile/unterirdische Objekte ausgeblendet.");
        }
        if (s.getOutsideCount() > 0) {
            sb.append("<br>").append(s.getOutsideCount())
                    .append(" Gebäude übersprungen, weil sie nicht vollständig im heruntergeladenen OSM-Bereich liegen.");
        }
        if (s.isTruncated()) {
            sb.append("<br><b>Achtung:</b> Der Dienst hat nicht alle Objekte geliefert – Ausschnitt verkleinern.");
        }
        return sb.append("</html>").toString();
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        // nichts
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        if (session != null && e.getRemovedLayer() instanceof org.openstreetmap.josm.gui.layer.OsmDataLayer
                && ((org.openstreetmap.josm.gui.layer.OsmDataLayer) e.getRemovedLayer()).getDataSet() == session.getDataSet()) {
            // Datenebene der Sitzung wird geschlossen → Sitzung verwerfen (nach dem laufenden Ereignis)
            javax.swing.SwingUtilities.invokeLater(this::clear);
        } else if (e.getRemovedLayer() == layer) {
            layer = null;
        }
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // nichts
    }

    /** Verwirft die aktuelle Sitzung und entfernt die Vorschau. */
    public void clear() {
        session = null;
        if (dialog != null) {
            dialog.showSession(null, null);
        }
        if (layer != null && MainApplication.getLayerManager().containsLayer(layer)) {
            AlkisPreviewLayer l = layer;
            layer = null;
            MainApplication.getLayerManager().removeLayer(l);
        }
    }
}
