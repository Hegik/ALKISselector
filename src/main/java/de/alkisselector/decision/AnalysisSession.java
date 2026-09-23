// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openstreetmap.josm.data.osm.DataSet;

import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.CrsTransformer;

/**
 * Ergebnis einer Analyse (Ausschnitt oder Einzelklick) mit allen Kandidaten.
 */
public final class AnalysisSession {

    private final ServiceProfile profile;
    private final CrsTransformer crs;
    private final DataSet dataSet;
    private final List<Candidate> candidates = new CopyOnWriteArrayList<>();
    private int excludedCount;
    private int outsideCount;
    private boolean truncated;

    /**
     * @param profile verwendetes Profil (Kopie)
     * @param crs Arbeits-CRS
     * @param dataSet Datensatz, in den übernommen wird
     */
    public AnalysisSession(ServiceProfile profile, CrsTransformer crs, DataSet dataSet) {
        this.profile = profile;
        this.crs = crs;
        this.dataSet = dataSet;
    }

    public ServiceProfile getProfile() {
        return profile;
    }

    public CrsTransformer getCrs() {
        return crs;
    }

    public DataSet getDataSet() {
        return dataSet;
    }

    /** @return veränderbare, threadsichere Kandidatenliste */
    public List<Candidate> getCandidates() {
        return candidates;
    }

    /** @return Anzahl der per Filter ausgeschlossenen ALKIS-Objekte (Bauteile usw.) */
    public int getExcludedCount() {
        return excludedCount;
    }

    public void setExcludedCount(int excludedCount) {
        this.excludedCount = excludedCount;
    }

    /** @return Anzahl der ALKIS-Gebäude, die nicht vollständig im geladenen OSM-Bereich liegen und übersprungen wurden */
    public int getOutsideCount() {
        return outsideCount;
    }

    public void setOutsideCount(int outsideCount) {
        this.outsideCount = outsideCount;
    }

    /** @return ob der WFS nicht alle Objekte geliefert hat */
    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
