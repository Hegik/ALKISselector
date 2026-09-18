// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Envelope;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.AlkisSettings;
import de.alkisselector.config.TagProposal;
import de.alkisselector.source.CrsTransformer;

/**
 * Überträgt einen Kandidaten in den OSM-Datensatz. Alle Änderungen laufen über das Undo-System
 * von JOSM; hochgeladen wird nichts.
 */
public final class ApplyAction {

    private final AnalysisSession session;

    /**
     * @param session Analyse-Sitzung
     */
    public ApplyAction(AnalysisSession session) {
        this.session = session;
    }

    /**
     * Übernimmt den Kandidaten als genau einen Undo-Schritt.
     * @param c Kandidat ({@link MatchClass#NEU} oder {@link MatchClass#ABWEICHEND})
     * @return der ausgeführte und im Undo-Stapel abgelegte Befehl, oder {@code null}, wenn der Nutzer abgebrochen hat
     * @throws ApplyException wenn die Übernahme nicht möglich ist
     */
    public Command apply(Candidate c) throws ApplyException {
        DataSet ds = session.getDataSet();
        if (ds == null || ds.isLocked()) {
            throw new ApplyException("Der Datensatz ist nicht bearbeitbar.");
        }
        if (c.getBuilding() == null) {
            throw new ApplyException("Für diesen Eintrag gibt es keine ALKIS-Geometrie.");
        }
        if (MainApplication.getLayerManager().getEditDataSet() != ds) {
            throw new ApplyException("Bitte die Datenebene, für die die Analyse gemacht wurde, als aktive Ebene wählen.");
        }
        Map<String, String> tags = selectedTags(c);
        Command cmd;
        if (c.getMatchClass() == MatchClass.NEU) {
            cmd = applyNew(c, ds, tags);
        } else if (c.getMatchClass() == MatchClass.ABWEICHEND) {
            cmd = applyReplace(c, ds, tags);
        } else {
            throw new ApplyException("„" + c.getMatchClass().getLabel() + "“ kann nicht automatisch übernommen werden.");
        }
        if (cmd != null) {
            addSourceTag(ds);
        }
        return cmd;
    }

