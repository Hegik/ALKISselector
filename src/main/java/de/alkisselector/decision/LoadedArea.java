// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;

import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.source.CrsTransformer;

/**
 * Bereich, für den OSM-Daten heruntergeladen wurden, im Arbeits-CRS. Nur ALKIS-Gebäude, die
 * vollständig darin liegen, dürfen verglichen werden: Außerhalb fehlen die OSM-Gebäude und
 * -Nachbarn, das Gebäude würde fälschlich als „neu“ eingestuft.
 */
public final class LoadedArea {

    /** Stützpunkte je Rechteckseite, damit Breitenkreise im projizierten CRS als Kurve folgen. */
    private static final int STEPS = 16;

    private LoadedArea() {
        // Hilfsklasse
    }

    /**
     * @param bounds heruntergeladene Bereiche ({@code DataSet#getDataSourceBounds()})
     * @param crs Arbeits-CRS
     * @return Vereinigung der Bereiche; leer, wenn keine Bereiche heruntergeladen wurden
     */
    public static Geometry of(List<Bounds> bounds, CrsTransformer crs) {
        List<Geometry> parts = new ArrayList<>();
        for (Bounds b : bounds) {
            if (!b.isCollapsed()) {
                parts.add(polygon(b, crs));
            }
        }
        if (parts.isEmpty()) {
            return GeometryComparator.FACTORY.createPolygon();
        }
        return UnaryUnionOp.union(parts);
    }

    private static Geometry polygon(Bounds b, CrsTransformer crs) {
        LatLon[] corners = {new LatLon(b.getMinLat(), b.getMinLon()), new LatLon(b.getMinLat(), b.getMaxLon()),
            new LatLon(b.getMaxLat(), b.getMaxLon()), new LatLon(b.getMaxLat(), b.getMinLon())};
        Coordinate[] ring = new Coordinate[4 * STEPS + 1];
        int n = 0;
        for (int side = 0; side < 4; side++) {
            LatLon from = corners[side];
            LatLon to = corners[(side + 1) % 4];
            for (int i = 0; i < STEPS; i++) {
                double t = (double) i / STEPS;
                EastNorth en = crs.toProjected(new LatLon(from.lat() + t * (to.lat() - from.lat()),
                        from.lon() + t * (to.lon() - from.lon())));
                ring[n++] = new Coordinate(en.east(), en.north());
            }
        }
        ring[n] = new Coordinate(ring[0]);
        return GeometryComparator.FACTORY.createPolygon(ring);
    }
}
