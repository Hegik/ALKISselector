// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.AlkisSettings;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Vorschau der Änderungen, die eine Übernahme bewirken würde: bisherige und neue Umrisse, verschobene,
 * neue und gelöschte Knoten. Die Zuordnung der alten Knoten zu den neuen Positionen folgt derselben
 * Logik wie {@link ApplyAction}, damit die angezeigten Verschiebungen der späteren Übernahme entsprechen.
 * Muss im EDT (bzw. mit Lesesperre) berechnet werden, weil die aktuellen OSM-Daten gelesen werden.
 */
public final class ChangePreview {

    private final List<List<LatLon>> oldOutlines = new ArrayList<>();
    private final List<List<LatLon>> newOutlines = new ArrayList<>();
    private final List<LatLon[]> moves = new ArrayList<>();
    private final List<LatLon> created = new ArrayList<>();
    private final List<LatLon> deleted = new ArrayList<>();

    private ChangePreview() {
        // über of(...)
    }

    /**
     * Berechnet die Vorschau für einen Kandidaten.
     * @param c Kandidat
     * @param crs Arbeits-CRS
     * @return Vorschau (leer für Einträge ohne ALKIS-Geometrie)
     */
    public static ChangePreview of(Candidate c, CrsTransformer crs) {
        ChangePreview p = new ChangePreview();
        if (c.getBuilding() == null) {
            return p;
        }
        List<List<NeighbourFitter.Vertex>> rings = new ArrayList<>();
        if (c.getFit() != null && !c.getFit().getPolygons().isEmpty()) {
            c.getFit().getPolygons().forEach(rings::addAll);
        }
        for (List<NeighbourFitter.Vertex> ring : rings) {
            List<LatLon> l = new ArrayList<>();
            ring.forEach(v -> l.add(crs.toLatLon(v.getX(), v.getY())));
            if (!l.isEmpty()) {
                l.add(l.get(0));
            }
            p.newOutlines.add(l);
        }
        if (rings.isEmpty()) {
            p.newOutlines.addAll(c.getOutlines());
        }

        OsmBuilding partner = c.getMatch().getPartner();
        if (c.getMatchClass() == MatchClass.ABWEICHEND && partner != null && partner.getPrimitive() instanceof Way
                && partner.getPrimitive().isUsable() && rings.size() == 1) {
            p.previewReplace((Way) partner.getPrimitive(), rings.get(0), crs);
        } else if (c.getMatchClass() == MatchClass.NEU) {
            p.previewNew(c.getBuilding(), rings, crs);
        }
        return p;
    }

    /** Ersetzen: welche alten Knoten wohin wandern (gleiche Zuordnung wie ApplyAction). */
    private void previewReplace(Way old, List<NeighbourFitter.Vertex> ring, CrsTransformer crs) {
        List<LatLon> o = new ArrayList<>();
        old.getNodes().forEach(n -> o.add(n.getCoor()));
        oldOutlines.add(o);

        Set<Node> keep = new HashSet<>();
        NeighbourWays.keepNodes(old, crs).forEach(k -> keep.add((Node) k.node));
        List<Node> pool = ApplyAction.reusableNodes(old, keep);
        // vorhandene Knoten (Nachbarn, festgehaltene) bleiben an ihrem Platz; die übrigen Positionen
        // werden wie bei der Übernahme mit alten Knoten besetzt (kürzeste Wege zuerst)
        List<LatLon> targets = ApplyAction.freePositions(ring, crs, null);
        java.util.Map<Integer, Node> assignment = ApplyAction.assignPool(targets, pool);
        for (int i = 0; i < targets.size(); i++) {
            Node n = assignment.get(i);
            LatLon ll = targets.get(i);
            if (n == null) {
                created.add(ll);
            } else {
                pool.remove(n);
                if (n.greatCircleDistance(ll) > 0.001) {
                    moves.add(new LatLon[] {n.getCoor(), ll});
                }
            }
        }
        pool.forEach(n -> deleted.add(n.getCoor()));
    }

    /** Neuanlage: neue Knoten und die Anpassung der ALKIS-Eckpunkte an die Nachbarn. */
    private void previewNew(AlkisBuilding b, List<List<NeighbourFitter.Vertex>> rings, CrsTransformer crs) {
        List<double[]> fitted = new ArrayList<>();
        for (List<NeighbourFitter.Vertex> ring : rings) {
            for (NeighbourFitter.Vertex v : ring) {
                fitted.add(new double[] {v.getX(), v.getY()});
                if (!(v.getNode() instanceof Node)) {
                    created.add(crs.toLatLon(v.getX(), v.getY()));
                }
            }
        }
        if (fitted.isEmpty()) {
            return;
        }
        // Pfeile vom ursprünglichen ALKIS-Eckpunkt zur angepassten Position
        double maxShift = Math.max(1.0, 2 * AlkisSettings.FIT_TOLERANCE.get());
        for (AlkisBuilding.Polygon poly : b.getPolygons()) {
            List<double[]> raw = new ArrayList<>();
            addRing(raw, poly.getOuter());
            poly.getHoles().forEach(h -> addRing(raw, h));
            for (double[] r : raw) {
                double[] best = null;
                double bestDist = Double.MAX_VALUE;
                for (double[] f : fitted) {
                    double d = Math.hypot(f[0] - r[0], f[1] - r[1]);
                    if (d < bestDist) {
                        bestDist = d;
                        best = f;
                    }
                }
                if (best != null && bestDist > 0.02 && bestDist <= maxShift) {
                    moves.add(new LatLon[] {crs.toLatLon(r[0], r[1]), crs.toLatLon(best[0], best[1])});
                }
            }
        }
    }

    private static void addRing(List<double[]> out, double[] ring) {
        for (int i = 0; i + 3 < ring.length; i += 2) { // letzter = erster Punkt
            out.add(new double[] {ring[i], ring[i + 1]});
        }
    }

    /** @return bisherige Umrisse in OSM (leer bei Neuanlage) */
    public List<List<LatLon>> getOldOutlines() {
        return Collections.unmodifiableList(oldOutlines);
    }

    /** @return Umrisse nach der Übernahme */
    public List<List<LatLon>> getNewOutlines() {
        return Collections.unmodifiableList(newOutlines);
    }

    /** @return Verschiebungen {von, nach} */
    public List<LatLon[]> getMoves() {
        return Collections.unmodifiableList(moves);
    }

    /** @return Positionen neu angelegter Knoten */
    public List<LatLon> getCreated() {
        return Collections.unmodifiableList(created);
    }

    /** @return Positionen gelöschter Knoten */
    public List<LatLon> getDeleted() {
        return Collections.unmodifiableList(deleted);
    }
}
