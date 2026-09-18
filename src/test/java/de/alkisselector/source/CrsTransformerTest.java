// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;

import de.alkisselector.TestSupport;
import de.alkisselector.config.DefaultProfiles;

class CrsTransformerTest {

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @Test
    void normalizesCrsIdentifiers() {
        assertEquals("EPSG:25832", CrsTransformer.normalize("urn:ogc:def:crs:EPSG::25832"));
        assertEquals("EPSG:25832", CrsTransformer.normalize("http://www.opengis.net/def/crs/EPSG/0/25832"));
        assertEquals("EPSG:25833", CrsTransformer.normalize("epsg:25833"));
    }

    @Test
    void transformsUtm32ToWgs84AndBack() {
        CrsTransformer t = new CrsTransformer("EPSG:25832");
        assertTrue(t.isMetric());
        assertEquals("urn:ogc:def:crs:EPSG::25832", t.getUrn());
        // Schloss Münster, Referenzwert aus dem NRW-WFS
        LatLon ll = t.toLatLon(405443.515, 5757941.967);
        assertEquals(51.960, ll.lat(), 0.005);
        assertEquals(7.622, ll.lon(), 0.005);
        EastNorth en = t.toProjected(ll);
        assertEquals(405443.515, en.east(), 0.01);
        assertEquals(5757941.967, en.north(), 0.01);
    }

    @Test
    void wgs84IsNotMetric() {
        assertFalse(new CrsTransformer("EPSG:4326").isMetric());
    }

    @Test
    void unknownCrsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CrsTransformer("EPSG:999999"));
    }

    @Test
    void buildsWfs20Request() {
        WfsClient c = new WfsClient(DefaultProfiles.nrw());
        String url = c.buildGetFeatureUrl(405300, 5757800, 405600, 5758100, 1000, 2000, true);
        assertTrue(url.startsWith("https://www.wfs.nrw.de/geobasis/wfs_nw_alkis_vereinfacht?SERVICE=WFS"), url);
        assertTrue(url.contains("TYPENAMES=ave%3AGebaeudeBauwerk"), url);
        assertTrue(url.contains("BBOX=405300.000%2C5757800.000%2C405600.000%2C5758100.000%2Curn%3Aogc%3Adef%3Acrs%3AEPSG%3A%3A25832"), url);
        assertTrue(url.contains("COUNT=1000") && url.contains("STARTINDEX=2000"), url);
    }

    @Test
    void appendQueryKeepsExistingParameters() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("SERVICE", "WFS");
        q.put("REQUEST", "GetCapabilities");
        assertEquals("https://x.de/wfs?map=a&SERVICE=WFS&REQUEST=GetCapabilities", WfsClient.appendQuery("https://x.de/wfs?map=a", q));
        assertEquals("https://x.de/wfs?service=wfs&REQUEST=GetCapabilities", WfsClient.appendQuery("https://x.de/wfs?service=wfs", q));
        assertEquals("https://x.de/wfs?SERVICE=WFS&REQUEST=GetCapabilities", WfsClient.appendQuery("https://x.de/wfs?", q));
    }
}
