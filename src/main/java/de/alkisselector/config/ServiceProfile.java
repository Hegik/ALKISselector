// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

/**
 * Ein Dienstprofil beschreibt, woher ALKIS-Gebäude, Orthophotos und Flurstücke kommen und wie
 * ALKIS-Attribute in OSM-Tags übersetzt werden. Profile sind frei konfigurierbar und nicht an
 * ein Bundesland gebunden.
 */
public final class ServiceProfile {

    private String name = "";
    private String description = "";
    /** Wert für das Changeset-Tag {@code source}. */
    private String sourceTag = "";

    // --- ALKIS-Gebäude (WFS) ---
    private String wfsUrl = "";
    private String wfsVersion = "2.0.0";
    private String buildingTypeName = "";
    /** Koordinatenreferenzsystem der Abfrage, muss metrisch sein, z. B. {@code EPSG:25832}. */
    private String crs = "EPSG:25832";
    /** Achsen im GML vertauschen (nur für Dienste, die Nord/Ost statt Ost/Nord liefern). */
    private boolean swapAxes;
    private int pageSize = 1000;
    private String idAttribute = "oid";

    private final List<ExcludeFilter> excludeFilters = new ArrayList<>();
    private TagMapping tagMapping = new TagMapping();
    private final List<AttributeRule> attributeRules = new ArrayList<>();

    // --- Orthophoto (WMS) ---
    private String orthoWmsUrl = "";
    private String orthoWmsVersion = "1.3.0";
    private String orthoLayers = "";
    private String orthoFormat = "image/jpeg";
    /** Bodenauflösung in Metern pro Pixel für die Auswertung. */
    private double orthoResolution = 0.1;

    // --- Flurstücke (WMS, nur Anzeige) ---
    private String parcelWmsUrl = "";
    private String parcelLayers = "";

    // --- ALKIS-Karte (WMS, zum visuellen Abgleich über dem Orthophoto) ---
    private String alkisMapUrl = "";
    private String alkisMapLayers = "";

    /** Leeres Profil. */
    public ServiceProfile() {
        // leer
    }

    /**
     * Tiefe Kopie.
     * @param o zu kopierendes Profil
     */
    public ServiceProfile(ServiceProfile o) {
        name = o.name;
        description = o.description;
        sourceTag = o.sourceTag;
        wfsUrl = o.wfsUrl;
        wfsVersion = o.wfsVersion;
        buildingTypeName = o.buildingTypeName;
        crs = o.crs;
        swapAxes = o.swapAxes;
        pageSize = o.pageSize;
        idAttribute = o.idAttribute;
        o.excludeFilters.forEach(f -> excludeFilters.add(new ExcludeFilter(f.getAttribute(), f.getRegex())));
        tagMapping = new TagMapping(o.tagMapping);
        o.attributeRules.forEach(r -> attributeRules.add(new AttributeRule(r)));
        orthoWmsUrl = o.orthoWmsUrl;
        orthoWmsVersion = o.orthoWmsVersion;
        orthoLayers = o.orthoLayers;
        orthoFormat = o.orthoFormat;
        orthoResolution = o.orthoResolution;
        parcelWmsUrl = o.parcelWmsUrl;
        parcelLayers = o.parcelLayers;
        alkisMapUrl = o.alkisMapUrl;
        alkisMapLayers = o.alkisMapLayers;
    }

    // ------------------------------------------------------------------ Getter / Setter

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceTag() {
        return sourceTag;
    }

    public void setSourceTag(String sourceTag) {
        this.sourceTag = sourceTag;
    }

    public String getWfsUrl() {
        return wfsUrl;
    }

    public void setWfsUrl(String wfsUrl) {
        this.wfsUrl = wfsUrl;
    }

    public String getWfsVersion() {
        return wfsVersion;
    }

    public void setWfsVersion(String wfsVersion) {
        this.wfsVersion = wfsVersion;
    }

