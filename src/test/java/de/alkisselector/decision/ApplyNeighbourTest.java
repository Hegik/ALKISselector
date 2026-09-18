// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.TagProposal;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Übernahme einer ALKIS-Überdachung neben einem vorhandenen OSM-Haus im echten JOSM-Datenmodell.
 */
class ApplyNeighbourTest {

    private static final double E = 405000;
    private static final double N = 5757000;

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @Test
    void roofIsGluedToHouseWithoutOverlapAndUndoable() throws Exception {
        CrsTransformer crs = new CrsTransformer("EPSG:25832");
        DataSet ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Nachbartest", null));

        // vorhandenes OSM-Haus 10 × 8 m
        List<Node> hn = new ArrayList<>();
        for (double[] p : new double[][] {{0, 0}, {10, 0}, {10, 8}, {0, 8}}) {
            Node n = new Node(crs.toLatLon(E + p[0], N + p[1]));
            ds.addPrimitive(n);
            hn.add(n);
        }
        hn.add(hn.get(0));
        Way house = new Way();
        house.setNodes(hn);
        house.put("building", "house");
        ds.addPrimitive(house);

        // ALKIS-Überdachung, die 0,4 m in das OSM-Haus hineinragt
        double[] ring = {E + 9.6, N, E + 14, N, E + 14, N + 5, E + 9.6, N + 5, E + 9.6, N};
        AlkisBuilding roof = new AlkisBuilding("DENW-TEST-ROOF",
                List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())), Map.of("funktion", "Überdachung"));
        Geometry g = GeometryComparator.toGeometry(roof);
        List<List<LatLon>> outline = new ArrayList<>();
        List<LatLon> ll = new ArrayList<>();
        for (int i = 0; i < ring.length; i += 2) {
            ll.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        outline.add(ll);
        Candidate c = new Candidate(roof, g, outline,
                new MatchResult(MatchClass.NEU, Collections.emptyList(), Double.NaN, Double.NaN, null));
        c.getTags().add(new TagProposal("building", "roof", "funktion=Überdachung"));
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);

        int houseNodesBefore = house.getNodesCount();
        Command cmd = new ApplyAction(session).apply(c);
        assertNotNull(cmd);
        Way created = (Way) ds.getSelected().iterator().next();
        assertEquals("roof", created.get("building"));

        // gemeinsamer Knoten an der Hausecke und eingefügter Knoten in der Hauskante
        assertTrue(created.getNodes().contains(hn.get(1)), "Hausecke wird nicht gemeinsam genutzt");
        assertEquals(houseNodesBefore + 1, house.getNodesCount(), "Knoten wurde nicht in die Hauskante eingefügt");
        long shared = created.getNodes().stream().distinct().filter(house.getNodes()::contains).count();
        assertEquals(2, shared, "Überdachung und Haus sollen die gemeinsame Kante teilen");

        // keine Überlappung
        Geometry houseGeom = polygon(crs, house);
        Geometry roofGeom = polygon(crs, created);
        assertTrue(GeometryComparator.intersectionArea(houseGeom, roofGeom) < 0.01, "Überlappung vorhanden");
        assertEquals(20, roofGeom.getArea(), 0.05);

        // ein Undo stellt das Haus wieder her
        assertEquals(cmd, UndoRedoHandler.getInstance().getLastCommand());
        UndoRedoHandler.getInstance().undo();
        assertEquals(houseNodesBefore, house.getNodesCount());
        assertTrue(created.isDeleted() || !ds.containsWay(created));
    }

    private static Geometry polygon(CrsTransformer crs, Way w) {
        double[] xy = new double[w.getNodesCount() * 2];
        for (int i = 0; i < w.getNodesCount(); i++) {
            EastNorth en = crs.toProjected(w.getNode(i));
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        return GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(xy));
    }
}
