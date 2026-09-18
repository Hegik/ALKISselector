// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class GmlBuildingParserTest {

    private static List<AlkisBuilding> parse(String xml) throws WfsException {
        return new GmlBuildingParser(false).parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "oid");
    }

    @Test
    void parsesRealNrwResponse() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/gml/nrw-gebaeude-sample.xml")) {
            assertNotNull(in, "Testdatei fehlt");
            List<AlkisBuilding> list = new GmlBuildingParser(false).parse(in, "oid");
            assertEquals(40, list.size());
            AlkisBuilding first = list.get(0);
            assertTrue(first.getId().startsWith("DENW"), first.getId());
            assertNotNull(first.getAttributes().get("funktion"));
            assertNotNull(first.getAttributes().get("gebnutzbez"));
            for (AlkisBuilding b : list) {
                double[] outer = b.getPolygons().get(0).getOuter();
                // geschlossen, UTM-Zone 32 in Münster
                assertEquals(outer[0], outer[outer.length - 2], 1e-9);
                assertEquals(outer[1], outer[outer.length - 1], 1e-9);
                assertTrue(outer[0] > 400_000 && outer[0] < 410_000, "Ostwert " + outer[0]);
                assertTrue(outer[1] > 5_750_000 && outer[1] < 5_760_000, "Nordwert " + outer[1]);
                // Geometrie-Element darf nicht als Attribut auftauchen
                assertFalse(b.getAttributes().containsKey("geometrie"));
            }
        }
    }

    @Test
    void parsesHolesMultiSurfaceAnd3d() throws Exception {
        String xml = "<wfs:FeatureCollection xmlns:wfs='http://www.opengis.net/wfs/2.0' xmlns:gml='http://www.opengis.net/gml/3.2'>"
                + "<wfs:member><Gebaeude gml:id='g1'><funktion>Wohngebäude</funktion><geometrie>"
                + "<gml:MultiSurface srsDimension='3'><gml:surfaceMember><gml:Polygon>"
                + "<gml:exterior><gml:LinearRing><gml:posList>0 0 5 10 0 5 10 10 5 0 10 5 0 0 5</gml:posList></gml:LinearRing></gml:exterior>"
                + "<gml:interior><gml:LinearRing><gml:posList>2 2 5 4 2 5 4 4 5 2 2 5</gml:posList></gml:LinearRing></gml:interior>"
                + "</gml:Polygon></gml:surfaceMember><gml:surfaceMember><gml:Polygon>"
                + "<gml:exterior><gml:LinearRing><gml:pos>20 0 1</gml:pos><gml:pos>30 0 1</gml:pos><gml:pos>30 5 1</gml:pos></gml:LinearRing></gml:exterior>"
                + "</gml:Polygon></gml:surfaceMember></gml:MultiSurface></geometrie></Gebaeude></wfs:member>"
                + "</wfs:FeatureCollection>";
        List<AlkisBuilding> list = parse(xml);
        assertEquals(1, list.size());
        AlkisBuilding b = list.get(0);
        assertEquals("g1", b.getId(), "Fallback auf gml:id");
        assertEquals("Wohngebäude", b.getAttributes().get("funktion"));
        assertEquals(2, b.getPolygons().size());
        assertEquals(1, b.getPolygons().get(0).getHoles().size());
        assertFalse(b.isSimple());
        // zweiter Ring war nicht geschlossen und wird geschlossen
        double[] second = b.getPolygons().get(1).getOuter();
        assertEquals(8, second.length);
        assertEquals(20, second[6], 1e-9);
        assertEquals(0, second[7], 1e-9);
    }

    @Test
    void swapsAxesIfConfigured() throws Exception {
        String xml = "<FeatureCollection xmlns:gml='http://www.opengis.net/gml'><gml:featureMember><B>"
                + "<geom><gml:Polygon><gml:outerBoundaryIs><gml:LinearRing>"
                + "<gml:coordinates>1,2 1,3 2,3 1,2</gml:coordinates>"
                + "</gml:LinearRing></gml:outerBoundaryIs></gml:Polygon></geom></B></gml:featureMember></FeatureCollection>";
        List<AlkisBuilding> list = new GmlBuildingParser(true)
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "oid");
        assertEquals(1, list.size());
        double[] r = list.get(0).getPolygons().get(0).getOuter();
        assertEquals(2, r[0], 1e-9);
        assertEquals(1, r[1], 1e-9);
    }

    @Test
    void reportsServiceException() {
        String xml = "<ows:ExceptionReport xmlns:ows='http://www.opengis.net/ows/1.1'><ows:Exception>"
                + "<ows:ExceptionText>Unknown type name 'foo'</ows:ExceptionText></ows:Exception></ows:ExceptionReport>";
        WfsException e = assertThrows(WfsException.class, () -> parse(xml));
        assertTrue(e.getMessage().contains("Unknown type name"), e.getMessage());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(WfsException.class, () -> parse("kein xml"));
    }
}
