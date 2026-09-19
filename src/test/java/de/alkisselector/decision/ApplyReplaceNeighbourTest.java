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
        // Eingang mit Fußweg bleibt verbunden; der Fußweg wird bis zur neuen Südwand verlängert, damit die
        // Wand keinen Knick bekommt. Die Südwand läuft von der ALKIS-Ecke (0,1|0,2) zur festen gemeinsamen
        // Ecke mit Haus B (10|0), der Eingang liegt genau auf dieser Geraden.
        assertTrue(houseA.getNodes().contains(entrance), "Eingang nicht mehr Teil des Gebäudes");
        assertTrue(footway.getNodes().contains(entrance));
        assertFalse(entrance.isDeleted());
        EastNorth ent = crs.toProjected(entrance);
        assertEquals(E + 5, ent.east(), 0.01);
        assertEquals(N + 0.2 - 0.2 * (4.9 / 9.9), ent.north(), 0.01);
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

    @Test
    void previewArrowsMatchTheActualNodeMovements() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Vorschau", null));
        // freistehendes OSM-Haus mit 5 Knoten (einer davon überzählig auf der Südkante)
        Node a0 = node(0, 0);
        Node a1 = node(4, 0.1);
        Node a2 = node(10, 0);
        Node a3 = node(10, 8);
        Node a4 = node(0, 8);
        Way house = way("building", "house", a0, a1, a2, a3, a4, a0);
        // ALKIS: 1 m nach Osten und 0,5 m nach Norden versetzt, 4 Ecken
        double[] ring = {E + 1, N + 0.5, E + 11, N + 0.5, E + 11, N + 8.5, E + 1, N + 8.5, E + 1, N + 0.5};
        AlkisBuilding alkis = new AlkisBuilding("DENW-TEST-B",
                List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())), Map.of("funktion", "Wohnhaus"));
        Geometry g = GeometryComparator.toGeometry(alkis);
        List<LatLon> ll = new ArrayList<>();
        for (int i = 0; i < ring.length; i += 2) {
            ll.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        Candidate c = new Candidate(alkis, g, List.of(ll), new MatchResult(MatchClass.ABWEICHEND,
                List.of(new OsmBuilding(house, polygon(house))), 0.8, 1.1, null));
        c.setFit(new NeighbourFitter(0.5, true).fit(g, List.of(), NeighbourWays.keepNodes(house, crs)), List.of());
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);

        ChangePreview preview = ChangePreview.of(c, session);
        java.util.Map<Node, LatLon> before = new java.util.HashMap<>();
        house.getNodes().forEach(n -> before.put(n, n.getCoor()));
        assertNotNull(new ApplyAction(session).apply(c));

        // jede Vorschau-Verschiebung entspricht einem tatsächlich verschobenen Knoten
        int matched = 0;
        for (LatLon[] m : preview.getMoves()) {
            for (Map.Entry<Node, LatLon> e : before.entrySet()) {
                Node n = e.getKey();
                if (!n.isDeleted() && e.getValue().greatCircleDistance(m[0]) < 0.001 && n.greatCircleDistance(m[1]) < 0.01) {
                    matched++;
                }
            }
        }
        assertEquals(4, preview.getMoves().size(), "vier Ecken werden verschoben");
        assertEquals(preview.getMoves().size(), matched, "Vorschau und Übernahme verschieben unterschiedlich");
        // der überzählige Knoten wird gelöscht – genau wie angezeigt
        assertEquals(1, preview.getDeleted().size());
        long deleted = before.keySet().stream().filter(Node::isDeleted).count();
        assertEquals(1, deleted);
        assertTrue(a1.isDeleted(), "der Knoten auf der Südkante sollte entfallen");
        assertEquals(before.get(a1).lat(), preview.getDeleted().get(0).lat(), 1e-9);
        assertTrue(preview.getCreated().isEmpty());
        assertEquals(1, preview.getOldOutlines().size());
    }

    @Test
    void annexGetsNewNodesWhileOldCornersStayNearby() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Anbau", null));
        Node a0 = node(0, 0);
        Node a1 = node(10, 0);
        Node a2 = node(10, 8);
        Node a3 = node(0, 8);
        Way house = way("building", "house", a0, a1, a2, a3, a0);
        // ALKIS: Haus 0,2 m schmaler plus Anbau an der Westseite (die Anbau-Ecken stehen in der
        // Eckpunktliste VOR den Hausecken – eine reihenfolgebasierte Zuordnung würde die alten Ecken verschleppen)
        double[] ring = {E - 2, N + 2, E + 0.2, N + 2, E + 0.2, N, E + 10, N, E + 10, N + 8, E + 0.2, N + 8,
            E + 0.2, N + 6, E - 2, N + 6, E - 2, N + 2};
        AlkisBuilding alkis = new AlkisBuilding("DENW-TEST-C",
                List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())), Map.of("funktion", "Wohnhaus"));
        Geometry g = GeometryComparator.toGeometry(alkis);
        Candidate c = new Candidate(alkis, g, List.of(), new MatchResult(MatchClass.ABWEICHEND,
                List.of(new OsmBuilding(house, polygon(house))), 0.8, 2.0, null));
        c.setFit(new NeighbourFitter(0.5, true).fit(g, List.of(), NeighbourWays.keepNodes(house, crs)), List.of());

        ChangePreview preview = ChangePreview.of(c, new AnalysisSession(DefaultProfiles.nrw(), crs, ds));
        assertEquals(4, preview.getCreated().size(), "vier neue Knoten für den Anbau");
        assertTrue(preview.getDeleted().isEmpty());
        for (LatLon[] m : preview.getMoves()) {
            assertTrue(m[0].greatCircleDistance(m[1]) < 0.3, "alte Ecke wandert zu weit: " + m[0].greatCircleDistance(m[1]));
        }
        assertNotNull(new ApplyAction(new AnalysisSession(DefaultProfiles.nrw(), crs, ds)).apply(c));
        // alte Ecken bleiben die Ecken des Hauses (Historie bleibt an der richtigen Stelle)
        assertEquals(E + 0.2, crs.toProjected(a0).east(), 0.01);
        assertEquals(N, crs.toProjected(a0).north(), 0.01);
        assertEquals(E + 0.2, crs.toProjected(a3).east(), 0.01);
        assertEquals(N + 8, crs.toProjected(a3).north(), 0.01);
        assertEquals(9, house.getNodesCount());
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
