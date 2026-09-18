// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;

import de.alkisselector.source.AlkisBuilding;

/**
 * Geometrische Kennzahlen für den Vergleich zweier Flächen (JTS).
 */
public final class GeometryComparator {

    /** Gemeinsame GeometryFactory. */
    public static final GeometryFactory FACTORY = new GeometryFactory();

    private GeometryComparator() {
        // Hilfsklasse
    }

    /**
     * @param b ALKIS-Gebäude
     * @return JTS-Geometrie (Polygon oder MultiPolygon), bereinigt falls ungültig
     */
    public static Geometry toGeometry(AlkisBuilding b) {
        List<Polygon> polys = new ArrayList<>();
        for (AlkisBuilding.Polygon p : b.getPolygons()) {
            LinearRing shell = ring(p.getOuter());
            LinearRing[] holes = p.getHoles().stream().map(GeometryComparator::ring).toArray(LinearRing[]::new);
            polys.add(FACTORY.createPolygon(shell, holes));
        }
        Geometry g = polys.size() == 1 ? polys.get(0) : FACTORY.createMultiPolygon(polys.toArray(new Polygon[0]));
        return clean(g);
    }

    /**
     * @param xy Ring als {@code x0,y0,x1,y1,...} (geschlossen)
     * @return JTS-Ring
     */
    public static LinearRing ring(double[] xy) {
        Coordinate[] c = new Coordinate[xy.length / 2];
        for (int i = 0; i < c.length; i++) {
            c[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        return FACTORY.createLinearRing(c);
    }

    /**
     * @param g Geometrie
     * @return gültige Geometrie (per {@code buffer(0)} repariert, falls nötig)
     */
    public static Geometry clean(Geometry g) {
        return g.isValid() ? g : g.buffer(0);
    }

    /**
     * Intersection over Union: Verhältnis von Schnittfläche zu Vereinigungsfläche (0..1).
     * @param a erste Fläche
     * @param b zweite Fläche
     * @return IoU
     */
    public static double iou(Geometry a, Geometry b) {
        double inter = intersectionArea(a, b);
        double union = a.getArea() + b.getArea() - inter;
        return union > 0 ? inter / union : 0;
    }

    /**
     * Anteil der Schnittfläche an der kleineren der beiden Flächen (0..1).
     * @param a erste Fläche
     * @param b zweite Fläche
     * @return Überdeckungsgrad
     */
    public static double overlapOfSmaller(Geometry a, Geometry b) {
        double min = Math.min(a.getArea(), b.getArea());
        return min > 0 ? intersectionArea(a, b) / min : 0;
    }

    /**
     * @param a erste Fläche
     * @param b zweite Fläche
     * @return Fläche des Schnitts
     */
    public static double intersectionArea(Geometry a, Geometry b) {
        if (!a.getEnvelopeInternal().intersects(b.getEnvelopeInternal())) {
            return 0;
        }
        try {
            return a.intersection(b).getArea();
        } catch (TopologyException e) {
            return clean(a.buffer(0)).intersection(clean(b.buffer(0))).getArea();
        }
    }

    /**
     * Hausdorff-Distanz der Umrisse (maximale Abweichung der Kanten, in Metern).
     * @param a erste Fläche
     * @param b zweite Fläche
     * @return Distanz
     */
    public static double hausdorff(Geometry a, Geometry b) {
        return DiscreteHausdorffDistance.distance(a.getBoundary(), b.getBoundary(), 0.1);
    }
}
