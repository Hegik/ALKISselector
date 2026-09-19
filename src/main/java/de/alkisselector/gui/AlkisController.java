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
        if (!isCovered(ds, view)) {
            int answer = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(),
                    "<html>Für den sichtbaren Ausschnitt sind nicht vollständig OSM-Daten geladen.<br>"
                            + "Gebäude außerhalb der geladenen Daten würden fälschlich als „neu“ eingestuft.<br><br>"
                            + "Trotzdem fortfahren?</html>",
                    "ALKISselector", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        ServiceProfile p = ProfileStore.getInstance().getActiveProfile();
        MainApplication.worker.submit(new AnalysisTask(p, view, ds));
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

    private static boolean isCovered(DataSet ds, Bounds view) {
        if (ds.getDataSourceBounds().isEmpty()) {
            return false;
        }
        LatLon[] corners = {view.getMin(), view.getMax(), new LatLon(view.getMinLat(), view.getMaxLon()),
                new LatLon(view.getMaxLat(), view.getMinLon()), view.getCenter()};
        for (LatLon c : corners) {
            boolean inside = ds.getDataSourceBounds().stream().anyMatch(b -> b.contains(c));
            if (!inside) {
                return false;
            }
        }
        return true;
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

    /**
     * @param c Kandidat
     * @return Vorschau der Änderungen (mit aktuellem Datenstand) oder {@code null}
     */
    public de.alkisselector.decision.ChangePreview getPreview(Candidate c) {
        if (c == null || session == null || c.getStatus() != Candidate.Status.OFFEN) {
            return null;
        }
        return de.alkisselector.decision.ChangePreview.of(c, session.getCrs());
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
