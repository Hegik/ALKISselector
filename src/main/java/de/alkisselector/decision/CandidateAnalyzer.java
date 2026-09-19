// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.compare.GeometryComparator;
import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.MatchResult;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.compare.OsmMatcher;
import de.alkisselector.config.AlkisSettings;
import de.alkisselector.config.AttributeMapping;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.config.TagProposal;
import de.alkisselector.ortho.EdgeMap;
import de.alkisselector.ortho.EdgeSupportScorer;
import de.alkisselector.ortho.GeoImage;
import de.alkisselector.ortho.OrthoFetcher;
import de.alkisselector.ortho.OrthoResult;
import de.alkisselector.source.AlkisBuilding;
import de.alkisselector.source.CrsTransformer;

/**
 * Analyse-Pipeline je ALKIS-Gebäude: Attribute übersetzen → mit OSM vergleichen →
 * Luftbildabgleich → Empfehlung.
 */
public final class CandidateAnalyzer {

    /** Anzahl paralleler Orthophoto-Abfragen. */
    private static final int ORTHO_THREADS = 4;

    private final ServiceProfile profile;
    private final CrsTransformer crs;
    private final Params params;

    /**
     * @param profile Dienstprofil
     * @param crs Arbeits-CRS
     * @param params Schwellenwerte
     */
    public CandidateAnalyzer(ServiceProfile profile, CrsTransformer crs, Params params) {
        this.profile = profile;
        this.crs = crs;
        this.params = params;
    }

    /**
     * Führt die Analyse aus und füllt die Kandidatenliste der Sitzung.
     * @param session Sitzung
     * @param buildings geladene ALKIS-Gebäude
     * @param osm OSM-Momentaufnahme
     * @param area untersuchter Bereich im Arbeits-CRS für die Suche nach „nur OSM“; {@code null} = nicht suchen
     * @param monitor Fortschrittsanzeige (darf {@code null} sein)
     */
    public void analyze(AnalysisSession session, List<AlkisBuilding> buildings, OsmSnapshot osm, Envelope area,
            ProgressMonitor monitor) {
        ProgressMonitor pm = monitor != null ? monitor : NullProgressMonitor.INSTANCE;
        List<AlkisBuilding> kept = new ArrayList<>();
        List<AttributeMapping.Result> mappings = new ArrayList<>();
        List<Geometry> geoms = new ArrayList<>();
        List<Geometry> allGeoms = new ArrayList<>();
        int excluded = 0;
        for (AlkisBuilding b : buildings) {
            Geometry g;
            try {
                g = GeometryComparator.toGeometry(b);
            } catch (IllegalArgumentException e) {
                Logging.warn("ALKISselector: ungültige Geometrie " + b.getId() + ": " + e.getMessage());
                continue;
            }
            if (g.isEmpty() || g.getArea() <= 0) {
                continue;
            }
            allGeoms.add(g);
            AttributeMapping.Result m = AttributeMapping.map(profile, b.getAttributes());
            if (m.isExcluded()) {
                excluded++;
                continue;
            }
            kept.add(b);
            mappings.add(m);
            geoms.add(g);
        }
        session.setExcludedCount(session.getExcludedCount() + excluded);

        pm.setCustomText("Vergleich mit OSM …");
        List<MatchResult> matches = OsmMatcher.classify(geoms, osm.getBuildings(), params.matching);

        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            AlkisBuilding b = kept.get(i);
            MatchResult mr = matches.get(i);
            Candidate c = new Candidate(b, geoms.get(i), outlines(b), mr);
            for (TagProposal t : mappings.get(i).getTags()) {
                c.getTags().add(new TagProposal(t.getKey(), t.getValue(), t.getOrigin()));
            }
            c.getHints().addAll(mappings.get(i).getHints());
            prepareTags(c, osm);
            if (mr.getNote() != null) {
                c.getHints().add(mr.getNote());
            }
            fitToNeighbours(c, osm);
            result.add(c);
        }

        STRtree neighbours = new STRtree();
        for (Geometry g : allGeoms) {
            neighbours.insert(g.getEnvelopeInternal(), g);
        }
        scoreOrtho(result, neighbours, pm);
        for (Candidate c : result) {
            c.setRecommendation(recommend(c, params.orthoThreshold));
        }

