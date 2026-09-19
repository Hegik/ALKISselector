// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

import de.alkisselector.compare.OsmMatcher;
import de.alkisselector.source.CrsTransformer;

/**
 * Sammelt vorhandene Gebäudeumrisse (geschlossene Wege mit {@code building=*} sowie Mitgliedswege von
 * Gebäude-Multipolygonen) als Nachbarn für den {@link NeighbourFitter}.
 */
public final class NeighbourWays {

    private NeighbourWays() {
        // Hilfsklasse
    }

    /**
     * Muss mit Lesesperre auf dem Datensatz oder im EDT aufgerufen werden.
     * @param ds Datensatz
     * @param crs Arbeits-CRS
     * @param bounds Suchbereich
     * @return Nachbarwege (Handle = {@link Way}, Knoten = {@link Node})
     */
    public static List<NeighbourFitter.NeighbourWay> collect(DataSet ds, CrsTransformer crs, Bounds bounds) {
        List<NeighbourFitter.NeighbourWay> result = new ArrayList<>();
        BBox bbox = new BBox(bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon(), bounds.getMaxLat());
        for (Way w : ds.searchWays(bbox)) {
            if (!w.isUsable() || !w.isClosed() || w.getNodesCount() < 4 || !isBuildingOutline(w)) {
                continue;
            }
            NeighbourFitter.NeighbourWay nw = of(w, crs);
            if (nw != null) {
                result.add(nw);
            }
        }
        return result;
    }

    private static boolean isBuildingOutline(Way w) {
        if (OsmMatcher.isBuilding(w)) {
            return true;
        }
        for (OsmPrimitive ref : w.getReferrers()) {
            if (ref instanceof Relation && ((Relation) ref).isMultipolygon() && OsmMatcher.isBuilding(ref)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ermittelt die Knoten eines Gebäudes, die beim Ersetzen der Geometrie an ihrer Position bleiben
     * müssen: Knoten, die zu weiteren Wegen gehören (Nachbargebäude, Fußwege, Mauern …), Mitglied
     * einer Relation sind oder Tags tragen (z. B. Eingänge).
     * Muss mit Lesesperre auf dem Datensatz oder im EDT aufgerufen werden.
     * @param w Gebäudeweg
     * @param crs Arbeits-CRS
     * @return festzuhaltende Knoten
     */
    public static List<NeighbourFitter.KeepNode> keepNodes(Way w, CrsTransformer crs) {
        List<NeighbourFitter.KeepNode> result = new ArrayList<>();
        java.util.Set<Node> seen = new java.util.HashSet<>();
        for (Node n : w.getNodes()) {
            if (!seen.add(n) || !n.isLatLonKnown()) {
                continue;
            }
            String label = connectionLabel(n, w);
            if (label == null) {
                continue;
            }
            EastNorth en = crs.toProjected(n);
            Node from = lineNeighbour(n, w);
            if (from != null) {
                EastNorth f = crs.toProjected(from);
                result.add(NeighbourFitter.KeepNode.sliding(n, en.east(), en.north(), f.east(), f.north(), label));
            } else if (n.hasKeys() && n.getReferrers().size() == 1) {
                result.add(NeighbourFitter.KeepNode.onOutline(n, en.east(), en.north(), label));
            } else {
                result.add(new NeighbourFitter.KeepNode(n, en.east(), en.north(), label));
            }
        }
        return result;
    }

    /**
     * Endet genau eine Linie (Zaun, Mauer, Fußweg …) am Knoten des Gebäudes, darf der Knoten entlang
     * dieser Linie auf die neue Fassade geschoben werden. Nicht bei Gebäuden, Relationen oder mehreren
     * abgehenden Linien – dort bleibt der Knoten fest.
     * @return Knoten der Linie, von dem aus verlängert wird, oder {@code null}
     */
    static Node lineNeighbour(Node n, Way owner) {
        Node from = null;
        for (OsmPrimitive ref : n.getReferrers()) {
            if (ref == owner || ref.isDeleted()) {
                continue;
            }
            if (!(ref instanceof Way) || OsmMatcher.isBuilding(ref) || isBuildingOutline((Way) ref)) {
                return null;
            }
            Way line = (Way) ref;
            List<Node> nodes = line.getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i) != n) {
                    continue;
                }
                for (int j : new int[] {i - 1, i + 1}) {
                    Node m = j >= 0 && j < nodes.size() ? nodes.get(j) : null;
                    if (line.isClosed() && m == null) {
                        m = nodes.get(j < 0 ? nodes.size() - 2 : 1);
                    }
                    if (m == null || m == n || owner.containsNode(m)) {
                        continue; // Linie verläuft entlang der Wand
                    }
                    if (from != null && from != m) {
                        return null; // mehrere Richtungen
                    }
                    from = m;
                }
            }
        }
        return from != null && from.isLatLonKnown() ? from : null;
    }

    /**
     * @return Beschreibung, warum der Knoten erhalten bleiben muss, oder {@code null}
     */
    static String connectionLabel(Node n, Way owner) {
        if (n.hasKeys()) {
            String key = n.hasKey("entrance") ? "entrance" : n.keySet().iterator().next();
            return (n.hasKey("entrance") ? "Eingang" : "Knoten mit Tags") + " (" + key + "=" + n.get(key) + ")";
        }
        for (OsmPrimitive ref : n.getReferrers()) {
            if (ref == owner || ref.isDeleted()) {
                continue;
            }
            if (ref instanceof Way) {
                return "Verbindung zu " + describe(ref);
            }
            if (ref instanceof Relation) {
                return "Mitglied von " + describe(ref);
            }
        }
        return null;
    }

    private static String describe(OsmPrimitive p) {
        for (String k : new String[] {"building", "highway", "barrier", "railway", "waterway", "man_made", "type"}) {
            if (p.hasKey(k)) {
                return k + "=" + p.get(k);
            }
        }
        return p instanceof Way ? "Weg " + p.getUniqueId() : "Relation " + p.getUniqueId();
    }

    /**
     * @param w geschlossener Weg
     * @param crs Arbeits-CRS
     * @return Nachbarweg oder {@code null}, wenn Knoten ohne Koordinaten enthalten sind
     */
    public static NeighbourFitter.NeighbourWay of(Way w, CrsTransformer crs) {
        List<Node> nodes = new ArrayList<>(w.getNodes());
        nodes.remove(nodes.size() - 1); // schließender Knoten
        double[] xy = new double[nodes.size() * 2];
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (!n.isLatLonKnown()) {
                return null;
            }
            EastNorth en = crs.toProjected(n);
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        return new NeighbourFitter.NeighbourWay(w, nodes, xy);
    }
}
