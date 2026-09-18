// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Schließt ALKIS-Objekte anhand eines Attributwerts (regulärer Ausdruck) von der Übernahme aus,
 * z. B. Bauteile ({@code gebnutzbez=Bauteil}) oder unterirdische Bauwerke
 * ({@code rellage=Unter der Erdoberfläche}).
 */
public final class ExcludeFilter {

    private String attribute;
    private String regex;
    private transient Pattern pattern;

    /**
     * @param attribute Name des ALKIS-Attributs
     * @param regex regulärer Ausdruck; ein Treffer irgendwo im Wert schließt das Objekt aus
     *              (Groß-/Kleinschreibung wird ignoriert)
     */
    public ExcludeFilter(String attribute, String regex) {
        this.attribute = attribute;
        setRegex(regex);
    }

    public String getAttribute() {
        return attribute;
    }

    public void setAttribute(String attribute) {
        this.attribute = attribute;
    }

    public String getRegex() {
        return regex;
    }

    /**
     * Setzt den regulären Ausdruck. Ungültige Ausdrücke werden gespeichert, filtern aber nichts.
     * @param regex regulärer Ausdruck
     */
    public void setRegex(String regex) {
        this.regex = regex;
        try {
            this.pattern = regex == null || regex.isEmpty() ? null
                    : Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            this.pattern = null;
        }
    }

    /** @return ob der reguläre Ausdruck gültig ist */
    public boolean isValid() {
        return pattern != null;
    }

    /**
     * @param attributes Attribute eines ALKIS-Objekts
     * @return {@code true}, wenn das Objekt ausgeschlossen werden soll
     */
    public boolean matches(Map<String, String> attributes) {
        if (pattern == null || attribute == null) {
            return false;
        }
        String v = attributes.get(attribute);
        return v != null && pattern.matcher(v).find();
    }

    @Override
    public String toString() {
        return attribute + " ~ " + regex;
    }

    JsonObject toJson() {
        return Json.createObjectBuilder()
                .add("attribute", attribute == null ? "" : attribute)
                .add("regex", regex == null ? "" : regex)
                .build();
    }

    static ExcludeFilter fromJson(JsonObject o) {
        return new ExcludeFilter(o.getString("attribute", ""), o.getString("regex", ""));
    }
}
