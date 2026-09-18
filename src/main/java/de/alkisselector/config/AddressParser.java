// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt eine ALKIS-Lagebezeichnung (z. B. {@code "Hittorfstraße 46 a"} oder
 * {@code "Am Schlossgarten 14, 16"}) in Straße und Hausnummer(n).
 * <p>
 * Mehrere Hausnummern an derselben Straße werden nach OSM-Konvention mit {@code ;} verbunden
 * ({@code 14;16}). Lagebezeichnungen mit mehreren Straßen (durch {@code ;} getrennt) werden
 * nicht übernommen, weil ein Gebäude-Umriss dann nicht eindeutig einer Adresse zugeordnet werden kann.
 */
public final class AddressParser {

    private static final String NUM = "\\d+(?:\\s*[a-zA-Z])?(?:\\s*[-/]\\s*\\d+(?:\\s*[a-zA-Z])?)?";
    private static final Pattern ADDRESS = Pattern.compile("^(.+?)\\s+(" + NUM + "(?:\\s*,\\s*" + NUM + ")*)$");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private AddressParser() {
        // Hilfsklasse
    }

    /**
     * Zerlegt eine Lagebezeichnung.
     * @param text Lagebezeichnung (darf {@code null} sein)
     * @return Ergebnis; {@link Result#isValid()} gibt an, ob eine Adresse übernommen werden kann
     */
    public static Result parse(String text) {
        if (text == null || text.isBlank()) {
            return Result.invalid(null);
        }
        String[] groups = text.split(";");
        Set<String> streets = new LinkedHashSet<>();
        List<String> numbers = new ArrayList<>();
        for (String g : groups) {
            String t = SPACE.matcher(g.strip()).replaceAll(" ");
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = ADDRESS.matcher(t);
            if (!m.matches()) {
                return Result.invalid("Lagebezeichnung ohne erkennbare Hausnummer: „" + text.strip() + "“");
            }
            streets.add(m.group(1).strip());
            for (String n : m.group(2).split(",")) {
                numbers.add(SPACE.matcher(n.strip()).replaceAll(""));
            }
        }
        if (streets.isEmpty()) {
            return Result.invalid(null);
        }
        if (streets.size() > 1) {
            return Result.invalid("Mehrere Straßen in der Lagebezeichnung („" + text.strip()
                    + "“) – Adresse bitte manuell erfassen");
        }
        return new Result(streets.iterator().next(), String.join(";", numbers), null);
    }

    /**
     * Ergebnis der Zerlegung.
     */
    public static final class Result {
        private final String street;
        private final String housenumber;
        private final String hint;

        Result(String street, String housenumber, String hint) {
            this.street = street;
            this.housenumber = housenumber;
            this.hint = hint;
        }

        static Result invalid(String hint) {
            return new Result(null, null, hint);
        }

        /** @return ob Straße und Hausnummer erkannt wurden */
        public boolean isValid() {
            return street != null && housenumber != null;
        }

        /** @return Straßenname oder {@code null} */
        public String getStreet() {
            return street;
        }

        /** @return Hausnummer(n), mehrere mit {@code ;} getrennt, oder {@code null} */
        public String getHousenumber() {
            return housenumber;
        }

        /** @return Hinweis für den Nutzer, falls keine Adresse übernommen werden kann, sonst {@code null} */
        public String getHint() {
            return hint;
        }
    }
}
