// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;

/**
 * Bewertet, wie gut ein Gebäudeumriss zu den Kanten eines Orthophotos passt.
 * <p>
 * Verfahren: Der Umriss wird in regelmäßigen Abständen abgetastet. Ein Abtastpunkt gilt als
 * „gestützt“, wenn innerhalb eines Toleranzbands quer zum Umriss ein Kantenpixel liegt, dessen
 * Gradient annähernd senkrecht zum Umrisssegment steht (also eine parallel verlaufende Kante).
 * Der Score ist der Anteil gestützter Punkte. Weil Dächer in normalen (nicht „true“) Orthophotos
 * je nach Gebäudehöhe versetzt abgebildet werden, wird der Umriss zusätzlich im Bereich
 * ±{@code maxOffset} verschoben und der beste Wert verwendet (grob-fein-Suche).
 * <p>
 * Umrissabschnitte, die an Nachbargebäude grenzen (Brandwände bei Reihen- und Blockbebauung),
 * sind im Luftbild nicht als Kante sichtbar. Sie können über eine Maske von der Bewertung
 * ausgenommen werden.
 * <p>
 * ALKIS erfasst die Außenwände, im Luftbild ist aber die Dachkante sichtbar, die um den
 * Dachüberstand weiter außen liegt. Deshalb wird der Umriss zusätzlich in Stufen von
 * {@value #OVERHANG_STEP} m bis {@code maxOverhang} nach außen gepuffert (eckige Ecken).
 */
public final class EdgeSupportScorer {

    /** Mindest-|cos| zwischen Gradient und Umriss-Normale (≈ 30° Richtungstoleranz). */
    public static final double MIN_COS = Math.cos(Math.toRadians(30));

    /** Schrittweite der Dachüberstands-Suche in Metern. */
    public static final double OVERHANG_STEP = 0.3;

    private static final GeometryFactory GF = new GeometryFactory();

    private final double tolerance;
    private final double maxOffset;
    private final double sampleStep;
    private final double maxOverhang;

    /**
     * Scorer ohne Dachüberstands-Suche.
     * @param tolerance halbe Breite des Toleranzbands in Metern
     * @param maxOffset maximaler Versatz in Metern
     * @param sampleStep Abtastabstand entlang des Umrisses in Metern
     */
    public EdgeSupportScorer(double tolerance, double maxOffset, double sampleStep) {
        this(tolerance, maxOffset, sampleStep, 0);
    }

    /**
     * @param tolerance halbe Breite des Toleranzbands in Metern
     * @param maxOffset maximaler Versatz in Metern
     * @param sampleStep Abtastabstand entlang des Umrisses in Metern
     * @param maxOverhang größter angenommener Dachüberstand in Metern (0 = keine Suche)
     */
    public EdgeSupportScorer(double tolerance, double maxOffset, double sampleStep, double maxOverhang) {
        this.tolerance = tolerance;
        this.maxOffset = Math.max(0, maxOffset);
        this.sampleStep = sampleStep;
        this.maxOverhang = Math.max(0, maxOverhang);
    }

    /**
     * Bewertet eine JTS-Fläche mit Versatz- und Dachüberstands-Suche.
     * @param geometry Polygon/MultiPolygon im Arbeits-CRS
     * @param img georeferenziertes Bild
     * @param edges Kantenbild zu {@code img}
     * @param mask Bereich, in dem Umrisspunkte nicht bewertet werden (z. B. Nachbargebäude), darf {@code null} sein
     * @return bestes Ergebnis (bei Gleichstand das mit dem kleineren Dachüberstand)
     */
    public OrthoResult score(Geometry geometry, GeoImage img, EdgeMap edges, Geometry mask) {
        OrthoResult best = null;
        for (double o = 0; o <= maxOverhang + 1e-9; o += OVERHANG_STEP) {
            OrthoResult r = score(rings(expand(geometry, o)), img, edges, mask);
            if (!r.isValid()) {
                if (best == null) {
                    best = r;
                }
                continue;
            }
            r = r.withOverhang(o);
            if (best == null || !best.isValid() || r.getScore() > best.getScore()) {
                best = r;
            }
        }
        return best;
    }

