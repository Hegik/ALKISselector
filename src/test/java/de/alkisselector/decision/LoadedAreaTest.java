// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;

import de.alkisselector.TestSupport;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Nur ALKIS-Gebäude, die vollständig im heruntergeladenen OSM-Bereich liegen, werden zu Kandidaten.
 */
class LoadedAreaTest {

    private static final double E = 405100;
    private static final double N = 5757100;

    private final CrsTransformer crs = new CrsTransformer("EPSG:25832");

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    private Bounds bounds(double x0, double y0, double x1, double y1) {
        Bounds b = new Bounds(crs.toLatLon(E + x0, N + y0));
        b.extend(crs.toLatLon(E + x1, N + y1));
        return b;
    }

    private static AlkisBuilding alkis(String id, double x0, double y0, double x1, double y1) {
        double[] ring = {E + x0, N + y0, E + x1, N + y0, E + x1, N + y1, E + x0, N + y1, E + x0, N + y0};
        return new AlkisBuilding(id, List.of(new AlkisBuilding.Polygon(ring, Collections.emptyList())),
                Map.of("funktion", "Wohnhaus"));
    }

    @Test
    void onlyBuildingsCompletelyInsideTheDownloadedAreaBecomeCandidates() {
        DataSet ds = new DataSet();
        // zwei heruntergeladene Bereiche nebeneinander mit gemeinsamer Kante (wie zwei JOSM-Downloads)
        Bounds all = bounds(0, 0, 200, 100);
        double seam = crs.toLatLon(E + 100, N + 50).lon();
        List<Bounds> loaded = List.of(new Bounds(all.getMinLat(), all.getMinLon(), all.getMaxLat(), seam),
                new Bounds(all.getMinLat(), seam, all.getMaxLat(), all.getMaxLon()));
        List<AlkisBuilding> buildings = List.of(
                alkis("INNEN", 10, 10, 20, 20),
                alkis("UEBER_NAHT", 95, 10, 105, 20), // über die Grenze beider Bereiche → vollständig geladen
                alkis("RAND", 195, 10, 215, 20), // ragt über den Ostrand hinaus
                alkis("AUSSEN", 300, 10, 310, 20));
        ServiceProfile profile = DefaultProfiles.nrw();
        profile.setOrthoWmsUrl("");
        AnalysisSession session = new AnalysisSession(profile, crs, ds);
        new CandidateAnalyzer(profile, crs, CandidateAnalyzer.Params.fromSettings())
                .analyze(session, buildings, OsmSnapshot.create(ds, crs, bounds(-50, -50, 350, 150)), null,
                        LoadedArea.of(loaded, crs), null);

        List<String> ids = session.getCandidates().stream().map(Candidate::getId).sorted().collect(Collectors.toList());
        assertEquals(List.of("INNEN", "UEBER_NAHT"), ids);
        assertEquals(2, session.getOutsideCount());
    }

    @Test
    void nothingDownloadedMeansNothingIsCovered() {
        assertTrue(LoadedArea.of(List.of(), crs).isEmpty());
    }
}