    private static Map<String, String> selectedTags(Candidate c) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (TagProposal t : c.getTags()) {
            if (t.isSelected() && t.getValue() != null && !t.getValue().isBlank()) {
                tags.put(t.getKey(), t.getValue().strip());
            }
        }
        return tags;
    }

    // ------------------------------------------------------------------ Neuanlage

    private Command applyNew(Candidate c, DataSet ds, Map<String, String> tags) throws ApplyException {
        // Anpassung an Nachbargebäude mit dem aktuellen Datenstand (inkl. zuvor übernommener Gebäude)
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        NeighbourFitter.Result fit = new NeighbourFitter(tol, AlkisSettings.isClipOverlaps())
                .fit(c.getGeometry(), NeighbourWays.collect(ds, session.getCrs(), boundsAround(c, tol + 1)));

        List<Command> cmds = new ArrayList<>();
        FittedNodes nodes = new FittedNodes(ds, cmds, Collections.emptyList());
        List<List<Way>> polygons = new ArrayList<>();
        for (List<List<NeighbourFitter.Vertex>> poly : fit.getPolygons()) {
            List<Way> rings = new ArrayList<>();
            for (List<NeighbourFitter.Vertex> ring : poly) {
                if (ring.size() < 3) {
                    throw new ApplyException("Der angepasste Umriss ist zu klein – bitte manuell zeichnen.");
                }
                rings.add(nodes.buildWay(ring));
            }
            polygons.add(rings);
        }
        if (polygons.isEmpty()) {
            throw new ApplyException("Nach der Anpassung an die Nachbargebäude bleibt keine Fläche übrig.");
        }
        OsmPrimitive result;
        if (polygons.size() == 1 && polygons.get(0).size() == 1) {
            Way w = polygons.get(0).get(0);
            w.setKeys(tags);
            cmds.add(new AddCommand(ds, w));
            result = w;
        } else {
            Relation r = new Relation();
            for (List<Way> rings : polygons) {
                for (int i = 0; i < rings.size(); i++) {
                    cmds.add(new AddCommand(ds, rings.get(i)));
                    r.addMember(new RelationMember(i == 0 ? "outer" : "inner", rings.get(i)));
                }
            }
            Map<String, String> rt = new LinkedHashMap<>();
            rt.put("type", "multipolygon");
            rt.putAll(tags);
            r.setKeys(rt);
            cmds.add(new AddCommand(ds, r));
            result = r;
        }
        // neue Knoten in die Kanten der Nachbargebäude einfügen (gemeinsame Punkte)
        cmds.addAll(nodes.glueCommands());
        Command cmd = new SequenceCommand("ALKIS-Gebäude anlegen: " + c.getTitle(), cmds);
        UndoRedoHandler.getInstance().add(cmd);
        ds.setSelected(result);
        return cmd;
    }

    /** Ein neuer Knoten, der in die Kante {@code segment} eines Nachbarwegs eingefügt wird. */
    private static final class Glue {
        final int segment;
        final double t;
        final Node node;

        Glue(int segment, double t, Node node) {
            this.segment = segment;
            this.t = t;
            this.node = node;
        }
    }

    /**
     * Setzt die Eckpunkte des {@link NeighbourFitter} in Knoten um: vorhandene Knoten werden
     * wiederverwendet, Punkte auf Nachbarkanten werden dort als neue Knoten eingefügt. Beim Ersetzen
     * werden freie Punkte bevorzugt mit verschobenen alten Knoten besetzt (Knotenhistorie bleibt).
     */
    private final class FittedNodes {
        private final DataSet ds;
        private final List<Command> cmds;
        private final List<Node> pool;
        private final List<Node> created = new ArrayList<>();
        /** je Nachbarweg: eingefügte Knoten mit Kantenindex und Position */
        private final Map<Way, List<Glue>> glue = new LinkedHashMap<>();

        FittedNodes(DataSet ds, List<Command> cmds, List<Node> pool) {
            this.ds = ds;
            this.cmds = cmds;
            this.pool = new ArrayList<>(pool);
        }

        Way buildWay(List<NeighbourFitter.Vertex> ring) {
            Way w = new Way();
            w.setNodes(ringNodes(ring));
            return w;
        }

        /** @return geschlossene Knotenliste für einen Ring */
        List<Node> ringNodes(List<NeighbourFitter.Vertex> ring) {
            List<Node> wayNodes = new ArrayList<>();
            for (NeighbourFitter.Vertex v : ring) {
                Node n = node(v);
                if (wayNodes.isEmpty() || wayNodes.get(wayNodes.size() - 1) != n) {
                    wayNodes.add(n);
                }
            }
            if (wayNodes.size() > 1 && wayNodes.get(wayNodes.size() - 1) == wayNodes.get(0)) {
                wayNodes.remove(wayNodes.size() - 1);
            }
            wayNodes.add(wayNodes.get(0));
            return wayNodes;
        }

        /** @return Knoten aus dem Pool, die nicht wiederverwendet wurden */
        List<Node> unusedPool() {
            return pool;
        }

        private Node node(NeighbourFitter.Vertex v) {
            if (v.getNode() instanceof Node && ((Node) v.getNode()).isUsable()) {
                return (Node) v.getNode();
            }
            LatLon ll = session.getCrs().toLatLon(v.getX(), v.getY());
            for (Node n : created) {
                if (n.greatCircleDistance(ll) <= 0.01) {
                    return n;
                }
            }
            Node n = takeFromPool(ll);
            if (n == null) {
                n = new Node(ll);
                cmds.add(new AddCommand(ds, n));
            }
            created.add(n);
            if (v.getGlueWay() != null && v.getGlueWay().getHandle() instanceof Way) {
                glue.computeIfAbsent((Way) v.getGlueWay().getHandle(), k -> new ArrayList<>())
                        .add(new Glue(v.getGlueSegment(), v.getGlueT(), n));
            }
            return n;
        }

        /** Nimmt den nächstgelegenen alten Knoten aus dem Pool und verschiebt ihn an die neue Position. */
        private Node takeFromPool(LatLon ll) {
            Node best = null;
            double bestDist = Double.MAX_VALUE;
            for (Node n : pool) {
                double d = n.greatCircleDistance(ll);
                if (d < bestDist) {
                    bestDist = d;
                    best = n;
                }
            }
            if (best != null) {
                pool.remove(best);
                if (bestDist > 0.001) {
                    cmds.add(new MoveCommand(best, ll));
                }
            }
            return best;
        }

        List<Command> glueCommands() {
            List<Command> result = new ArrayList<>();
            for (Map.Entry<Way, List<Glue>> e : glue.entrySet()) {
                Way w = e.getKey();
                List<Glue> inserts = e.getValue();
                inserts.sort(Comparator.comparingInt((Glue g) -> g.segment).thenComparingDouble(g -> g.t));
                List<Node> old = w.getNodes();
                List<Node> nodes = new ArrayList<>();
                for (int i = 0; i < old.size(); i++) {
                    nodes.add(old.get(i));
                    for (Glue g : inserts) {
                        if (g.segment == i && i < old.size() - 1) {
                            nodes.add(g.node);
                        }
                    }
                }
                result.add(new ChangeNodesCommand(ds, w, nodes));
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ Geometrie ersetzen

    /**
     * Ersetzt die Geometrie eines bestehenden Wegs durch die (an Nachbarn angepasste) ALKIS-Geometrie.
     * Der Weg behält ID, Historie, Tags und Relationen. Knoten, die mit anderen Wegen verbunden sind
     * oder Tags tragen, bleiben an ihrer Position und werden – sofern nahe genug – in den neuen Umriss
     * eingebaut. Die übrigen alten Knoten werden verschoben und wiederverwendet, überzählige gelöscht.
     */
    private Command applyReplace(Candidate c, DataSet ds, Map<String, String> tags) throws ApplyException {
        OsmBuilding partner = c.getMatch().getPartner();
        if (partner == null || !(partner.getPrimitive() instanceof Way)) {
            throw new ApplyException("Geometrie ersetzen ist nur für einfache OSM-Wege möglich.");
        }
        if (!c.getBuilding().isSimple()) {
            throw new ApplyException("Das ALKIS-Gebäude hat Innenhöfe oder mehrere Teile – bitte manuell ersetzen.");
        }
        Way old = (Way) partner.getPrimitive();
        if (old.isDeleted() || !old.isUsable() || !old.isClosed()) {
            throw new ApplyException("Das OSM-Gebäude wurde inzwischen gelöscht oder verändert.");
        }
        CrsTransformer crs = session.getCrs();
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        List<NeighbourFitter.NeighbourWay> neighbours = new ArrayList<>();
        for (NeighbourFitter.NeighbourWay n : NeighbourWays.collect(ds, crs, boundsAround(c, tol + 1))) {
            if (n.getHandle() != old) {
                neighbours.add(n);
            }
        }
        List<NeighbourFitter.KeepNode> keep = NeighbourWays.keepNodes(old, crs);
        NeighbourFitter.Result fit = new NeighbourFitter(tol, AlkisSettings.isClipOverlaps())
                .fit(c.getGeometry(), neighbours, keep);
        if (fit.getPolygons().size() != 1 || fit.getPolygons().get(0).size() != 1
                || fit.getPolygons().get(0).get(0).size() < 3) {
            throw new ApplyException("Die angepasste Geometrie besteht aus mehreren Teilen – bitte manuell bearbeiten.");
        }

        // alte Knoten, die nur zu diesem Gebäude gehören, werden verschoben und wiederverwendet
        Set<Node> keepSet = new HashSet<>();
        keep.forEach(k -> keepSet.add((Node) k.node));
        List<Node> pool = new ArrayList<>();
        for (Node n : new LinkedHashSet<>(old.getNodes())) {
            if (!keepSet.contains(n) && !n.hasKeys() && n.getReferrers().size() == 1) {
                pool.add(n);
            }
        }
        List<Command> cmds = new ArrayList<>();
        FittedNodes nodes = new FittedNodes(ds, cmds, pool);
        List<Node> newNodes = nodes.ringNodes(fit.getPolygons().get(0).get(0));
        cmds.add(new ChangeNodesCommand(ds, old, newNodes));
        cmds.addAll(nodes.glueCommands());
        List<Node> unused = nodes.unusedPool();
        if (!unused.isEmpty()) {
            cmds.add(new DeleteCommand(ds, unused));
        }

        // Tags nur ergänzen bzw. vom Nutzer ausdrücklich gewählte Werte setzen
        Map<String, String> changes = new LinkedHashMap<>();
        tags.forEach((k, v) -> {
            if (!v.equals(old.get(k))) {
                changes.put(k, v);
            }
        });
        if (!changes.isEmpty()) {
            cmds.add(new ChangePropertyCommand(ds, List.of(old), changes));
        }
        Command all = new SequenceCommand("ALKIS-Geometrie übernehmen: " + c.getTitle(), cmds);
        UndoRedoHandler.getInstance().add(all);
        ds.setSelected(old);
        return all;
    }

    private Bounds boundsAround(Candidate c, double margin) {
        Envelope env = new Envelope(c.getGeometry().getEnvelopeInternal());
        env.expandBy(margin);
        CrsTransformer crs = session.getCrs();
        Bounds bounds = new Bounds(crs.toLatLon(env.getMinX(), env.getMinY()));
        bounds.extend(crs.toLatLon(env.getMaxX(), env.getMaxY()));
        bounds.extend(crs.toLatLon(env.getMinX(), env.getMaxY()));
        bounds.extend(crs.toLatLon(env.getMaxX(), env.getMinY()));
        return bounds;
    }

    private void addSourceTag(DataSet ds) {
        String src = session.getProfile().getSourceTag();
        if (src == null || src.isBlank()) {
            return;
        }
        String existing = ds.getChangeSetTags().get("source");
        if (existing == null || existing.isBlank()) {
            ds.addChangeSetTag("source", src);
        } else if (!existing.contains(src)) {
            ds.addChangeSetTag("source", existing + ";" + src);
        }
    }

    /**
     * Die Übernahme ist nicht möglich.
     */
    public static final class ApplyException extends Exception {
        private static final long serialVersionUID = 1L;

        /**
         * @param message Meldung für den Nutzer
         */
        public ApplyException(String message) {
            super(message);
        }
    }
}
