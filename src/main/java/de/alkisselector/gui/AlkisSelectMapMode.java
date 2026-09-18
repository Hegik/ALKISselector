// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.Cursor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Einzelklick-Modus (wie areaselector): Ein Klick in die Karte fragt das ALKIS-Gebäude an dieser
 * Stelle ab, vergleicht es mit OSM und dem Luftbild und legt es als Eintrag in den Review-Dialog.
 */
public class AlkisSelectMapMode extends MapMode {

    /** Erzeugt den Modus. */
    public AlkisSelectMapMode() {
        super("ALKIS-Gebäude auswählen", "alkisselector",
                "Auf ein Gebäude klicken, um es mit ALKIS abzugleichen und zu übernehmen",
                Shortcut.registerShortcut("mapmode:alkisselector", "Modus: ALKIS-Gebäude auswählen",
                        KeyEvent.VK_K, Shortcut.ALT_CTRL),
                Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    @Override
    public void enterMode() {
        super.enterMode();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.addMouseListener(this);
        }
    }

    @Override
    public void exitMode() {
        super.exitMode();
        MapFrame map = MainApplication.getMap();
        if (map != null) {
            map.mapView.removeMouseListener(this);
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1 || !isEnabled()) {
            return;
        }
        MapFrame map = MainApplication.getMap();
        if (map == null) {
            return;
        }
        LatLon ll = map.mapView.getLatLon(e.getX(), e.getY());
        AlkisController.getInstance().analyzePoint(ll);
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }
}