        if (area != null && !session.isTruncated()) {
            for (OsmBuilding o : OsmMatcher.findOsmOnly(allGeoms, osm.getBuildings(), area)) {
                Candidate c = new Candidate(o, new MatchResult(MatchClass.NUR_OSM, List.of(o), Double.NaN, Double.NaN, null));
                c.getHints().add("Kein ALKIS-Gebäude an dieser Stelle – abgerissen, nicht im Kataster oder falsch erfasst?");
                result.add(c);
            }
        }
        result.sort(SPATIAL_ORDER);
        session.getCandidates().addAll(result);
    }

    /**
     * Berechnet die an vorhandene Nachbargebäude angepasste Geometrie (Vorschau, Hinweise,
     * Konflikterkennung) auf Basis der OSM-Momentaufnahme. Sobald der Kandidat ausgewählt wird,
     * rechnet die Oberfläche mit dem aktuellen Datenstand neu ({@link ApplyAction#computeFit}).
     */
    private void fitToNeighbours(Candidate c, OsmSnapshot osm) {
        NeighbourFitter.Result fit;
        NeighbourFitter fitter = new NeighbourFitter(params.fitTolerance, params.clipOverlaps);
        if (c.getMatchClass() == MatchClass.NEU) {
            if (osm.getNeighbourWays().isEmpty()) {
                return;
            }
            fit = fitter.fit(c.getGeometry(), osm.getNeighbourWays());
        } else if (c.getMatchClass() == MatchClass.ABWEICHEND && c.getMatch().getPartner() != null
                && c.getBuilding().isSimple()) {
            // beim Ersetzen: an Nachbarn (ohne das zu ersetzende Gebäude) anpassen und
            // Verbindungen zu angrenzenden Wegen/Eingängen festhalten
            Object partner = c.getMatch().getPartner().getPrimitive();
            List<NeighbourFitter.NeighbourWay> others = new ArrayList<>();
            for (NeighbourFitter.NeighbourWay n : osm.getNeighbourWays()) {
                if (n.getHandle() != partner) {
                    others.add(n);
                }
            }
            fit = fitter.fit(c.getGeometry(), others, osm.getKeepNodes(c.getMatch().getPartner().getPrimitive()));
        } else {
            return;
        }
        c.setFit(fit, crs);
    }

    private List<List<LatLon>> outlines(AlkisBuilding b) {
        List<List<LatLon>> out = new ArrayList<>();
        for (AlkisBuilding.Polygon p : b.getPolygons()) {
            out.add(toLatLon(p.getOuter()));
            for (double[] h : p.getHoles()) {
                out.add(toLatLon(h));
            }
        }
        return out;
    }

    private List<LatLon> toLatLon(double[] ring) {
        List<LatLon> l = new ArrayList<>(ring.length / 2);
        for (int i = 0; i + 1 < ring.length; i += 2) {
            l.add(crs.toLatLon(ring[i], ring[i + 1]));
        }
        return l;
    }

    /**
     * Gleicht Tag-Vorschläge mit dem bestehenden OSM-Gebäude und vorhandenen Adressen ab.
     */
    private void prepareTags(Candidate c, OsmSnapshot osm) {
        OsmBuilding partner = c.getMatch().getPartner();
        if (partner != null && c.getMatchClass() != MatchClass.NEU) {
            for (TagProposal t : c.getTags()) {
                t.setExistingValue(partner.getPrimitive().get(t.getKey()));
            }
        }
        TagProposal street = find(c, "addr:street");
        TagProposal number = find(c, "addr:housenumber");
        if (street != null && number != null && street.isSelected() && number.isSelected()) {
            org.locationtech.jts.geom.Point centroid = c.getGeometry().getCentroid();
            OsmSnapshot.AddressEntry e = osm.findAddress(street.getValue(), number.getValue(),
                    centroid.getX(), centroid.getY(), params.addressRadius,
                    partner != null ? partner.getPrimitive() : null);
            if (e != null) {
                street.setSelected(false);
                number.setSelected(false);
                c.getHints().add("Adresse „" + street.getValue() + " " + number.getValue() + "“ ist in OSM bereits vorhanden ("
                        + e.getPrimitive().getDisplayType() + " " + e.getPrimitive().getId() + ") – nicht doppelt gesetzt");
            }
        }
    }

    private static TagProposal find(Candidate c, String key) {
        for (TagProposal t : c.getTags()) {
            if (t.getKey().equals(key)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Bildet die Maske der Umrissabschnitte, die an andere ALKIS-Objekte grenzen (Brandwände,
     * Anbauten, Auskragungen). Dort ist im Luftbild keine Kante zu erwarten.
     */
    Geometry neighbourMask(Geometry own, STRtree index) {
        Envelope env = new Envelope(own.getEnvelopeInternal());
        env.expandBy(params.orthoTolerance + 0.1);
        List<Geometry> parts = new ArrayList<>();
        for (Object o : index.query(env)) {
            Geometry g = (Geometry) o;
            if (g == own || g.equalsExact(own, 1e-6)) {
                continue;
            }
            try {
                // Objekte innerhalb des eigenen Gebäudes (z. B. Durchfahrten) verdecken keine Außenkante
                if (own.covers(g) || GeometryComparator.overlapOfSmaller(own, g) > 0.9) {
                    continue;
                }
                if (own.distance(g) <= params.orthoTolerance) {
                    parts.add(g);
                }
            } catch (RuntimeException e) {
                Logging.trace(e);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return UnaryUnionOp.union(parts).buffer(params.orthoTolerance);
    }

    private void scoreOrtho(List<Candidate> candidates, STRtree neighbours, ProgressMonitor pm) {
        List<Candidate> todo = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.getMatchClass() == MatchClass.NEU || c.getMatchClass() == MatchClass.ABWEICHEND) {
                todo.add(c);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        if (!profile.hasOrtho()) {
            todo.forEach(c -> c.setOrtho(OrthoResult.failed("Kein Orthophoto-Dienst im Profil konfiguriert")));
            return;
        }
        OrthoFetcher fetcher = new OrthoFetcher(profile);
        EdgeSupportScorer scorer = new EdgeSupportScorer(params.orthoTolerance, params.orthoMaxOffset, 0.25,
                params.orthoMaxOverhang);
        pm.setTicksCount(todo.size());
        pm.setCustomText("Luftbildabgleich …");
        ExecutorService pool = Executors.newFixedThreadPool(ORTHO_THREADS, r -> {
            Thread t = new Thread(r, "alkisselector-ortho");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Candidate c : todo) {
                futures.add(pool.submit(() -> {
                    if (!pm.isCanceled()) {
                        scoreOne(c, fetcher, scorer, neighbourMask(c.getGeometry(), neighbours));
                    }
                    synchronized (pm) {
                        pm.worked(1);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Logging.warn(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void scoreOne(Candidate c, OrthoFetcher fetcher, EdgeSupportScorer scorer, Geometry mask) {
        Envelope env = new Envelope(c.getGeometry().getEnvelopeInternal());
        OsmBuilding partner = c.getMatch().getPartner();
        if (partner != null) {
            env.expandToInclude(partner.getGeometry().getEnvelopeInternal());
        }
        env.expandBy(params.orthoMaxOffset + params.orthoMaxOverhang + params.orthoTolerance + 2);
        try {
            GeoImage img = fetcher.fetch(env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());
            EdgeMap edges = EdgeMap.compute(img.getImage());
            OrthoResult alkis = scorer.score(c.getGeometry(), img, edges, mask);
            c.setOrtho(alkis);
            if (alkis.isValid() && alkis.getMaskedFraction() >= 0.25) {
                c.getHints().add(String.format(Locale.GERMAN,
                        "%.0f %% des Umrisses grenzen an Nachbargebäude und wurden im Luftbild nicht bewertet",
                        alkis.getMaskedFraction() * 100));
            }
            if (partner != null && c.getMatchClass() == MatchClass.ABWEICHEND && alkis.isValid()) {
                // OSM beim selben Bildversatz bewerten, sonst würde die Versatzsuche eine Verschiebung kaschieren
                c.setOsmOrtho(scorer.scoreAt(partner.getGeometry(), img, edges, mask, alkis.getOffsetX(), alkis.getOffsetY(),
                        alkis.getOverhang()));
            }
        } catch (IOException | RuntimeException e) {
            Logging.debug(e);
            c.setOrtho(OrthoResult.failed("Luftbild nicht verfügbar: " + e.getMessage()));
        }
    }

    /**
     * Leitet die Empfehlung aus Vergleichsklasse und Luftbild-Score ab.
     * @param c Kandidat
     * @param threshold Schwellenwert des Luftbild-Scores
     * @return Empfehlung
     */
    static Recommendation recommend(Candidate c, double threshold) {
        switch (c.getMatchClass()) {
        case IDENTISCH:
            return Recommendation.NICHTS_ZU_TUN;
        case KOMPLEX:
            return Recommendation.MANUELL;
        case NUR_OSM:
            return Recommendation.HINWEIS;
        default:
            break;
        }
        OrthoResult o = c.getOrtho();
        if (o == null || !o.isValid()) {
            if (o != null && o.getError() != null) {
                c.getHints().add(o.getError());
            }
            return Recommendation.UNGEPRUEFT;
        }
        if (o.getScore() < threshold) {
            c.getHints().add(String.format(Locale.GERMAN,
                    "Nur %.0f %% des ALKIS-Umrisses sind im Luftbild als Kante erkennbar (Schwelle %.0f %%)",
                    o.getScore() * 100, threshold * 100));
            return Recommendation.DISKREPANZ;
        }
        OrthoResult osm = c.getOsmOrtho();
        if (osm != null && osm.isValid() && osm.getScore() > o.getScore() + 0.05) {
            c.getHints().add(String.format(Locale.GERMAN,
                    "Das Luftbild passt besser zur bestehenden OSM-Geometrie (%.0f %%) als zu ALKIS (%.0f %%)",
                    osm.getScore() * 100, o.getScore() * 100));
            return Recommendation.DISKREPANZ;
        }
        return Recommendation.UEBERNAHME_EMPFOHLEN;
    }

    /** Sortiert in Streifen von 50 m von Nord nach Süd, innerhalb eines Streifens von West nach Ost. */
    private static final Comparator<Candidate> SPATIAL_ORDER = Comparator
            .comparingDouble((Candidate c) -> -Math.floor(c.getGeometry().getCentroid().getY() / 50))
            .thenComparingDouble(c -> c.getGeometry().getCentroid().getX());

    /**
     * Schwellenwerte der Analyse.
     */
    public static final class Params {
        final OsmMatcher.Thresholds matching;
        final double orthoThreshold;
        final double orthoTolerance;
        final double orthoMaxOffset;
        final double orthoMaxOverhang;
        final double addressRadius;
        double fitTolerance = 0.5;
        boolean clipOverlaps = true;

        /**
         * @param matching Schwellen der OSM-Zuordnung
         * @param orthoThreshold Schwelle des Luftbild-Scores
         * @param orthoTolerance Toleranzband (m)
         * @param orthoMaxOffset maximaler Versatz (m)
         * @param orthoMaxOverhang maximaler Dachüberstand (m)
         * @param addressRadius Suchradius für vorhandene Adressen (m)
         */
        public Params(OsmMatcher.Thresholds matching, double orthoThreshold, double orthoTolerance, double orthoMaxOffset,
                double orthoMaxOverhang, double addressRadius) {
            this.matching = matching;
            this.orthoThreshold = orthoThreshold;
            this.orthoTolerance = orthoTolerance;
            this.orthoMaxOffset = orthoMaxOffset;
            this.orthoMaxOverhang = orthoMaxOverhang;
            this.addressRadius = addressRadius;
        }

        /**
         * @param tolerance Abstand (m) für den Anschluss an Nachbargebäude
         * @param clip Überlappungen abschneiden
         * @return diese Parameter (für Verkettung)
         */
        public Params withFit(double tolerance, boolean clip) {
            this.fitTolerance = tolerance;
            this.clipOverlaps = clip;
            return this;
        }

        /** @return Parameter aus den aktuellen Einstellungen */
        public static Params fromSettings() {
            return new Params(
                    new OsmMatcher.Thresholds(
                            AlkisSettings.IDENTICAL_IOU.get(),
                            AlkisSettings.IDENTICAL_HAUSDORFF.get(),
                            AlkisSettings.PARTNER_OVERLAP.get(),
                            AlkisSettings.DEVIATING_MIN_IOU.get()),
                    AlkisSettings.ORTHO_THRESHOLD.get(),
                    AlkisSettings.ORTHO_TOLERANCE.get(),
                    AlkisSettings.ORTHO_MAX_OFFSET.get(),
                    AlkisSettings.ORTHO_MAX_OVERHANG.get(),
                    AlkisSettings.ADDRESS_SEARCH_RADIUS.get())
                    .withFit(AlkisSettings.FIT_TOLERANCE.get(), AlkisSettings.isClipOverlaps());
        }
    }
}
