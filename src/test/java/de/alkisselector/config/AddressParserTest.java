// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class AddressParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Hittorfstraße 44|Hittorfstraße|44",
        "Hittorfstraße 46 a|Hittorfstraße|46a",
        "Musterstraße 5a|Musterstraße|5a",
        "Am Schlossgarten 14, 16|Am Schlossgarten|14;16",
        "Wilhelmstraße 21, 21 a|Wilhelmstraße|21;21a",
        "Schlossplatz 2 a, 2 b, 2 c|Schlossplatz|2a;2b;2c",
        "Am Markt 1-3|Am Markt|1-3",
        "Straße des 17. Juni 5|Straße des 17. Juni|5",
        "  Steinfurter   Straße 51  |Steinfurter Straße|51",
    })
    void parsesValidAddresses(String text, String street, String number) {
        AddressParser.Result r = AddressParser.parse(text);
        assertTrue(r.isValid(), text);
        assertEquals(street, r.getStreet());
        assertEquals(number, r.getHousenumber());
        assertNull(r.getHint());
    }

    @Test
    void rejectsMultipleStreets() {
        AddressParser.Result r = AddressParser.parse("Schmale Straße 17, 19; Veghestraße 26 a");
        assertFalse(r.isValid());
        assertNotNull(r.getHint());
        assertTrue(r.getHint().contains("Mehrere Straßen"));
    }

    @Test
    void sameStreetSeparatedBySemicolonIsCombined() {
        AddressParser.Result r = AddressParser.parse("Hauptstraße 1; Hauptstraße 3");
        assertTrue(r.isValid());
        assertEquals("1;3", r.getHousenumber());
    }

    @Test
    void rejectsStreetWithoutNumber() {
        AddressParser.Result r = AddressParser.parse("Hittorfstraße");
        assertFalse(r.isValid());
        assertNotNull(r.getHint());
        assertFalse(AddressParser.parse(null).isValid());
        assertFalse(AddressParser.parse("  ").isValid());
    }
}
