// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;

/**
 * Hält die ALKIS-Karte (WMS) dauerhaft über den Luftbildern: Wird ein Luftbild (irgendeine andere
 * Imagery-Ebene) hinzugefügt oder werden Ebenen umsortiert, rückt die ALKIS-Karte direkt über das
 * oberste Luftbild. Datenebenen und andere Ebenen bleiben unberührt.
 */
public final class WmsLayerOrder implements LayerChangeListener {

    /** Deckkraft, mit der die ALKIS-Karte angelegt wird. */
    public static final double ALKIS_MAP_OPACITY = 0.5;

    private final LayerManager manager;
    private final Set<Layer> alkisMaps = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean listening;
    private boolean adjusting;

    /**
     * @param manager Ebenenverwaltung (in JOSM {@code MainApplication.getLayerManager()})
     */
    public WmsLayerOrder(LayerManager manager) {
        this.manager = manager;
    }

    /**
     * Fügt eine ALKIS-Karte hinzu: 50 % Deckkraft, direkt über dem obersten Luftbild.
     * @param layer ALKIS-Karte
     */
    public void addAlkisMap(ImageryLayer layer) {
        layer.setOpacity(ALKIS_MAP_OPACITY);
        alkisMaps.add(layer);
        if (!listening) {
            manager.addLayerChangeListener(this);
            listening = true;
        }
        manager.addLayer(layer);
        ensureOrder();
    }

    /**
     * @param layer Ebene
     * @return ob die Ebene eine von diesem Plugin angelegte ALKIS-Karte ist
     */
    public boolean isAlkisMap(Layer layer) {
        return alkisMaps.contains(layer);
    }

    /**
     * Stellt die Reihenfolge her: Jede ALKIS-Karte liegt über allen anderen Imagery-Ebenen.
     * (Index 0 ist die oberste Ebene.)
     */
    public void ensureOrder() {
        if (adjusting) {
            return;
        }
        adjusting = true;
        try {
            for (Layer alkis : alkisMaps.toArray(new Layer[0])) {
                List<Layer> layers = manager.getLayers();
                int alkisIdx = layers.indexOf(alkis);
                if (alkisIdx < 0) {
                    continue;
                }
                int topImagery = -1;
                for (int i = 0; i < layers.size(); i++) {
                    Layer l = layers.get(i);
                    if (l instanceof ImageryLayer && !alkisMaps.contains(l)) {
                        topImagery = i;
                        break;
                    }
                }
                if (topImagery >= 0 && alkisIdx > topImagery) {
                    manager.moveLayer(alkis, topImagery);
                }
            }
        } finally {
            adjusting = false;
        }
    }

    private void ensureOrderLater() {
        if (!adjusting) {
            SwingUtilities.invokeLater(this::ensureOrder);
        }
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        ensureOrderLater();
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        alkisMaps.remove(e.getRemovedLayer());
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        ensureOrderLater();
    }
}