    public String getBuildingTypeName() {
        return buildingTypeName;
    }

    public void setBuildingTypeName(String buildingTypeName) {
        this.buildingTypeName = buildingTypeName;
    }

    public String getCrs() {
        return crs;
    }

    public void setCrs(String crs) {
        this.crs = crs;
    }

    public boolean isSwapAxes() {
        return swapAxes;
    }

    public void setSwapAxes(boolean swapAxes) {
        this.swapAxes = swapAxes;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
    }

    public String getIdAttribute() {
        return idAttribute;
    }

    public void setIdAttribute(String idAttribute) {
        this.idAttribute = idAttribute;
    }

    /** @return veränderbare Liste der Ausschlussfilter */
    public List<ExcludeFilter> getExcludeFilters() {
        return excludeFilters;
    }

    public TagMapping getTagMapping() {
        return tagMapping;
    }

    public void setTagMapping(TagMapping tagMapping) {
        this.tagMapping = tagMapping;
    }

    /** @return veränderbare Liste der Attributregeln */
    public List<AttributeRule> getAttributeRules() {
        return attributeRules;
    }

    public String getOrthoWmsUrl() {
        return orthoWmsUrl;
    }

    public void setOrthoWmsUrl(String orthoWmsUrl) {
        this.orthoWmsUrl = orthoWmsUrl;
    }

    public String getOrthoWmsVersion() {
        return orthoWmsVersion;
    }

    public void setOrthoWmsVersion(String orthoWmsVersion) {
        this.orthoWmsVersion = orthoWmsVersion;
    }

    public String getOrthoLayers() {
        return orthoLayers;
    }

    public void setOrthoLayers(String orthoLayers) {
        this.orthoLayers = orthoLayers;
    }

    public String getOrthoFormat() {
        return orthoFormat;
    }

    public void setOrthoFormat(String orthoFormat) {
        this.orthoFormat = orthoFormat;
    }

    public double getOrthoResolution() {
        return orthoResolution;
    }

    public void setOrthoResolution(double orthoResolution) {
        this.orthoResolution = orthoResolution > 0 ? orthoResolution : 0.1;
    }

    public String getParcelWmsUrl() {
        return parcelWmsUrl;
    }

    public void setParcelWmsUrl(String parcelWmsUrl) {
        this.parcelWmsUrl = parcelWmsUrl;
    }

    public String getParcelLayers() {
        return parcelLayers;
    }

    public void setParcelLayers(String parcelLayers) {
        this.parcelLayers = parcelLayers;
    }

    public String getAlkisMapUrl() {
        return alkisMapUrl;
    }

    public void setAlkisMapUrl(String alkisMapUrl) {
        this.alkisMapUrl = alkisMapUrl;
    }

    public String getAlkisMapLayers() {
        return alkisMapLayers;
    }

    public void setAlkisMapLayers(String alkisMapLayers) {
        this.alkisMapLayers = alkisMapLayers;
    }

    /** @return ob ein Orthophoto-Dienst konfiguriert ist */
    public boolean hasOrtho() {
        return !orthoWmsUrl.isBlank() && !orthoLayers.isBlank();
    }

    /** @return ob ein Flurstücks-Dienst konfiguriert ist */
    public boolean hasParcels() {
        return !parcelWmsUrl.isBlank() && !parcelLayers.isBlank();
    }

    @Override
    public String toString() {
        return name;
    }

    // ------------------------------------------------------------------ JSON

