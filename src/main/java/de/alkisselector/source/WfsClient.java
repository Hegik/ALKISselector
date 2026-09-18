// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.HttpClient;

import de.alkisselector.config.ServiceProfile;

/**
 * Lädt ALKIS-Gebäude per WFS-GetFeature (WFS 2.0 mit Paging, alternativ WFS 1.1).
 */
public final class WfsClient {

    /** Obergrenze für die Anzahl der Objekte einer Abfrage, um JOSM nicht zu überlasten. */
    public static final int MAX_FEATURES = 20_000;

    private final ServiceProfile profile;
    private final CrsTransformer crs;

    /**
     * @param profile Dienstprofil
     */
    public WfsClient(ServiceProfile profile) {
        this.profile = profile;
        this.crs = new CrsTransformer(profile.getCrs());
    }

    /** @return Transformation für das Koordinatensystem des Profils */
    public CrsTransformer getCrs() {
        return crs;
    }

    /**
     * Lädt alle Gebäude im Rechteck (Koordinaten im Dienst-CRS).
     * @param minX minimaler Ostwert
     * @param minY minimaler Nordwert
     * @param maxX maximaler Ostwert
     * @param maxY maximaler Nordwert
     * @param monitor Fortschrittsanzeige (darf {@code null} sein)
     * @return Ergebnis mit Gebäuden und Hinweis, ob die Obergrenze erreicht wurde
     * @throws IOException bei Netzwerk- oder Dienstfehlern
     */
    public Result fetch(double minX, double minY, double maxX, double maxY, ProgressMonitor monitor) throws IOException {
        ProgressMonitor pm = monitor != null ? monitor : NullProgressMonitor.INSTANCE;
        boolean v2 = profile.getWfsVersion().startsWith("2");
        int pageSize = profile.getPageSize();
        Map<String, AlkisBuilding> byId = new LinkedHashMap<>();
        int start = 0;
        boolean truncated = false;
        while (true) {
            if (pm.isCanceled()) {
                break;
            }
            pm.setCustomText("ALKIS-Gebäude laden … (" + byId.size() + ")");
            String url = buildGetFeatureUrl(minX, minY, maxX, maxY, pageSize, start, v2);
            List<AlkisBuilding> page = request(url, pm);
            for (AlkisBuilding b : page) {
                byId.putIfAbsent(b.getId(), b);
            }
            start += page.size();
            if (page.size() < pageSize) {
                break;
            }
            if (!v2 || byId.size() >= MAX_FEATURES) {
                truncated = true;
                break;
            }
        }
        return new Result(new ArrayList<>(byId.values()), truncated);
    }

    String buildGetFeatureUrl(double minX, double minY, double maxX, double maxY, int count, int startIndex, boolean v2) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("SERVICE", "WFS");
        q.put("REQUEST", "GetFeature");
        String bbox = profile.isSwapAxes()
                ? fmt(minY) + "," + fmt(minX) + "," + fmt(maxY) + "," + fmt(maxX)
                : fmt(minX) + "," + fmt(minY) + "," + fmt(maxX) + "," + fmt(maxY);
        if (v2) {
            q.put("VERSION", profile.getWfsVersion());
            q.put("TYPENAMES", profile.getBuildingTypeName());
            q.put("SRSNAME", crs.getUrn());
            q.put("BBOX", bbox + "," + crs.getUrn());
            q.put("COUNT", Integer.toString(count));
            if (startIndex > 0) {
                q.put("STARTINDEX", Integer.toString(startIndex));
            }
        } else {
            q.put("VERSION", profile.getWfsVersion());
            q.put("TYPENAME", profile.getBuildingTypeName());
            q.put("SRSNAME", crs.getCode());
            q.put("BBOX", bbox + "," + crs.getCode());
            q.put("MAXFEATURES", Integer.toString(count));
        }
        return appendQuery(profile.getWfsUrl(), q);
    }

    private List<AlkisBuilding> request(String url, ProgressMonitor pm) throws IOException {
        HttpClient.Response resp = HttpClient.create(toUrl(url)).connect(pm.createSubTaskMonitor(0, false));
        try {
            if (resp.getResponseCode() != 200) {
                throw new WfsException("WFS antwortet mit HTTP " + resp.getResponseCode() + ": " + snippet(resp.fetchContent()));
            }
            try (InputStream in = resp.getContent()) {
                return new GmlBuildingParser(profile.isSwapAxes()).parse(in, profile.getIdAttribute());
            }
        } finally {
            resp.disconnect();
        }
    }

    /**
     * Prüft, ob der WFS erreichbar ist und den konfigurierten Objekttyp anbietet.
     * @return Klartext-Ergebnis für den Nutzer
     * @throws IOException bei Netzwerkfehlern
     */
    public String checkCapabilities() throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("SERVICE", "WFS");
        q.put("REQUEST", "GetCapabilities");
        String caps = fetchText(appendQuery(profile.getWfsUrl(), q));
        String type = profile.getBuildingTypeName();
        String local = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        if (caps.contains(">" + type + "<") || caps.contains(":" + local + "<") || caps.contains(">" + local + "<")) {
            return "WFS erreichbar, Objektart „" + type + "“ gefunden.";
        }
        return "WFS erreichbar, aber Objektart „" + type + "“ nicht in den Capabilities gefunden.";
    }

    /**
     * Lädt eine URL als Text.
     * @param url URL
     * @return Inhalt
     * @throws IOException bei Fehlern
     */
    public static String fetchText(String url) throws IOException {
        HttpClient.Response resp = HttpClient.create(toUrl(url)).connect();
        try {
            String content = resp.fetchContent();
            if (resp.getResponseCode() != 200) {
                throw new WfsException("HTTP " + resp.getResponseCode() + ": " + snippet(content));
            }
            return content;
        } finally {
            resp.disconnect();
        }
    }

    /**
     * Hängt Parameter an eine Dienst-URL an. Bereits vorhandene Parameter mit gleichem Namen
     * (ohne Beachtung der Groß-/Kleinschreibung) werden nicht doppelt gesetzt.
     * @param base Basis-URL, darf bereits Parameter enthalten
     * @param params anzuhängende Parameter
     * @return vollständige URL
     */
    public static String appendQuery(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base.trim());
        String lower = base.toLowerCase(Locale.ROOT);
        char sep = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? 0 : '&') : '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (lower.contains("?" + k + "=") || lower.contains("&" + k + "=")) {
                continue;
            }
            if (sep != 0) {
                sb.append(sep);
            }
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    static URL toUrl(String url) throws WfsException {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new WfsException("Ungültige Dienst-URL: " + url, e);
        }
    }

    static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + " …" : t;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * Ergebnis einer WFS-Abfrage.
     */
    public static final class Result {
        private final List<AlkisBuilding> buildings;
        private final boolean truncated;

        Result(List<AlkisBuilding> buildings, boolean truncated) {
            this.buildings = buildings;
            this.truncated = truncated;
        }

        public List<AlkisBuilding> getBuildings() {
            return buildings;
        }

        /** @return ob möglicherweise nicht alle Objekte geladen wurden */
        public boolean isTruncated() {
            return truncated;
        }
    }
}
