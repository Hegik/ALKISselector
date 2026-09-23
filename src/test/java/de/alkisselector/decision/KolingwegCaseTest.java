// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.io.OsmReader;

import de.alkisselector.TestSupport;
import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.GmlBuildingParser;

/**
 * Problemmeldungen aus dem Praxistest (Kolingweg, NRW): Beim Angleichen an ALKIS darf die angepasste
 * Geometrie keine ALKIS-Ecke auslassen und keine Punkte neben dem ALKIS-Umriss erzeugen.
 * Daten: OSM-Stand zum Zeitpunkt der Meldung, ALKIS-Antwort des NRW-WFS für denselben Bereich.
 */
class KolingwegCaseTest {

    /** Toleranz für „liegt auf dem ALKIS-Umriss“ (m). */
    private static final double TOL = 0.02;

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({"osm-kolingweg1.osm, DENW53AL000028wkBL", "osm-kolingweg10.osm, DENW53AL000028vfBL"})
    void fitKeepsAllAlkisCornersAndAddsNoneBeside(String osmFile, String id) throws Exception {
        AnalysisSession session = analyze(osmFile);
        Candidate c = find(session, id);
        NeighbourFitter.Result fit = ApplyAction.computeFit(c, session);
        Geometry fitted = fit != null && fit.getGeometry() != null ? fit.getGeometry() : c.getGeometry();
        System.out.println(id + " ALKIS:     " + c.getGeometry().toText());
        System.out.println(id + " angepasst: " + fitted.toText());
        assertSameCorners(c.getGeometry(), fitted, id);
    }

    /**
     * Nach der Übernahme liegt jedes Gebäude der Angleichungsgruppe exakt auf ALKIS, und Ecken, die in
     * ALKIS gemeinsam sind, sind in OSM gemeinsame Knoten (die Gebäude werden nicht getrennt).
     */
    @ParameterizedTest(name = "{1}")
    @CsvSource({"osm-kolingweg1.osm, DENW53AL000028wkBL", "osm-kolingweg10.osm, DENW53AL000028vfBL"})
    void applyPutsGroupOnAlkisAndConnectsCommonCorners(String osmFile, String id) throws Exception {
        AnalysisSession session = analyze(osmFile);
        Candidate c = find(session, id);
        List<Candidate> group = ApplyAction.alignmentGroup(c, session);
        System.out.println(id + " Gruppe: " + group.stream().map(Candidate::getTitle).collect(Collectors.toList()));
        ApplyAction action = new ApplyAction(session);
        assertNotNull(action.apply(c));
        CrsTransformer crs = session.getCrs();
        for (Candidate m : action.getAppliedGroup()) {
            Way w = (Way) m.getMatch().getPartner().getPrimitive();
            assertSameCorners(m.getGeometry(), polygon(w, crs), m.getTitle());
        }
        // gemeinsame ALKIS-Ecken der Gruppe → gemeinsame Knoten
        List<Candidate> applied = action.getAppliedGroup();
        for (int i = 0; i < applied.size(); i++) {
            for (int j = i + 1; j < applied.size(); j++) {
                Way a = (Way) applied.get(i).getMatch().getPartner().getPrimitive();
                Way b = (Way) applied.get(j).getMatch().getPartner().getPrimitive();
                for (Coordinate p : applied.get(i).getGeometry().getCoordinates()) {
                    boolean common = false;
                    for (Coordinate q : applied.get(j).getGeometry().getCoordinates()) {
                        common |= p.distance(q) < 0.01;
                    }
                    if (common) {
                        Node na = nodeAt(a, p, crs);
                        Node nb = nodeAt(b, p, crs);
                        assertTrue(na != null && na == nb, String.format(Locale.ROOT,
                                "%s / %s: gemeinsame ALKIS-Ecke %.3f %.3f ist kein gemeinsamer Knoten",
                                applied.get(i).getTitle(), applied.get(j).getTitle(), p.x, p.y));
                    }
                }
            }
        }
    }

