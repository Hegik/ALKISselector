// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

import de.alkisselector.TestSupport;
import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.MatchResult;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Am Gebäude angeschlossene Linien (Zaun, Mauer, Fußweg): Beim Angleichen an ALKIS wird die Linie bis
 * zur neuen Fassade verlängert bzw. gekürzt, statt die Wand am Anschlusspunkt abzuknicken.
 */
class ConnectedLineTest {

    private static final double E = 405100;
    private static final double N = 5757100;

    private final CrsTransformer crs = new CrsTransformer("EPSG:25832");
    private DataSet ds;

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    private Node node(double x, double y) {
        Node n = new Node(crs.toLatLon(E + x, N + y));
        ds.addPrimitive(n);
        return n;
    }

    private Way way(String key, String value, Node... nodes) {
        Way w = new Way();
        w.setNodes(List.of(nodes));
        w.put(key, value);
        ds.addPrimitive(w);
        return w;
    }

    private Candidate candidate(double[] ring, Way osm) {
        AlkisBuilding b = new AlkisBuilding("HAUS", List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())),
                Map.of("funktion", "Wohnhaus"));
        Geometry g = GeometryComparator.toGeometry(b);
        double[] xy = new double[osm.getNodesCount() * 2];
        for (int i = 0; i < osm.getNodesCount(); i++) {
            EastNorth en = crs.toProjected(osm.getNode(i));
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        Geometry og = GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(xy)));
        return new Candidate(b, g, List.of(), new MatchResult(MatchClass.ABWEICHEND,
                List.of(new OsmBuilding(osm, og)), 0.94, 0.3, null));
    }

    private static double[] rect(double x0, double y0, double x1, double y1) {
        return new double[] {E + x0, N + y0, E + x1, N + y0, E + x1, N + y1, E + x0, N + y1, E + x0, N + y0};
    }

    @Test
    void fenceIsExtendedToTheAlkisFacade() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Haus mit Zaun", null));
        Node h0 = node(0, 0);
        Node h1 = node(10, 0);
        Node f0 = node(10, 4);   // Zaun beginnt an der Ostwand
        Node h2 = node(10, 8);
        Node h3 = node(0, 8);
        Way house = way("building", "house", h0, h1, f0, h2, h3, h0);
        Node f1 = node(15, 4.5);
        Way fence = way("barrier", "fence", f0, f1);
        // ALKIS: Ostwand 0,3 m weiter östlich
        Candidate c = candidate(rect(0, 0, 10.3, 8), house);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);
        c.setFit(ApplyAction.computeFit(c, session), crs);
        ChangePreview preview = ChangePreview.of(c, session);

        assertNotNull(new ApplyAction(session).apply(c));

        // Zaun bleibt verbunden, sein Ende liegt auf der neuen Fassade – in Verlängerung der Zaunlinie
        assertTrue(house.getNodes().contains(f0) && fence.getNodes().contains(f0));
        EastNorth p = crs.toProjected(f0);
        assertEquals(10.3, p.east() - E, 0.01);
        assertEquals(4.5 - 0.5 * 4.7 / 5, p.north() - N, 0.01);
        // die Ostwand ist gerade: alle ihre Knoten liegen auf x = 10,3
        for (Node n : house.getNodes()) {
            double x = crs.toProjected(n).east() - E;
            assertTrue(Math.abs(x) < 0.01 || Math.abs(x - 10.3) < 0.01, "Knick in der Wand bei x = " + x);
        }
        // die Vorschau zeigt die Verschiebung des Zaunendes
        LatLon target = f0.getCoor();
        assertTrue(preview.getMoves().stream().anyMatch(m -> m[1].greatCircleDistance(target) < 0.01),
                "Vorschau zeigt die Verlängerung des Zauns nicht");
    }

    @Test
    void fenceIsShortenedWhenTheFacadeMovesTowardsIt() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Haus mit Mauer", null));
        Node h0 = node(0, 0);
        Node h1 = node(10, 0);
        Node f0 = node(10, 4);
        Node h2 = node(10, 8);
        Node h3 = node(0, 8);
        Way house = way("building", "house", h0, h1, f0, h2, h3, h0);
        Node f1 = node(14, 4);
        Node f2 = node(14, 12);
        way("barrier", "wall", f0, f1, f2);
        // ALKIS: Ostwand 0,4 m weiter westlich
        Candidate c = candidate(rect(0, 0, 9.6, 8), house);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);
        assertNotNull(new ApplyAction(session).apply(c));
        EastNorth p = crs.toProjected(f0);
        assertEquals(9.6, p.east() - E, 0.01);
        assertEquals(4, p.north() - N, 0.01);
        assertEquals(6, house.getNodesCount(), "Zaunknoten bleibt Teil der Wand");
    }

    @Test
    void lineStartingInsideTheNewBuildingKeepsItsNode() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Haus mit Weg", null));
        Node h0 = node(0, 0);
        Node h1 = node(10, 0);
        Node f0 = node(10, 4);
        Node h2 = node(10, 8);
        Node h3 = node(0, 8);
        Way house = way("building", "house", h0, h1, f0, h2, h3, h0);
        Node f1 = node(10.2, 4.1);   // Linie kommt aus dem künftigen Gebäude
        way("highway", "footway", f0, f1, node(20, 4));
        Candidate c = candidate(rect(0, 0, 10.4, 8), house);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);
        assertNotNull(new ApplyAction(session).apply(c));
        // wie bisher: fester Knoten, in den Umriss eingebaut
        assertEquals(10, crs.toProjected(f0).east() - E, 0.01);
        assertTrue(house.getNodes().contains(f0));
    }
}
