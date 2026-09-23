// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.tools.HttpClient;

import de.alkisselector.TestSupport;
import de.alkisselector.ortho.GeoImage;
import de.alkisselector.ortho.OrthoFetcher;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.WfsClient;

/**
 * Prüft für jedes Standardprofil, dass WFS, Orthophoto-WMS und die Karten-WMS in einem kleinen
 * Innenstadt-Ausschnitt antworten und die Gebäudeattribute übersetzt werden.
 * Läuft nur mit {@code ./gradlew test -Donline=true}.
 */
@Tag("online")
@EnabledIfSystemProperty(named = "online", matches = "true")
class StateProfilesOnlineTest {

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    /** Profil und 250 × 250 m-Ausschnitt (untere linke Ecke im Profil-CRS). */
    static Stream<Arguments> profiles() {
        return Stream.of(
                Arguments.of(DefaultProfiles.nrw(), 404900, 5757700),
                Arguments.of(DefaultProfiles.berlin(), 392357, 5821044),
                Arguments.of(DefaultProfiles.brandenburg(), 367547, 5807072),
                Arguments.of(DefaultProfiles.hamburg(), 563793, 5934988),
                Arguments.of(DefaultProfiles.hessen(), 444792, 5548574),
                Arguments.of(DefaultProfiles.mecklenburgVorpommern(), 262516, 5948223),
                Arguments.of(DefaultProfiles.rheinlandPfalz(), 446844, 5538990),
                Arguments.of(DefaultProfiles.sachsen(), 412278, 5657115));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void servicesRespond(ServiceProfile profile, double minX, double minY) throws Exception {
        double maxX = minX + 250;
        double maxY = minY + 250;

        WfsClient.Result wfs = new WfsClient(profile).fetch(minX, minY, maxX, maxY, null);
        int total = wfs.getBuildings().size();
        int excluded = 0;
        int mapped = 0;
        Map<String, Integer> unknown = new TreeMap<>();
        for (AlkisBuilding b : wfs.getBuildings()) {
            AttributeMapping.Result r = AttributeMapping.map(profile, b.getAttributes());
            if (r.isExcluded()) {
                excluded++;
                continue;
            }
            String building = r.getTags().stream().filter(t -> "building".equals(t.getKey()))
                    .map(TagProposal::getValue).findFirst().orElse(null);
            if (building != null && !"yes".equals(building)) {
                mapped++;
            }
            r.getHints().forEach(h -> unknown.merge(h, 1, Integer::sum));
            assertTrue(inside(b.getEnvelope(), minX - 500, minY - 500, maxX + 500, maxY + 500),
                    "Koordinaten außerhalb des Ausschnitts – Achsen vertauscht? " + b.getId());
        }
        System.out.println(String.format(Locale.ROOT, "%s: %d Objekte, %d ausgeschlossen, %d mit building-Wert ≠ yes",
                profile.getName(), total, excluded, mapped));
        unknown.forEach((h, n) -> System.out.println("  Hinweis (" + n + "×): " + h));
        assertTrue(total > 20, "zu wenige Objekte: " + total);
        assertTrue(mapped > (total - excluded) / 2, "Gebäudefunktion wird kaum übersetzt");

        GeoImage ortho = new OrthoFetcher(profile).fetch(minX, minY, minX + 50, minY + 50);
        assertTrue(ortho.getImage().getWidth() > 100, "Orthophoto zu klein");
        assertTrue(!isUniform(ortho.getImage()), "Orthophoto ist einfarbig (kein Bild im Ausschnitt?)");

        if (!profile.getAlkisMapUrl().isBlank()) {
            assertWmsImage(profile, profile.getAlkisMapUrl(), profile.getAlkisMapLayers(), minX, minY, maxX, maxY);
        }
        if (profile.hasParcels()) {
            assertWmsImage(profile, profile.getParcelWmsUrl(), profile.getParcelLayers(), minX, minY, maxX, maxY);
        }
    }

    private static void assertWmsImage(ServiceProfile p, String url, String layers, double minX, double minY,
            double maxX, double maxY) throws Exception {
        String bbox = String.format(Locale.ROOT, "%.0f,%.0f,%.0f,%.0f", minX, minY, maxX, maxY);
        String u = OrthoFetcher.josmWmsTemplate(url, layers, "image/png", true)
                .replace("{proj}", p.getCrs()).replace("{width}", "1024").replace("{height}", "1024")
                .replace("{bbox}", bbox);
        HttpClient.Response resp = HttpClient.create(new URL(u)).connect();
        try {
            assertEquals(200, resp.getResponseCode(), u);
            if (!resp.getContentType().startsWith("image")) {
                throw new AssertionError(u + " liefert " + resp.getContentType() + ": " + resp.fetchContent());
            }
            BufferedImage img;
            try (InputStream in = resp.getContent()) {
                img = ImageIO.read(in);
            }
            assertTrue(img != null && !isUniform(img), "leeres Kartenbild: " + u);
        } finally {
            resp.disconnect();
        }
    }

    private static boolean inside(double[] env, double minX, double minY, double maxX, double maxY) {
        return env[0] >= minX && env[1] >= minY && env[2] <= maxX && env[3] <= maxY;
    }

    private static boolean isUniform(BufferedImage img) {
        int first = img.getRGB(0, 0);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) != first) {
                    return false;
                }
            }
        }
        return true;
    }
}
