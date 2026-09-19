// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
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
import de.alkisselector.decision.ChangePreview;
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
        COLORS.put(Recommendation.ANGLEICHEN, new Color(0x00, 0xa8, 0xc8));
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
    /** Farbe der bisherigen OSM-Geometrie im Vergleich. */
    public static final Color OLD = new Color(0x30, 0x90, 0xff);
    private static final Color DELETED = new Color(0xe0, 0x20, 0x20);
    private static final Color ARROW = new Color(0xff, 0xe0, 0x40);
    private static final Color ARROW_OUTLINE = new Color(0x20, 0x20, 0x20);
    private static final Stroke OLD_DASHED = new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] {8f, 5f}, 0f);
    private static final Stroke NORMAL_THIN = new BasicStroke(1.5f);
    private static final Stroke ARROW_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke ARROW_BACK = new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    /** Mindestlänge eines Verschiebungspfeils in Pixeln. */
    private static final double MIN_ARROW_PX = 12;

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
            ChangePreview preview = AlkisController.getInstance().getPreview(selected);
            if (preview == null || selected.getMatchClass() == MatchClass.NUR_OSM) {
                draw(g, mv, selected, true);
            } else {
                drawComparison(g, mv, selected, preview, AlkisController.getInstance().getViewMode());
            }
        }
    }

    /**
     * Zeichnet den ausgewählten Kandidaten als Vergleich alt/neu:
     * NEU = neue Geometrie kräftig, bisherige blau gestrichelt, Verschiebungen als Pfeile;
     * ALT = nur die bisherige Geometrie kräftig in Blau.
     */
    private static void drawComparison(Graphics2D g, MapView mv, Candidate c, ChangePreview p,
            AlkisController.ViewMode mode) {
        Color color = COLORS.getOrDefault(c.getRecommendation(), Color.WHITE);
        Composite oldComposite = g.getComposite();
        if (mode == AlkisController.ViewMode.ALT) {
            if (!p.getOldOutlines().isEmpty()) {
                Path2D old = ringsPath(mv, p.getOldOutlines());
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                g.setColor(OLD);
                g.fill(old);
                g.setComposite(oldComposite);
                g.setStroke(SELECTED);
                g.draw(old);
            }
            badge(g, mv, AlkisController.ViewMode.ALT.getLabel()
                    + (p.getOldOutlines().isEmpty() ? " (Gebäude noch nicht vorhanden)" : "") + "  ·  V = umschalten", OLD);
            return;
        }
        Path2D neu = ringsPath(mv, p.getNewOutlines());
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
        g.setColor(color);
        g.fill(neu);
        g.setComposite(oldComposite);
        g.setStroke(SELECTED);
        g.draw(neu);
        if (!p.getOldOutlines().isEmpty()) {
            g.setColor(OLD);
            g.setStroke(OLD_DASHED);
            g.draw(ringsPath(mv, p.getOldOutlines()));
        } else if (!c.getFittedOutlines().isEmpty()) {
            // ursprünglicher ALKIS-Umriss vor der Anpassung an Nachbargebäude
            g.setStroke(THIN_DASHED);
            g.draw(ringsPath(mv, c.getOutlines()));
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
        for (LatLon[] m : p.getMoves()) {
            arrow(g, mv.getPoint2D(m[0]), mv.getPoint2D(m[1]));
        }
        for (LatLon ll : p.getCreated()) {
            Point2D pt = mv.getPoint2D(ll);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(pt.getX() - 3.5, pt.getY() - 3.5, 7, 7));
            g.setColor(color.darker());
            g.setStroke(NORMAL_THIN);
            g.draw(new Ellipse2D.Double(pt.getX() - 3.5, pt.getY() - 3.5, 7, 7));
        }
        g.setColor(DELETED);
        g.setStroke(NORMAL);
        for (LatLon ll : p.getDeleted()) {
            Point2D pt = mv.getPoint2D(ll);
            g.draw(new Line2D.Double(pt.getX() - 4, pt.getY() - 4, pt.getX() + 4, pt.getY() + 4));
            g.draw(new Line2D.Double(pt.getX() - 4, pt.getY() + 4, pt.getX() + 4, pt.getY() - 4));
        }
        g.setComposite(oldComposite);
        badge(g, mv, AlkisController.ViewMode.NEU.getLabel() + "  ·  V = umschalten", color);
    }

    /** Pfeil von a nach b, nur wenn er auf dem Bildschirm lang genug ist, um nicht zu stören. */
    private static void arrow(Graphics2D g, Point2D a, Point2D b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double len = Math.hypot(dx, dy);
        if (len < MIN_ARROW_PX) {
            return;
        }
        double head = Math.min(10, len / 2.5);
        double ang = Math.atan2(dy, dx);
        Path2D p = new Path2D.Double();
        p.moveTo(a.getX(), a.getY());
        p.lineTo(b.getX(), b.getY());
        p.moveTo(b.getX() - head * Math.cos(ang - Math.PI / 7), b.getY() - head * Math.sin(ang - Math.PI / 7));
        p.lineTo(b.getX(), b.getY());
        p.lineTo(b.getX() - head * Math.cos(ang + Math.PI / 7), b.getY() - head * Math.sin(ang + Math.PI / 7));
        g.setColor(ARROW_OUTLINE);
        g.setStroke(ARROW_BACK);
        g.draw(p);
        g.setColor(ARROW);
        g.setStroke(ARROW_STROKE);
        g.draw(p);
    }

    /** Hinweis oben in der Karte, welche Ansicht aktiv ist. */
    private static void badge(Graphics2D g, MapView mv, String text, Color accent) {
        Font f = g.getFont().deriveFont(Font.BOLD, 13f);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 20;
        int h = fm.getHeight() + 8;
        int x = (mv.getWidth() - w) / 2;
        int y = 10;
        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
        g.setColor(new Color(30, 30, 30));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setComposite(oldComposite);
        g.setColor(accent);
        g.setStroke(NORMAL);
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 10, y + 4 + fm.getAscent());
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
