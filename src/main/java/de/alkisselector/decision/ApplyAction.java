// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryCommand;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryException;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryUtils;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.TagProposal;
import de.alkisselector.source.AlkisBuilding;

/**
 * Überträgt einen Kandidaten in den OSM-Datensatz. Alle Änderungen laufen über das Undo-System
 * von JOSM; hochgeladen wird nichts.
 */
public final class ApplyAction {

    private final AnalysisSession session;
    private final double snapDistance;

    /**
     * @param session Analyse-Sitzung
     * @param snapDistance Abstand (m), in dem vorhandene Knoten wiederverwendet werden
     */
    public ApplyAction(AnalysisSession session, double snapDistance) {
        this.session = session;
        this.snapDistance = snapDistance;
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

    private Command applyNew(Candidate c, DataSet ds, Map<String, String> tags) {
        AlkisBuilding b = c.getBuilding();
        List<Command> cmds = new ArrayList<>();
        NodeFactory nodes = new NodeFactory(ds, cmds, Set.of());
        OsmPrimitive result;
        if (b.isSimple()) {
            Way w = nodes.buildWay(b.getPolygons().get(0).getOuter());
            w.setKeys(tags);
            cmds.add(new AddCommand(ds, w));
            result = w;
        } else {
            Relation r = new Relation();
            for (AlkisBuilding.Polygon p : b.getPolygons()) {
                Way outer = nodes.buildWay(p.getOuter());
                cmds.add(new AddCommand(ds, outer));
                r.addMember(new RelationMember("outer", outer));
                for (double[] h : p.getHoles()) {
                    Way inner = nodes.buildWay(h);
                    cmds.add(new AddCommand(ds, inner));
                    r.addMember(new RelationMember("inner", inner));
                }
            }
            Map<String, String> rt = new LinkedHashMap<>();
            rt.put("type", "multipolygon");
            rt.putAll(tags);
            r.setKeys(rt);
            cmds.add(new AddCommand(ds, r));
            result = r;
        }
        Command cmd = new SequenceCommand("ALKIS-Gebäude anlegen: " + c.getTitle(), cmds);
        UndoRedoHandler.getInstance().add(cmd);
        ds.setSelected(result);
        return cmd;
    }

    // ------------------------------------------------------------------ Geometrie ersetzen

    private Command applyReplace(Candidate c, DataSet ds, Map<String, String> tags) throws ApplyException {
        OsmBuilding partner = c.getMatch().getPartner();
        if (partner == null || !(partner.getPrimitive() instanceof Way)) {
            throw new ApplyException("Geometrie ersetzen ist nur für einfache OSM-Wege möglich.");
        }
        if (!c.getBuilding().isSimple()) {
            throw new ApplyException("Das ALKIS-Gebäude hat Innenhöfe oder mehrere Teile – bitte manuell ersetzen.");
        }
        Way old = (Way) partner.getPrimitive();
        if (old.isDeleted() || !old.isUsable()) {
            throw new ApplyException("Das OSM-Gebäude wurde inzwischen gelöscht oder verändert.");
        }
        // Die Teilschritte hängen voneinander ab (Schritt 2 braucht den Weg aus Schritt 1) und werden
        // daher einzeln aufgebaut und ausgeführt; am Ende landen sie als EIN Undo-Schritt im Stapel.
        List<Command> steps = new ArrayList<>();

        // 1. neuen Weg ohne Tags anlegen (Knoten des alten Wegs nicht wiederverwenden)
        List<Command> prep = new ArrayList<>();
        NodeFactory nodes = new NodeFactory(ds, prep, new HashSet<>(old.getNodes()));
        Way w = nodes.buildWay(c.getBuilding().getPolygons().get(0).getOuter());
        prep.add(new AddCommand(ds, w));
        Command prepCmd = new SequenceCommand("ALKIS-Geometrie vorbereiten", prep);
        prepCmd.executeCommand();
        steps.add(prepCmd);

        // 2. Geometrie des bestehenden Wegs durch die neue ersetzen (Historie und ID bleiben erhalten)
        ReplaceGeometryCommand replace;
        try {
            replace = ReplaceGeometryUtils.buildReplaceWayCommand(old, w);
        } catch (ReplaceGeometryException | IllegalArgumentException e) {
            prepCmd.undoCommand();
            throw new ApplyException("Geometrie konnte nicht ersetzt werden: " + e.getMessage());
        }
        if (replace == null) {
            prepCmd.undoCommand();
            return null;
        }
        replace.executeCommand();
        steps.add(replace);

        // 3. Tags nur ergänzen bzw. vom Nutzer ausdrücklich gewählte Werte setzen
        Map<String, String> changes = new LinkedHashMap<>();
        tags.forEach((k, v) -> {
            if (!v.equals(old.get(k))) {
                changes.put(k, v);
            }
        });
        if (!changes.isEmpty()) {
            Command tagCmd = new ChangePropertyCommand(ds, List.of(old), changes);
            tagCmd.executeCommand();
            steps.add(tagCmd);
        }
        // SequenceCommand macht Undo nur, wenn es selbst ausgeführt wurde. Deshalb die Teilschritte
        // zurücknehmen und die Sequenz regulär (wie bei „Wiederholen“) ausführen lassen.
        for (int i = steps.size() - 1; i >= 0; i--) {
            steps.get(i).undoCommand();
        }
        Command all = new SequenceCommand("ALKIS-Geometrie übernehmen: " + c.getTitle(), steps);
        UndoRedoHandler.getInstance().add(all);
        ds.setSelected(old);
        return all;
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
     * Erzeugt Knoten und verwendet dabei vorhandene Knoten in unmittelbarer Nähe wieder, damit
     * aneinandergrenzende Gebäude gemeinsame Knoten haben.
     */
    private final class NodeFactory {
        private final DataSet ds;
        private final List<Command> cmds;
        private final Collection<Node> forbidden;
        private final List<Node> created = new ArrayList<>();

        NodeFactory(DataSet ds, List<Command> cmds, Collection<Node> forbidden) {
            this.ds = ds;
            this.cmds = cmds;
            this.forbidden = forbidden;
        }

        Way buildWay(double[] ring) {
            List<Node> wayNodes = new ArrayList<>();
            for (int i = 0; i + 3 < ring.length; i += 2) { // letzter Punkt = erster Punkt
                LatLon ll = session.getCrs().toLatLon(ring[i], ring[i + 1]);
                Node n = findOrCreate(ll);
                if (wayNodes.isEmpty() || wayNodes.get(wayNodes.size() - 1) != n) {
                    wayNodes.add(n);
                }
            }
            if (wayNodes.size() > 1 && wayNodes.get(wayNodes.size() - 1) == wayNodes.get(0)) {
                wayNodes.remove(wayNodes.size() - 1);
            }
            wayNodes.add(wayNodes.get(0));
            Way w = new Way();
            w.setNodes(wayNodes);
            return w;
        }

        private Node findOrCreate(LatLon ll) {
            for (Node n : created) {
                if (n.greatCircleDistance(ll) <= snapDistance) {
                    return n;
                }
            }
            double d = snapDistance / 111_000.0 * 2;
            BBox box = new BBox(ll.lon() - d * 2, ll.lat() - d, ll.lon() + d * 2, ll.lat() + d);
            Node best = null;
            double bestDist = snapDistance;
            for (Node n : ds.searchNodes(box)) {
                if (!n.isUsable() || forbidden.contains(n) || !n.isLatLonKnown()) {
                    continue;
                }
                double dist = n.greatCircleDistance(ll);
                if (dist <= bestDist && isBuildingNode(n)) {
                    best = n;
                    bestDist = dist;
                }
            }
            if (best != null) {
                return best;
            }
            Node n = new Node(ll);
            cmds.add(new AddCommand(ds, n));
            created.add(n);
            return n;
        }

        private boolean isBuildingNode(Node n) {
            for (OsmPrimitive ref : n.getReferrers()) {
                if (ref instanceof Way && (ref.hasKey("building") || ((Way) ref).getReferrers().stream()
                        .anyMatch(r -> r instanceof Relation && r.hasKey("building")))) {
                    return true;
                }
            }
            return false;
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
