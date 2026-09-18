// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

import de.alkisselector.TestSupport;

class OsmMatcherTest {

    private static final OsmMatcher.Thresholds T = new OsmMatcher.Thresholds(0.95, 0.5, 0.3, 0.3);

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    static Geometry rect(double x, double y, double w, double h) {
        return GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(
                new double[] {x, y, x + w, y, x + w, y + h, x, y + h, x, y}));
    }

    private static OsmBuilding way(Geometry g) {
        return new OsmBuilding(new Way(), g);
    }

    @Test
    void metrics() {
        Geometry a = rect(0, 0, 10, 10);
        Geometry b = rect(5, 0, 10, 10);
        assertEquals(50.0 / 150.0, GeometryComparator.iou(a, b), 1e-9);
        assertEquals(0.5, GeometryComparator.overlapOfSmaller(a, b), 1e-9);
        assertEquals(5.0, GeometryComparator.hausdorff(a, b), 1e-6);
        assertEquals(1.0, GeometryComparator.iou(a, a), 1e-9);
        assertEquals(0.0, GeometryComparator.iou(a, rect(20, 20, 1, 1)), 1e-9);
    }

    @Test
    void classifiesNewIdenticalDeviating() {
        Geometry alkisNew = rect(0, 0, 10, 10);
        Geometry alkisSame = rect(100, 0, 10, 10);
        Geometry alkisShifted = rect(200, 0, 10, 10);
        List<OsmBuilding> osm = List.of(
                way(rect(100.1, 0, 10, 10)),   // 10 cm Versatz → identisch
                way(rect(201.5, 0, 10, 10)));  // 1,5 m Versatz → abweichend
        List<MatchResult> r = OsmMatcher.classify(List.of(alkisNew, alkisSame, alkisShifted), osm, T);
        assertEquals(MatchClass.NEU, r.get(0).getMatchClass());
        assertEquals(MatchClass.IDENTISCH, r.get(1).getMatchClass());
        assertEquals(MatchClass.ABWEICHEND, r.get(2).getMatchClass());
        assertEquals(osm.get(1), r.get(2).getPartner());
        assertTrue(r.get(2).getHausdorff() > 1.4);
    }

    @Test
    void oneOsmBuildingCoveringSeveralAlkisIsComplex() {
        // OSM hat eine Häuserzeile als ein Gebäude erfasst, ALKIS hat drei Häuser
        List<Geometry> alkis = List.of(rect(0, 0, 8, 10), rect(8, 0, 8, 10), rect(16, 0, 8, 10));
        List<MatchResult> r = OsmMatcher.classify(alkis, List.of(way(rect(0, 0, 24, 10))), T);
        r.forEach(m -> assertEquals(MatchClass.KOMPLEX, m.getMatchClass()));
    }

    @Test
    void alkisOverlappingTwoOsmBuildingsIsComplex() {
        List<MatchResult> r = OsmMatcher.classify(List.of(rect(0, 0, 20, 10)),
                List.of(way(rect(0, 0, 10, 10)), way(rect(10, 0, 10, 10))), T);
        assertEquals(MatchClass.KOMPLEX, r.get(0).getMatchClass());
        assertEquals(2, r.get(0).getPartners().size());
    }

    @Test
    void multipolygonPartnerIsComplexUnlessIdentical() {
        OsmBuilding mp = new OsmBuilding(new Relation(), rect(0, 0, 10, 10));
        assertEquals(MatchClass.IDENTISCH, OsmMatcher.classify(List.of(rect(0, 0, 10, 10)), List.of(mp), T).get(0).getMatchClass());
        assertEquals(MatchClass.KOMPLEX, OsmMatcher.classify(List.of(rect(2, 0, 10, 10)), List.of(mp), T).get(0).getMatchClass());
    }

    @Test
    void smallNeighbourTouchIsNotAPartner() {
        // Nachbargebäude überlappt nur 5 % → ALKIS-Gebäude ist neu
        List<MatchResult> r = OsmMatcher.classify(List.of(rect(0, 0, 10, 10)), List.of(way(rect(9.5, 0, 10, 10))), T);
        assertEquals(MatchClass.NEU, r.get(0).getMatchClass());
    }

    @Test
    void findsOsmOnlyBuildingsInsideArea() {
        OsmBuilding matched = way(rect(0, 0, 10, 10));
        OsmBuilding orphan = way(rect(50, 50, 10, 10));
        OsmBuilding outside = way(rect(500, 500, 10, 10));
        List<OsmBuilding> only = OsmMatcher.findOsmOnly(List.of(rect(0, 0, 10, 10)),
                List.of(matched, orphan, outside), new Envelope(-100, 100, -100, 100));
        assertEquals(List.of(orphan), only);
    }
}