    /** @return JSON-Darstellung (für Speicherung und Export) */
    public JsonObject toJson() {
        JsonArrayBuilder filters = Json.createArrayBuilder();
        excludeFilters.forEach(f -> filters.add(f.toJson()));
        JsonArrayBuilder rules = Json.createArrayBuilder();
        attributeRules.forEach(r -> rules.add(r.toJson()));
        return Json.createObjectBuilder()
                .add("name", name)
                .add("description", description)
                .add("sourceTag", sourceTag)
                .add("wfs", Json.createObjectBuilder()
                        .add("url", wfsUrl)
                        .add("version", wfsVersion)
                        .add("typeName", buildingTypeName)
                        .add("crs", crs)
                        .add("swapAxes", swapAxes)
                        .add("pageSize", pageSize)
                        .add("idAttribute", idAttribute))
                .add("excludeFilters", filters)
                .add("tagMapping", tagMapping.toJson())
                .add("attributeRules", rules)
                .add("ortho", Json.createObjectBuilder()
                        .add("url", orthoWmsUrl)
                        .add("version", orthoWmsVersion)
                        .add("layers", orthoLayers)
                        .add("format", orthoFormat)
                        .add("resolution", orthoResolution))
                .add("parcels", Json.createObjectBuilder()
                        .add("url", parcelWmsUrl)
                        .add("layers", parcelLayers))
                .add("alkisMap", Json.createObjectBuilder()
                        .add("url", alkisMapUrl)
                        .add("layers", alkisMapLayers))
                .build();
    }

    /**
     * Liest ein Profil aus JSON. Fehlende Felder erhalten Standardwerte.
     * @param o JSON-Objekt
     * @return Profil
     */
    public static ServiceProfile fromJson(JsonObject o) {
        ServiceProfile p = new ServiceProfile();
        p.name = o.getString("name", "");
        p.description = o.getString("description", "");
        p.sourceTag = o.getString("sourceTag", "");
        JsonObject wfs = obj(o, "wfs");
        p.wfsUrl = wfs.getString("url", "");
        p.wfsVersion = wfs.getString("version", "2.0.0");
        p.buildingTypeName = wfs.getString("typeName", "");
        p.crs = wfs.getString("crs", "EPSG:25832");
        p.swapAxes = wfs.getBoolean("swapAxes", false);
        p.setPageSize(wfs.getInt("pageSize", 1000));
        p.idAttribute = wfs.getString("idAttribute", "oid");
        for (JsonValue v : arr(o, "excludeFilters")) {
            p.excludeFilters.add(ExcludeFilter.fromJson(v.asJsonObject()));
        }
        JsonObject tm = o.getJsonObject("tagMapping");
        if (tm != null) {
            p.tagMapping = TagMapping.fromJson(tm);
        }
        for (JsonValue v : arr(o, "attributeRules")) {
            p.attributeRules.add(AttributeRule.fromJson(v.asJsonObject()));
        }
        JsonObject ortho = obj(o, "ortho");
        p.orthoWmsUrl = ortho.getString("url", "");
        p.orthoWmsVersion = ortho.getString("version", "1.3.0");
        p.orthoLayers = ortho.getString("layers", "");
        p.orthoFormat = ortho.getString("format", "image/jpeg");
        p.setOrthoResolution(ortho.containsKey("resolution") ? ortho.getJsonNumber("resolution").doubleValue() : 0.1);
        JsonObject parcels = obj(o, "parcels");
        p.parcelWmsUrl = parcels.getString("url", "");
        p.parcelLayers = parcels.getString("layers", "");
        JsonObject alkisMap = obj(o, "alkisMap");
        p.alkisMapUrl = alkisMap.getString("url", "");
        p.alkisMapLayers = alkisMap.getString("layers", "");
        if (!o.containsKey("tagMapping") && !o.containsKey("attributeRules") && !o.containsKey("excludeFilters")) {
            // Kompakte Profildatei ohne eigene Übersetzung: Standard für „ALKIS vereinfacht“ verwenden
            DefaultProfiles.addStandardAlkisSettings(p);
        }
        return p;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonObject r = o.getJsonObject(key);
        return r != null ? r : JsonValue.EMPTY_JSON_OBJECT;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonArray r = o.getJsonArray(key);
        return r != null ? r : JsonValue.EMPTY_JSON_ARRAY;
    }
}
