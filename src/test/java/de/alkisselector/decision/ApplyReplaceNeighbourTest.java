// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
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
import de.alkisselector.config.TagProposal;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Geometrie ersetzen bei Reihenhäusern: Das linke Haus (A) teilt eine Wand mit dem rechten Haus (B)
 * und hat einen Eingang mit Fußweg. Die ALKIS-Geometrie von A ist etwas breiter und würde B überlappen.
 */
class ApplyReplaceNeighbourTest {

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

    @Test
    void replacementKeepsSharedWallAndFootwayWithoutOverlap() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Reihenhäuser", null));
        Node a0 = node(0, 0);
        Node entrance = node(5, 0);
        entrance.put("entrance", "main");
        Node w0 = node(10, 0);   // gemeinsame Wand A/B
        Node w1 = node(10, 8);
        Node a3 = node(0, 8);
        Node b1 = node(18, 0);
        Node b2 = node(18, 8);
        Way houseA = way("building", "house", a0, entrance, w0, w1, a3, a0);
        Way houseB = way("building", "house", w0, b1, b2, w1, w0);
        Node street = node(5, -10);
        Way footway = way("highway", "footway", entrance, street);

        // ALKIS: Haus A 10,4 m breit und leicht nach Norden versetzt → würde B um 0,4 m überlappen
        double[] ring = {E + 0.1, N + 0.2, E + 10.4, N + 0.2, E + 10.4, N + 8.2, E + 0.1, N + 8.2, E + 0.1, N + 0.2};
        AlkisBuilding alkis = new AlkisBuilding("DENW-TEST-A",
                List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())), Map.of("funktion", "Wohnhaus"));
        Geometry g = GeometryComparator.toGeometry(alkis);
        List<LatLon> ll = new ArrayList<>();
        for (int i = 0; i < ring.length; i += 2) {
            ll.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        OsmBuilding partner = new OsmBuilding(houseA, polygon(houseA));
        Candidate c = new Candidate(alkis, g, List.of(ll),
                new MatchResult(MatchClass.ABWEICHEND, List.of(partner), 0.9, 0.45, null));
        TagProposal t = new TagProposal("building", "house", "funktion=Wohnhaus");
        t.setExistingValue("house");
        c.getTags().add(t);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);

        long idA = houseA.getUniqueId();
        List<Node> nodesBefore = houseA.getNodes();
        Command cmd = new ApplyAction(session).apply(c);
        assertNotNull(cmd);

        // gleiche ID, Tags erhalten
        assertEquals(idA, houseA.getUniqueId());
        assertEquals("house", houseA.get("building"));
        // gemeinsame Wand bleibt gemeinsam
        assertTrue(houseA.getNodes().contains(w0) && houseA.getNodes().contains(w1), "Wandknoten fehlen: " + houseA.getNodes());
        assertTrue(houseB.getNodes().contains(w0) && houseB.getNodes().contains(w1));
        // keine Überlappung zwischen den Häusern
        double ov = GeometryComparator.intersectionArea(polygon(houseA), polygon(houseB));
        assertTrue(ov < 0.01, "Überlappung " + ov);
        // Eingang mit Fußweg bleibt verbunden und unverändert
        assertTrue(houseA.getNodes().contains(entrance), "Eingang nicht mehr Teil des Gebäudes");
        assertTrue(footway.getNodes().contains(entrance));
        assertFalse(entrance.isDeleted());
        assertEquals(crs.toLatLon(E + 5, N).lat(), entrance.lat(), 1e-9);
        // die übrigen Ecken liegen auf der ALKIS-Geometrie
        EastNorth nw = null;
        for (Node n : houseA.getNodes()) {
            EastNorth en = crs.toProjected(n);
            if (en.east() - E < 1 && en.north() - N > 7) {
                nw = en;
            }
        }
        assertNotNull(nw, "Nordwestecke fehlt");
        assertEquals(E + 0.1, nw.east(), 0.01);
        assertEquals(N + 8.2, nw.north(), 0.01);

        // ein Undo stellt den alten Zustand wieder her
        assertEquals(cmd, UndoRedoHandler.getInstance().getLastCommand());
        UndoRedoHandler.getInstance().undo();
        assertEquals(nodesBefore, houseA.getNodes());
        assertEquals(crs.toLatLon(E, N + 8).lat(), a3.lat(), 1e-9);
        assertFalse(a0.isDeleted());
    }

    private Geometry polygon(Way w) {
        double[] xy = new double[w.getNodesCount() * 2];
        for (int i = 0; i < w.getNodesCount(); i++) {
            EastNorth en = crs.toProjected(w.getNode(i));
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        return GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(xy)));
    }
}
