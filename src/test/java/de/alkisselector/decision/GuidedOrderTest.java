// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.Bounds;
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
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Geführte Reihenfolge: Neue ALKIS-Objekte werden erst angebaut, wenn die angrenzenden OSM-Gebäude
 * an ALKIS angeglichen sind. Gebäude mit gemeinsamen Ecken werden vollständig gemeinsam angeglichen.
 */
class GuidedOrderTest {

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

    private Way house(Node... nodes) {
        Way w = new Way();
        w.setNodes(List.of(nodes));
        w.put("building", "house");
        ds.addPrimitive(w);
        return w;
    }

    private static double[] rect(double x0, double y0, double x1, double y1) {
        return new double[] {E + x0, N + y0, E + x1, N + y0, E + x1, N + y1, E + x0, N + y1, E + x0, N + y0};
    }

    private static AlkisBuilding alkis(String id, double[] ring, String funktion) {
        return new AlkisBuilding(id, List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())),
                Map.of("funktion", funktion));
    }

    private double x(Node n) {
        return crs.toProjected(n).east() - E;
    }

    private double y(Node n) {
        return crs.toProjected(n).north() - N;
    }

    @Test
    void neighbourIsAlignedBeforeTheAnnexIsAdded() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Haus mit Vordach", null));
        Node h0 = node(0, 0);
        Node h1 = node(10, 0);
        Node h2 = node(10, 8);
        Node h3 = node(0, 8);
        Way house = house(h0, h1, h2, h3, h0);

        // ALKIS: Haus 0,2 m weiter östlich (fast identisch), Vordach grenzt an die Ostwand
        List<AlkisBuilding> buildings = List.of(alkis("HAUS", rect(0.2, 0, 10.2, 8), "Wohnhaus"),
                alkis("DACH", rect(10.2, 2, 13, 6), "Überdachung"));
        ServiceProfile profile = DefaultProfiles.nrw();
        profile.setOrthoWmsUrl("");
        AnalysisSession session = new AnalysisSession(profile, crs, ds);
        Bounds area = new Bounds(crs.toLatLon(E - 20, N - 20));
        area.extend(crs.toLatLon(E + 40, N + 30));
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings())
                .analyze(session, buildings, OsmSnapshot.create(ds, crs, area), null, null);

        Candidate houseC = find(session, "HAUS");
        Candidate roof = find(session, "DACH");
        assertEquals(MatchClass.IDENTISCH, houseC.getMatchClass());
        assertEquals(MatchClass.NEU, roof.getMatchClass());
        assertEquals(List.of(houseC), roof.getPrerequisites(), "das Haus ist Vorbedingung für das Vordach");
        assertEquals(Recommendation.ANGLEICHEN, houseC.getRecommendation());
        assertTrue(houseC.isReplacement());
        assertTrue(session.getCandidates().indexOf(houseC) < session.getCandidates().indexOf(roof),
                "das Haus steht in der Liste vor dem Vordach");

        // Vordach zuerst → abgelehnt
        assertThrows(ApplyAction.ApplyException.class, () -> new ApplyAction(session).apply(roof));

        // Haus angleichen → exakt ALKIS
        assertNotNull(new ApplyAction(session).apply(houseC));
        houseC.setStatus(Candidate.Status.UEBERNOMMEN);
        for (Node n : house.getNodes()) {
            double nx = x(n);
            assertTrue(Math.abs(nx - 0.2) < 0.01 || Math.abs(nx - 10.2) < 0.01, "Hausecke nicht auf ALKIS: " + nx);
        }

        // jetzt wird das Vordach in amtlicher Lage angebaut
        assertTrue(roof.getOpenPrerequisites().isEmpty());
        roof.setFit(ApplyAction.computeFit(roof, session), crs);
        assertNotNull(new ApplyAction(session).apply(roof));
        Way created = (Way) ds.getSelected().iterator().next();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (Node n : created.getNodes()) {
            minX = Math.min(minX, x(n));
            maxX = Math.max(maxX, x(n));
        }
        assertEquals(10.2, minX, 0.01, "Vordach nicht an der ALKIS-Ostwand");
        assertEquals(13, maxX, 0.01);
        // gemeinsame Knoten mit dem Haus, keine Überlappung
        Set<Node> shared = new HashSet<>(created.getNodes());
        shared.retainAll(house.getNodes());
        assertEquals(2, shared.size(), "Vordach teilt die Wandknoten mit dem Haus");
    }

    @Test
    void terracedHousesAreAlignedTogetherWithoutDistortion() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Reihenhäuser", null));
        Node a0 = node(0, 0);
        Node w0 = node(10, 0);   // gemeinsame Wand
        Node w1 = node(10, 8);
        Node a3 = node(0, 8);
        Node b1 = node(18, 0);
        Node b2 = node(18, 8);
        Way houseA = house(a0, w0, w1, a3, a0);
        Way houseB = house(w0, b1, b2, w1, w0);
        // ALKIS: beide Häuser 0,3 m weiter östlich
        Candidate ca = candidate(alkis("A", rect(0.3, 0, 10.3, 8), "Wohnhaus"), houseA);
        Candidate cb = candidate(alkis("B", rect(10.3, 0, 18.3, 8), "Wohnhaus"), houseB);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);
        session.getCandidates().add(ca);
        session.getCandidates().add(cb);

        assertEquals(List.of(ca, cb), ApplyAction.alignmentGroup(ca, session));
        ca.setFit(ApplyAction.computeFit(ca, session), crs);
        assertFalse(ca.getFit().isConflict(), "keine Überlappung mit dem mit angeglichenen Nachbarn");

        ChangePreview preview = ChangePreview.of(ca, session);
        assertEquals(2, preview.getGroupSize());
        assertEquals(2, preview.getNewOutlines().size());
        int undoBefore = UndoRedoHandler.getInstance().getUndoCommands().size();

        ApplyAction action = new ApplyAction(session);
        assertNotNull(action.apply(ca));
        assertEquals(List.of(ca, cb), action.getAppliedGroup());
        assertEquals(undoBefore + 1, UndoRedoHandler.getInstance().getUndoCommands().size(), "ein Undo-Schritt");

        // beide Häuser vollständig auf ALKIS, gemeinsame Wand bleibt gemeinsam
        assertEquals(0.3, x(a0), 0.01);
        assertEquals(10.3, x(w0), 0.01);
        assertEquals(10.3, x(w1), 0.01);
        assertEquals(18.3, x(b1), 0.01);
        assertEquals(18.3, x(b2), 0.01);
        assertEquals(8, y(b2), 0.01);
        assertTrue(houseA.getNodes().contains(w0) && houseB.getNodes().contains(w0));
        assertEquals(5, houseA.getNodesCount());
        assertEquals(5, houseB.getNodesCount());
        // Vorschau entspricht der Übernahme
        for (LatLon[] m : preview.getMoves()) {
            boolean found = false;
            for (Way w : List.of(houseA, houseB)) {
                for (Node n : w.getNodes()) {
                    found |= n.getCoor().greatCircleDistance(m[1]) < 0.01;
                }
            }
            assertTrue(found, "Vorschau-Ziel ohne Knoten: " + m[1]);
        }
        assertEquals(6, preview.getMoves().size(), "alle sechs Ecken werden verschoben");

        // ein Undo nimmt beides zurück
        UndoRedoHandler.getInstance().undo();
        assertEquals(10, x(w0), 0.01);
        assertEquals(18, x(b1), 0.01);
        assertEquals(0, x(a0), 0.01);
    }

    @Test
    void neighbourWithoutCandidateKeepsTheSharedCorner() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Reihenhäuser ohne B", null));
        Node a0 = node(0, 0);
        Node w0 = node(10, 0);
        Node w1 = node(10, 8);
        Node a3 = node(0, 8);
        Node b1 = node(18, 0);
        Node b2 = node(18, 8);
        Way houseA = house(a0, w0, w1, a3, a0);
        house(w0, b1, b2, w1, w0);
        Candidate ca = candidate(alkis("A", rect(0.3, 0, 10.3, 8), "Wohnhaus"), houseA);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);
        session.getCandidates().add(ca);

        assertEquals(List.of(ca), ApplyAction.alignmentGroup(ca, session));
        assertNotNull(new ApplyAction(session).apply(ca));
        // B wird nicht verzerrt: die gemeinsame Wand bleibt, wo sie war
        assertEquals(10, x(w0), 0.01);
        assertEquals(10, x(w1), 0.01);
        assertEquals(18, x(b1), 0.01);
    }

    private Candidate candidate(AlkisBuilding b, Way osm) {
        Geometry g = GeometryComparator.toGeometry(b);
        double[] xy = new double[osm.getNodesCount() * 2];
        for (int i = 0; i < osm.getNodesCount(); i++) {
            EastNorth en = crs.toProjected(osm.getNode(i));
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        Geometry og = GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(xy)));
        List<LatLon> ll = new ArrayList<>();
        double[] ring = b.getPolygons().get(0).getOuter();
        for (int i = 0; i < ring.length; i += 2) {
            ll.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        return new Candidate(b, g, List.of(ll), new MatchResult(MatchClass.ABWEICHEND,
                List.of(new OsmBuilding(osm, og)), 0.94, 0.3, null));
    }

    private static Candidate find(AnalysisSession session, String id) {
        return session.getCandidates().stream().filter(c -> id.equals(c.getId())).findFirst().orElseThrow();
    }
}