    /**
     * Puffert eine Fläche um den Dachüberstand nach außen (eckige Ecken, damit Rechtecke Rechtecke bleiben).
     * @param g Fläche
     * @param overhang Abstand in Metern (0 = unverändert)
     * @return gepufferte Fläche
     */
    public static Geometry expand(Geometry g, double overhang) {
        if (overhang <= 0) {
            return g;
        }
        BufferParameters p = new BufferParameters();
        p.setJoinStyle(BufferParameters.JOIN_MITRE);
        p.setMitreLimit(3);
        return BufferOp.bufferOp(g, overhang, p);
    }

    /**
     * Bewertet Ringe ({@code x0,y0,x1,y1,...}) ohne Maske.
     * @param rings Ringe im Arbeits-CRS
     * @param img georeferenziertes Bild
     * @param edges Kantenbild zu {@code img}
     * @return Ergebnis
     */
    public OrthoResult score(List<double[]> rings, GeoImage img, EdgeMap edges) {
        return score(rings, img, edges, null);
    }

    /**
     * Bewertet eine Fläche bei fest vorgegebenem Versatz (ohne Suche). Wird genutzt, um die
     * OSM-Geometrie beim selben Bildversatz wie die ALKIS-Geometrie zu bewerten – sonst würde die
     * Versatzsuche eine Verschiebung der OSM-Geometrie „wegkompensieren“.
     * @param geometry Fläche im Arbeits-CRS
     * @param img georeferenziertes Bild
     * @param edges Kantenbild
     * @param mask Maske (darf {@code null} sein)
     * @param offsetX Versatz Ost (m)
     * @param offsetY Versatz Nord (m)
     * @param overhang Dachüberstand (m)
     * @return Ergebnis
     */
    public OrthoResult scoreAt(Geometry geometry, GeoImage img, EdgeMap edges, Geometry mask, double offsetX, double offsetY,
            double overhang) {
        Samples s = sample(rings(expand(geometry, overhang)), img, mask);
        if (s.count == 0) {
            return OrthoResult.failed(s.masked > 0 ? "Umriss vollständig von Nachbargebäuden umgeben" : "Umriss zu klein");
        }
        int tolPx = Math.max(1, (int) Math.round(tolerance / img.getResolution()));
        double sc = evaluate(s, edges, offsetX / img.getResolution(), -offsetY / img.getResolution(), tolPx);
        return new OrthoResult(sc, offsetX, offsetY, s.count, s.masked).withOverhang(overhang);
    }

    /**
     * Bewertet Ringe mit Versatzsuche.
     * @param rings Ringe im Arbeits-CRS
     * @param img georeferenziertes Bild
     * @param edges Kantenbild zu {@code img}
     * @param mask Maske (darf {@code null} sein)
     * @return Ergebnis
     */
    public OrthoResult score(List<double[]> rings, GeoImage img, EdgeMap edges, Geometry mask) {
        Samples s = sample(rings, img, mask);
        if (s.count == 0) {
            return OrthoResult.failed(s.masked > 0 ? "Umriss vollständig von Nachbargebäuden umgeben"
                    : "Umriss zu klein für den Luftbildabgleich");
        }
        double res = img.getResolution();
        int tolPx = Math.max(1, (int) Math.round(tolerance / res));

        // Grobsuche
        double coarse = Math.max(res, 0.3);
        double bestScore = -1;
        double bestX = 0;
        double bestY = 0;
        for (double ox = -maxOffset; ox <= maxOffset + 1e-9; ox += coarse) {
            for (double oy = -maxOffset; oy <= maxOffset + 1e-9; oy += coarse) {
                double sc = evaluate(s, edges, ox / res, -oy / res, tolPx);
                if (sc > bestScore || (sc == bestScore && Math.hypot(ox, oy) < Math.hypot(bestX, bestY))) {
                    bestScore = sc;
                    bestX = ox;
                    bestY = oy;
                }
            }
        }
        // Feinsuche um das beste Grobergebnis
        double cx = bestX;
        double cy = bestY;
        for (double ox = cx - coarse; ox <= cx + coarse + 1e-9; ox += res) {
            for (double oy = cy - coarse; oy <= cy + coarse + 1e-9; oy += res) {
                if (Math.abs(ox) > maxOffset + 1e-9 || Math.abs(oy) > maxOffset + 1e-9) {
                    continue;
                }
                double sc = evaluate(s, edges, ox / res, -oy / res, tolPx);
                if (sc > bestScore || (sc == bestScore && Math.hypot(ox, oy) < Math.hypot(bestX, bestY))) {
                    bestScore = sc;
                    bestX = ox;
                    bestY = oy;
                }
            }
        }
        return new OrthoResult(Math.max(0, bestScore), bestX, bestY, s.count, s.masked);
    }

