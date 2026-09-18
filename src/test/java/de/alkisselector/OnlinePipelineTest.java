// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Envelope;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.config.AttributeMapping;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.ApplyAction;
import de.alkisselector.decision.Candidate;
import de.alkisselector.decision.CandidateAnalyzer;
import de.alkisselector.decision.OsmSnapshot;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.WfsClient;

/**
 * Ende-zu-Ende-Test gegen die echten Dienste von Geobasis NRW (Münster, Umgebung Schloss).
 * Läuft nur mit {@code ./gradlew test -Donline=true}.
 */
@Tag("online")
@EnabledIfSystemProperty(named = "online", matches = "true")
class OnlinePipelineTest {

    // 250 × 250 m westlich des Schlosses (Wohnbebauung Hittorfstraße / Am Schlossgarten)
    private static final double MIN_X = 404900;
    private static final double MIN_Y = 5757700;
    private static final double MAX_X = 405150;
    private static final double MAX_Y = 5757950;

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    @Test
    void fullPipelineAgainstNrwServices() throws Exception {
        ServiceProfile profile = DefaultProfiles.nrw();
        CrsTransformer crs = new CrsTransformer(profile.getCrs());

        // 1. ALKIS laden
        WfsClient.Result wfs = new WfsClient(profile).fetch(MIN_X, MIN_Y, MAX_X, MAX_Y, null);
        List<AlkisBuilding> buildings = wfs.getBuildings();
        System.out.println("ALKIS-Objekte: " + buildings.size());
        assertTrue(buildings.size() > 20, "zu wenige Objekte: " + buildings.size());

        // 2. Künstlichen OSM-Bestand bauen: Gebäude A 1,5 m versetzt, Gebäude B identisch
        List<AlkisBuilding> simple = new ArrayList<>();
        for (AlkisBuilding b : buildings) {
            if (b.isSimple() && !AttributeMapping.map(profile, b.getAttributes()).isExcluded()
                    && "Wohngebäude".equals(b.getAttributes().get("funktion")) && isInside(b)) {
                simple.add(b);
            }
        }
        assertTrue(simple.size() >= 3, "zu wenige Wohngebäude");
        AlkisBuilding shifted = simple.get(0);
        AlkisBuilding identical = simple.get(1);
        DataSet ds = new DataSet();
        Way shiftedWay = addWay(ds, crs, shifted.getPolygons().get(0).getOuter(), 1.5, 0);
        shiftedWay.put("building", "yes");
        shiftedWay.put("roof:shape", "gabled");
        Way identicalWay = addWay(ds, crs, identical.getPolygons().get(0).getOuter(), 0, 0);
        identicalWay.put("building", "house");
        Bounds bounds = new Bounds(crs.toLatLon(MIN_X - 50, MIN_Y - 50));
        bounds.extend(crs.toLatLon(MAX_X + 50, MAX_Y + 50));
        ds.addDataSource(new DataSource(bounds, "Test"));
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "Test", null));

        // 3. Analyse inkl. Luftbild
        AnalysisSession session = new AnalysisSession(profile, crs, ds);
        long t0 = System.currentTimeMillis();
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings())
                .analyze(session, buildings, OsmSnapshot.create(ds, crs, bounds), new Envelope(MIN_X, MAX_X, MIN_Y, MAX_Y), null);
        System.out.println("Analyse: " + (System.currentTimeMillis() - t0) + " ms, " + session.getCandidates().size()
                + " Kandidaten, " + session.getExcludedCount() + " ausgeschlossen");

        Candidate cShifted = find(session, shifted.getId());
        Candidate cIdentical = find(session, identical.getId());
        assertEquals(MatchClass.ABWEICHEND, cShifted.getMatchClass());
        assertEquals(MatchClass.IDENTISCH, cIdentical.getMatchClass());
        assertTrue(session.getExcludedCount() > 0, "Bauteile sollten ausgeschlossen werden");

        Map<String, Integer> classes = new TreeMap<>();
        List<Double> scores = new ArrayList<>();
        for (Candidate c : session.getCandidates()) {
            classes.merge(c.getMatchClass() + "/" + c.getRecommendation(), 1, Integer::sum);
            if (c.getMatchClass() == MatchClass.NEU) {
                assertNotNull(c.getOrtho());
                assertTrue(c.getOrtho().isValid(), "Luftbild: " + c.getOrtho().getError());
                scores.add(c.getOrtho().getScore());
            }
        }
        System.out.println("Klassen/Empfehlungen: " + classes);
        scores.sort(null);
        System.out.println("Luftbild-Scores (NEU): " + scores.stream().map(s -> String.format(Locale.ROOT, "%.2f", s)).collect(java.util.stream.Collectors.toList()));
        System.out.println(String.format(Locale.ROOT, "Versetztes Gebäude: ALKIS %.2f, OSM %.2f, Versatz %.2f m",
                cShifted.getOrtho().getScore(), cShifted.getOsmOrtho().getScore(), cShifted.getOrtho().getOffset()));
        assertTrue(cShifted.getOsmOrtho().isValid());

        // 4. Neues Gebäude übernehmen
        Candidate neu = session.getCandidates().stream()
                .filter(c -> c.getMatchClass() == MatchClass.NEU && c.getBuilding().isSimple()).findFirst().orElseThrow();
        int before = ds.getWays().size();
        Command cmdNew = new ApplyAction(session).apply(neu);
        assertNotNull(cmdNew);
        assertEquals(before + 1, ds.getWays().size());
        OsmPrimitive created = ds.getSelected().iterator().next();
        assertEquals(neu.getTags().get(0).getValue(), created.get("building"));
        System.out.println("Neu angelegt: " + created.getKeys());
        assertNotNull(ds.getChangeSetTags().get("source"));

        // 5. Abweichendes Gebäude: Geometrie ersetzen, ID und vorhandene Tags bleiben
        long id = shiftedWay.getUniqueId();
        EastNorth oldFirst = crs.toProjected(shiftedWay.getNode(0));
        cShifted.getTags().forEach(t -> System.out.println("  Vorschlag " + t + " (OSM: " + t.getExistingValue()
                + ", gewählt: " + t.isSelected() + ")"));
        Command cmdReplace = new ApplyAction(session).apply(cShifted);
        assertNotNull(cmdReplace);
        assertEquals(cmdReplace, UndoRedoHandler.getInstance().getLastCommand(), "ein Undo-Schritt");
        assertFalse(shiftedWay.isDeleted());
        assertEquals(id, shiftedWay.getUniqueId());
        assertEquals("gabled", shiftedWay.get("roof:shape"), "vorhandene Tags bleiben");
        assertEquals("yes", shiftedWay.get("building"), "Konflikt building=yes wird nicht ungefragt überschrieben");
        double moved = maxNodeDistanceToAlkis(crs, shiftedWay, shifted);
        assertTrue(moved < 0.05, "Weg liegt nach dem Ersetzen auf der ALKIS-Geometrie (Abstand " + moved + ")");

        // 6. Undo stellt den alten Zustand wieder her
        UndoRedoHandler.getInstance().undo();
        EastNorth restored = crs.toProjected(shiftedWay.getNode(0));
        assertEquals(oldFirst.east(), restored.east(), 0.01);
        assertEquals(oldFirst.north(), restored.north(), 0.01);
        assertFalse(created.isDeleted(), "die vorherige Neuanlage bleibt bestehen");
    }

    private static boolean isInside(AlkisBuilding b) {
        double[] e = b.getEnvelope();
        return e[0] > MIN_X + 5 && e[1] > MIN_Y + 5 && e[2] < MAX_X - 5 && e[3] < MAX_Y - 5;
    }

    private static Candidate find(AnalysisSession s, String id) {
        return s.getCandidates().stream().filter(c -> c.getId().equals(id)).findFirst().orElseThrow();
    }

    private static Way addWay(DataSet ds, CrsTransformer crs, double[] ring, double dx, double dy) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i + 3 < ring.length; i += 2) {
            Node n = new Node(crs.toLatLon(ring[i] + dx, ring[i + 1] + dy));
            ds.addPrimitive(n);
            nodes.add(n);
        }
        nodes.add(nodes.get(0));
        Way w = new Way();
        w.setNodes(nodes);
        ds.addPrimitive(w);
        return w;
    }

    /** @return größter Abstand eines Wegknotens zum nächsten ALKIS-Stützpunkt (m) */
    private static double maxNodeDistanceToAlkis(CrsTransformer crs, Way w, AlkisBuilding b) {
        double worst = 0;
        double[] r = b.getPolygons().get(0).getOuter();
        for (Node n : w.getNodes()) {
            EastNorth en = crs.toProjected(n);
            double nearest = Double.MAX_VALUE;
            for (int i = 0; i + 1 < r.length; i += 2) {
                nearest = Math.min(nearest, Math.hypot(r[i] - en.east(), r[i + 1] - en.north()));
            }
            worst = Math.max(worst, nearest);
        }
        return worst;
    }
}
