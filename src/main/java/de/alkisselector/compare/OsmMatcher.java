// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.paint.relations.Multipolygon;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.source.CrsTransformer;

/**
 * Sammelt OSM-Gebäude und ordnet ALKIS-Gebäude ihnen zu.
 */
public final class OsmMatcher {

    private OsmMatcher() {
        // Hilfsklasse
    }

    /**
     * Sammelt alle OSM-Gebäude (geschlossene Wege und Multipolygone mit {@code building=*}) im Bereich.
     * Muss mit Lesesperre auf dem Datensatz oder im EDT aufgerufen werden.
     * @param ds Datensatz
     * @param crs Arbeits-Koordinatensystem
     * @param bounds Suchbereich
     * @return Gebäude mit Geometrie im Arbeits-CRS
     */
    public static List<OsmBuilding> collect(DataSet ds, CrsTransformer crs, Bounds bounds) {
        List<OsmBuilding> result = new ArrayList<>();
        BBox bbox = new BBox(bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon(), bounds.getMaxLat());
        for (Way w : ds.searchWays(bbox)) {
            if (isBuilding(w) && w.isClosed() && w.getNodesCount() >= 4 && w.isUsable()) {
                Geometry g = wayPolygon(w.getNodes(), crs);
                if (g != null) {
                    result.add(new OsmBuilding(w, g));
                }
            }
        }
        for (Relation r : ds.searchRelations(bbox)) {
            if (isBuilding(r) && r.isMultipolygon() && r.isUsable() && !r.isIncomplete()) {
                Geometry g = multipolygon(r, crs);
                if (g != null && !g.isEmpty()) {
                    result.add(new OsmBuilding(r, g));
                }
            }
        }
        return result;
    }

    /**
     * @param p OSM-Objekt
     * @return ob das Objekt ein Gebäude ist ({@code building=*} außer {@code no})
     */
    public static boolean isBuilding(OsmPrimitive p) {
        String b = p.get("building");
        return b != null && !"no".equals(b);
    }

