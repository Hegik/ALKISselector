// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Übersetzungstabelle ALKIS-Gebäudefunktion → OSM {@code building=*}.
 * <p>
 * Die Tabelle wird in der Reihenfolge der konfigurierten Attribute durchsucht (z. B. zuerst
 * {@code funktion}, dann {@code gebnutzbez}). Der Vergleich ignoriert Groß-/Kleinschreibung und
 * führende/abschließende Leerzeichen. Der Zielwert {@value #EXCLUDE} bedeutet: Das Objekt ist
 * kein eigenständiges Gebäude (z. B. „Durchfahrt im Gebäude“) und wird nicht angeboten.
 */
public final class TagMapping {

    /** Zielwert, der ein Objekt von der Übernahme ausschließt. */
    public static final String EXCLUDE = "-";

    private final List<String> lookupAttributes = new ArrayList<>();
    private final Map<String, String> table = new LinkedHashMap<>();
    private String defaultValue = "yes";

    /** Leere Tabelle, Standardwert {@code yes}. */
    public TagMapping() {
        // leer
    }

    /**
     * Kopierkonstruktor.
     * @param other zu kopierende Tabelle
     */
    public TagMapping(TagMapping other) {
        lookupAttributes.addAll(other.lookupAttributes);
        table.putAll(other.table);
        defaultValue = other.defaultValue;
    }

    /** @return veränderbare Liste der Attribute, in denen nachgeschlagen wird */
    public List<String> getLookupAttributes() {
        return lookupAttributes;
    }

    /** @return veränderbare Tabelle ALKIS-Wert → building-Wert (Reihenfolge bleibt erhalten) */
    public Map<String, String> getTable() {
        return table;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue == null || defaultValue.isBlank() ? "yes" : defaultValue.trim();
    }

    /**
     * Ermittelt den {@code building}-Wert für ein ALKIS-Objekt.
     * @param attributes Attribute des ALKIS-Objekts
     * @return Ergebnis mit Wert und dem ALKIS-Wert, der zum Treffer geführt hat
     */
    public Result lookup(Map<String, String> attributes) {
        for (String attr : lookupAttributes) {
            String v = attributes.get(attr);
            if (v == null || v.isBlank()) {
                continue;
            }
            String hit = find(v);
            if (hit != null) {
                return new Result(hit, attr + "=" + v.trim(), true);
            }
        }
        return new Result(defaultValue, null, false);
    }

    private String find(String alkisValue) {
        String key = normalize(alkisValue);
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (normalize(e.getKey()).equals(key)) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.GERMAN);
    }

    /**
     * Lädt eine Tabelle aus einer CSV-Datei (Trennzeichen {@code ;}, Kommentarzeilen mit {@code #}).
     * @param in Eingabe (UTF-8)
     * @return Einträge in Dateireihenfolge
     * @throws IOException bei Lesefehlern
     */
    public static Map<String, String> readCsv(InputStream in) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int idx = t.lastIndexOf(';');
                if (idx <= 0 || idx == t.length() - 1) {
                    continue;
                }
                result.put(t.substring(0, idx).trim(), t.substring(idx + 1).trim());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    JsonObject toJson() {
        JsonArrayBuilder attrs = Json.createArrayBuilder();
        lookupAttributes.forEach(attrs::add);
        var t = Json.createObjectBuilder();
        table.forEach(t::add);
        return Json.createObjectBuilder()
                .add("lookupAttributes", attrs)
                .add("default", defaultValue)
                .add("table", t)
                .build();
    }

    static TagMapping fromJson(JsonObject o) {
        TagMapping m = new TagMapping();
        var attrs = o.getJsonArray("lookupAttributes");
        if (attrs != null) {
            for (JsonValue v : attrs) {
                if (v instanceof JsonString) {
                    m.lookupAttributes.add(((JsonString) v).getString());
                }
            }
        }
        m.setDefaultValue(o.getString("default", "yes"));
        JsonObject t = o.getJsonObject("table");
        if (t != null) {
            for (Map.Entry<String, JsonValue> e : t.entrySet()) {
                if (e.getValue() instanceof JsonString) {
                    m.table.put(e.getKey(), ((JsonString) e.getValue()).getString());
                }
            }
        }
        return m;
    }

    /**
     * Ergebnis einer Tabellenabfrage.
     */
    public static final class Result {
        private final String value;
        private final String matchedBy;
        private final boolean matched;

        Result(String value, String matchedBy, boolean matched) {
            this.value = value;
            this.matchedBy = matchedBy;
            this.matched = matched;
        }

        /** @return building-Wert oder {@link TagMapping#EXCLUDE} */
        public String getValue() {
            return value;
        }

        /** @return z. B. {@code funktion=Wohngebäude}, oder {@code null} beim Standardwert */
        public String getMatchedBy() {
            return matchedBy;
        }

        /** @return ob ein Tabelleneintrag gefunden wurde (sonst Standardwert) */
        public boolean isMatched() {
            return matched;
        }

        /** @return ob das Objekt ausgeschlossen werden soll */
        public boolean isExcluded() {
            return EXCLUDE.equals(value);
        }
    }
}
