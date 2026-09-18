// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.Action;
import javax.swing.Icon;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.tools.ImageProvider;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.Candidate;
import de.alkisselector.decision.Recommendation;

/**
 * Zeichnet die ALKIS-Umrisse der aktuellen Sitzung farbcodiert über die Karte:
 * grün = Übernahme empfohlen, orange = Diskrepanz, gelb = ungeprüft, violett = manuell,
 * grau = identisch/erledigt, rot gestrichelt = OSM-Gebäude ohne ALKIS-Gegenstück.
 * Wurde ein Umriss an Nachbargebäude angepasst, zeigt die kräftige Linie die angepasste Form und
 * eine dünne gestrichelte Linie den ursprünglichen ALKIS-Umriss.
 */
public class AlkisPreviewLayer extends Layer {

    /** Farben je Empfehlung. */
    public static final Map<Recommendation, Color> COLORS = new EnumMap<>(Recommendation.class);

    static {
        COLORS.put(Recommendation.UEBERNAHME_EMPFOHLEN, new Color(0x00, 0xb0, 0x3c));
        COLORS.put(Recommendation.DISKREPANZ, new Color(0xff, 0x8c, 0x00));
        COLORS.put(Recommendation.UNGEPRUEFT, new Color(0xf0, 0xd0, 0x00));
        COLORS.put(Recommendation.MANUELL, new Color(0xc0, 0x40, 0xe0));
        COLORS.put(Recommendation.NICHTS_ZU_TUN, new Color(0x90, 0x90, 0x90));
        COLORS.put(Recommendation.HINWEIS, new Color(0xe0, 0x20, 0x20));
    }

    private static final Color DONE = new Color(0x70, 0x70, 0x70);
    private static final Stroke NORMAL = new BasicStroke(2f);
    private static final Stroke SELECTED = new BasicStroke(4f);
    private static final Stroke DASHED = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] {6f, 4f}, 0f);
    private static final Stroke THIN_DASHED = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] {3f, 3f}, 0f);

    /** Neuer Layer. */
    public AlkisPreviewLayer() {
        super("ALKIS-Abgleich");
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds box) {
        AnalysisSession s = AlkisController.getInstance().getSession();
        if (s == null) {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Candidate selected = AlkisController.getInstance().getSelectedCandidate();
        for (Candidate c : AlkisController.getInstance().getVisibleCandidates()) {
            if (c != selected) {
                draw(g, mv, c, false);
            }
        }
        if (selected != null) {
            draw(g, mv, selected, true);
        }
    }

    private static void draw(Graphics2D g, MapView mv, Candidate c, boolean selected) {
        boolean fitted = !c.getFittedOutlines().isEmpty();
        Path2D path = c.getMatchClass() == MatchClass.NUR_OSM ? osmPath(mv, c.getOsmOnly().getPrimitive())
                : ringsPath(mv, fitted ? c.getFittedOutlines() : c.getOutlines());
        if (path == null) {
            return;
        }
        boolean done = c.getStatus() != Candidate.Status.OFFEN;
        Color color = done ? DONE : COLORS.getOrDefault(c.getRecommendation(), Color.WHITE);
        if (selected) {
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
            g.setColor(color);
            g.fill(path);
            g.setComposite(old);
        }
        g.setColor(color);
        g.setStroke(selected ? SELECTED
                : (c.getMatchClass() == MatchClass.NUR_OSM || c.getStatus() == Candidate.Status.VERWORFEN ? DASHED : NORMAL));
        g.draw(path);
        if (fitted && c.getStatus() == Candidate.Status.OFFEN) {
            // ursprünglicher ALKIS-Umriss zum Vergleich (vor der Anpassung an Nachbargebäude)
            g.setStroke(THIN_DASHED);
            g.draw(ringsPath(mv, c.getOutlines()));
        }
    }

    private static Path2D ringsPath(MapView mv, List<List<LatLon>> rings) {
        Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (List<LatLon> ring : rings) {
            boolean first = true;
            for (LatLon ll : ring) {
                Point2D p = mv.getPoint2D(ll);
                if (first) {
                    path.moveTo(p.getX(), p.getY());
                    first = false;
                } else {
                    path.lineTo(p.getX(), p.getY());
                }
            }
            path.closePath();
        }
        return path;
    }

    private static Path2D osmPath(MapView mv, OsmPrimitive p) {
        if (!(p instanceof Way) || p.isDeleted()) {
            return null;
        }
        Path2D path = new Path2D.Double();
        boolean first = true;
        for (Node n : ((Way) p).getNodes()) {
            if (!n.isLatLonKnown()) {
                return null;
            }
            Point2D pt = mv.getPoint2D(n);
            if (first) {
                path.moveTo(pt.getX(), pt.getY());
                first = false;
            } else {
                path.lineTo(pt.getX(), pt.getY());
            }
        }
        return path;
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs", "alkisselector", ImageProvider.ImageSizes.LAYER);
    }

    @Override
    public String getToolTipText() {
        AnalysisSession s = AlkisController.getInstance().getSession();
        return s == null ? "ALKIS-Abgleich (leer)" : "ALKIS-Abgleich: " + s.getCandidates().size() + " Einträge";
    }

    @Override
    public void mergeFrom(Layer from) {
        // nicht zusammenführbar
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        AnalysisSession s = AlkisController.getInstance().getSession();
        if (s == null) {
            return;
        }
        for (Candidate c : s.getCandidates()) {
            for (List<LatLon> ring : c.getOutlines()) {
                ring.forEach(v::visit);
            }
        }
    }

    @Override
    public Object getInfoComponent() {
        AnalysisSession s = AlkisController.getInstance().getSession();
        if (s == null) {
            return "Keine Analyse vorhanden.";
        }
        StringBuilder sb = new StringBuilder("<html>Profil: ").append(s.getProfile().getName()).append("<br>");
        Map<Recommendation, Integer> counts = new EnumMap<>(Recommendation.class);
        s.getCandidates().forEach(c -> counts.merge(c.getRecommendation(), 1, Integer::sum));
        counts.forEach((r, n) -> sb.append(r.getLabel()).append(": ").append(n).append("<br>"));
        return sb.append("</html>").toString();
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[] {
            LayerListDialog.getInstance().createShowHideLayerAction(),
            LayerListDialog.getInstance().createDeleteLayerAction(),
            SeparatorLayerAction.INSTANCE,
            new LayerListPopup.InfoAction(this)
        };
    }
}