    private static Geometry wayPolygon(List<Node> nodes, CrsTransformer crs) {
        LinearRing ring = ring(nodes, crs);
        return ring == null ? null : GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(ring));
    }

    private static LinearRing ring(List<Node> nodes, CrsTransformer crs) {
        List<Coordinate> coords = new ArrayList<>(nodes.size() + 1);
        for (Node n : nodes) {
            if (!n.isLatLonKnown()) {
                return null;
            }
            EastNorth en = crs.toProjected(n);
            coords.add(new Coordinate(en.east(), en.north()));
        }
        if (coords.size() < 4) {
            return null;
        }
        if (!coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(new Coordinate(coords.get(0)));
        }
        return GeometryComparator.FACTORY.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private static Geometry multipolygon(Relation r, CrsTransformer crs) {
        try {
            Multipolygon mp = new Multipolygon(r);
            List<Polygon> polys = new ArrayList<>();
            for (Multipolygon.PolyData outer : mp.getOuterPolygons()) {
                LinearRing shell = ring(outer.getNodes(), crs);
                if (shell == null) {
                    continue;
                }
                List<LinearRing> holes = new ArrayList<>();
                for (Multipolygon.PolyData inner : outer.getInners()) {
                    LinearRing h = ring(inner.getNodes(), crs);
                    if (h != null) {
                        holes.add(h);
                    }
                }
                polys.add(GeometryComparator.FACTORY.createPolygon(shell, holes.toArray(new LinearRing[0])));
            }
            if (polys.isEmpty()) {
                return null;
            }
            return GeometryComparator.clean(GeometryComparator.FACTORY.createMultiPolygon(polys.toArray(new Polygon[0])));
        } catch (RuntimeException e) {
            Logging.debug(e);
            return null;
        }
    }

    /**
     * Ordnet ALKIS-Gebäude den OSM-Gebäuden zu.
     * @param alkis Geometrien der zu prüfenden ALKIS-Gebäude
     * @param osm OSM-Gebäude
     * @param thresholds Schwellenwerte
     * @return ein {@link MatchResult} je ALKIS-Gebäude (gleiche Reihenfolge)
     */
    public static List<MatchResult> classify(List<Geometry> alkis, List<OsmBuilding> osm, Thresholds thresholds) {
        STRtree index = new STRtree();
        for (OsmBuilding o : osm) {
            index.insert(o.getGeometry().getEnvelopeInternal(), o);
        }
        List<List<OsmBuilding>> partners = new ArrayList<>(alkis.size());
        Map<OsmBuilding, Integer> partnerCount = new IdentityHashMap<>();
        for (Geometry a : alkis) {
            List<OsmBuilding> p = new ArrayList<>();
            for (Object hit : index.query(a.getEnvelopeInternal())) {
                OsmBuilding o = (OsmBuilding) hit;
                if (GeometryComparator.overlapOfSmaller(a, o.getGeometry()) >= thresholds.partnerOverlap) {
                    p.add(o);
                    partnerCount.merge(o, 1, Integer::sum);
                }
            }
            partners.add(p);
        }
        List<MatchResult> result = new ArrayList<>(alkis.size());
        for (int i = 0; i < alkis.size(); i++) {
            result.add(classifyOne(alkis.get(i), partners.get(i), partnerCount, thresholds));
        }
        return result;
    }

    private static MatchResult classifyOne(Geometry a, List<OsmBuilding> p, Map<OsmBuilding, Integer> partnerCount,
            Thresholds t) {
        if (p.isEmpty()) {
            return new MatchResult(MatchClass.NEU, Collections.emptyList(), Double.NaN, Double.NaN, null);
        }
        if (p.size() > 1) {
            return new MatchResult(MatchClass.KOMPLEX, p, Double.NaN, Double.NaN,
                    "Überlappt " + p.size() + " OSM-Gebäude");
        }
        OsmBuilding o = p.get(0);
        double iou = GeometryComparator.iou(a, o.getGeometry());
        double hd = GeometryComparator.hausdorff(a, o.getGeometry());
        if (!o.isWay()) {
            MatchClass c = iou >= t.identicalIou && hd <= t.identicalHausdorff ? MatchClass.IDENTISCH : MatchClass.KOMPLEX;
            return new MatchResult(c, p, iou, hd, c == MatchClass.KOMPLEX ? "OSM-Gebäude ist ein Multipolygon" : null);
        }
        if (partnerCount.getOrDefault(o, 0) > 1) {
            return new MatchResult(MatchClass.KOMPLEX, p, iou, hd,
                    "OSM-Gebäude umfasst " + partnerCount.get(o) + " ALKIS-Gebäude");
        }
        if (iou >= t.identicalIou && hd <= t.identicalHausdorff) {
            return new MatchResult(MatchClass.IDENTISCH, p, iou, hd, null);
        }
        if (iou >= t.deviatingMinIou) {
            return new MatchResult(MatchClass.ABWEICHEND, p, iou, hd, null);
        }
        return new MatchResult(MatchClass.KOMPLEX, p, iou, hd, "Geringe Übereinstimmung mit dem OSM-Gebäude");
    }

    /**
     * Ermittelt OSM-Gebäude ohne ALKIS-Gegenstück, die vollständig im untersuchten Bereich liegen.
     * @param allAlkis Geometrien aller geladenen ALKIS-Objekte (auch ausgeschlossene)
     * @param osm OSM-Gebäude
     * @param area untersuchter Bereich im Arbeits-CRS
     * @return OSM-Gebäude ohne Gegenstück
     */
    public static List<OsmBuilding> findOsmOnly(List<Geometry> allAlkis, List<OsmBuilding> osm, Envelope area) {
        STRtree index = new STRtree();
        for (Geometry a : allAlkis) {
            index.insert(a.getEnvelopeInternal(), a);
        }
        List<OsmBuilding> result = new ArrayList<>();
        for (OsmBuilding o : osm) {
            if (!area.contains(o.getGeometry().getEnvelopeInternal())) {
                continue;
            }
            boolean matched = false;
            for (Object hit : index.query(o.getGeometry().getEnvelopeInternal())) {
                if (GeometryComparator.overlapOfSmaller((Geometry) hit, o.getGeometry()) >= 0.1) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                result.add(o);
            }
        }
        return result;
    }

    /**
     * Schwellenwerte der Zuordnung.
     */
    public static final class Thresholds {
        final double identicalIou;
        final double identicalHausdorff;
        final double partnerOverlap;
        final double deviatingMinIou;

        /**
         * @param identicalIou IoU für „identisch“
         * @param identicalHausdorff maximale Hausdorff-Distanz (m) für „identisch“
         * @param partnerOverlap Überdeckung der kleineren Fläche für „Partner“
         * @param deviatingMinIou minimale IoU für „abweichend“
         */
        public Thresholds(double identicalIou, double identicalHausdorff, double partnerOverlap, double deviatingMinIou) {
            this.identicalIou = identicalIou;
            this.identicalHausdorff = identicalHausdorff;
            this.partnerOverlap = partnerOverlap;
            this.deviatingMinIou = deviatingMinIou;
        }
    }
}
