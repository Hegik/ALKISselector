// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

/**
 * Ergebnis des Luftbildabgleichs eines Umrisses.
 */
public final class OrthoResult {

    private final double score;
    private final double offsetX;
    private final double offsetY;
    private final int samples;
    private final int masked;
    private final String error;
    private double overhang;

    /**
     * @param score Anteil der Umrisspunkte mit passender Kante im Luftbild (0..1)
     * @param offsetX bester Versatz in Ostrichtung (m)
     * @param offsetY bester Versatz in Nordrichtung (m)
     * @param samples Anzahl der ausgewerteten Umrisspunkte
     * @param masked Anzahl der Umrisspunkte, die wegen angrenzender Gebäude nicht bewertet wurden
     */
    public OrthoResult(double score, double offsetX, double offsetY, int samples, int masked) {
        this.score = score;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.samples = samples;
        this.masked = masked;
        this.error = null;
    }

    /**
     * @param score Score
     * @param offsetX Versatz Ost (m)
     * @param offsetY Versatz Nord (m)
     * @param samples Anzahl der ausgewerteten Umrisspunkte
     */
    public OrthoResult(double score, double offsetX, double offsetY, int samples) {
        this(score, offsetX, offsetY, samples, 0);
    }

    private OrthoResult(String error) {
        this.score = Double.NaN;
        this.offsetX = 0;
        this.offsetY = 0;
        this.samples = 0;
        this.masked = 0;
        this.error = error;
    }

    /**
     * @param error Fehlermeldung
     * @return Ergebnis ohne Score
     */
    public static OrthoResult failed(String error) {
        return new OrthoResult(error);
    }

    /** @return Score 0..1 oder {@code NaN}, wenn nicht auswertbar */
    public double getScore() {
        return score;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    /** @return Länge des besten Versatzes in Metern */
    public double getOffset() {
        return Math.hypot(offsetX, offsetY);
    }

    public int getSamples() {
        return samples;
    }

    /** @return Anteil (0..1) des Umrisses, der wegen angrenzender Gebäude nicht bewertet wurde */
    public double getMaskedFraction() {
        int total = samples + masked;
        return total > 0 ? (double) masked / total : 0;
    }

    /**
     * @param value angenommener Dachüberstand (m)
     * @return dieses Ergebnis (für Verkettung)
     */
    OrthoResult withOverhang(double value) {
        this.overhang = value;
        return this;
    }

    /** @return Dachüberstand (m), mit dem der beste Score erreicht wurde */
    public double getOverhang() {
        return overhang;
    }

    /** @return ob ein Score vorliegt */
    public boolean isValid() {
        return error == null && !Double.isNaN(score);
    }

    /** @return Fehlermeldung oder {@code null} */
    public String getError() {
        return error;
    }
}
