// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.alkisselector.TestSupport;

class AttributeMappingTest {

    @BeforeAll
    static void init() {
        TestSupport.initPreferences();
    }

    private static Map<String, String> tags(AttributeMapping.Result r) {
        return r.getTags().stream().collect(Collectors.toMap(TagProposal::getKey, TagProposal::getValue));
    }

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void mapsResidentialBuildingWithAddress() {
        ServiceProfile p = DefaultProfiles.nrw();
        AttributeMapping.Result r = AttributeMapping.map(p, attrs(
                "funktion", "Wohngebäude", "gebnutzbez", "Gebäude",
                "lagebeztxt", "Hittorfstraße 46 a", "anzahlgs", "3"));
        assertFalse(r.isExcluded());
        Map<String, String> t = tags(r);
        assertEquals("residential", t.get("building"));
        assertEquals("Hittorfstraße", t.get("addr:street"));
        assertEquals("46a", t.get("addr:housenumber"));
        assertEquals("3", t.get("building:levels"));
        assertEquals("building", r.getTags().get(0).getKey());
    }

    @Test
    void lookupIgnoresCaseAndFallsBackToSecondAttribute() {
        ServiceProfile p = DefaultProfiles.nrw();
        assertEquals("roof", tags(AttributeMapping.map(p, attrs("funktion", "ÜBERDACHUNG"))).get("building"));
        // unbekannte Funktion → Standardwert und Hinweis
        AttributeMapping.Result r = AttributeMapping.map(p, attrs("funktion", "Raumstation", "gebnutzbez", "Gebäude"));
        assertEquals("yes", tags(r).get("building"));
        assertEquals(1, r.getHints().size());
    }

    @Test
    void excludesBuildingPartsAndUndergroundStructures() {
        ServiceProfile p = DefaultProfiles.nrw();
        assertTrue(AttributeMapping.map(p, attrs("funktion", "Auskragender Geschossteil", "gebnutzbez", "Bauteil")).isExcluded());
        assertTrue(AttributeMapping.map(p, attrs("funktion", "Durchfahrt im Gebäude")).isExcluded());
        assertTrue(AttributeMapping.map(p, attrs("funktion", "Wohngebäude", "rellage", "Unter der Erdoberfläche")).isExcluded());
        assertFalse(AttributeMapping.map(p, attrs("funktion", "Wohngebäude", "rellage", "Aufgeständert")).isExcluded());
    }

    @Test
    void disabledRulesAreSkippedButBuildingIsAlwaysSet() {
        ServiceProfile p = DefaultProfiles.nrw();
        p.getAttributeRules().forEach(rule -> rule.setEnabled(false));
        Map<String, String> t = tags(AttributeMapping.map(p, attrs("funktion", "Garage", "lagebeztxt", "Weg 1", "name", "X")));
        assertEquals(Map.of("building", "yes"), t);
    }

    @Test
    void multipleStreetsGiveHintInsteadOfAddress() {
        AttributeMapping.Result r = AttributeMapping.map(DefaultProfiles.nrw(),
                attrs("funktion", "Wohngebäude", "lagebeztxt", "Schmale Straße 17, 19; Veghestraße 26 a"));
        assertNull(tags(r).get("addr:street"));
        assertTrue(r.getHints().stream().anyMatch(h -> h.contains("Mehrere Straßen")));
    }

    @Test
    void valueTableAndIntegerRules() {
        ServiceProfile p = DefaultProfiles.nrw();
        AttributeRule roof = new AttributeRule(true, "dachform", "roof:shape", AttributeRule.Type.TABLE);
        roof.getValueTable().put("Satteldach", "gabled");
        roof.getValueTable().put("Flachdach", "flat");
        p.getAttributeRules().add(roof);
        Map<String, String> t = tags(AttributeMapping.map(p, attrs("funktion", "Wohnhaus", "dachform", "satteldach", "anzahlgs", "zwei")));
        assertEquals("gabled", t.get("roof:shape"));
        assertEquals("house", t.get("building"));
        assertNull(t.get("building:levels"), "keine Zahl → kein Tag");
        // unbekannter Tabellenwert → kein Tag
        assertNull(tags(AttributeMapping.map(p, attrs("dachform", "Kuppeldach"))).get("roof:shape"));
    }

    @Test
    void existingValueDeselectsConflicts() {
        TagProposal same = new TagProposal("building", "house", "x");
        same.setExistingValue("house");
        assertFalse(same.isSelected());
        assertFalse(same.isConflict());
        TagProposal conflict = new TagProposal("building", "house", "x");
        conflict.setExistingValue("yes");
        assertFalse(conflict.isSelected());
        assertTrue(conflict.isConflict());
        TagProposal fresh = new TagProposal("addr:street", "Weg", "x");
        fresh.setExistingValue(null);
        assertTrue(fresh.isSelected());
    }

    @Test
    void profileJsonRoundTrip() {
        ServiceProfile p = DefaultProfiles.nrw();
        p.getAttributeRules().get(3).setEnabled(false);
        String json = ProfileStore.toJsonString(List.of(p), true);
        List<ServiceProfile> back = ProfileStore.parse(new StringReader(json));
        assertEquals(1, back.size());
        ServiceProfile q = back.get(0);
        assertEquals(p.getName(), q.getName());
        assertEquals(p.getWfsUrl(), q.getWfsUrl());
        assertEquals(p.getOrthoResolution(), q.getOrthoResolution(), 1e-9);
        assertEquals(p.getTagMapping().getTable(), q.getTagMapping().getTable());
        assertEquals(p.getExcludeFilters().size(), q.getExcludeFilters().size());
        assertEquals(p.getAttributeRules().size(), q.getAttributeRules().size());
        assertFalse(q.getAttributeRules().get(3).isEnabled());
        assertEquals(ProfileStore.toJsonString(List.of(p), false), ProfileStore.toJsonString(back, false));
    }

    @Test
    void compactProfileFileGetsStandardMapping() throws Exception {
        java.nio.file.Path file = java.nio.file.Paths.get("docs", "beispielprofil-sachsen.json");
        List<ServiceProfile> list = ProfileStore.importFile(file);
        assertEquals(1, list.size());
        ServiceProfile p = list.get(0);
        assertEquals("EPSG:25833", p.getCrs());
        assertTrue(p.getTagMapping().getTable().size() > 100);
        assertEquals(4, p.getAttributeRules().size());
        assertEquals("residential", tags(AttributeMapping.map(p, attrs("funktion", "Wohngebäude"))).get("building"));
    }

    @Test
    void defaultTableIsLoaded() {
        Map<String, String> table = DefaultProfiles.loadDefaultTagTable();
        assertTrue(table.size() > 100, "Einträge: " + table.size());
        assertEquals("residential", table.get("Wohngebäude"));
        assertEquals(TagMapping.EXCLUDE, table.get("Durchfahrt im Gebäude"));
    }
}
