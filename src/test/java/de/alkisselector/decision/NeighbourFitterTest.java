// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import de.alkisselector.compare.GeometryComparator;

/**
 * Szenario aus der Praxis: An ein vorhandenes OSM-Haus grenzt eine ALKIS-Überdachung. Das OSM-Haus
 * weicht leicht von ALKIS ab – die neue Überdachung darf es weder überlappen noch einen Spalt lassen.
 */
class NeighbourFitterTest {

    private static final NeighbourFitter FITTER = new NeighbourFitter(0.5, true);

    /** OSM-Haus 10 × 8 m; Knoten h0..h3 bzw. mit Zwischenknoten. */
    private static NeighbourFitter.NeighbourWay house(double... xy) {
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < xy.length / 2; i++) {
            nodes.add("h" + i);
        }
        return new NeighbourFitter.NeighbourWay("haus", nodes, xy);
    }

    private static final double[] HOUSE = {0, 0, 10, 0, 10, 8, 0, 8};

    private static Geometry rect(double x0, double y0, double x1, double y1) {
        return GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(
                new double[] {x0, y0, x1, y0, x1, y1, x0, y1, x0, y0}));
    }

    private static List<NeighbourFitter.Vertex> outer(NeighbourFitter.Result r) {
        return r.getPolygons().get(0).get(0);
    }

    private static List<Object> nodes(NeighbourFitter.Result r) {
        List<Object> l = new ArrayList<>();
        outer(r).forEach(v -> l.add(v.getNode()));
        return l;
    }

    @Test
    void overlappingRoofIsClippedAndSharesTheHouseBoundary() {
        // OSM-Haus reicht 0,4 m in die ALKIS-Überdachung hinein
        NeighbourFitter.Result r = FITTER.fit(rect(9.6, 0, 14, 5), List.of(house(HOUSE)));
        assertEquals(0.4 * 5, r.getClippedArea(), 0.05);
        assertTrue(r.getRemainingOverlap() < 0.01, "Restüberlappung " + r.getRemainingOverlap());
        assertFalse(r.isConflict());
        assertEquals(4 * 5, r.getGeometry().getArea(), 0.05);
        // Ecke unten nutzt den Hausknoten, Ecke oben wird in die Hauskante eingefügt
        assertTrue(nodes(r).contains("h1"), "gemeinsamer Knoten fehlt: " + nodes(r));
        assertTrue(outer(r).stream().anyMatch(v -> v.getGlueWay() != null
                && Math.abs(v.getX() - 10) < 1e-6 && Math.abs(v.getY() - 5) < 1e-6), "kein eingefügter Punkt auf der Hauskante");
        assertTrue(r.getSharedPoints() >= 2);
    }

    @Test
    void smallGapIsClosed() {
        // ALKIS-Überdachung beginnt 0,3 m neben dem OSM-Haus
        NeighbourFitter.Result r = FITTER.fit(rect(10.3, 0, 14, 5), List.of(house(HOUSE)));
        assertEquals(0, r.getClippedArea(), 1e-9);
        assertEquals(10, r.getGeometry().getEnvelopeInternal().getMinX(), 1e-6, "Spalt wurde nicht geschlossen");
        assertTrue(nodes(r).contains("h1"));
    }

    @Test
    void sharedEdgeTakesOverIntermediateNodes() {
        // Hauskante rechts hat einen Zwischenknoten bei y = 2,5
        NeighbourFitter.Result r = FITTER.fit(rect(10, 0, 14, 5), List.of(house(0, 0, 10, 0, 10, 2.5, 10, 8, 0, 8)));
        List<Object> n = nodes(r);
        assertTrue(n.contains("h1") && n.contains("h2"), "Zwischenknoten nicht übernommen: " + n);
        assertEquals(20, r.getGeometry().getArea(), 1e-6);
    }

    @Test
    void neighbourEndingAtNewEdgeIsJoined() {
        // T-Stoß: Die Nachbargarage (Knoten g0..g3) endet an der Südkante des neuen Hauses. Ihre oberen
        // Knoten liegen auf (g3) bzw. 0,15 m neben (g2) dieser Kante – die neue Kante muss durch beide verlaufen.
        NeighbourFitter.NeighbourWay garage = new NeighbourFitter.NeighbourWay("garage",
                List.of("g0", "g1", "g2", "g3"), new double[] {3, -4, 6, -4, 6, -0.15, 3, 0});
        NeighbourFitter.Result r = FITTER.fit(rect(0, 0, 10, 8), List.of(garage));
        List<Object> n = nodes(r);
        assertTrue(n.contains("g2"), "Nachbarknoten nicht eingebaut: " + n);
        assertTrue(n.contains("g3"), "Nachbarknoten nicht eingebaut: " + n);
        assertFalse(n.contains("g0") || n.contains("g1"), "entfernte Garagenknoten dürfen nicht eingebaut werden: " + n);
        assertTrue(r.getRemainingOverlap() < 0.01);
        // Reihenfolge entlang der Südkante: Ecke (0,0), g3 bei x=3, g2 bei x=6, Ecke (10,0)
        int i3 = n.indexOf("g3");
        int i2 = n.indexOf("g2");
        assertEquals(1, i2 - i3, "g3 und g2 sollen in Kantenrichtung aufeinander folgen: " + n);
    }

    @Test
    void mostlyCoveredOutlineIsNotClippedButFlagged() {
        NeighbourFitter.Result r = FITTER.fit(rect(5, 1, 11, 6), List.of(house(HOUSE)));
        assertEquals(0, r.getClippedArea(), 1e-9);
        assertTrue(r.isConflict());
        assertTrue(r.getHints().stream().anyMatch(h -> h.contains("nicht automatisch angepasst")), r.getHints().toString());
    }

    @Test
    void clippingCanBeSwitchedOff() {
        // 0,8 m Überlappung – mehr als die Anschlusstoleranz, wird also nicht durch Anschließen behoben
        NeighbourFitter.Result r = new NeighbourFitter(0.5, false).fit(rect(9.2, 0, 14, 5), List.of(house(HOUSE)));
        assertEquals(0, r.getClippedArea(), 1e-9);
        assertTrue(r.isConflict(), "Überlappung muss als Konflikt gemeldet werden");
    }

    @Test
    void connectedNodeNearOutlineIsKept() {
        // Eingang 0,3 m vor der neuen Südwand wird in den Umriss eingebaut
        NeighbourFitter.KeepNode entrance = new NeighbourFitter.KeepNode("eingang", 35, 29.7, "Eingang (entrance=main)");
        NeighbourFitter.Result r = FITTER.fit(rect(30, 30, 40, 38), List.of(), List.of(entrance));
        assertTrue(nodes(r).contains("eingang"), nodes(r).toString());
        assertEquals(1, r.getKeptConnections());
        assertFalse(r.isConflict());
    }

    @Test
    void connectedNodeFarFromOutlineIsReportedAsLost() {
        NeighbourFitter.KeepNode path = new NeighbourFitter.KeepNode("weg", 35, 28, "Verbindung zu highway=footway");
        NeighbourFitter.Result r = FITTER.fit(rect(30, 30, 40, 38), List.of(), List.of(path));
        assertTrue(r.isConflict());
        assertEquals(List.of("Verbindung zu highway=footway"), r.getLostConnections());
        assertFalse(nodes(r).contains("weg"));
    }

    @Test
    void freeStandingBuildingIsUnchanged() {
        Geometry g = rect(30, 30, 40, 38);
        NeighbourFitter.Result r = FITTER.fit(g, List.of(house(HOUSE)));
        assertFalse(r.isModified());
        assertEquals(Arrays.asList(null, null, null, null), nodes(r));
        assertEquals(g.getArea(), r.getGeometry().getArea(), 1e-9);
    }
}
