// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;

import de.alkisselector.compare.MatchClass;
import de.alkisselector.compare.MatchResult;
import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.TagProposal;
import de.alkisselector.ortho.OrthoResult;
import de.alkisselector.source.AlkisBuilding;

/**
 * Ein Eintrag der Review-Liste: ein ALKIS-Gebäude mit Vergleichsergebnis, Luftbild-Score,
 * Empfehlung und Tag-Vorschlägen – oder ein OSM-Gebäude ohne ALKIS-Gegenstück.
 */
public final class Candidate {

    /** Bearbeitungsstand. */
    public enum Status {
        OFFEN("offen"),
        UEBERNOMMEN("übernommen"),
        VERWORFEN("verworfen"),
        UEBERSPRUNGEN("übersprungen");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final AlkisBuilding building;
    private final OsmBuilding osmOnly;
    private final Geometry geometry;
    private final List<List<LatLon>> outlines;
    private final MatchResult match;
    private OrthoResult ortho;
    private OrthoResult osmOrtho;
    private Recommendation recommendation;
    private final List<TagProposal> tags = new ArrayList<>();
    private final List<String> hints = new ArrayList<>();
    private Status status = Status.OFFEN;
    private long shownSince;
    private Command appliedCommand;

    /**
     * Kandidat für ein ALKIS-Gebäude.
     * @param building ALKIS-Gebäude
     * @param geometry Geometrie im Arbeits-CRS
     * @param outlines Umrisse in WGS84 (für die Darstellung)
     * @param match Vergleichsergebnis
     */
    public Candidate(AlkisBuilding building, Geometry geometry, List<List<LatLon>> outlines, MatchResult match) {
        this.building = building;
        this.osmOnly = null;
        this.geometry = geometry;
        this.outlines = outlines;
        this.match = match;
    }

    /**
     * Kandidat für ein OSM-Gebäude ohne ALKIS-Gegenstück.
     * @param osm OSM-Gebäude
     * @param match Vergleichsergebnis ({@link MatchClass#NUR_OSM})
     */
    public Candidate(OsmBuilding osm, MatchResult match) {
        this.building = null;
        this.osmOnly = osm;
        this.geometry = osm.getGeometry();
        this.outlines = Collections.emptyList();
        this.match = match;
        this.recommendation = Recommendation.HINWEIS;
    }

    /** @return ALKIS-Gebäude oder {@code null} bei {@link MatchClass#NUR_OSM} */
    public AlkisBuilding getBuilding() {
        return building;
    }

    /** @return OSM-Gebäude ohne Gegenstück oder {@code null} */
    public OsmBuilding getOsmOnly() {
        return osmOnly;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    /** @return ALKIS-Umrisse in WGS84 (leer bei {@link MatchClass#NUR_OSM}) */
    public List<List<LatLon>> getOutlines() {
        return outlines;
    }

    public MatchResult getMatch() {
        return match;
    }

    public MatchClass getMatchClass() {
        return match.getMatchClass();
    }

    public OrthoResult getOrtho() {
        return ortho;
    }

    public void setOrtho(OrthoResult ortho) {
        this.ortho = ortho;
    }

    /** @return Luftbild-Score der bestehenden OSM-Geometrie (nur bei „abweichend“) */
    public OrthoResult getOsmOrtho() {
        return osmOrtho;
    }

    public void setOsmOrtho(OrthoResult osmOrtho) {
        this.osmOrtho = osmOrtho;
    }

    public Recommendation getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(Recommendation recommendation) {
        this.recommendation = recommendation;
    }

    /** @return veränderbare Liste der Tag-Vorschläge */
    public List<TagProposal> getTags() {
        return tags;
    }

    /** @return veränderbare Liste der Hinweise */
    public List<String> getHints() {
        return hints;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** @return Zeitpunkt (ms), seit dem der Kandidat angezeigt wird (für die Evaluierung) */
    public long getShownSince() {
        return shownSince;
    }

    public void setShownSince(long shownSince) {
        this.shownSince = shownSince;
    }

    /** @return der Undo-Befehl der Übernahme oder {@code null} */
    public Command getAppliedCommand() {
        return appliedCommand;
    }

    public void setAppliedCommand(Command appliedCommand) {
        this.appliedCommand = appliedCommand;
    }

    /** @return Anzeigename, z. B. {@code Wohngebäude, Hittorfstraße 46 a} */
    public String getTitle() {
        if (building == null) {
            String b = osmOnly.getPrimitive().get("building");
            return "OSM-Gebäude (building=" + b + ")";
        }
        String f = building.getAttributes().get("funktion");
        String l = building.getAttributes().get("lagebeztxt");
        StringBuilder sb = new StringBuilder(f != null ? f : "Gebäude");
        if (l != null && !l.isBlank()) {
            sb.append(", ").append(l);
        }
        return sb.toString();
    }

    /** @return Kennung (ALKIS-OID bzw. OSM-ID) */
    public String getId() {
        return building != null ? building.getId() : osmOnly.getPrimitive().getPrimitiveId().toString();
    }
}
