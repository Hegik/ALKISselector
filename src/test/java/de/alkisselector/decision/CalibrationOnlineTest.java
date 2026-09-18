// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.index.strtree.STRtree;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;

import de.alkisselector.TestSupport;
import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.config.AttributeMapping;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.ortho.EdgeMap;
import de.alkisselector.ortho.EdgeSupportScorer;
import de.alkisselector.ortho.GeoImage;
import de.alkisselector.ortho.OrthoFetcher;
import de.alkisselector.ortho.OrthoResult;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.WfsClient;

/**
 * Kalibrierung des Luftbild-Schwellenwerts mit echten NRW-Daten.
 * <p>
 * Positive Fälle: ALKIS-Umrisse realer Gebäude. Negative Fälle: dieselben Umrisse um 5 m
 * verschoben (außerhalb der Versatzsuche) bzw. um 40 % vergrößert – dort passt im Luftbild kein
 * Gebäude. Ergebnis: Trefferquote (TPR) und Fehlalarmrate (FPR) je Schwelle, gespeichert in
 * {@code build/kalibrierung.csv}. Läuft nur mit {@code -Donline=true}.
 */
@Tag("online")
@EnabledIfSystemProperty(named = "online", matches = "true")
class CalibrationOnlineTest {

    /** Untersuchungsgebiete: Name, Mittelpunkt (WGS84), halbe Kantenlänge (m). */
    private static final Object[][] AREAS = {
        {"Münster Innenstadt (Block-/Reihenbebauung)", 51.9625, 7.6180, 150.0},
        {"Münster-Gievenbeck (Einfamilienhäuser)", 51.9660, 7.5600, 150.0},
    };

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @Test
    void calibrateThreshold() throws IOException {
        ServiceProfile profile = DefaultProfiles.nrw();
        CrsTransformer crs = new CrsTransformer(profile.getCrs());
        CandidateAnalyzer.Params params = CandidateAnalyzer.Params.fromSettings();
        CandidateAnalyzer analyzer = new CandidateAnalyzer(profile, crs, params);
        EdgeSupportScorer scorer = new EdgeSupportScorer(params.orthoTolerance, params.orthoMaxOffset, 0.25,
                params.orthoMaxOverhang);
        OrthoFetcher fetcher = new OrthoFetcher(profile);

        StringBuilder csv = new StringBuilder("gebiet;alkis_id;funktion;art;score;versatz_m;dachueberstand_m;maskiert\n");
        List<double[]> all = new ArrayList<>(); // {score, positiv?1:0, gebiet}
        for (int a = 0; a < AREAS.length; a++) {
            String name = (String) AREAS[a][0];
            EastNorth c = crs.toProjected(new LatLon((double) AREAS[a][1], (double) AREAS[a][2]));
            double r = (double) AREAS[a][3];
            List<AlkisBuilding> buildings = new WfsClient(profile)
                    .fetch(c.east() - r, c.north() - r, c.east() + r, c.north() + r, null).getBuildings();
            STRtree index = new STRtree();
            List<Geometry> geoms = new ArrayList<>();
            for (AlkisBuilding b : buildings) {
                Geometry g = GeometryComparator.toGeometry(b);
                index.insert(g.getEnvelopeInternal(), g);
                geoms.add(g);
            }
            int n = 0;
            int failed = 0;
            for (int i = 0; i < buildings.size(); i++) {
                AlkisBuilding b = buildings.get(i);
                Geometry g = geoms.get(i);
                if (AttributeMapping.map(profile, b.getAttributes()).isExcluded() || g.getArea() < 15) {
                    continue;
                }
                Geometry mask = analyzer.neighbourMask(g, index);
                Point ct = g.getCentroid();
                Geometry shifted = AffineTransformation.translationInstance(5, 0).transform(g);
                Geometry scaled = AffineTransformation.scaleInstance(1.4, 1.4, ct.getX(), ct.getY()).transform(g);
                Envelope env = new Envelope(scaled.getEnvelopeInternal());
                env.expandToInclude(shifted.getEnvelopeInternal());
                env.expandBy(params.orthoMaxOffset + params.orthoMaxOverhang + params.orthoTolerance + 2);
                GeoImage img;
                try {
                    img = fetcher.fetch(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());
                } catch (IOException e) {
                    // einzelne Zeitüberschreitungen des Dienstes nicht als Testfehler werten
                    failed++;
                    continue;
                }
                EdgeMap edges = EdgeMap.compute(img.getImage());
                String f = b.getAttributes().getOrDefault("funktion", "");
                n += add(csv, all, a, name, b.getId(), f, "positiv", scorer.score(g, img, edges, mask), 1);
                n += add(csv, all, a, name, b.getId(), f, "verschoben_5m", scorer.score(shifted, img, edges, mask), 0);
                n += add(csv, all, a, name, b.getId(), f, "vergroessert_40pct", scorer.score(scaled, img, edges, mask), 0);
            }
            System.out.println(name + ": " + n + " Bewertungen, " + failed + " Bildabrufe fehlgeschlagen");
        }

        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%-9s", "Schwelle"));
        for (Object[] area : AREAS) {
            table.append(String.format(Locale.ROOT, " | %-22s", ((String) area[0]).split(" \\(")[0] + " TPR/FPR"));
        }
        table.append(" | gesamt TPR/FPR\n");
        for (double t = 0.40; t <= 0.951; t += 0.05) {
            table.append(String.format(Locale.ROOT, "%-9.2f", t));
            for (int a = 0; a <= AREAS.length; a++) {
                int tp = 0, p = 0, fp = 0, neg = 0;
                for (double[] s : all) {
                    if (a < AREAS.length && s[2] != a) {
                        continue;
                    }
                    if (s[1] == 1) {
                        p++;
                        tp += s[0] >= t ? 1 : 0;
                    } else {
                        neg++;
                        fp += s[0] >= t ? 1 : 0;
                    }
                }
                table.append(String.format(Locale.ROOT, " | %5.1f %% / %5.1f %%     ", 100.0 * tp / Math.max(1, p),
                        100.0 * fp / Math.max(1, neg)));
            }
            table.append('\n');
        }
        System.out.println(table);
        Path out = Paths.get("build", "kalibrierung.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, csv.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get("build", "kalibrierung.txt"), table.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(all.size() > 100, "zu wenige Bewertungen: " + all.size());
    }

    private static int add(StringBuilder csv, List<double[]> all, int area, String name, String id, String f, String kind,
            OrthoResult r, int positive) {
        if (!r.isValid()) {
            return 0;
        }
        csv.append(String.join(";", name, id, f, kind, String.format(Locale.ROOT, "%.4f", r.getScore()),
                String.format(Locale.ROOT, "%.2f", r.getOffset()), String.format(Locale.ROOT, "%.1f", r.getOverhang()),
                String.format(Locale.ROOT, "%.2f", r.getMaskedFraction())))
                .append('\n');
        all.add(new double[] {r.getScore(), positive, area});
        return 1;
    }
}
