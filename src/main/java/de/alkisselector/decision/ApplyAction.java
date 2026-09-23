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
import org.locationtech.jts.geom.Geometry;
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

import de.alkisselector.compare.GeometryComparator;
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
    /** bei der letzten Übernahme gemeinsam angeglichene Kandidaten (inkl. des gewählten) */
    private List<Candidate> appliedGroup = Collections.emptyList();

    /**
     * @param session Analyse-Sitzung
     */
    public ApplyAction(AnalysisSession session) {
        this.session = session;
    }

    /**
     * @return Kandidaten, die bei der letzten Übernahme gemeinsam angeglichen wurden (der gewählte
     *         Kandidat steht an erster Stelle; bei Neuanlagen nur dieser)
     */
    public List<Candidate> getAppliedGroup() {
        return appliedGroup;
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
        List<Candidate> open = c.getOpenPrerequisites();
        if (!open.isEmpty()) {
            throw new ApplyException("Zuerst das angrenzende Gebäude „" + open.get(0).getTitle()
                    + "“ an ALKIS angleichen (oder verwerfen) – sonst würde die ALKIS-Geometrie an die "
                    + "abweichende OSM-Lage angepasst.");
        }
        Map<String, String> tags = selectedTags(c);
        Command cmd;
        appliedGroup = List.of(c);
        if (c.getMatchClass() == MatchClass.NEU) {
            cmd = applyNew(c, ds, tags);
        } else if (c.isReplacement()) {
            cmd = applyReplaceGroup(c, ds);
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
        NeighbourFitter.Result fit = computeFit(c, session);

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
        /** bereits verschobene angeschlossene Knoten */
        private final Set<Node> moved = new HashSet<>();
        /** Eckpunkt → wiederverwendeter alter Knoten (nur beim Ersetzen) */
        private final Map<NeighbourFitter.Vertex, Node> assigned = new java.util.IdentityHashMap<>();
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
            if (!pool.isEmpty()) {
                // alte Knoten den neuen Positionen mit möglichst kurzen Wegen zuordnen
                List<NeighbourFitter.Vertex> vs = new ArrayList<>();
                List<LatLon> targets = freePositions(ring, session.getCrs(), vs);
                assignPool(targets, pool).forEach((i, n) -> assigned.put(vs.get(i), n));
            }
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
                Node existing = (Node) v.getNode();
                if (v.isMove() && moved.add(existing)) {
                    // Ende einer angeschlossenen Linie bzw. Eingang auf die neue Fassade setzen
                    LatLon target = session.getCrs().toLatLon(v.getX(), v.getY());
                    if (existing.greatCircleDistance(target) > 0.001) {
                        cmds.add(new MoveCommand(existing, target));
                    }
                }
                return existing;
            }
            LatLon ll = session.getCrs().toLatLon(v.getX(), v.getY());
            for (Node n : created) {
                if (n.greatCircleDistance(ll) <= 0.01) {
                    return n;
                }
            }
            Node n = takeFromPool(v, ll);
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

        /** Nimmt den zugeordneten alten Knoten aus dem Pool und verschiebt ihn an die neue Position. */
        private Node takeFromPool(NeighbourFitter.Vertex v, LatLon ll) {
            Node n = assigned.get(v);
            if (n != null && pool.remove(n)) {
                if (n.greatCircleDistance(ll) > 0.001) {
                    cmds.add(new MoveCommand(n, ll));
                }
                return n;
            }
            return null;
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
     * Ersetzt die Geometrie des gewählten Gebäudes und aller Nachbargebäude seiner Angleichungsgruppe
     * ({@link #alignmentGroup}) vollständig durch ALKIS – als ein Undo-Schritt. Zuerst werden die
     * gemeinsamen Knoten der Gruppe auf ihre gemeinsame ALKIS-Ecke gesetzt, danach wird jedes Gebäude
     * komplett ersetzt. Kein Gebäude wird nur teilweise verschoben, gemeinsame Knoten bleiben gemeinsam.
     */
    private Command applyReplaceGroup(Candidate c, DataSet ds) throws ApplyException {
        List<Candidate> group = alignmentGroup(c, session);
        List<Command> executed = executeGroup(group, ds);
        undo(executed);
        Command all = executed.size() == 1 ? executed.get(0)
                : new SequenceCommand(group.size() > 1 ? "ALKIS-Geometrie angleichen: " + c.getTitle() + " und "
                        + (group.size() - 1) + " angrenzende Gebäude" : "ALKIS-Geometrie übernehmen: " + c.getTitle(),
                        executed);
        UndoRedoHandler.getInstance().add(all);
        ds.setSelected(c.getMatch().getPartner().getPrimitive());
        appliedGroup = group;
        return all;
    }

    /**
     * Führt das Angleichen einer Gruppe Schritt für Schritt aus (ohne Undo-Stapel). Schlägt ein Schritt
     * fehl, werden die bereits ausgeführten zurückgenommen.
     * @return ausgeführte Befehle in Reihenfolge – der Aufrufer muss sie wieder zurücknehmen
     */
    private List<Command> executeGroup(List<Candidate> group, DataSet ds) throws ApplyException {
        List<Command> executed = new ArrayList<>();
        try {
            Command corners = groupCornerMoves(group, session.getCrs());
            if (corners != null) {
                corners.executeCommand();
                executed.add(corners);
            }
            Set<OsmPrimitive> ways = groupWays(group);
            Set<OsmPrimitive> replaced = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Candidate m : group) {
                // nacheinander: jedes weitere Gebäude sieht die bereits ersetzten Nachbarn
                Command cmd = buildReplace(m, ds, selectedTags(m), ways, replaced);
                cmd.executeCommand();
                executed.add(cmd);
                replaced.add(m.getMatch().getPartner().getPrimitive());
            }
            return executed;
        } catch (ApplyException | RuntimeException e) {
            undo(executed);
            throw e;
        }
    }

    private static void undo(List<Command> executed) {
        for (int i = executed.size() - 1; i >= 0; i--) {
            executed.get(i).undoCommand();
        }
    }

    /**
     * Probeausführung für die Vorschau: Die Gruppe des Kandidaten wird genau wie bei der Übernahme
     * angeglichen, {@code inspect} sieht den Ergebnisstand, danach wird alles zurückgenommen.
     * @param c Kandidat
     * @param inspect wird mit der Gruppe im angeglichenen Zustand aufgerufen
     * @throws ApplyException wenn das Angleichen nicht möglich ist
     */
    void simulateReplace(Candidate c, java.util.function.Consumer<List<Candidate>> inspect) throws ApplyException {
        List<Candidate> group = alignmentGroup(c, session);
        List<Command> executed = executeGroup(group, session.getDataSet());
        try {
            inspect.accept(group);
        } finally {
            undo(executed);
        }
    }

    /**
     * Setzt die gemeinsamen Knoten der Gruppenmitglieder auf ihre gemeinsame ALKIS-Ecke. Nur Knoten ohne
     * Tags, die ausschließlich zu Gebäuden der Gruppe gehören, werden verschoben.
     * @return Befehl oder {@code null}, wenn nichts zu verschieben ist
     */
    static Command groupCornerMoves(List<Candidate> group, CrsTransformer crs) {
        if (group.size() < 2) {
            return null;
        }
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        Map<OsmPrimitive, Candidate> byWay = new java.util.IdentityHashMap<>();
        group.forEach(m -> byWay.put(m.getMatch().getPartner().getPrimitive(), m));
        Map<Node, double[]> targets = new LinkedHashMap<>();
        for (Candidate m : group) {
            Way w = (Way) m.getMatch().getPartner().getPrimitive();
            for (Node n : new LinkedHashSet<>(w.getNodes())) {
                if (targets.containsKey(n) || n.hasKeys() || n.getReferrers().size() < 2 || !n.isLatLonKnown()) {
                    continue;
                }
                org.openstreetmap.josm.data.coor.EastNorth en = crs.toProjected(n);
                double[] corner = nearestPoint(alkisVertices(m), en.east(), en.north(), tol);
                if (corner == null) {
                    continue;
                }
                boolean ok = true;
                for (OsmPrimitive ref : n.getReferrers()) {
                    Candidate o = byWay.get(ref);
                    if (o == null || nearestPoint(alkisVertices(o), corner[0], corner[1], SAME_ALKIS_CORNER) == null) {
                        ok = false;
                        break;
                    }
                }
                if (ok && Math.hypot(corner[0] - en.east(), corner[1] - en.north()) > 0.001) {
                    targets.put(n, corner);
                }
            }
        }
        // jede ALKIS-Ecke bekommt höchstens einen Knoten (den nächstgelegenen), sonst lägen zwei Knoten
        // aufeinander und eine benachbarte Ecke ginge verloren
        Map<String, Map.Entry<Node, double[]>> owner = new LinkedHashMap<>();
        for (Map.Entry<Node, double[]> e : targets.entrySet()) {
            String key = String.format(java.util.Locale.ROOT, "%.2f %.2f", e.getValue()[0], e.getValue()[1]);
            Map.Entry<Node, double[]> other = owner.get(key);
            if (other == null || distance(crs, e.getKey(), e.getValue()) < distance(crs, other.getKey(), other.getValue())) {
                owner.put(key, e);
            }
        }
        if (owner.isEmpty()) {
            return null;
        }
        List<Command> moves = new ArrayList<>();
        for (Map.Entry<Node, double[]> e : owner.values()) {
            moves.add(new MoveCommand(e.getKey(), crs.toLatLon(e.getValue()[0], e.getValue()[1])));
        }
        return new SequenceCommand("Gemeinsame Ecken auf ALKIS setzen", moves);
    }

    /**
     * Ersetzt die Geometrie eines bestehenden Wegs durch die (an Nachbarn angepasste) ALKIS-Geometrie.
     * Der Weg behält ID, Historie, Tags und Relationen. Knoten, die mit anderen Wegen verbunden sind
     * oder Tags tragen, bleiben an ihrer Position und werden – sofern nahe genug – in den neuen Umriss
     * eingebaut. Die übrigen alten Knoten werden verschoben und wiederverwendet, überzählige gelöscht.
     * Baut nur die Befehle, führt sie nicht aus.
     */
    private Command buildReplace(Candidate c, DataSet ds, Map<String, String> tags, Set<OsmPrimitive> group,
            Set<OsmPrimitive> replaced) throws ApplyException {
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
        List<NeighbourFitter.KeepNode> keep = NeighbourWays.keepNodes(old, crs);
        NeighbourFitter.Result fit = fitReplacement(c, session, group, replaced);
        if (fit.getPolygons().size() != 1 || fit.getPolygons().get(0).size() != 1
                || fit.getPolygons().get(0).get(0).size() < 3) {
            throw new ApplyException("Die angepasste Geometrie besteht aus mehreren Teilen – bitte manuell bearbeiten.");
        }

        // alte Knoten, die nur zu diesem Gebäude gehören, werden verschoben und wiederverwendet
        Set<Node> keepSet = new HashSet<>();
        keep.forEach(k -> keepSet.add((Node) k.node));
        List<Node> pool = reusableNodes(old, keepSet);
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
        return new SequenceCommand("ALKIS-Geometrie übernehmen: " + c.getTitle(), cmds);
    }

    /**
     * Knoten des bisherigen Gebäudes, die beim Ersetzen verschoben und wiederverwendet werden dürfen
     * (gehören nur zu diesem Gebäude, keine Tags, nicht festzuhalten). Wird auch von der
     * {@link ChangePreview} genutzt, damit Vorschau und Übernahme gleich rechnen.
     * @param old bisheriges Gebäude
     * @param keep festzuhaltende Knoten
     * @return wiederverwendbare Knoten in Wegreihenfolge
     */
    static List<Node> reusableNodes(Way old, Set<Node> keep) {
        List<Node> pool = new ArrayList<>();
        for (Node n : new LinkedHashSet<>(old.getNodes())) {
            if (!keep.contains(n) && !n.hasKeys() && n.getReferrers().size() == 1) {
                pool.add(n);
            }
        }
        return pool;
    }

    /**
     * Ordnet alte Knoten neuen Positionen zu, sodass die Wege möglichst kurz sind: Zuerst wird das
     * global kürzeste Paar vergeben, dann das nächstkürzere usw. So bleiben alte Knoten (und ihre
     * Historie) an der naheliegenden Ecke, und die Verschiebungspfeile der Vorschau sind intuitiv.
     * @param targets neue Positionen (ohne Duplikate)
     * @param pool wiederverwendbare alte Knoten
     * @return Index der Zielposition → zugeordneter alter Knoten
     */
    static Map<Integer, Node> assignPool(List<LatLon> targets, List<Node> pool) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            for (int j = 0; j < pool.size(); j++) {
                pairs.add(new double[] {pool.get(j).greatCircleDistance(targets.get(i)), i, j});
            }
        }
        pairs.sort(Comparator.comparingDouble(p -> p[0]));
        Map<Integer, Node> result = new java.util.HashMap<>();
        Set<Integer> usedPool = new HashSet<>();
        for (double[] p : pairs) {
            int i = (int) p[1];
            int j = (int) p[2];
            if (!result.containsKey(i) && !usedPool.contains(j)) {
                result.put(i, pool.get(j));
                usedPool.add(j);
            }
        }
        uncross(result, targets);
        return result;
    }

    /**
     * Tauscht die Ziele zweier Knoten, solange das die Summe der Wege verkürzt. Zwei sich kreuzende
     * Verschiebungen lassen sich so immer auflösen (die getauschten Wege sind zusammen kürzer), die
     * Pfeile der Vorschau kreuzen sich danach nicht mehr.
     */
    private static void uncross(Map<Integer, Node> result, List<LatLon> targets) {
        List<Integer> idx = new ArrayList<>(result.keySet());
        boolean improved = true;
        for (int round = 0; improved && round < 100; round++) {
            improved = false;
            for (int a = 0; a < idx.size(); a++) {
                for (int b = a + 1; b < idx.size(); b++) {
                    int i = idx.get(a);
                    int k = idx.get(b);
                    Node ni = result.get(i);
                    Node nk = result.get(k);
                    double now = ni.greatCircleDistance(targets.get(i)) + nk.greatCircleDistance(targets.get(k));
                    double swapped = ni.greatCircleDistance(targets.get(k)) + nk.greatCircleDistance(targets.get(i));
                    if (swapped < now - 1e-6) {
                        result.put(i, nk);
                        result.put(k, ni);
                        improved = true;
                    }
                }
            }
        }
    }

    /**
     * Freie Positionen eines Rings in der Reihenfolge, in der sie Knoten benötigen (ohne vorhandene
     * Knoten und ohne Punkte, die weniger als 1 cm von einem früheren Punkt entfernt sind).
     * @param ring Eckpunkte
     * @param crs Arbeits-CRS
     * @param vertices Ausgabe: die zugehörigen Eckpunkte (gleiche Reihenfolge), darf {@code null} sein
     * @return Positionen
     */
    static List<LatLon> freePositions(List<NeighbourFitter.Vertex> ring, CrsTransformer crs,
            List<NeighbourFitter.Vertex> vertices) {
        List<LatLon> out = new ArrayList<>();
        for (NeighbourFitter.Vertex v : ring) {
            if (v.getNode() instanceof Node && ((Node) v.getNode()).isUsable()) {
                continue;
            }
            LatLon ll = crs.toLatLon(v.getX(), v.getY());
            if (out.stream().anyMatch(q -> q.greatCircleDistance(ll) <= 0.01)) {
                continue;
            }
            out.add(ll);
            if (vertices != null) {
                vertices.add(v);
            }
        }
        return out;
    }

    /**
     * Passt die ALKIS-Geometrie eines Kandidaten mit dem <em>aktuellen</em> Datenstand an Nachbargebäude an.
     * Gemeinsame Grundlage für Übernahme und Vorschau, damit beide dasselbe Ergebnis zeigen – auch wenn
     * Nachbargebäude seit der Analyse verändert wurden. Muss im EDT oder mit Lesesperre aufgerufen werden.
     * @param c Kandidat
     * @param session Analyse-Sitzung (Datensatz, Arbeits-CRS und alle Kandidaten)
     * @return Anpassung oder {@code null}, wenn für den Kandidaten keine Übernahme möglich ist
     */
    public static NeighbourFitter.Result computeFit(Candidate c, AnalysisSession session) {
        if (c.getBuilding() == null) {
            return null;
        }
        DataSet ds = session.getDataSet();
        CrsTransformer crs = session.getCrs();
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        NeighbourFitter fitter = new NeighbourFitter(tol, AlkisSettings.isClipOverlaps());
        List<NeighbourFitter.NeighbourWay> all = NeighbourWays.collect(ds, crs, boundsAround(c, tol + 1, crs));
        if (c.getMatchClass() == MatchClass.NEU) {
            return fitter.fit(c.getGeometry(), all);
        }
        OsmBuilding partner = c.getMatch().getPartner();
        if (c.isReplacement() && replaceable(c)) {
            // wie bei der Übernahme: gemeinsame Ecken der Angleichungsgruppe liegen dann schon auf ALKIS
            List<Candidate> group = alignmentGroup(c, session);
            Command corners = groupCornerMoves(group, crs);
            if (corners == null) {
                return fitReplacement(c, session, groupWays(group), java.util.Collections.emptySet());
            }
            corners.executeCommand();
            try {
                return fitReplacement(c, session, groupWays(group), java.util.Collections.emptySet());
            } finally {
                corners.undoCommand();
            }
        }
        return null;
    }

    /**
     * Anpassung beim Ersetzen eines Gebäudes an den aktuellen Datenstand: Verbindungen zu Nachbarn
     * (gemeinsame Knoten, Fußwege, Eingänge) bleiben fest und werden in den neuen Umriss eingebaut.
     * @param c zu ersetzendes Gebäude
     * @param session Sitzung
     * @return Anpassung
     */
    static NeighbourFitter.Result fitReplacement(Candidate c, AnalysisSession session, Set<OsmPrimitive> group,
            Set<OsmPrimitive> replaced) {
        CrsTransformer crs = session.getCrs();
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        Way old = (Way) c.getMatch().getPartner().getPrimitive();
        // Nachbarn, die selbst noch an ALKIS angeglichen werden (offen oder später in dieser Gruppe),
        // liegen falsch: nicht an sie anpassen. Sie schließen bei ihrer eigenen Angleichung an die dann
        // amtlich liegenden Knoten an.
        Set<OsmPrimitive> pending = pendingAlignment(session, crs, replaced);
        Set<OsmPrimitive> onAlkis = alignedNeighbours(session, crs, replaced);
        List<NeighbourFitter.NeighbourWay> neighbours = new ArrayList<>();
        for (NeighbourFitter.NeighbourWay n : NeighbourWays.collect(session.getDataSet(), crs, boundsAround(c, tol + 1, crs))) {
            boolean laterInGroup = group.contains(n.getHandle()) && !replaced.contains(n.getHandle());
            if (n.getHandle() != old && !pending.contains(n.getHandle()) && !laterInGroup) {
                // an amtlich liegende Nachbarn nur dort anschließen, wo ALKIS dieselbe Ecke/Kante hat
                neighbours.add(onAlkis.contains(n.getHandle()) ? n.withTolerance(SAME_ALKIS_CORNER) : n);
            }
        }
        // Knoten neben dem ALKIS-Umriss, die nur mit Gebäuden derselben Angleichungsgruppe geteilt werden,
        // nicht festhalten: Die Gebäude werden im selben Schritt angeglichen und schließen an den gemeinsamen
        // ALKIS-Ecken an. Gemeinsame Knoten auf dem Umriss bleiben erhalten (Knotenhistorie).
        Geometry boundary = c.getGeometry().getBoundary();
        List<NeighbourFitter.KeepNode> keep = new ArrayList<>();
        for (NeighbourFitter.KeepNode k : NeighbourWays.keepNodes(old, crs)) {
            boolean offOutline = boundary.distance(GeometryComparator.FACTORY.createPoint(
                    new org.locationtech.jts.geom.Coordinate(k.x, k.y))) > ON_ALKIS;
            if (!offOutline || !onlySharedWith(k, old, group)) {
                keep.add(k);
            }
        }
        return new NeighbourFitter(tol, AlkisSettings.isClipOverlaps()).fit(c.getGeometry(), neighbours, keep);
    }

    /**
     * @return OSM-Gebäude offener Einträge, die sich ersetzen lassen, aber noch nicht auf ihrem
     *         ALKIS-Umriss liegen (ohne die im laufenden Schritt bereits ersetzten)
     */
    static Set<OsmPrimitive> pendingAlignment(AnalysisSession session, CrsTransformer crs, Set<OsmPrimitive> replaced) {
        Set<OsmPrimitive> pending = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Candidate o : session.getCandidates()) {
            if (awaitsAlignment(o, crs) && !replaced.contains(o.getMatch().getPartner().getPrimitive())) {
                pending.add(o.getMatch().getPartner().getPrimitive());
            }
        }
        return pending;
    }

    /**
     * @return ob ein offener Eintrag sein OSM-Gebäude noch an ALKIS angleichen soll (übernehmbare
     *         Empfehlung, liegt noch nicht auf ALKIS). Identische Gebäude mit „Nichts zu tun“ zählen nicht.
     */
    static boolean awaitsAlignment(Candidate o, CrsTransformer crs) {
        return isOpen(o) && replaceable(o) && o.getRecommendation() != null && o.getRecommendation().isApplicable()
                && !atAlkis(o, crs);
    }

    /**
     * @return OSM-Gebäude, die bereits auf ALKIS liegen: übernommene Einträge, im laufenden Schritt
     *         ersetzte Gebäude und Gebäude, deren Knoten exakt auf ihrem ALKIS-Umriss liegen
     */
    static Set<OsmPrimitive> alignedNeighbours(AnalysisSession session, CrsTransformer crs, Set<OsmPrimitive> replaced) {
        Set<OsmPrimitive> aligned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        aligned.addAll(replaced);
        for (Candidate o : session.getCandidates()) {
            if (replaceable(o) && (o.getStatus() == Candidate.Status.UEBERNOMMEN || atAlkis(o, crs))) {
                aligned.add(o.getMatch().getPartner().getPrimitive());
            }
        }
        return aligned;
    }

    /** @return ob der Knoten ohne Tags ist und außer {@code old} nur zu Gebäuden der Gruppe gehört */
    private static boolean onlySharedWith(NeighbourFitter.KeepNode k, Way old, Set<OsmPrimitive> group) {
        if (!(k.getNode() instanceof Node)) {
            return false;
        }
        Node n = (Node) k.getNode();
        if (n.hasKeys()) {
            return false;
        }
        for (OsmPrimitive ref : n.getReferrers()) {
            if (ref != old && !group.contains(ref)) {
                return false;
            }
        }
        return true;
    }

    /** @return OSM-Gebäude der Gruppenmitglieder */
    private static Set<OsmPrimitive> groupWays(List<Candidate> group) {
        Set<OsmPrimitive> ways = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        group.forEach(m -> ways.add(m.getMatch().getPartner().getPrimitive()));
        return ways;
    }

    private static boolean isOpen(Candidate o) {
        return o.getStatus() == Candidate.Status.OFFEN || o.getStatus() == Candidate.Status.UEBERSPRUNGEN;
    }

    /** Abstand (m), bis zu dem ein OSM-Knoten als „liegt auf ALKIS“ gilt. */
    private static final double ON_ALKIS = 0.01;

    /**
     * @return ob das OSM-Gebäude des Eintrags schon auf dem ALKIS-Umriss liegt (alle Knoten auf dem
     *         Umriss, jede ALKIS-Ecke mit einem Knoten besetzt)
     */
    static boolean atAlkis(Candidate o, CrsTransformer crs) {
        Way w = (Way) o.getMatch().getPartner().getPrimitive();
        Geometry boundary = o.getGeometry().getBoundary();
        List<double[]> nodes = new ArrayList<>();
        for (Node n : w.getNodes()) {
            if (!n.isLatLonKnown()) {
                return false;
            }
            org.openstreetmap.josm.data.coor.EastNorth en = crs.toProjected(n);
            if (boundary.distance(GeometryComparator.FACTORY.createPoint(
                    new org.locationtech.jts.geom.Coordinate(en.east(), en.north()))) > ON_ALKIS) {
                return false;
            }
            nodes.add(new double[] {en.east(), en.north()});
        }
        for (double[] corner : alkisVertices(o)) {
            if (nearestPoint(nodes, corner[0], corner[1], ON_ALKIS) == null) {
                return false;
            }
        }
        return true;
    }

    /** @return ob die Geometrie des OSM-Partners durch ALKIS ersetzt werden kann */
    static boolean replaceable(Candidate c) {
        OsmBuilding p = c.getMatch().getPartner();
        return (c.getMatchClass() == MatchClass.ABWEICHEND || c.getMatchClass() == MatchClass.IDENTISCH)
                && c.getBuilding() != null && c.getBuilding().isSimple()
                && p != null && p.getPrimitive() instanceof Way && p.getPrimitive().isUsable()
                && ((Way) p.getPrimitive()).isClosed();
    }

    /**
     * Angleichungsgruppe eines Gebäudes: das Gebäude selbst und – fortgesetzt – alle Nachbargebäude, mit
     * denen es einen Knoten teilt, den ALKIS an eine (um mehr als 1 cm) andere Stelle legt, sofern der
     * Nachbar laut ALKIS dieselbe Ecke hat und selbst angeglichen werden kann. Solche Nachbarn müssen
     * vollständig mit angeglichen werden, sonst würde ihre Form durch den verschobenen Knoten verzerrt.
     * @param c Kandidat (steht an erster Stelle)
     * @param session Sitzung
     * @return Gruppe
     */
    public static List<Candidate> alignmentGroup(Candidate c, AnalysisSession session) {
        List<Candidate> group = new ArrayList<>();
        group.add(c);
        if (!replaceable(c)) {
            return group;
        }
        CrsTransformer crs = session.getCrs();
        double tol = AlkisSettings.FIT_TOLERANCE.get();
        Map<OsmPrimitive, Candidate> byPartner = new java.util.IdentityHashMap<>();
        for (Candidate o : session.getCandidates()) {
            if (o != c && replaceable(o)
                    && (o.getStatus() == Candidate.Status.OFFEN || o.getStatus() == Candidate.Status.UEBERSPRUNGEN)) {
                byPartner.put(o.getMatch().getPartner().getPrimitive(), o);
            }
        }
        java.util.ArrayDeque<Candidate> queue = new java.util.ArrayDeque<>(group);
        while (!queue.isEmpty()) {
            Candidate m = queue.poll();
            // Nachbarn, die laut ALKIS angrenzen, in OSM aber noch abweichend liegen, gleich mit angleichen:
            // sonst würde das Gebäude neben ihnen amtlich liegen, sie selbst aber getrennt davon bleiben
            for (Candidate o : byPartner.values()) {
                if (!group.contains(o) && touchesInAlkis(m, o, tol) && awaitsAlignment(o, crs)) {
                    group.add(o);
                    queue.add(o);
                }
            }
            Way w = (Way) m.getMatch().getPartner().getPrimitive();
            List<double[]> corners = alkisVertices(m);
            for (Node n : new LinkedHashSet<>(w.getNodes())) {
                if (n.hasKeys() || n.getReferrers().size() < 2 || !n.isLatLonKnown()) {
                    continue;
                }
                org.openstreetmap.josm.data.coor.EastNorth en = crs.toProjected(n);
                double[] corner = nearestPoint(corners, en.east(), en.north(), tol);
                if (corner == null || Math.hypot(corner[0] - en.east(), corner[1] - en.north()) <= 0.01) {
                    continue; // Knoten liegt schon auf der ALKIS-Ecke – Nachbar muss nicht mit
                }
                for (OsmPrimitive ref : n.getReferrers()) {
                    Candidate o = byPartner.get(ref);
                    if (o != null && !group.contains(o)
                            && nearestPoint(alkisVertices(o), corner[0], corner[1], SAME_ALKIS_CORNER) != null) {
                        group.add(o);
                        queue.add(o);
                    }
                }
            }
        }
        return group;
    }

    /**
     * @return ob sich die ALKIS-Umrisse zweier Einträge berühren und das OSM-Gebäude von {@code o} so nah
     *         an {@code m} liegt, dass es dessen Anpassung beeinflussen würde
     */
    private static boolean touchesInAlkis(Candidate m, Candidate o, double tol) {
        return m.getGeometry().distance(o.getGeometry()) <= SAME_ALKIS_CORNER
                && m.getGeometry().distance(o.getMatch().getPartner().getGeometry()) <= tol;
    }

    private static double distance(CrsTransformer crs, Node n, double[] p) {
        org.openstreetmap.josm.data.coor.EastNorth en = crs.toProjected(n);
        return Math.hypot(en.east() - p[0], en.north() - p[1]);
    }

    /** Abstand (m), bis zu dem zwei ALKIS-Eckpunkte als dieselbe Ecke gelten. */
    private static final double SAME_ALKIS_CORNER = 0.05;

    private static List<double[]> alkisVertices(Candidate c) {
        List<double[]> out = new ArrayList<>();
        for (de.alkisselector.source.AlkisBuilding.Polygon p : c.getBuilding().getPolygons()) {
            addVertices(out, p.getOuter());
            p.getHoles().forEach(h -> addVertices(out, h));
        }
        return out;
    }

    private static void addVertices(List<double[]> out, double[] ring) {
        for (int i = 0; i + 1 < ring.length; i += 2) {
            out.add(new double[] {ring[i], ring[i + 1]});
        }
    }

    private static double[] nearestPoint(List<double[]> pts, double x, double y, double maxDist) {
        double[] best = null;
        double bestDist = maxDist;
        for (double[] p : pts) {
            double d = Math.hypot(p[0] - x, p[1] - y);
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private static Bounds boundsAround(Candidate c, double margin, CrsTransformer crs) {
        Envelope env = new Envelope(c.getGeometry().getEnvelopeInternal());
        env.expandBy(margin);
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
