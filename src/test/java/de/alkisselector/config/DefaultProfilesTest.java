// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DefaultProfilesTest {

    @Test
    void profilesAreCompleteAndUnique() {
        List<ServiceProfile> all = DefaultProfiles.all();
        Set<String> names = new HashSet<>();
        for (ServiceProfile p : all) {
            assertTrue(names.add(p.getName()), "doppelter Name: " + p.getName());
            assertFalse(p.getSourceTag().isBlank(), p.getName());
            assertTrue(p.getWfsUrl().startsWith("https://"), p.getName());
            assertTrue(p.getCrs().equals("EPSG:25832") || p.getCrs().equals("EPSG:25833"), p.getName());
            assertTrue(p.hasOrtho(), p.getName());
            assertFalse(p.getTagMapping().getTable().isEmpty(), p.getName());
        }
        assertEquals(8, all.size());
    }

    @Test
    void onlyDefaultServicesAreVerified() {
        ServiceProfile hamburg = DefaultProfiles.hamburg();
        assertTrue(DefaultProfiles.isVerified(hamburg));
        hamburg.setWfsUrl("http://geodienste.hamburg.de/WFS_HH_ALKIS_vereinfacht/?SERVICE=WFS");
        assertTrue(DefaultProfiles.isVerified(hamburg), "Schema, Parameter und Schrägstrich egal");

        ServiceProfile own = new ServiceProfile();
        DefaultProfiles.addStandardAlkisSettings(own);
        own.setBuildingTypeName("ave:GebaeudeBauwerk");
        own.setWfsUrl("https://geoservices.bayern.de/wfs/v1/ogc_alkis_ave.cgi");
        assertFalse(DefaultProfiles.isVerified(own));
        own.setWfsUrl("https://opendata.lgln.niedersachsen.de/doorman/noauth/alkis_wfs_einfach");
        assertFalse(DefaultProfiles.isVerified(own), "Niedersachsen ist nicht eindeutig freigegeben");
    }

    @Test
    void berlinSurvivesJsonRoundTrip() {
        ServiceProfile p = ServiceProfile.fromJson(DefaultProfiles.berlin().toJson());
        assertEquals("alkis_gebaeude:gebaeude", p.getBuildingTypeName());
        assertEquals("uuid", p.getIdAttribute());
        assertEquals(List.of("bezgfk"), p.getTagMapping().getLookupAttributes());
        assertEquals("bezgfk", p.getAttributeRules().get(0).getAlkisAttribute());
        assertTrue(AttributeMapping.map(p, java.util.Map.of("bezeich", "AX_Bauteil")).isExcluded());
        assertEquals("house", AttributeMapping.map(p, java.util.Map.of("bezeich", "AX_Gebaeude", "bezgfk", "Wohnhaus"))
                .getTags().get(0).getValue());
    }
}
