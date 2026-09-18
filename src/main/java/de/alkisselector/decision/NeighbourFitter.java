// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import de.alkisselector.compare.GeometryComparator;

/**
 * Passt einen neuen ALKIS-Umriss an vorhandene, angrenzende OSM-Gebäude an, damit keine
 * Überlappungen und keine schmalen Spalten entstehen:
 * <ol>
 * <li><b>Abschneiden:</b> Ragt ein vorhandenes Gebäude in den neuen Umriss, wird dieser Teil
 *     abgezogen (sofern dabei höchstens {@value #MAX_CLIP_SHARE} der Fläche wegfällt und die Fläche
 *     zusammenhängend bleibt).</li>
 * <li><b>Gemeinsame Punkte:</b> Eckpunkte in der Nähe eines Nachbarknotens verwenden diesen Knoten;
 *     Eckpunkte in der Nähe einer Nachbarkante werden als neuer Knoten in diese Kante eingefügt.</li>
 * <li><b>Gemeinsame Kante:</b> Verlaufen zwei aufeinanderfolgende Punkte entlang desselben
 *     Nachbargebäudes, werden dessen Zwischenknoten übernommen – die Grenze ist dann identisch.</li>
 * </ol>
 * Die Klasse arbeitet nur mit Koordinaten im metrischen Arbeits-CRS und abstrakten Knotenreferenzen;
 * die Umsetzung in JOSM-Befehle erfolgt in {@link ApplyAction}.
 */
public final class NeighbourFitter {

    /** Größter Flächenanteil, der beim Abschneiden wegfallen darf. */
    public static final double MAX_CLIP_SHARE = 0.3;
    /** Überlappungen unterhalb dieser Fläche (m²) werden ignoriert. */
    public static final double MIN_OVERLAP_AREA = 0.05;
    /** Ab dieser verbleibenden Überlappung (m²) gilt die Anpassung als Konflikt. */
    public static final double MAX_REMAINING_OVERLAP = 0.1;

    private final double tolerance;
    private final boolean clip;

    /**
     * @param tolerance Abstand (m), bis zu dem an Nachbarknoten/-kanten angeschlossen wird
     * @param clip Überlappungen mit Nachbargebäuden abschneiden
     */
    public NeighbourFitter(double tolerance, boolean clip) {
        this.tolerance = tolerance;
        this.clip = clip;
    }

    /**
     * Passt eine Fläche an.
     * @param alkis ALKIS-Fläche (Polygon oder MultiPolygon) im Arbeits-CRS
     * @param neighbours in Frage kommende Nachbargebäude (geschlossene Wege)
     * @return Ergebnis
     */
    public Result fit(Geometry alkis, List<NeighbourWay> neighbours) {
        return fit(alkis, neighbours, java.util.Collections.emptyList());
    }

