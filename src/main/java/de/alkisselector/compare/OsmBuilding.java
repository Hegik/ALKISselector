// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Momentaufnahme eines OSM-Gebäudes (Weg oder Multipolygon) mit Geometrie im metrischen
 * Arbeitskoordinatensystem.
 */
public final class OsmBuilding {

    private final OsmPrimitive primitive;
    private final Geometry geometry;

    /**
     * @param primitive OSM-Objekt
     * @param geometry Geometrie im Arbeits-CRS
     */
    public OsmBuilding(OsmPrimitive primitive, Geometry geometry) {
        this.primitive = primitive;
        this.geometry = geometry;
    }

    public OsmPrimitive getPrimitive() {
        return primitive;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    /** @return ob es sich um einen einfachen geschlossenen Weg handelt */
    public boolean isWay() {
        return primitive instanceof org.openstreetmap.josm.data.osm.Way;
    }

    @Override
    public String toString() {
        return primitive.getDisplayType() + " " + primitive.getUniqueId();
    }
}
