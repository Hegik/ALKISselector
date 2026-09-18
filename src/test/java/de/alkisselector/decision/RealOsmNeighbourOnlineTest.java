// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.tools.HttpClient;

import de.alkisselector.TestSupport;
import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.OsmMatcher;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.WfsClient;

/**
 * Echte OSM-Daten (Münster-Kreuzviertel, dichte Wohnbebauung) gegen echtes ALKIS: Alle neuen Gebäude, die an
 * vorhandene OSM-Gebäude grenzen, werden übernommen; danach darf keines ein OSM-Gebäude überlappen.
 * Läuft nur mit {@code -Donline=true}.
 */
@Tag("online")
@EnabledIfSystemProperty(named = "online", matches = "true")
class RealOsmNeighbourOnlineTest {

    private static final Bounds AREA = new Bounds(51.9685, 7.6150, 51.9712, 7.6205);

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @Test
    void adjacentNewBuildingsDoNotOverlapOsm() throws Exception {
        DataSet ds;
        URL url = new URL("https://api.openstreetmap.org/api/0.6/map?bbox=" + AREA.getMinLon() + "," + AREA.getMinLat()
                + "," + AREA.getMaxLon() + "," + AREA.getMaxLat());
        HttpClient.Response resp = HttpClient.create(url).connect();
        try (InputStream in = resp.getContent()) {
            ds = OsmReader.parseDataSet(in, null);
        } finally {
            resp.disconnect();
        }
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Münster", null));

        ServiceProfile profile = DefaultProfiles.nrw();
        CrsTransformer crs = new CrsTransformer(profile.getCrs());
        Envelope env = new Envelope();
        for (LatLon ll : new LatLon[] {AREA.getMin(), AREA.getMax()}) {
            EastNorth en = crs.toProjected(ll);
            env.expandToInclude(en.east(), en.north());
        }
        // die Überprüfung braucht keinen Luftbildabgleich
        profile.setOrthoWmsUrl("");
        AnalysisSession session = new AnalysisSession(profile, crs, ds);
        OsmSnapshot snap = OsmSnapshot.create(ds, crs, AREA);
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings()).analyze(session,
                new WfsClient(profile).fetch(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY(), null).getBuildings(),
                snap, env, null);

