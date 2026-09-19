// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

import de.alkisselector.TestSupport;

class WmsLayerOrderTest {

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    private static ImageryLayer wms(String name) {
        return ImageryLayer.create(new ImageryInfo(name,
                "https://example.org/wms?SERVICE=WMS&REQUEST=GetMap&CRS={proj}&WIDTH={width}&HEIGHT={height}&BBOX={bbox}",
                "wms", null, null));
    }

    /** Wartet, bis die per invokeLater nachgezogene Sortierung ausgeführt wurde. */
    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void alkisMapStaysAboveOrthophotoWithHalfOpacity() throws Exception {
        LayerManager lm = new LayerManager();
        WmsLayerOrder order = new WmsLayerOrder(lm);
        OsmDataLayer data = new OsmDataLayer(new DataSet(), "Daten", null);
        SwingUtilities.invokeAndWait(() -> lm.addLayer(data));

        ImageryLayer alkis = wms("ALKIS-Karte");
        SwingUtilities.invokeAndWait(() -> order.addAlkisMap(alkis));
        assertEquals(0.5, alkis.getOpacity(), 1e-9);

        // Orthophoto wird erst danach geladen – ALKIS-Karte muss trotzdem darüber liegen
        ImageryLayer ortho = wms("Orthophoto");
        SwingUtilities.invokeAndWait(() -> lm.addLayer(ortho));
        flush();
        assertAbove(lm, alkis, ortho);

        // Nutzer schiebt das Orthophoto ganz nach oben – die ALKIS-Karte rückt wieder darüber
        SwingUtilities.invokeAndWait(() -> lm.moveLayer(ortho, 0));
        flush();
        assertAbove(lm, alkis, ortho);

        // ein zweites Luftbild darüber
        ImageryLayer ortho2 = wms("Anderes Luftbild");
        SwingUtilities.invokeAndWait(() -> {
            lm.addLayer(ortho2);
            lm.moveLayer(ortho2, 0);
        });
        flush();
        assertAbove(lm, alkis, ortho2);
        assertAbove(lm, alkis, ortho);
        assertTrue(order.isAlkisMap(alkis));
    }

    private static void assertAbove(LayerManager lm, Layer upper, Layer lower) {
        List<Layer> layers = lm.getLayers();
        assertTrue(layers.indexOf(upper) < layers.indexOf(lower),
                upper.getName() + " liegt nicht über " + lower.getName() + ": " + layers);
    }
}
