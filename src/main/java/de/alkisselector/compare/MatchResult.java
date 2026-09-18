// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

import java.util.Collections;
import java.util.List;

/**
 * Vergleichsergebnis für ein ALKIS-Gebäude.
 */
public final class MatchResult {

    private final MatchClass matchClass;
    private final List<OsmBuilding> partners;
    private final double iou;
    private final double hausdorff;
    private final String note;

    /**
     * @param matchClass Einstufung
     * @param partners zugeordnete OSM-Gebäude
     * @param iou IoU zum (einzigen) Partner, sonst {@code NaN}
     * @param hausdorff Hausdorff-Distanz zum (einzigen) Partner in m, sonst {@code NaN}
     * @param note Erläuterung (z. B. Grund für „komplex“), darf {@code null} sein
     */
    public MatchResult(MatchClass matchClass, List<OsmBuilding> partners, double iou, double hausdorff, String note) {
        this.matchClass = matchClass;
        this.partners = Collections.unmodifiableList(partners);
        this.iou = iou;
        this.hausdorff = hausdorff;
        this.note = note;
    }

    public MatchClass getMatchClass() {
        return matchClass;
    }

    public List<OsmBuilding> getPartners() {
        return partners;
    }

    /** @return einziger Partner oder {@code null} */
    public OsmBuilding getPartner() {
        return partners.size() == 1 ? partners.get(0) : null;
    }

    public double getIou() {
        return iou;
    }

    public double getHausdorff() {
        return hausdorff;
    }

    public String getNote() {
        return note;
    }
}
