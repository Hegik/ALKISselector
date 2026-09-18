// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.tools.Logging;

/**
 * Mitgelieferte Standardprofile.
 */
public final class DefaultProfiles {

    /** Klassenpfad der Standard-Gebäudetabelle. */
    public static final String DEFAULT_TAGMAPPING_RESOURCE = "/de/alkisselector/tagmapping-alkis.csv";

    private DefaultProfiles() {
        // Hilfsklasse
    }

    /** @return alle Standardprofile (derzeit nur NRW) */
    public static List<ServiceProfile> all() {
        return Collections.singletonList(nrw());
    }

    /**
     * Profil für Nordrhein-Westfalen (Geobasis NRW, Datenlizenz Deutschland – Zero – 2.0).
     * @return neues Profil
     */
    public static ServiceProfile nrw() {
        ServiceProfile p = new ServiceProfile();
        p.setName("NRW (Geobasis NRW)");
        p.setDescription("ALKIS vereinfacht (WFS), DOP (WMS) und ALKIS-Flurstücke (WMS) von Geobasis NRW. "
                + "Lizenz: Datenlizenz Deutschland – Zero – Version 2.0 (dl-de/zero-2-0).");
        p.setSourceTag("Land NRW (dl-de/zero-2-0): ALKIS, DOP");
        p.setWfsUrl("https://www.wfs.nrw.de/geobasis/wfs_nw_alkis_vereinfacht");
        p.setWfsVersion("2.0.0");
        p.setBuildingTypeName("ave:GebaeudeBauwerk");
        p.setCrs("EPSG:25832");
        p.setPageSize(1000);
        p.setIdAttribute("oid");
        addStandardAlkisSettings(p);
        p.setOrthoWmsUrl("https://www.wms.nrw.de/geobasis/wms_nw_dop");
        p.setOrthoWmsVersion("1.3.0");
        p.setOrthoLayers("nw_dop_rgb");
        p.setOrthoFormat("image/jpeg");
        p.setOrthoResolution(0.1);
        p.setParcelWmsUrl("https://www.wms.nrw.de/geobasis/wms_nw_alkis");
        p.setParcelLayers("adv_alkis_flurstuecke");
        return p;
    }

    /**
     * Setzt Ausschlussfilter, Gebäudetabelle und Attributregeln für das AdV-Produkt
     * „ALKIS vereinfacht“, das viele Bundesländer in gleicher Struktur anbieten.
     * @param p zu ergänzendes Profil
     */
    public static void addStandardAlkisSettings(ServiceProfile p) {
        p.getExcludeFilters().clear();
        p.getExcludeFilters().add(new ExcludeFilter("gebnutzbez", "^\\s*Bauteil\\s*$"));
        p.getExcludeFilters().add(new ExcludeFilter("rellage", "unter der Erdoberfläche"));

        TagMapping tm = new TagMapping();
        tm.getLookupAttributes().add("funktion");
        tm.getLookupAttributes().add("gebnutzbez");
        tm.setDefaultValue("yes");
        tm.getTable().putAll(loadDefaultTagTable());
        p.setTagMapping(tm);

        p.getAttributeRules().clear();
        p.getAttributeRules().add(new AttributeRule(true, "funktion", "building", AttributeRule.Type.BUILDING));
        p.getAttributeRules().add(new AttributeRule(true, "anzahlgs", "building:levels", AttributeRule.Type.INTEGER));
        p.getAttributeRules().add(new AttributeRule(true, "lagebeztxt", "addr:*", AttributeRule.Type.ADDRESS));
        p.getAttributeRules().add(new AttributeRule(true, "name", "name", AttributeRule.Type.DIRECT));
    }

    /** @return Standard-Gebäudetabelle aus der mitgelieferten CSV-Datei */
    public static Map<String, String> loadDefaultTagTable() {
        try (InputStream in = DefaultProfiles.class.getResourceAsStream(DEFAULT_TAGMAPPING_RESOURCE)) {
            if (in == null) {
                Logging.warn("ALKISselector: " + DEFAULT_TAGMAPPING_RESOURCE + " nicht gefunden");
                return Collections.emptyMap();
            }
            return TagMapping.readCsv(in);
        } catch (IOException e) {
            Logging.warn(e);
            return Collections.emptyMap();
        }
    }
}
