// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Profilunabhängige Schwellenwerte und Einstellungen des Plugins (gespeichert in den JOSM-Einstellungen).
 */
public final class AlkisSettings {

    private static final String P = "alkisselector.";

    /** IoU, ab der ALKIS und OSM als identisch gelten. */
    public static final Setting IDENTICAL_IOU = new Setting("identical.iou", 0.95);
    /** Maximale Hausdorff-Distanz (m), bis zu der ALKIS und OSM als identisch gelten. */
    public static final Setting IDENTICAL_HAUSDORFF = new Setting("identical.hausdorff", 0.5);
    /** Anteil der Überlappung (bezogen auf die kleinere Fläche), ab dem ein OSM-Gebäude als Partner gilt. */
    public static final Setting PARTNER_OVERLAP = new Setting("partner.overlap", 0.3);
    /** Minimale IoU, damit ein einzelner Partner als „abweichend“ statt „komplex“ gilt. */
    public static final Setting DEVIATING_MIN_IOU = new Setting("deviating.min_iou", 0.3);
    /** Kanten-Übereinstimmung mit dem Orthophoto, ab der die Übernahme empfohlen wird. */
    public static final Setting ORTHO_THRESHOLD = new Setting("ortho.threshold", 0.7);
    /** Toleranzband um den Umriss, in dem eine Kante im Luftbild gesucht wird (m). */
    public static final Setting ORTHO_TOLERANCE = new Setting("ortho.tolerance", 0.3);
    /** Maximaler Versatz (m), um den der Umriss für den Luftbildabgleich verschoben werden darf. */
    public static final Setting ORTHO_MAX_OFFSET = new Setting("ortho.max_offset", 1.5);
    /** Größter angenommener Dachüberstand (m) beim Luftbildabgleich. */
    public static final Setting ORTHO_MAX_OVERHANG = new Setting("ortho.max_overhang", 0.9);
    /** Maximale Fläche (km²) für die Ausschnittsanalyse. */
    public static final Setting MAX_AREA_KM2 = new Setting("analysis.max_area_km2", 1.0);
    /** Abstand (m), bis zu dem neue Umrisse an vorhandene Nachbargebäude angeschlossen werden. */
    public static final Setting FIT_TOLERANCE = new Setting("apply.fit_tolerance", 0.5);
    /** Suchradius (m) für vorhandene Adressobjekte mit gleicher Adresse. */
    public static final Setting ADDRESS_SEARCH_RADIUS = new Setting("apply.address_radius", 50);

    private AlkisSettings() {
        // Hilfsklasse
    }

    /** @return ob Überlappungen mit vorhandenen Nachbargebäuden abgeschnitten werden */
    public static boolean isClipOverlaps() {
        return Config.getPref().getBoolean(P + "apply.clip_overlaps", true);
    }

    /** @param clip Überlappungen abschneiden */
    public static void setClipOverlaps(boolean clip) {
        Config.getPref().putBoolean(P + "apply.clip_overlaps", clip);
    }

    /** @return ob das Entscheidungsprotokoll (CSV) geschrieben wird */
    public static boolean isDecisionLogEnabled() {
        return Config.getPref().getBoolean(P + "log.enabled", true);
    }

    /** @param enabled Entscheidungsprotokoll schreiben */
    public static void setDecisionLogEnabled(boolean enabled) {
        Config.getPref().putBoolean(P + "log.enabled", enabled);
    }

    /**
     * Ein numerischer Einstellungswert mit Standardwert.
     */
    public static final class Setting {
        private final String key;
        private final double def;

        Setting(String key, double def) {
            this.key = P + key;
            this.def = def;
        }

        /** @return aktueller Wert */
        public double get() {
            return Config.getPref().getDouble(key, def);
        }

        /** @param value neuer Wert */
        public void put(double value) {
            Config.getPref().putDouble(key, value);
        }

        /** @return Standardwert */
        public double getDefault() {
            return def;
        }
    }
}
