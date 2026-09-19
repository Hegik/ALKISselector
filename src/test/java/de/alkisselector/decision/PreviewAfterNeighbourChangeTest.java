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
 * Fall aus der Praxis: Ein OSM-Haus wird zuerst ersetzt (leicht versetzt), danach folgt das angebaute,
 * neue Vordach. Die Vorschau des Vordachs muss den veränderten Nachbarn berücksichtigen und genau der
 * Geometrie entsprechen, die beim Übernehmen entsteht.
 */
class PreviewAfterNeighbourChangeTest {

    private static final double E = 405300;
    private static final double N = 5757300;
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

    private Candidate candidate(String id, double[] ring, MatchResult m, String building) {
        AlkisBuilding b = new AlkisBuilding(id, List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())),
                Map.of("funktion", building));
        Geometry g = GeometryComparator.toGeometry(b);
        List<LatLon> ll = new ArrayList<>();
        for (int i = 0; i < ring.length; i += 2) {
            ll.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        Candidate c = new Candidate(b, g, List.of(ll), m);
        c.getTags().add(new TagProposal("building", "roof", "funktion=" + building));
        return c;
    }

    private static double[] rect(double x0, double y0, double x1, double y1) {
        return new double[] {E + x0, N + y0, E + x1, N + y0, E + x1, N + y1, E + x0, N + y1, E + x0, N + y0};
    }

    @Test
    void roofPreviewFollowsTheReplacedHouse() throws Exception {
        ds = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Haus mit Vordach", null));
        Node h0 = node(0, 0);
        Node h1 = node(10, 0);
        Node h2 = node(10, 8);
        Node h3 = node(0, 8);
        Way house = new Way();
        house.setNodes(List.of(h0, h1, h2, h3, h0));
        house.put("building", "house");
        ds.addPrimitive(house);
        AnalysisSession session = new AnalysisSession(DefaultProfiles.nrw(), crs, ds);

        // ALKIS: Ostwand des Hauses 0,3 m weiter westlich (x = 9,7), das Vordach grenzt dort an.
        // Vor dem Ersetzen ragt das OSM-Haus 0,3 m in das Vordach hinein → es würde bei x = 10 abgeschnitten.
        Candidate houseC = candidate("HAUS", rect(0, 0, 9.7, 8), new MatchResult(MatchClass.ABWEICHEND,
                List.of(new OsmBuilding(house, GeometryComparator.toGeometry(new AlkisBuilding("x",
                        List.of(new AlkisBuilding.Polygon(rect(0, 0, 10, 8), Collections.emptyList())), Map.of())))),
                0.96, 0.3, null), "Wohnhaus");
        houseC.getTags().clear();
        Candidate roof = candidate("DACH", rect(9.7, 2, 13, 6), new MatchResult(MatchClass.NEU,
                Collections.emptyList(), Double.NaN, Double.NaN, null), "Überdachung");

        // Stand bei „Ausschnitt analysieren“: das Vordach wird an das ALTE OSM-Haus (Ostwand x = 10) angepasst
        roof.setFit(ApplyAction.computeFit(roof, ds, crs), crs);
        ChangePreview stale = ChangePreview.of(roof, crs);

        // Haus ersetzen → Ostwand liegt jetzt bei x = 9,7
        assertNotNull(new ApplyAction(session).apply(houseC));

        // Vorschau neu (wie beim Auswählen im Dialog) und Übernahme vergleichen
        roof.setFit(ApplyAction.computeFit(roof, ds, crs), crs);
        ChangePreview fresh = ChangePreview.of(roof, crs);
        assertNotNull(new ApplyAction(session).apply(roof));
        Way created = (Way) ds.getSelected().iterator().next();

        List<LatLon> applied = new ArrayList<>();
        created.getNodes().forEach(n -> applied.add(n.getCoor()));
        assertTrue(sameShape(fresh.getNewOutlines().get(0), applied),
                "Vorschau " + fresh.getNewOutlines().get(0) + " weicht von der Übernahme " + applied + " ab");
        assertFalse(sameShape(stale.getNewOutlines().get(0), applied),
                "Testfall ungeeignet: die alte Vorschau stimmt zufällig auch");
        // das Vordach hängt an der neuen Hauswand
        assertEquals(2, created.getNodes().stream().distinct().filter(house.getNodes()::contains).count());
    }

    /** Gleiche Punktmenge (Reihenfolge egal, 1 cm Toleranz). */
    private static boolean sameShape(List<LatLon> a, List<LatLon> b) {
        List<LatLon> x = new ArrayList<>(new java.util.LinkedHashSet<>(a));
        List<LatLon> y = new ArrayList<>(new java.util.LinkedHashSet<>(b));
        if (x.size() != y.size()) {
            return false;
        }
        for (LatLon p : x) {
            if (y.stream().noneMatch(q -> q.greatCircleDistance(p) < 0.01)) {
                return false;
            }
        }
        return true;
    }
}
