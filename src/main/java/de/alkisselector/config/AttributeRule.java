// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Eine Regel, die ein ALKIS-Attribut in ein oder mehrere OSM-Tags übersetzt.
 * <p>
 * Die Regeln werden pro {@link ServiceProfile} gespeichert und im Einstellungsdialog bearbeitet.
 * Jede Regel lässt sich einzeln abschalten.
 */
public final class AttributeRule {

    /** Art der Übersetzung. */
    public enum Type {
        /** Gebäudeart über die {@link TagMapping}-Tabelle; erzeugt immer {@code building=*}. */
        BUILDING("Gebäudeart (Tabelle)"),
        /** Wert unverändert übernehmen. */
        DIRECT("Wert direkt übernehmen"),
        /** Wert als positive Ganzzahl übernehmen (z. B. Geschosszahl). */
        INTEGER("Ganzzahl"),
        /** Wert über eine eigene Wertetabelle übersetzen; nicht gelistete Werte werden ignoriert. */
        TABLE("Wertetabelle"),
        /** Lagebezeichnung in {@code addr:street} und {@code addr:housenumber} zerlegen. */
        ADDRESS("Adresse (Straße + Hausnummer)");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        /** @return deutsche Bezeichnung für die Oberfläche */
        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private boolean enabled;
    private String alkisAttribute;
    private String osmKey;
    private Type type;
    private final Map<String, String> valueTable = new LinkedHashMap<>();

    /**
     * Erzeugt eine Regel.
     * @param enabled ob die Regel aktiv ist
     * @param alkisAttribute Name des ALKIS-Attributs (lokaler Elementname im GML, z. B. {@code anzahlgs})
     * @param osmKey Ziel-Key in OSM (bei {@link Type#ADDRESS} und {@link Type#BUILDING} ohne Bedeutung)
     * @param type Art der Übersetzung
     */
    public AttributeRule(boolean enabled, String alkisAttribute, String osmKey, Type type) {
        this.enabled = enabled;
        this.alkisAttribute = alkisAttribute;
        this.osmKey = osmKey;
        this.type = type;
    }

    /**
     * Kopierkonstruktor.
     * @param other zu kopierende Regel
     */
    public AttributeRule(AttributeRule other) {
        this(other.enabled, other.alkisAttribute, other.osmKey, other.type);
        valueTable.putAll(other.valueTable);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAlkisAttribute() {
        return alkisAttribute;
    }

    public void setAlkisAttribute(String alkisAttribute) {
        this.alkisAttribute = alkisAttribute;
    }

    public String getOsmKey() {
        return osmKey;
    }

    public void setOsmKey(String osmKey) {
        this.osmKey = osmKey;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = Objects.requireNonNull(type);
    }

    /** @return veränderbare Wertetabelle (nur für {@link Type#TABLE}) */
    public Map<String, String> getValueTable() {
        return valueTable;
    }

    JsonObject toJson() {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("enabled", enabled)
                .add("alkisAttribute", nz(alkisAttribute))
                .add("osmKey", nz(osmKey))
                .add("type", type.name());
        if (!valueTable.isEmpty()) {
            JsonObjectBuilder t = Json.createObjectBuilder();
            valueTable.forEach(t::add);
            b.add("valueTable", t);
        }
        return b.build();
    }

    static AttributeRule fromJson(JsonObject o) {
        AttributeRule r = new AttributeRule(
                o.getBoolean("enabled", true),
                o.getString("alkisAttribute", ""),
                o.getString("osmKey", ""),
                Type.valueOf(o.getString("type", Type.DIRECT.name())));
        JsonObject t = o.getJsonObject("valueTable");
        if (t != null) {
            for (Map.Entry<String, JsonValue> e : t.entrySet()) {
                if (e.getValue() instanceof JsonString) {
                    r.valueTable.put(e.getKey(), ((JsonString) e.getValue()).getString());
                }
            }
        }
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
