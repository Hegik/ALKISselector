// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wendet Ausschlussfilter, Gebäudetabelle und Attributregeln eines Profils auf ein ALKIS-Objekt an
 * und erzeugt daraus Tag-Vorschläge.
 */
public final class AttributeMapping {

    private AttributeMapping() {
        // Hilfsklasse
    }

    /**
     * Übersetzt die Attribute eines ALKIS-Objekts.
     * @param profile Dienstprofil
     * @param attributes ALKIS-Attribute (Name → Wert)
     * @return Ergebnis mit Tag-Vorschlägen, Hinweisen oder Ausschlussgrund
     */
    public static Result map(ServiceProfile profile, Map<String, String> attributes) {
        for (ExcludeFilter f : profile.getExcludeFilters()) {
            if (f.matches(attributes)) {
                return Result.excluded("Ausschlussfilter " + f.getAttribute() + "=" + attributes.get(f.getAttribute()));
            }
        }
        List<TagProposal> tags = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        boolean hasBuilding = false;
        for (AttributeRule rule : profile.getAttributeRules()) {
            if (!rule.isEnabled()) {
                continue;
            }
            String attr = rule.getAlkisAttribute();
            String raw = attributes.get(attr);
            switch (rule.getType()) {
            case BUILDING:
                TagMapping.Result r = profile.getTagMapping().lookup(attributes);
                if (r.isExcluded()) {
                    return Result.excluded("Kein eigenständiges Gebäude (" + r.getMatchedBy() + ")");
                }
                tags.add(new TagProposal("building", r.getValue(),
                        r.getMatchedBy() != null ? r.getMatchedBy() : "Standardwert"));
                if (!r.isMatched()) {
                    String f = attributes.get("funktion");
                    if (f != null && !f.isBlank()) {
                        hints.add("Gebäudefunktion „" + f + "“ ist nicht in der Tabelle – building="
                                + r.getValue() + " vorgeschlagen");
                    }
                }
                hasBuilding = true;
                break;
            case DIRECT:
                if (notBlank(raw) && notBlank(rule.getOsmKey())) {
                    tags.add(new TagProposal(rule.getOsmKey().trim(), raw.strip(), attr + "=" + raw.strip()));
                }
                break;
            case INTEGER:
                if (notBlank(raw) && notBlank(rule.getOsmKey())) {
                    Integer n = parsePositiveInt(raw);
                    if (n != null) {
                        tags.add(new TagProposal(rule.getOsmKey().trim(), Integer.toString(n), attr + "=" + raw.strip()));
                    } else {
                        hints.add(attr + "=„" + raw + "“ ist keine positive Ganzzahl");
                    }
                }
                break;
            case TABLE:
                if (notBlank(raw) && notBlank(rule.getOsmKey())) {
                    String mapped = lookupIgnoreCase(rule.getValueTable(), raw.strip());
                    if (mapped != null && !mapped.isBlank() && !TagMapping.EXCLUDE.equals(mapped)) {
                        tags.add(new TagProposal(rule.getOsmKey().trim(), mapped.trim(), attr + "=" + raw.strip()));
                    }
                }
                break;
            case ADDRESS:
                if (notBlank(raw)) {
                    AddressParser.Result a = AddressParser.parse(raw);
                    if (a.isValid()) {
                        tags.add(new TagProposal("addr:street", a.getStreet(), attr + "=" + raw.strip()));
                        tags.add(new TagProposal("addr:housenumber", a.getHousenumber(), attr + "=" + raw.strip()));
                    } else if (a.getHint() != null) {
                        hints.add(a.getHint());
                    }
                }
                break;
            default:
                break;
            }
        }
        if (!hasBuilding) {
            tags.add(0, new TagProposal("building", "yes", "Standardwert"));
        }
        return new Result(tags, hints, null);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Integer parsePositiveInt(String s) {
        try {
            double d = Double.parseDouble(s.strip().replace(',', '.'));
            if (d >= 1 && d == Math.rint(d) && d < 1000) {
                return (int) d;
            }
        } catch (NumberFormatException e) {
            // unten: null
        }
        return null;
    }

    private static String lookupIgnoreCase(Map<String, String> table, String key) {
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (e.getKey().trim().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Ergebnis der Übersetzung.
     */
    public static final class Result {
        private final List<TagProposal> tags;
        private final List<String> hints;
        private final String excludeReason;

        Result(List<TagProposal> tags, List<String> hints, String excludeReason) {
            this.tags = tags;
            this.hints = hints;
            this.excludeReason = excludeReason;
        }

        static Result excluded(String reason) {
            return new Result(Collections.emptyList(), Collections.emptyList(), reason);
        }

        /** @return Tag-Vorschläge ({@code building} steht immer an erster Stelle) */
        public List<TagProposal> getTags() {
            return tags;
        }

        /** @return Hinweise für den Nutzer */
        public List<String> getHints() {
            return hints;
        }

        /** @return ob das Objekt nicht als Gebäude angeboten werden soll */
        public boolean isExcluded() {
            return excludeReason != null;
        }

        /** @return Ausschlussgrund oder {@code null} */
        public String getExcludeReason() {
            return excludeReason;
        }
    }
}