    private static double evaluate(Samples s, EdgeMap edges, double dxPx, double dyPx, int tolPx) {
        int supported = 0;
        for (int i = 0; i < s.count; i++) {
            double px = s.px[i] + dxPx;
            double py = s.py[i] + dyPx;
            double nx = s.nx[i];
            double ny = s.ny[i];
            for (int k = 0; k <= tolPx; k++) {
                if (edges.isAlignedEdge((int) Math.floor(px + k * nx), (int) Math.floor(py + k * ny), nx, ny, MIN_COS)
                        || (k > 0 && edges.isAlignedEdge((int) Math.floor(px - k * nx), (int) Math.floor(py - k * ny),
                                nx, ny, MIN_COS))) {
                    supported++;
                    break;
                }
            }
        }
        return (double) supported / s.count;
    }

    private Samples sample(List<double[]> rings, GeoImage img, Geometry mask) {
        PreparedGeometry prepared = mask != null && !mask.isEmpty() ? PreparedGeometryFactory.prepare(mask) : null;
        int masked = 0;
        List<double[]> pts = new ArrayList<>();
        for (double[] r : rings) {
            for (int i = 0; i + 3 < r.length; i += 2) {
                double x0 = r[i];
                double y0 = r[i + 1];
                double x1 = r[i + 2];
                double y1 = r[i + 3];
                double len = Math.hypot(x1 - x0, y1 - y0);
                if (len < 1e-3) {
                    continue;
                }
                int n = Math.max(1, (int) Math.floor(len / sampleStep));
                // Normale in Weltkoordinaten (-dy, dx); in Bildkoordinaten ist y gespiegelt
                double wnx = -(y1 - y0) / len;
                double wny = (x1 - x0) / len;
                for (int k = 0; k < n; k++) {
                    double t = (k + 0.5) / n;
                    double x = x0 + t * (x1 - x0);
                    double y = y0 + t * (y1 - y0);
                    if (prepared != null && prepared.contains(GF.createPoint(new Coordinate(x, y)))) {
                        masked++;
                        continue;
                    }
                    pts.add(new double[] {img.toPixelX(x), img.toPixelY(y), wnx, -wny});
                }
            }
        }
        Samples s = new Samples(pts.size());
        s.masked = masked;
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            s.px[i] = p[0];
            s.py[i] = p[1];
            s.nx[i] = p[2];
            s.ny[i] = p[3];
        }
        return s;
    }

    /**
     * Zerlegt eine JTS-Fläche in Ringe.
     * @param g Polygon oder MultiPolygon
     * @return Ringe als {@code x0,y0,x1,y1,...}
     */
    public static List<double[]> rings(Geometry g) {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (part instanceof Polygon) {
                Polygon p = (Polygon) part;
                result.add(toArray(p.getExteriorRing().getCoordinates()));
                for (int h = 0; h < p.getNumInteriorRing(); h++) {
                    result.add(toArray(p.getInteriorRingN(h).getCoordinates()));
                }
            }
        }
        return result;
    }

    private static double[] toArray(Coordinate[] c) {
        double[] a = new double[c.length * 2];
        for (int i = 0; i < c.length; i++) {
            a[2 * i] = c[i].x;
            a[2 * i + 1] = c[i].y;
        }
        return a;
    }

    private static final class Samples {
        final int count;
        int masked;
        final double[] px;
        final double[] py;
        final double[] nx;
        final double[] ny;

        Samples(int n) {
            count = n;
            px = new double[n];
            py = new double[n];
            nx = new double[n];
            ny = new double[n];
        }
    }
}
