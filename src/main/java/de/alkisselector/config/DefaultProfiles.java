// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.io.IOException;
import java.io.InputStream;
import java.time.Year;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.tools.Logging;

/**
 * Mitgelieferte Standardprofile für die Bundesländer, deren ALKIS-Gebäude laut Lizenz oder
 * ausdrücklicher Erlaubnis eindeutig in OSM verwendet werden dürfen. Rechtsgrundlagen: siehe
 * {@code docs/bundeslaender.md}.
 */
public final class DefaultProfiles {

    /** Klassenpfad der Standard-Gebäudetabelle. */
    public static final String DEFAULT_TAGMAPPING_RESOURCE = "/de/alkisselector/tagmapping-alkis.csv";

    private DefaultProfiles() {
        // Hilfsklasse
    }

    /** @return alle Standardprofile, NRW zuerst, danach alphabetisch */
    public static List<ServiceProfile> all() {
        return Arrays.asList(nrw(), berlin(), brandenburg(), hamburg(), hessen(), mecklenburgVorpommern(),
                rheinlandPfalz(), sachsen());
    }

    /**
     * Prüft, ob ein Profil einen der mitgelieferten, rechtlich geprüften Gebäude-Dienste nutzt.
     * Für alle anderen Dienste ist nicht geklärt, ob die Daten in OSM verwendet werden dürfen.
     * @param profile Profil
     * @return ob der WFS zu einem Standardprofil gehört
     */
    public static boolean isVerified(ServiceProfile profile) {
        String url = normalizeUrl(profile.getWfsUrl());
        return all().stream().anyMatch(d -> normalizeUrl(d.getWfsUrl()).equals(url)
                && d.getBuildingTypeName().equals(profile.getBuildingTypeName()));
    }

    private static String normalizeUrl(String url) {
        String u = url.strip();
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.replaceFirst("^(?i)http://", "https://").toLowerCase(Locale.ROOT);
    }

