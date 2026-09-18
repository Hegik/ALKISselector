// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.compare;

/**
 * Ergebnis des Vergleichs eines ALKIS-Gebäudes mit den OSM-Daten.
 */
public enum MatchClass {
    /** Kein passendes OSM-Gebäude vorhanden. */
    NEU("Neu", "Gebäude fehlt in OSM"),
    /** OSM-Gebäude stimmt praktisch überein. */
    IDENTISCH("Identisch", "OSM stimmt mit ALKIS überein"),
    /** Genau ein OSM-Gebäude, dessen Geometrie abweicht. */
    ABWEICHEND("Abweichend", "OSM-Geometrie weicht von ALKIS ab"),
    /** Mehrdeutige Zuordnung (mehrere Partner, Multipolygon, geringe Überdeckung). */
    KOMPLEX("Komplex", "Zuordnung nicht eindeutig – bitte manuell prüfen"),
    /** OSM-Gebäude ohne ALKIS-Gegenstück (z. B. abgerissen oder nicht im Kataster). */
    NUR_OSM("Nur OSM", "In OSM, aber nicht in ALKIS");

    private final String label;
    private final String description;

    MatchClass(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** @return kurze Bezeichnung */
    public String getLabel() {
        return label;
    }

    /** @return Erläuterung */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return label;
    }
}