    /** Die Verschiebungspfeile der Vorschau dürfen sich nicht kreuzen (Meldung „Pfeile über Kreuz“). */
    @ParameterizedTest(name = "{1}")
    @CsvSource({"osm-kolingweg1.osm, DENW53AL000028wkBL", "osm-kolingweg10.osm, DENW53AL000028vfBL"})
    void previewArrowsDoNotCross(String osmFile, String id) throws Exception {
        AnalysisSession session = analyze(osmFile);
        Candidate c = find(session, id);
        List<LatLon[]> moves = ChangePreview.of(c, session).getMoves();
        CrsTransformer crs = session.getCrs();
        List<Geometry> arrows = new ArrayList<>();
        for (LatLon[] m : moves) {
            EastNorth a = crs.toProjected(m[0]);
            EastNorth b = crs.toProjected(m[1]);
            arrows.add(GeometryComparator.FACTORY.createLineString(new Coordinate[] {
                new Coordinate(a.east(), a.north()), new Coordinate(b.east(), b.north())}));
        }
        for (int i = 0; i < arrows.size(); i++) {
            for (int j = i + 1; j < arrows.size(); j++) {
                assertTrue(!arrows.get(i).crosses(arrows.get(j)),
                        id + ": Pfeile kreuzen sich: " + arrows.get(i) + " / " + arrows.get(j));
            }
        }
    }

    private static Geometry polygon(Way w, CrsTransformer crs) {
        Coordinate[] cs = new Coordinate[w.getNodesCount()];
        for (int i = 0; i < cs.length; i++) {
            EastNorth en = crs.toProjected(w.getNode(i));
            cs[i] = new Coordinate(en.east(), en.north());
        }
        return GeometryComparator.FACTORY.createPolygon(cs);
    }

    private static Node nodeAt(Way w, Coordinate p, CrsTransformer crs) {
        for (Node n : w.getNodes()) {
            EastNorth en = crs.toProjected(n);
            if (Math.hypot(en.east() - p.x, en.north() - p.y) < 0.02) {
                return n;
            }
        }
        return null;
    }

    private static Candidate find(AnalysisSession session, String id) {
        Candidate c = session.getCandidates().stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
        assertNotNull(c, "Eintrag " + id);
        return c;
    }

    private AnalysisSession analyze(String osmFile) throws Exception {
        DataSet ds;
        try (InputStream in = getClass().getResourceAsStream("/cases/kolingweg/" + osmFile)) {
            ds = OsmReader.parseDataSet(in, null);
        }
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, osmFile, null));
        ServiceProfile profile = DefaultProfiles.nrw();
        profile.setOrthoWmsUrl("");
        CrsTransformer crs = new CrsTransformer(profile.getCrs());
        List<AlkisBuilding> buildings;
        try (InputStream in = getClass().getResourceAsStream("/cases/kolingweg/alkis.xml")) {
            buildings = new GmlBuildingParser(profile.isSwapAxes()).parse(in, profile.getIdAttribute());
        }
        Bounds loaded = ds.getDataSourceBounds().get(0);
        AnalysisSession session = new AnalysisSession(profile, crs, ds);
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings()).analyze(session, buildings,
                OsmSnapshot.create(ds, crs, loaded), null, LoadedArea.of(ds.getDataSourceBounds(), crs), null);
        return session;
    }

    static void assertSameCorners(Geometry alkis, Geometry fitted, String id) {
        List<String> errors = new ArrayList<>();
        Coordinate[] fc = fitted.getCoordinates();
        for (int i = 1; i < fc.length; i++) {
            if (fc[i].distance(fc[i - 1]) < 1e-3) {
                errors.add(String.format(Locale.ROOT, "doppelter Punkt %.3f %.3f", fc[i].x, fc[i].y));
            }
        }
        Geometry boundary = alkis.getBoundary();
        for (Coordinate p : fc) {
            Point pt = GeometryComparator.FACTORY.createPoint(p);
            double d = boundary.distance(pt);
            if (d > TOL) {
                errors.add(String.format(Locale.ROOT, "Punkt %.3f %.3f liegt %.2f m neben dem ALKIS-Umriss", p.x, p.y, d));
            }
        }
        Geometry fittedBoundary = fitted.getBoundary();
        for (Coordinate a : alkis.getCoordinates()) {
            boolean kept = false;
            for (Coordinate p : fc) {
                if (p.distance(a) <= TOL) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                errors.add(String.format(Locale.ROOT, "ALKIS-Ecke %.3f %.3f fehlt (Abstand zum Umriss %.2f m)", a.x, a.y,
                        fittedBoundary.distance(GeometryComparator.FACTORY.createPoint(a))));
            }
        }
        assertTrue(errors.isEmpty(), id + ": " + String.join("; ", errors));
    }
}