    /**
     * Profil für Nordrhein-Westfalen (Geobasis NRW, Datenlizenz Deutschland – Zero – 2.0).
     * @return neues Profil
     */
    public static ServiceProfile nrw() {
        ServiceProfile p = new ServiceProfile();
        p.setName("NRW (Geobasis NRW)");
        p.setDescription("ALKIS vereinfacht (WFS), DOP (WMS) sowie ALKIS-Karte und -Flurstücke (WMS) von Geobasis NRW. "
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
        p.setAlkisMapUrl("https://www.wms.nrw.de/geobasis/wms_nw_alkis");
        p.setAlkisMapLayers("adv_alkis_flurstuecke,adv_alkis_gebaeude,adv_alkis_bauw_einricht");
        return p;
    }

    /**
     * Berlin (Geoportal Berlin, Datenlizenz Deutschland – Zero – 2.0). Der Gebäude-WFS hat ein
     * eigenes Schema, deshalb eigene Filter und Attributregeln.
     * @return neues Profil
     */
    public static ServiceProfile berlin() {
        ServiceProfile p = new ServiceProfile();
        p.setName("Berlin (Geoportal Berlin)");
        p.setDescription("ALKIS Gebäude (WFS), TrueDOP 2026 (WMS) und ALKIS-Karte (WMS) aus dem Geoportal Berlin. "
                + "Lizenz: Datenlizenz Deutschland – Zero – Version 2.0 (dl-de/zero-2-0).");
        p.setSourceTag("Geoportal Berlin / ALKIS Gebäude, TrueDOP20RGBI 2026");
        p.setWfsUrl("https://gdi.berlin.de/services/wfs/alkis_gebaeude");
        p.setWfsVersion("2.0.0");
        p.setBuildingTypeName("alkis_gebaeude:gebaeude");
        p.setCrs("EPSG:25833");
        p.setPageSize(1000);
        p.setIdAttribute("uuid");
        addStandardAlkisSettings(p);
        p.getExcludeFilters().clear();
        p.getExcludeFilters().add(new ExcludeFilter("bezeich", "^\\s*AX_Bauteil\\s*$"));
        p.getExcludeFilters().add(new ExcludeFilter("bezofl", "unter der Erdoberfläche"));
        p.getTagMapping().getLookupAttributes().clear();
        p.getTagMapping().getLookupAttributes().add("bezgfk");
        p.getAttributeRules().clear();
        p.getAttributeRules().add(new AttributeRule(true, "bezgfk", "building", AttributeRule.Type.BUILDING));
        p.getAttributeRules().add(new AttributeRule(true, "aog", "building:levels", AttributeRule.Type.INTEGER));
        p.getAttributeRules().add(new AttributeRule(true, "namlag", "addr:*", AttributeRule.Type.ADDRESS));
        p.getAttributeRules().add(new AttributeRule(true, "nam", "name", AttributeRule.Type.DIRECT));
        setOrtho(p, "https://gdi.berlin.de/services/wms/truedop_2026", "truedop_2026", 0.2);
        // Die Einzellayer *_sd liefern kein Bild; die Gesamtkarte erscheint erst ab ca. 1:2500
        setAlkisMap(p, "https://gdi.berlin.de/services/wms/alkis", "a_alkis_raster");
        return p;
    }

    /**
     * Brandenburg (LGB, dl-de/by-2.0; Quellenvermerk an anderer Stelle laut AGNB der LGB zulässig).
     * @return neues Profil
     */
    public static ServiceProfile brandenburg() {
        ServiceProfile p = aveProfile("Brandenburg (LGB)",
                "ALKIS vereinfacht (WFS), DOP20 (WMS) und ALKIS-Karte (WMS) der LGB. Lizenz: dl-de/by-2-0, "
                        + "Quellenvermerk laut AGNB der LGB (April 2020) im Changeset und auf der OSM-Contributors-Seite. "
                        + "Die regionale Community lehnt großflächige Übernahmen von Gebäudeumringen derzeit ab.",
                "GeoBasis-DE/LGB (" + Year.now() + ")/ ALKIS: Gebäude, DOP20",
                "https://isk.geobasis-bb.de/ows/alkis_vereinf_wfs", "EPSG:25833");
        setOrtho(p, "https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms", "bebb_dop20c", 0.2);
        String alkis = "https://isk.geobasis-bb.de/ows/alkis_wms";
        setParcels(p, alkis, "adv_alkis_flurstuecke");
        setAlkisMap(p, alkis, "adv_alkis_flurstuecke,adv_alkis_gebaeude");
        return p;
    }

    /**
     * Hamburg (LGV, dl-de/by-2.0; Quellenangabe im Changeset laut LGV 2021 ausreichend).
     * @return neues Profil
     */
    public static ServiceProfile hamburg() {
        ServiceProfile p = aveProfile("Hamburg (LGV)",
                "ALKIS vereinfacht (WFS), DOP 20 cm (WMS) und ALKIS-Basiskarte (WMS) des LGV Hamburg. Lizenz: "
                        + "dl-de/by-2-0, Quellenangabe „HH LGV <Dienst> <Jahr>“ im Changeset ist laut LGV ausreichend.",
                "HH LGV ALKIS " + Year.now() + ", HH LGV DOP20 " + Year.now(),
                "https://geodienste.hamburg.de/WFS_HH_ALKIS_vereinfacht", "EPSG:25832");
        setOrtho(p, "https://geodienste.hamburg.de/wms_dop_zeitreihe_unbelaubt", "dop_zeitreihe_unbelaubt", 0.2);
        String alkis = "https://geodienste.hamburg.de/HH_WMS_ALKIS_Basiskarte";
        // 13 Bauwerke Siedlung, 14 Gebäude, 15 Bauteile, 19 Flurstücksumringe
        setParcels(p, alkis, "19");
        setAlkisMap(p, alkis, "13,14,15,19");
        return p;
    }

    /**
     * Hessen (HVBG, Datenlizenz Deutschland – Zero – 2.0).
     * @return neues Profil
     */
    public static ServiceProfile hessen() {
        ServiceProfile p = aveProfile("Hessen (HVBG)",
                "ALKIS vereinfacht (WFS), DOP20 (WMS) und Liegenschaftskarte (WMS) der HVBG. "
                        + "Lizenz: Datenlizenz Deutschland – Zero – Version 2.0 (dl-de/zero-2-0).",
                "HVBG (dl-de/zero-2-0): ALKIS, DOP20",
                "https://www.gds.hessen.de/wfs2/aaa-suite/cgi-bin/alkis/vereinf/wfs", "EPSG:25832");
        setOrtho(p, "https://gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows", "he_dop20_rgb", 0.2);
        setAlkisMap(p, "https://gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-maps.ows", "he_alk");
        return p;
    }

    /**
     * Mecklenburg-Vorpommern (LAiV, CC BY 4.0 mit OSM-Erlaubnis vom 26.09.2025).
     * @return neues Profil
     */
    public static ServiceProfile mecklenburgVorpommern() {
        ServiceProfile p = aveProfile("Mecklenburg-Vorpommern (LAiV)",
                "ALKIS vereinfacht (WFS), DOP (WMS) und ALKIS-Karte (WMS) des LAiV M-V. Lizenz: CC BY 4.0 mit "
                        + "ausdrücklicher Erlaubnis für OpenStreetMap (https://www.laiv-mv.de/Geoinformation/FAQ/).",
                "©GeoBasis-DE/MV/CC BY 4.0: ALKIS, DOP",
                "https://www.geodaten-mv.de/dienste/alkis_wfs_einfach", "EPSG:25833");
        setOrtho(p, "https://www.geodaten-mv.de/dienste/adv_dop", "mv_dop", 0.2);
        String alkis = "https://www.geodaten-mv.de/dienste/alkis_wms";
        setParcels(p, alkis, "adv_alkis_flurstuecke");
        setAlkisMap(p, alkis, "adv_alkis_flurstuecke,adv_alkis_gebaeude,adv_alkis_bauw_einricht");
        return p;
    }

    /**
     * Rheinland-Pfalz (VermKV, dl-de/by-2.0 mit OSM-Erlaubnis vom 25.09.2025).
     * @return neues Profil
     */
    public static ServiceProfile rheinlandPfalz() {
        ServiceProfile p = aveProfile("Rheinland-Pfalz (LVermGeo)",
                "ALKIS vereinfacht (WFS), DOP20 (WMS) und Liegenschaftskarte (WMS) des LVermGeo RP. Lizenz: "
                        + "dl-de/by-2-0 mit ausdrücklicher Erlaubnis für OpenStreetMap (Nennung auf der Contributors-Seite).",
                "©GeoBasis-DE / LVermGeoRP" + Year.now() + ", dl-de/by-2-0, www.lvermgeo.rlp.de: ALKIS, DOP20",
                "https://geo5.service24.rlp.de/wfs/alkis_rp.fcgi", "EPSG:25832");
        setOrtho(p, "https://geo4.service24.rlp.de/wms/rp_dop20.fcgi", "rp_dop20", 0.2);
        String alkis = "https://geo5.service24.rlp.de/wms/liegenschaften_rp.fcgi";
        setParcels(p, alkis, "Flurstueck");
        setAlkisMap(p, alkis, "Flurstueck,GebaeudeBauwerke");
        return p;
    }

    /**
     * Sachsen (GeoSN, dl-de/by-2.0 mit OSM-Erlaubnis vom 06.03.2026).
     * @return neues Profil
     */
    public static ServiceProfile sachsen() {
        ServiceProfile p = aveProfile("Sachsen (GeoSN)",
                "ALKIS vereinfacht (WFS), DOP 20 cm (WMS) und ALKIS-Karte (WMS) des GeoSN. Lizenz: dl-de/by-2-0 mit "
                        + "ausdrücklicher Erlaubnis für OpenStreetMap (https://wiki.openstreetmap.org/wiki/GeoSN_Open_Data).",
                "Landesamt für Geobasisinformation Sachsen (GeoSN), Datenlizenz Deutschland Namensnennung 2.0: ALKIS, DOP",
                "https://geodienste.sachsen.de/aaa/public_alkis/vereinf/wfs", "EPSG:25833");
        setOrtho(p, "https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest", "sn_dop_020", 0.2);
        setParcels(p, "https://geodienste.sachsen.de/wms_geosn_flurstuecke/guest", "Flurstueck");
        setAlkisMap(p, "https://geodienste.sachsen.de/wms_geosn_alkis-adv/guest",
                "adv_alkis_flurstuecke,adv_alkis_gebaeude,adv_alkis_bauw_einricht");
        return p;
    }

    /** Profil für einen WFS „ALKIS vereinfacht“ mit den Standardeinstellungen. */
    private static ServiceProfile aveProfile(String name, String description, String sourceTag, String wfsUrl,
            String crs) {
        ServiceProfile p = new ServiceProfile();
        p.setName(name);
        p.setDescription(description);
        p.setSourceTag(sourceTag);
        p.setWfsUrl(wfsUrl);
        p.setWfsVersion("2.0.0");
        p.setBuildingTypeName("ave:GebaeudeBauwerk");
        p.setCrs(crs);
        p.setPageSize(1000);
        p.setIdAttribute("oid");
        addStandardAlkisSettings(p);
        return p;
    }

    private static void setOrtho(ServiceProfile p, String url, String layers, double resolution) {
        p.setOrthoWmsUrl(url);
        p.setOrthoWmsVersion("1.3.0");
        p.setOrthoLayers(layers);
        p.setOrthoFormat("image/jpeg");
        p.setOrthoResolution(resolution);
    }

    private static void setParcels(ServiceProfile p, String url, String layers) {
        p.setParcelWmsUrl(url);
        p.setParcelLayers(layers);
    }

    private static void setAlkisMap(ServiceProfile p, String url, String layers) {
        p.setAlkisMapUrl(url);
        p.setAlkisMapLayers(layers);
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