    /**
     * Passt eine Fläche an und hält dabei Knoten fest, die mit anderen Wegen verbunden sind
     * (z. B. Eingänge, Fußwege, Mauern am bisherigen Gebäude). Liegt ein solcher Knoten höchstens
     * {@code tolerance} vom neuen Umriss entfernt, wird er an seiner Position in den Umriss
     * eingebaut; sonst wird die verlorene Verbindung als Konflikt gemeldet.
     * @param alkis ALKIS-Fläche im Arbeits-CRS
     * @param neighbours Nachbargebäude (ohne das zu ersetzende Gebäude)
     * @param keep festzuhaltende Knoten
     * @return Ergebnis
     */
    public Result fit(Geometry alkis, List<NeighbourWay> neighbours, List<KeepNode> keep) {
        Result r = new Result();
        Envelope env = new Envelope(alkis.getEnvelopeInternal());
        env.expandBy(tolerance);
        List<NeighbourWay> near = new ArrayList<>();
        for (NeighbourWay n : neighbours) {
            if (n.polygon != null && env.intersects(n.polygon.getEnvelopeInternal())) {
                near.add(n);
            }
        }
        Geometry geom = alkis;
        if (!near.isEmpty()) {
            geom = clipOverlaps(alkis, near, r);
        }
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Polygon p = (Polygon) geom.getGeometryN(i);
            List<List<Vertex>> rings = new ArrayList<>();
            rings.add(fitRing(p.getExteriorRing().getCoordinates(), near, r));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                rings.add(fitRing(p.getInteriorRingN(h).getCoordinates(), near, r));
            }
            r.polygons.add(rings);
        }
        for (KeepNode k : keep) {
            insertKeepNode(k, r);
        }
        r.geometry = toGeometry(r.polygons);
        for (NeighbourWay n : near) {
            double ov = GeometryComparator.intersectionArea(r.geometry, n.polygon);
            if (ov > MIN_OVERLAP_AREA) {
                r.remainingOverlap += ov;
            }
        }
        if (r.remainingOverlap > MAX_REMAINING_OVERLAP) {
            r.conflict = true;
            r.hints.add(String.format(Locale.GERMAN,
                    "Überlappt vorhandene OSM-Gebäude um %.1f m² – bitte Nachbargebäude prüfen", r.remainingOverlap));
        }
        if (r.clippedArea > 0) {
            r.hints.add(String.format(Locale.GERMAN,
                    "%.1f m² abgeschnitten, weil ein vorhandenes OSM-Gebäude hineinragt (ggf. Nachbar ebenfalls abgleichen)",
                    r.clippedArea));
        }
        if (r.sharedPoints > 0) {
            r.hints.add("An " + r.sharedPoints + " Punkten mit angrenzenden OSM-Gebäuden verbunden");
        }
        if (r.keptConnections > 0) {
            r.hints.add(r.keptConnections + " Verbindung(en) zu angrenzenden Wegen bzw. Eingängen bleiben erhalten");
        }
        return r;
    }

    // ------------------------------------------------------------------ Verbundene Knoten festhalten

    private void insertKeepNode(KeepNode k, Result r) {
        List<Vertex> bestRing = null;
        int bestIdx = -1;
        double bestDist = Double.MAX_VALUE;
        for (List<List<Vertex>> poly : r.polygons) {
            for (List<Vertex> ring : poly) {
                for (int i = 0; i < ring.size(); i++) {
                    Vertex a = ring.get(i);
                    if (a.node == k.node) {
                        return; // bereits Teil des Umrisses (z. B. gemeinsamer Knoten mit Nachbargebäude)
                    }
                    Vertex b = ring.get((i + 1) % ring.size());
                    double d = Math.hypot(a.x - k.x, a.y - k.y);
                    int idx = -1; // -1 = Eckpunkt a ersetzen
                    double[] p = project(k.x, k.y, a.x, a.y, b.x, b.y);
                    if (p != null && p[2] < d) {
                        d = p[2];
                        idx = i + 1; // zwischen a und b einfügen
                    }
                    if (d < bestDist) {
                        bestDist = d;
                        bestRing = ring;
                        bestIdx = idx >= 0 ? idx : -(i + 1);
                    }
                }
            }
        }
        if (bestRing == null || bestDist > tolerance) {
            r.conflict = true;
            r.lostConnections.add(k.label);
            r.hints.add(String.format(Locale.GERMAN,
                    "%s liegt %.1f m neben dem neuen Umriss – die Verbindung ginge verloren", k.label,
                    bestRing == null ? 0 : bestDist));
            return;
        }
        Vertex v = Vertex.ofNode(k.x, k.y, k.node);
        if (bestIdx < 0) {
            // Eckpunkt liegt fast auf dem Knoten: Eckpunkt durch den vorhandenen Knoten ersetzen,
            // sofern er nicht selbst schon an einem Nachbargebäude hängt
            int i = -bestIdx - 1;
            Vertex old = bestRing.get(i);
            if (old.node == null && old.glueWay == null) {
                bestRing.set(i, v);
            } else {
                bestRing.add(i + 1, v);
            }
        } else {
            bestRing.add(bestIdx, v);
        }
        r.keptConnections++;
    }

    // ------------------------------------------------------------------ 1. Abschneiden

    private Geometry clipOverlaps(Geometry alkis, List<NeighbourWay> near, Result r) {
        List<Geometry> overlapping = new ArrayList<>();
        for (NeighbourWay n : near) {
            if (GeometryComparator.intersectionArea(alkis, n.polygon) > MIN_OVERLAP_AREA) {
                overlapping.add(n.polygon);
            }
        }
        if (overlapping.isEmpty()) {
            return alkis;
        }
        if (!clip) {
            return alkis;
        }
        Geometry diff;
        try {
            diff = alkis.difference(UnaryUnionOp.union(overlapping));
            // Rundungsartefakte des Verschneidens entfernen, ohne die Topologie zu verändern
            diff = TopologyPreservingSimplifier.simplify(diff, 0.01);
            diff = removeSlivers(diff);
        } catch (RuntimeException e) {
            r.hints.add("Überlappung mit vorhandenen Gebäuden konnte nicht berechnet werden");
            return alkis;
        }
        double lost = alkis.getArea() - diff.getArea();
        boolean polygonal = !diff.isEmpty() && diff.getNumGeometries() == alkis.getNumGeometries();
        if (!polygonal || lost > MAX_CLIP_SHARE * alkis.getArea()) {
            r.hints.add(String.format(Locale.GERMAN,
                    "Vorhandene OSM-Gebäude überdecken %.0f %% des Umrisses – nicht automatisch angepasst",
                    100 * lost / alkis.getArea()));
            return alkis;
        }
        r.clippedArea = lost;
        return diff;
    }

    /** Entfernt beim Verschneiden entstandene Kleinstteile (< 0,5 m²). */
    private static Geometry removeSlivers(Geometry g) {
        List<Polygon> keep = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (part instanceof Polygon && part.getArea() >= 0.5) {
                keep.add((Polygon) part);
            }
        }
        if (keep.size() == 1) {
            return keep.get(0);
        }
        return GeometryComparator.FACTORY.createMultiPolygon(keep.toArray(new Polygon[0]));
    }

    // ------------------------------------------------------------------ 2./3. Punkte und Kanten

    private List<Vertex> fitRing(Coordinate[] coords, List<NeighbourWay> near, Result r) {
        List<Vertex> ring = new ArrayList<>();
        for (int i = 0; i < coords.length - 1; i++) { // letzter = erster Punkt
            ring.add(snap(coords[i], near));
        }
        ring = densify(ring, near);
        ring = attachNeighbourNodes(ring, near);
        ring = dedupe(ring);
        for (Vertex v : ring) {
            if (v.node != null || v.glueWay != null) {
                r.sharedPoints++;
            }
        }
        return ring;
    }

    private Vertex snap(Coordinate c, List<NeighbourWay> near) {
        // bevorzugt vorhandene Knoten
        Vertex best = null;
        double bestDist = tolerance;
        for (NeighbourWay n : near) {
            for (int i = 0; i < n.size(); i++) {
                double d = Math.hypot(n.x(i) - c.x, n.y(i) - c.y);
                if (d <= bestDist) {
                    bestDist = d;
                    best = Vertex.ofNode(n.x(i), n.y(i), n.nodes.get(i));
                }
            }
        }
        if (best != null) {
            return best;
        }
        // sonst in die nächste Kante einfügen
        bestDist = tolerance;
        for (NeighbourWay n : near) {
            for (int i = 0; i < n.size(); i++) {
                int j = (i + 1) % n.size();
                double[] proj = project(c.x, c.y, n.x(i), n.y(i), n.x(j), n.y(j));
                if (proj != null && proj[2] <= bestDist) {
                    bestDist = proj[2];
                    best = Vertex.glue(proj[0], proj[1], n, i, proj[3]);
                }
            }
        }
        return best != null ? best : Vertex.free(c.x, c.y);
    }

    /**
     * Fügt zwischen zwei Punkten, die am selben Nachbargebäude liegen, dessen Zwischenknoten ein,
     * sofern diese nahe an der Verbindungslinie liegen.
     */
    private List<Vertex> densify(List<Vertex> ring, List<NeighbourWay> near) {
        List<Vertex> out = new ArrayList<>();
        int m = ring.size();
        for (int k = 0; k < m; k++) {
            Vertex a = ring.get(k);
            Vertex b = ring.get((k + 1) % m);
            out.add(a);
            for (NeighbourWay w : commonWays(a, b, near)) {
                List<Vertex> between = pathBetween(w, a, b);
                if (between != null) {
                    out.addAll(between);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Baut Knoten von Nachbargebäuden, die nahe an einer Kante des neuen Umrisses liegen, in diese
     * Kante ein (T-Stoß: ein Nachbargebäude endet an der neuen Kante). Ohne diesen Schritt bliebe
     * zwischen Nachbarknoten und neuer Kante ein schmaler Spalt bzw. eine Überlappung.
     */
    private List<Vertex> attachNeighbourNodes(List<Vertex> ring, List<NeighbourWay> near) {
        int m = ring.size();
        if (m < 3) {
            return ring;
        }
        // je Kante: einzufügende Knoten mit Position t entlang der Kante
        List<List<double[]>> inserts = new ArrayList<>();
        List<List<Vertex>> insertVertices = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            inserts.add(new ArrayList<>());
            insertVertices.add(new ArrayList<>());
        }
        java.util.Set<Object> used = new java.util.HashSet<>();
        for (Vertex v : ring) {
            if (v.node != null) {
                used.add(v.node);
            }
        }
        LinearRing lr = ring(ring);
        Polygon current = lr != null && lr.isValid() ? GeometryComparator.FACTORY.createPolygon(lr) : null;
        for (NeighbourWay n : near) {
            for (int i = 0; i < n.size(); i++) {
                Object node = n.nodes.get(i);
                if (used.contains(node)) {
                    continue;
                }
                int bestEdge = -1;
                double bestDist = tolerance;
                double bestT = 0;
                for (int k = 0; k < m; k++) {
                    Vertex a = ring.get(k);
                    Vertex b = ring.get((k + 1) % m);
                    double[] p = project(n.x(i), n.y(i), a.x, a.y, b.x, b.y);
                    if (p == null || p[2] > bestDist) {
                        continue;
                    }
                    // nicht unmittelbar an einem Eckpunkt einfügen (sonst entsteht ein Zickzack)
                    double len = Math.hypot(b.x - a.x, b.y - a.y);
                    if (p[3] * len < 0.02 || (1 - p[3]) * len < 0.02) {
                        continue;
                    }
                    bestDist = p[2];
                    bestEdge = k;
                    bestT = p[3];
                }
                if (bestEdge >= 0) {
                    inserts.get(bestEdge).add(new double[] {bestT, insertVertices.get(bestEdge).size()});
                    insertVertices.get(bestEdge).add(Vertex.ofNode(n.x(i), n.y(i), node));
                    used.add(node);
                }
            }
        }
        List<Vertex> out = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            Vertex a = ring.get(k);
            Vertex b = ring.get((k + 1) % m);
            out.add(a);
            List<double[]> ins = inserts.get(k);
            ins.sort((p, q) -> Double.compare(p[0], q[0]));
            List<Vertex> chosen = new ArrayList<>();
            for (double[] e : ins) {
                chosen.add(insertVertices.get(k).get((int) e[1]));
            }
            // Alle Knoten einer Kante gemeinsam prüfen (erst zusammen folgen sie z. B. einer Nachbarwand);
            // entsteht dabei eine Überlappung, nur die Knoten behalten, die einzeln unkritisch sind.
            if (!chosen.isEmpty() && bulgeOverlap(a, chosen, b, current, near) > 0.01) {
                List<Vertex> safe = new ArrayList<>();
                for (Vertex v : chosen) {
                    if (!createsOverlap(a, b, v.x, v.y, current, near)) {
                        safe.add(v);
                    }
                }
                chosen = bulgeOverlap(a, safe, b, current, near) > 0.01 ? new ArrayList<>() : safe;
            }
            out.addAll(chosen);
        }
        return out;
    }

    /**
     * Fläche, um die die Kante a–b beim Führen über die Punkte {@code via} in Nachbargebäude hineinragt.
     */
    private static double bulgeOverlap(Vertex a, List<Vertex> via, Vertex b, Polygon current, List<NeighbourWay> near) {
        Coordinate[] c = new Coordinate[via.size() + 3];
        c[0] = new Coordinate(a.x, a.y);
        for (int i = 0; i < via.size(); i++) {
            c[i + 1] = new Coordinate(via.get(i).x, via.get(i).y);
        }
        c[via.size() + 1] = new Coordinate(b.x, b.y);
        c[via.size() + 2] = new Coordinate(a.x, a.y);
        Geometry bulge;
        try {
            bulge = GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(c));
            if (current != null) {
                bulge = bulge.difference(current);
            }
        } catch (RuntimeException e) {
            return 0;
        }
        double sum = 0;
        for (NeighbourWay n : near) {
            sum += GeometryComparator.intersectionArea(bulge, n.polygon);
        }
        return sum;
    }

    /**
     * Prüft, ob das Ausbeulen der Kante a–b zum Punkt (x, y) in ein Nachbargebäude hineinschneiden würde.
     */
    private static boolean createsOverlap(Vertex a, Vertex b, double x, double y, Polygon current,
            List<NeighbourWay> near) {
        Polygon tri;
        try {
            tri = GeometryComparator.FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(a.x, a.y), new Coordinate(x, y), new Coordinate(b.x, b.y), new Coordinate(a.x, a.y)});
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (tri.getArea() < 1e-6) {
            return false;
        }
        // Liegt der Punkt innerhalb des Umrisses, wird die Fläche nur kleiner – keine neue Überlappung
        if (current != null && current.contains(GeometryComparator.FACTORY.createPoint(new Coordinate(x, y)))) {
            return false;
        }
        for (NeighbourWay n : near) {
            if (GeometryComparator.intersectionArea(tri, n.polygon) > 0.01) {
                return true;
            }
        }
        return false;
    }

    private static List<NeighbourWay> commonWays(Vertex a, Vertex b, List<NeighbourWay> near) {
        List<NeighbourWay> result = new ArrayList<>();
        for (NeighbourWay w : near) {
            if (a.paramOn(w) >= 0 && b.paramOn(w) >= 0) {
                result.add(w);
            }
        }
        return result;
    }

    private List<Vertex> pathBetween(NeighbourWay w, Vertex a, Vertex b) {
        double pa = a.paramOn(w);
        double pb = b.paramOn(w);
        int n = w.size();
        if (pa < 0 || pb < 0 || Math.abs(pa - pb) < 1e-9) {
            return null;
        }
        List<Vertex> fwd = walk(w, pa, pb, true);
        List<Vertex> bwd = walk(w, pa, pb, false);
        List<Vertex> best = null;
        for (List<Vertex> cand : java.util.Arrays.asList(fwd, bwd)) {
            if (cand != null && allNearSegment(cand, a, b) && (best == null || cand.size() < best.size())) {
                best = cand;
            }
        }
        return best != null && best.size() < n ? best : null;
    }

    /** Knoten des Nachbarrings strikt zwischen den Parametern pa und pb (vorwärts oder rückwärts). */
    private static List<Vertex> walk(NeighbourWay w, double pa, double pb, boolean forward) {
        int n = w.size();
        double span = forward ? mod(pb - pa, n) : mod(pa - pb, n);
        List<Vertex> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double d = forward ? mod(i - pa, n) : mod(pa - i, n);
            if (d > 1e-9 && d < span - 1e-9) {
                out.add(Vertex.ofNode(w.x(i), w.y(i), w.nodes.get(i)));
            }
        }
        out.sort((u, v) -> Double.compare(
                forward ? mod(u.paramOn(w) - pa, n) : mod(pa - u.paramOn(w), n),
                forward ? mod(v.paramOn(w) - pa, n) : mod(pa - v.paramOn(w), n)));
        return out;
    }

    private boolean allNearSegment(List<Vertex> vs, Vertex a, Vertex b) {
        for (Vertex v : vs) {
            double[] p = project(v.x, v.y, a.x, a.y, b.x, b.y);
            if (p == null || p[2] > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static List<Vertex> dedupe(List<Vertex> ring) {
        List<Vertex> out = new ArrayList<>();
        for (Vertex v : ring) {
            if (out.isEmpty() || !out.get(out.size() - 1).sameAs(v)) {
                out.add(v);
            }
        }
        while (out.size() > 1 && out.get(0).sameAs(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static double mod(double a, int n) {
        double r = a % n;
        return r < 0 ? r + n : r;
    }

    /**
     * Projiziert einen Punkt auf eine Strecke.
     * @return {x, y, Abstand, t} oder {@code null}, wenn die Projektion außerhalb der Strecke liegt
     */
    static double[] project(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 < 1e-12) {
            return null;
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        if (t <= 1e-6 || t >= 1 - 1e-6) {
            return null;
        }
        double x = ax + t * dx;
        double y = ay + t * dy;
        return new double[] {x, y, Math.hypot(px - x, py - y), t};
    }

    private static Geometry toGeometry(List<List<List<Vertex>>> polygons) {
        List<Polygon> polys = new ArrayList<>();
        for (List<List<Vertex>> rings : polygons) {
            LinearRing shell = ring(rings.get(0));
            if (shell == null) {
                continue;
            }
            List<LinearRing> holes = new ArrayList<>();
            for (int i = 1; i < rings.size(); i++) {
                LinearRing h = ring(rings.get(i));
                if (h != null) {
                    holes.add(h);
                }
            }
            polys.add(GeometryComparator.FACTORY.createPolygon(shell, holes.toArray(new LinearRing[0])));
        }
        Geometry g = polys.size() == 1 ? polys.get(0) : GeometryComparator.FACTORY.createMultiPolygon(polys.toArray(new Polygon[0]));
        return GeometryComparator.clean(g);
    }

    private static LinearRing ring(List<Vertex> vs) {
        if (vs.size() < 3) {
            return null;
        }
        Coordinate[] c = new Coordinate[vs.size() + 1];
        for (int i = 0; i < vs.size(); i++) {
            c[i] = new Coordinate(vs.get(i).x, vs.get(i).y);
        }
        c[vs.size()] = new Coordinate(c[0]);
        return GeometryComparator.FACTORY.createLinearRing(c);
    }

    // ------------------------------------------------------------------ Datentypen

    /**
     * Ein vorhandenes, geschlossenes OSM-Gebäude mit seinen Knoten.
     */
    public static final class NeighbourWay {
        final Object handle;
        final List<Object> nodes;
        final double[] xy;
        final Polygon polygon;

        /**
         * @param handle Referenz auf das OSM-Objekt (z. B. {@code Way})
         * @param nodes Knotenreferenzen ohne den schließenden Knoten
         * @param xy Koordinaten der Knoten im Arbeits-CRS ({@code x0,y0,x1,y1,...}), gleiche Reihenfolge
         */
        public NeighbourWay(Object handle, List<?> nodes, double[] xy) {
            this.handle = handle;
            this.nodes = new ArrayList<>(nodes);
            this.xy = xy;
            Polygon p = null;
            if (nodes.size() >= 3) {
                Coordinate[] c = new Coordinate[nodes.size() + 1];
                for (int i = 0; i < nodes.size(); i++) {
                    c[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
                }
                c[nodes.size()] = new Coordinate(c[0]);
                try {
                    Geometry g = GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(c));
                    p = g instanceof Polygon ? (Polygon) g : null;
                } catch (IllegalArgumentException e) {
                    p = null;
                }
            }
            this.polygon = p;
        }

        public Object getHandle() {
            return handle;
        }

        int size() {
            return nodes.size();
        }

        double x(int i) {
            return xy[2 * i];
        }

        double y(int i) {
            return xy[2 * i + 1];
        }
    }

    /**
     * Ein vorhandener Knoten des bisherigen Gebäudes, der mit anderen Wegen verbunden ist oder
     * Tags trägt und daher an seiner Position bleiben muss.
     */
    public static final class KeepNode {
        final Object node;
        final double x;
        final double y;
        final String label;

        /**
         * @param node Knotenreferenz
         * @param x Ostwert
         * @param y Nordwert
         * @param label Beschreibung für Hinweise, z. B. „Eingang (entrance=main)“
         */
        public KeepNode(Object node, double x, double y, String label) {
            this.node = node;
            this.x = x;
            this.y = y;
            this.label = label;
        }
    }

    /**
     * Ein Eckpunkt des angepassten Umrisses.
     */
    public static final class Vertex {
        final double x;
        final double y;
        /** vorhandener Knoten, der wiederverwendet wird (oder {@code null}) */
        final Object node;
        /** Nachbarweg, in dessen Kante ein neuer Knoten eingefügt wird (oder {@code null}) */
        final NeighbourWay glueWay;
        /** Index des Kantenanfangs im Nachbarweg */
        final int glueSegment;
        /** Position auf der Kante (0..1) */
        final double glueT;

        private Vertex(double x, double y, Object node, NeighbourWay glueWay, int glueSegment, double glueT) {
            this.x = x;
            this.y = y;
            this.node = node;
            this.glueWay = glueWay;
            this.glueSegment = glueSegment;
            this.glueT = glueT;
        }

        static Vertex free(double x, double y) {
            return new Vertex(x, y, null, null, -1, 0);
        }

        static Vertex ofNode(double x, double y, Object node) {
            return new Vertex(x, y, node, null, -1, 0);
        }

        static Vertex glue(double x, double y, NeighbourWay w, int segment, double t) {
            return new Vertex(x, y, null, w, segment, t);
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        /** @return wiederverwendeter Knoten oder {@code null} */
        public Object getNode() {
            return node;
        }

        /** @return Nachbarweg, in den ein neuer Knoten eingefügt wird, oder {@code null} */
        public NeighbourWay getGlueWay() {
            return glueWay;
        }

        public int getGlueSegment() {
            return glueSegment;
        }

        public double getGlueT() {
            return glueT;
        }

        /** Parameter entlang des Nachbarrings (Knotenindex + Anteil) oder -1. */
        double paramOn(NeighbourWay w) {
            if (glueWay == w) {
                return glueSegment + glueT;
            }
            if (node != null) {
                int idx = w.nodes.indexOf(node);
                return idx;
            }
            return -1;
        }

        boolean sameAs(Vertex o) {
            if (node != null && node == o.node) {
                return true;
            }
            return Math.hypot(x - o.x, y - o.y) < 0.01;
        }
    }

    /**
     * Ergebnis der Anpassung.
     */
    public static final class Result {
        /** je Polygon: Außenring, dann Innenringe */
        final List<List<List<Vertex>>> polygons = new ArrayList<>();
        Geometry geometry;
        double clippedArea;
        double remainingOverlap;
        int sharedPoints;
        int keptConnections;
        final List<String> lostConnections = new ArrayList<>();
        boolean conflict;
        final List<String> hints = new ArrayList<>();

        /** @return Beschreibungen der Verbindungen, die bei der Übernahme verloren gingen */
        public List<String> getLostConnections() {
            return lostConnections;
        }

        /** @return Anzahl der festgehaltenen Verbindungen zu anderen Wegen */
        public int getKeptConnections() {
            return keptConnections;
        }

        /** @return Polygone (je: Außenring, Innenringe) als Eckpunktlisten */
        public List<List<List<Vertex>>> getPolygons() {
            return polygons;
        }

        /** @return angepasste Geometrie im Arbeits-CRS */
        public Geometry getGeometry() {
            return geometry;
        }

        public double getClippedArea() {
            return clippedArea;
        }

        public double getRemainingOverlap() {
            return remainingOverlap;
        }

        public int getSharedPoints() {
            return sharedPoints;
        }

        /** @return ob trotz Anpassung eine nennenswerte Überlappung bleibt */
        public boolean isConflict() {
            return conflict;
        }

        public List<String> getHints() {
            return hints;
        }

        /** @return ob die Geometrie gegenüber ALKIS verändert wurde */
        public boolean isModified() {
            return clippedArea > 0 || sharedPoints > 0 || keptConnections > 0;
        }
    }
}
