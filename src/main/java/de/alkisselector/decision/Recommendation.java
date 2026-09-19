// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

/**
 * Empfehlung des Plugins für einen Kandidaten. Die endgültige Entscheidung trifft immer der Nutzer.
 */
public enum Recommendation {
    /** Luftbild bestätigt die ALKIS-Geometrie – Übernahme empfohlen (Enter). */
    UEBERNAHME_EMPFOHLEN("Übernahme empfohlen"),
    /**
     * OSM-Gebäude stimmt fast mit ALKIS überein, muss aber exakt angeglichen werden, bevor ein
     * angrenzendes neues Objekt angebaut wird (Enter).
     */
    ANGLEICHEN("An ALKIS angleichen"),
    /** Luftbild bestätigt die ALKIS-Geometrie nicht – keine Standardaktion, Übernahme nur mit Umschalt+Enter. */
    DISKREPANZ("Diskrepanz zum Luftbild"),
    /** Kein Luftbildabgleich möglich (kein Dienst oder Fehler) – keine Standardaktion. */
    UNGEPRUEFT("Nicht mit Luftbild geprüft"),
    /** Zuordnung mehrdeutig – manuell bearbeiten. */
    MANUELL("Manuell bearbeiten"),
    /** ALKIS und OSM stimmen überein. */
    NICHTS_ZU_TUN("Nichts zu tun"),
    /** Reiner Hinweis (z. B. OSM-Gebäude ohne ALKIS-Gegenstück). */
    HINWEIS("Hinweis");

    private final String label;

    Recommendation(String label) {
        this.label = label;
    }

    /** @return deutsche Bezeichnung */
    public String getLabel() {
        return label;
    }

    /** @return ob der Nutzer die ALKIS-Geometrie übernehmen kann */
    public boolean isApplicable() {
        return this == UEBERNAHME_EMPFOHLEN || this == ANGLEICHEN || this == DISKREPANZ || this == UNGEPRUEFT;
    }

    /** @return ob die Übernahme ohne zusätzliche Bestätigung (Enter) möglich ist */
    public boolean isDefaultAccept() {
        return this == UEBERNAHME_EMPFOHLEN || this == ANGLEICHEN;
    }

    @Override
    public String toString() {
        return label;
    }
}
