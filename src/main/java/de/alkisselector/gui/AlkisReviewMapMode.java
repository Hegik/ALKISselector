// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.List;

import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Shortcut;

import de.alkisselector.decision.Candidate;

/**
 * Prüfmodus für den Stapel-Review: Solange er aktiv ist, gelten die Entscheidungstasten des
 * ALKIS-Fensters (Enter, Umschalt+Enter, Entf, Leertaste, Rücktaste, V, M) immer für den aktuellen
 * Eintrag, auch wenn die Karte den Fokus hat. In der Karte lässt sich frei umschauen: Ziehen mit der
 * linken Maustaste verschiebt die Karte, ein Klick auf ein ALKIS-Gebäude wählt dessen Eintrag.
 * OSM-Objekte werden in diesem Modus weder ausgewählt noch verändert. Esc beendet den Modus.
 */
public class AlkisReviewMapMode extends MapMode implements KeyEventDispatcher {

    private Point dragFrom;
    private boolean dispatcherInstalled;

    /** Erzeugt den Modus. */
    public AlkisReviewMapMode() {
        super("ALKIS prüfen", "alkisreview",
                "Einträge des ALKIS-Abgleichs per Tastatur entscheiden und dabei frei in der Karte umschauen",
                Shortcut.registerShortcut("mapmode:alkisreview", "Modus: ALKIS prüfen", KeyEvent.VK_P, Shortcut.ALT_CTRL),
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public void enterMode() {
        super.enterMode();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.addMouseListener(this);
            map.mapView.addMouseMotionListener(this);
            map.statusLine.setHelpText("ALKIS prüfen: Enter übernehmen · Entf verwerfen · Leertaste überspringen · "
                    + "Rücktaste zurücksetzen · V alt/neu · M Problem melden · Esc beendet den Modus");
        }
        if (!dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
            dispatcherInstalled = true;
        }
    }

    @Override
    public void exitMode() {
        super.exitMode();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.removeMouseListener(this);
            map.mapView.removeMouseMotionListener(this);
            map.statusLine.setHelpText("");
        }
        if (dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
            dispatcherInstalled = false;
        }
        dragFrom = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        ReviewDialog dialog = AlkisController.getInstance().getDialog();
        Component focus = e.getComponent();
        if (e.getID() != KeyEvent.KEY_PRESSED || !isInMainWindow(focus) || isTyping(focus)
                || (dialog != null && dialog.isInTagTable(focus))) {
            return false;
        }
        KeyStroke ks = KeyStroke.getKeyStrokeForEvent(e);
        if (ks.equals(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))) {
            MapFrame map = MainApplication.getMap();
            if (map != null) {
                SwingUtilities.invokeLater(() -> map.selectSelectTool(false));
                e.consume();
                return true;
            }
            return false;
        }
        if (dialog != null && dialog.handleReviewKey(ks)) {
            e.consume();
            return true;
        }
        return false;
    }

    /** Nur Tastendrücke im Hauptfenster (keine Dialoge wie „Problem melden“ oder die Einstellungen). */
    private static boolean isInMainWindow(Component c) {
        Window w = c != null ? SwingUtilities.getWindowAncestor(c) : null;
        if (c instanceof Window) {
            w = (Window) c;
        }
        return w != null && w == MainApplication.getMainFrame();
    }

    /** Beim Schreiben in Textfeldern oder beim Bearbeiten einer Tabellenzelle bleiben die Tasten normal. */
    private static boolean isTyping(Component c) {
        if (c instanceof JTextComponent && ((JTextComponent) c).isEditable()) {
            return true;
        }
        JTable table = c instanceof JTable ? (JTable) c : (JTable) SwingUtilities.getAncestorOfClass(JTable.class, c);
        return table != null && table.isEditing();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            dragFrom = e.getPoint();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        dragFrom = null;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        MapFrame map = MainApplication.getMap();
        if (dragFrom == null || map == null || !SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        MapView mv = map.mapView;
        EastNorth from = mv.getEastNorth(dragFrom.x, dragFrom.y);
        EastNorth to = mv.getEastNorth(e.getX(), e.getY());
        mv.zoomTo(mv.getCenter().add(from.subtract(to)));
        dragFrom = e.getPoint();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        MapFrame map = MainApplication.getMap();
        if (e.getButton() != MouseEvent.BUTTON1 || map == null) {
            return;
        }
        Candidate hit = candidateAt(map.mapView, e.getPoint());
        ReviewDialog dialog = AlkisController.getInstance().getDialog();
        if (hit != null && dialog != null) {
            dialog.selectCandidate(hit);
        }
    }

    /** @return der kleinste sichtbare Eintrag, dessen Umriss den Punkt enthält, oder {@code null} */
    static Candidate candidateAt(MapView mv, Point p) {
        Candidate best = null;
        double bestArea = Double.MAX_VALUE;
        for (Candidate c : AlkisController.getInstance().getVisibleCandidates()) {
            List<List<LatLon>> rings = c.getOutlines();
            if (rings == null || rings.isEmpty()) {
                continue;
            }
            Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            for (List<LatLon> ring : rings) {
                boolean first = true;
                for (LatLon ll : ring) {
                    Point q = mv.getPoint(ll);
                    if (first) {
                        path.moveTo(q.x, q.y);
                        first = false;
                    } else {
                        path.lineTo(q.x, q.y);
                    }
                }
                path.closePath();
            }
            if (path.contains(p)) {
                double area = path.getBounds2D().getWidth() * path.getBounds2D().getHeight();
                if (area < bestArea) {
                    best = c;
                    bestArea = area;
                }
            }
        }
        return best;
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }
}
