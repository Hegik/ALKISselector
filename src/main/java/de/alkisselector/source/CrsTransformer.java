// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.Projections;

/**
 * Transformiert zwischen dem Koordinatensystem eines Dienstes (z. B. EPSG:25832) und WGS84.
 * Nutzt die Projektionen von JOSM, damit keine zusätzliche Bibliothek nötig ist.
 */
public final class CrsTransformer {

    private static final Pattern EPSG = Pattern.compile("(?i)EPSG(?::+|/\\d+/)(\\d+)$");

    private final String code;
    private final Projection projection;

    /**
     * @param crs CRS-Kennung, z. B. {@code EPSG:25832}, {@code urn:ogc:def:crs:EPSG::25832} oder
     *            {@code http://www.opengis.net/def/crs/EPSG/0/25832}
     * @throws IllegalArgumentException wenn JOSM das Koordinatensystem nicht kennt
     */
    public CrsTransformer(String crs) {
        this.code = normalize(crs);
        this.projection = Projections.getProjectionByCode(code);
        if (projection == null) {
            throw new IllegalArgumentException("Unbekanntes Koordinatensystem: " + crs);
        }
    }

    /**
     * Normalisiert eine CRS-Kennung auf die Form {@code EPSG:nnnn}.
     * @param crs CRS-Kennung
     * @return normalisierte Kennung
     */
    public static String normalize(String crs) {
        if (crs == null) {
            return null;
        }
        Matcher m = EPSG.matcher(crs.trim());
        return m.find() ? "EPSG:" + m.group(1) : crs.trim().toUpperCase(Locale.ROOT);
    }

    /** @return Kennung in der Form {@code EPSG:nnnn} */
    public String getCode() {
        return code;
    }

    /** @return Kennung als OGC-URN (für WFS 2.0), z. B. {@code urn:ogc:def:crs:EPSG::25832} */
    public String getUrn() {
        return code.startsWith("EPSG:") ? "urn:ogc:def:crs:EPSG::" + code.substring(5) : code;
    }

    /** @return ob das Koordinatensystem in Metern rechnet (Voraussetzung für die Geometrievergleiche) */
    public boolean isMetric() {
        return Math.abs(projection.getMetersPerUnit() - 1.0) < 1e-6;
    }

    /**
     * @param x Ostwert
     * @param y Nordwert
     * @return geografische Koordinate
     */
    public LatLon toLatLon(double x, double y) {
        return projection.eastNorth2latlon(new EastNorth(x, y));
    }

    /**
     * @param ll geografische Koordinate
     * @return Koordinate im Dienst-CRS
     */
    public EastNorth toProjected(ILatLon ll) {
        return projection.latlon2eastNorth(ll);
    }
}