        java.util.Map<String, Integer> cls = new java.util.TreeMap<>();
        session.getCandidates().forEach(c -> cls.merge(c.getMatchClass().name(), 1, Integer::sum));
        System.out.println("OSM-Wege: " + ds.getWays().size() + ", Gebäude: " + snap.getBuildings().size()
                + ", Nachbarwege: " + snap.getNeighbourWays().size() + ", Klassen: " + cls);
        int adjacent = 0;
        int clipped = 0;
        int conflicts = 0;
        int applied = 0;
        List<String> examples = new ArrayList<>();
        int replaced = 0;
        int replaceConflicts = 0;
        for (Candidate c : session.getCandidates()) {
            if (c.getMatchClass() == MatchClass.ABWEICHEND && c.getBuilding().isSimple() && c.getMatch().getPartner() != null
                    && c.getMatch().getPartner().getPrimitive() instanceof Way) {
                if (c.getFit() != null && c.getFit().isConflict()) {
                    replaceConflicts++;
                    System.out.println("  Konflikt " + c.getTitle() + ": " + c.getFit().getHints());
                    continue;
                }
                Way w = (Way) c.getMatch().getPartner().getPrimitive();
                // mit anderen Wegen verbundene Knoten vorher merken
                java.util.Set<org.openstreetmap.josm.data.osm.Node> connected = new java.util.HashSet<>();
                for (org.openstreetmap.josm.data.osm.Node n : w.getNodes()) {
                    if (n.getReferrers().size() > 1 || n.hasKeys()) {
                        connected.add(n);
                    }
                }
                assertTrue(new ApplyAction(session).apply(c) != null);
                replaced++;
                for (org.openstreetmap.josm.data.osm.Node n : connected) {
                    assertTrue(w.getNodes().contains(n), c.getTitle() + ": Verbindung über Knoten " + n.getUniqueId() + " verloren");
                }
                assertNoOverlap(crs, ds, w, c.getTitle());
                continue;
            }
            if (c.getMatchClass() != MatchClass.NEU || c.getFit() == null || !c.getFit().isModified()) {
                continue;
            }
            adjacent++;
            if (c.getFit().getClippedArea() > 0) {
                clipped++;
                if (examples.size() < 3) {
                    LatLon ll = crs.toLatLon(c.getGeometry().getCentroid().getX(), c.getGeometry().getCentroid().getY());
                    examples.add(String.format(Locale.ROOT, "%s (%.1f m² abgeschnitten) bei %.6f,%.6f", c.getTitle(),
                            c.getFit().getClippedArea(), ll.lat(), ll.lon()));
                }
            }
            if (c.getFit().isConflict()) {
                conflicts++;
                continue;
            }
            Command cmd = new ApplyAction(session).apply(c);
            if (cmd == null) {
                continue;
            }
            applied++;
            OsmPrimitive p = ds.getSelected().iterator().next();
            if (p instanceof Way) {
                assertNoOverlap(crs, ds, (Way) p, c.getTitle());
            }
        }
        System.out.println("Abweichend ersetzt: " + replaced + ", Konflikte (nicht ersetzt): " + replaceConflicts);
        assertTrue(replaced > 0, "kein abweichendes Gebäude ersetzt");
        System.out.println("Neu und angrenzend: " + adjacent + ", davon abgeschnitten: " + clipped
                + ", Konflikte: " + conflicts + ", übernommen: " + applied);
        examples.forEach(e -> System.out.println("  Beispiel: " + e));
        assertTrue(adjacent > 0, "kein angrenzendes neues Gebäude im Testgebiet");
        assertEquals(adjacent - conflicts, applied);
    }

    /** Kein Knoten eines Nachbargebäudes darf knapp neben einer Kante von {@code w} liegen, ohne verbunden zu sein. */
    private static int assertNoDanglingNeighbourNodes(CrsTransformer crs, DataSet ds, Way w, String title) {
        int checked = 0;
        for (Way other : ds.searchWays(w.getBBox())) {
            if (other == w || !other.isUsable() || !OsmMatcher.isBuilding(other)) {
                continue;
            }
            for (org.openstreetmap.josm.data.osm.Node n : other.getNodes()) {
                if (w.getNodes().contains(n)) {
                    continue;
                }
                EastNorth p = crs.toProjected(n);
                for (int i = 0; i + 1 < w.getNodesCount(); i++) {
                    EastNorth a = crs.toProjected(w.getNode(i));
                    EastNorth b = crs.toProjected(w.getNode(i + 1));
                    double[] pr = NeighbourFitter.project(p.east(), p.north(), a.east(), a.north(), b.east(), b.north());
                    if (pr != null && pr[2] > 0.03 && pr[2] < 0.4) {
                        double len = Math.hypot(b.east() - a.east(), b.north() - a.north());
                        if (pr[3] * len > 0.05 && (1 - pr[3]) * len > 0.05) {
                            throw new AssertionError(String.format(Locale.ROOT,
                                    "%s: Knoten %d von Weg %d liegt %.2f m neben der Kante, ohne verbunden zu sein",
                                    title, n.getUniqueId(), other.getUniqueId(), pr[2]));
                        }
                    }
                }
                checked++;
            }
        }
        return checked;
    }

    private static void assertNoOverlap(CrsTransformer crs, DataSet ds, Way w, String title) {
        assertNoDanglingNeighbourNodes(crs, ds, w, title);
        Geometry g = polygon(crs, w);
        for (Way other : ds.searchWays(w.getBBox())) {
            if (other != w && other.isUsable() && other.isClosed() && OsmMatcher.isBuilding(other)) {
                double ov = GeometryComparator.intersectionArea(g, polygon(crs, other));
                assertTrue(ov < 0.1, String.format(Locale.ROOT, "%s überlappt Weg %d um %.2f m²", title, other.getUniqueId(), ov));
            }
        }
    }

    private static Geometry polygon(CrsTransformer crs, Way w) {
        double[] xy = new double[w.getNodesCount() * 2];
        for (int i = 0; i < w.getNodesCount(); i++) {
            EastNorth en = crs.toProjected(w.getNode(i));
            xy[2 * i] = en.east();
            xy[2 * i + 1] = en.north();
        }
        return GeometryComparator.clean(GeometryComparator.FACTORY.createPolygon(GeometryComparator.ring(xy)));
    }
}
