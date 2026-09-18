// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.JOptionPane;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.config.AlkisSettings;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.CandidateAnalyzer;
import de.alkisselector.decision.OsmSnapshot;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.WfsClient;

/**
 * Hintergrundaufgabe: ALKIS-Gebäude laden, mit OSM vergleichen, Luftbild abgleichen.
 * Läuft entweder für einen Kartenausschnitt (Stapel-Review) oder für einen Klickpunkt.
 */
public class AnalysisTask extends PleaseWaitRunnable {

    /** Suchradius (m) um den Klickpunkt für die WFS-Abfrage im Einzelklick-Modus. */
    private static final double CLICK_RADIUS = 40;

    private final ServiceProfile profile;
    private final Bounds bounds;
    private final LatLon clickPoint;
    private final DataSet dataSet;

    private AnalysisSession session;
    private String error;
    private String info;
    private boolean canceled;

    /**
     * Analyse eines Kartenausschnitts.
     * @param profile Dienstprofil
     * @param bounds Ausschnitt
     * @param dataSet Datensatz
     */
    public AnalysisTask(ServiceProfile profile, Bounds bounds, DataSet dataSet) {
        super("ALKIS-Abgleich", false);
        this.profile = new ServiceProfile(profile);
        this.bounds = bounds;
        this.clickPoint = null;
        this.dataSet = dataSet;
    }

    /**
     * Analyse des Gebäudes am Klickpunkt.
     * @param profile Dienstprofil
     * @param clickPoint geklickter Punkt
     * @param dataSet Datensatz
     */
    public AnalysisTask(ServiceProfile profile, LatLon clickPoint, DataSet dataSet) {
        super("ALKIS-Gebäude abfragen", false);
        this.profile = new ServiceProfile(profile);
        this.bounds = null;
        this.clickPoint = clickPoint;
        this.dataSet = dataSet;
    }

    @Override
    protected void cancel() {
        canceled = true;
    }

    @Override
    protected void realRun() {
        try {
            run0();
        } catch (IOException e) {
            Logging.warn(e);
            error = "Fehler beim Abruf des ALKIS-Dienstes:\n" + e.getMessage();
        } catch (IllegalArgumentException e) {
            Logging.warn(e);
            error = e.getMessage();
        } catch (RuntimeException e) {
            Logging.error(e);
            error = "Unerwarteter Fehler: " + e;
        }
    }

    private void run0() throws IOException {
        CrsTransformer crs = new CrsTransformer(profile.getCrs());
        if (!crs.isMetric()) {
            throw new IllegalArgumentException("Das Koordinatensystem " + crs.getCode()
                    + " ist nicht metrisch. Bitte im Profil ein projiziertes System wie EPSG:25832 eintragen.");
        }
        Envelope env;
        Envelope area;
        if (clickPoint != null) {
            EastNorth c = crs.toProjected(clickPoint);
            env = new Envelope(c.east() - CLICK_RADIUS, c.east() + CLICK_RADIUS, c.north() - CLICK_RADIUS, c.north() + CLICK_RADIUS);
            area = null;
        } else {
            env = envelope(crs, bounds);
            area = env;
            double km2 = env.getArea() / 1e6;
            double max = AlkisSettings.MAX_AREA_KM2.get();
            if (km2 > max) {
                throw new IllegalArgumentException(String.format(Locale.GERMAN,
                        "Der Ausschnitt ist mit %.2f km² zu groß (höchstens %.2f km², einstellbar). Bitte hineinzoomen.", km2, max));
            }
        }

        progressMonitor.setCustomText("OSM-Daten vorbereiten …");
        Envelope osmEnv = new Envelope(env);
        osmEnv.expandBy(50);
        OsmSnapshot osm = OsmSnapshot.create(dataSet, crs, toBounds(crs, osmEnv));

        progressMonitor.setCustomText("ALKIS-Gebäude laden …");
        WfsClient.Result wfs = new WfsClient(profile).fetch(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY(),
                progressMonitor);
        if (canceled) {
            return;
        }
        List<AlkisBuilding> buildings = wfs.getBuildings();
        if (clickPoint != null) {
            buildings = atPoint(buildings, crs.toProjected(clickPoint));
            if (buildings.isEmpty()) {
                info = "An dieser Stelle liefert der ALKIS-Dienst kein Gebäude.";
                return;
            }
        }
        AnalysisSession s = new AnalysisSession(profile, crs, dataSet);
        s.setTruncated(wfs.isTruncated());
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings())
                .analyze(s, buildings, osm, area, progressMonitor);
        if (clickPoint != null && s.getCandidates().isEmpty()) {
            info = "Das ALKIS-Objekt an dieser Stelle ist kein eigenständiges Gebäude (z. B. Bauteil oder unterirdisch).";
            return;
        }
        session = s;
    }

    private static List<AlkisBuilding> atPoint(List<AlkisBuilding> all, EastNorth p) {
        Point pt = GeometryComparator.FACTORY.createPoint(new org.locationtech.jts.geom.Coordinate(p.east(), p.north()));
        List<AlkisBuilding> hit = new ArrayList<>();
        for (AlkisBuilding b : all) {
            try {
                Geometry g = GeometryComparator.toGeometry(b);
                if (g.contains(pt)) {
                    hit.add(b);
                }
            } catch (IllegalArgumentException e) {
                Logging.trace(e);
            }
        }
        return hit;
    }

    /**
     * @param crs Arbeits-CRS
     * @param b Bereich in WGS84
     * @return Hüllrechteck im Arbeits-CRS
     */
    static Envelope envelope(CrsTransformer crs, Bounds b) {
        Envelope e = new Envelope();
        for (LatLon ll : new LatLon[] {b.getMin(), b.getMax(), new LatLon(b.getMinLat(), b.getMaxLon()),
                new LatLon(b.getMaxLat(), b.getMinLon())}) {
            EastNorth en = crs.toProjected(ll);
            e.expandToInclude(en.east(), en.north());
        }
        return e;
    }

    private static Bounds toBounds(CrsTransformer crs, Envelope e) {
        Bounds b = new Bounds(crs.toLatLon(e.getMinX(), e.getMinY()));
        b.extend(crs.toLatLon(e.getMaxX(), e.getMaxY()));
        b.extend(crs.toLatLon(e.getMinX(), e.getMaxY()));
        b.extend(crs.toLatLon(e.getMaxX(), e.getMinY()));
        return b;
    }

    @Override
    protected void finish() {
        if (canceled) {
            return;
        }
        if (error != null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), error, "ALKISselector", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (info != null) {
            AlkisController.getInstance().notifyUser(info);
            return;
        }
        if (session != null) {
            AlkisController.getInstance().showSession(session, clickPoint != null);
        }
    }
}
