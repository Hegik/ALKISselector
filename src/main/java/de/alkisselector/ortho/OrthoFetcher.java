// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.tools.HttpClient;

import de.alkisselector.config.ServiceProfile;
import de.alkisselector.source.CrsTransformer;
import de.alkisselector.source.WfsClient;
import de.alkisselector.source.WfsException;

/**
 * Lädt Orthophoto-Ausschnitte per WMS-GetMap im metrischen Arbeits-CRS des Profils, sodass
 * Pixel- und Kartenkoordinaten linear zusammenhängen.
 */
public final class OrthoFetcher {

    /** Maximale Kantenlänge eines angeforderten Bildes in Pixeln. */
    public static final int MAX_SIZE = 3000;

    private final ServiceProfile profile;
    private final CrsTransformer crs;

    /**
     * @param profile Dienstprofil mit Orthophoto-WMS
     */
    public OrthoFetcher(ServiceProfile profile) {
        this.profile = profile;
        this.crs = new CrsTransformer(profile.getCrs());
    }

    /**
     * Lädt den Bildausschnitt für ein Rechteck im Arbeits-CRS.
     * @param minX minimaler Ostwert
     * @param minY minimaler Nordwert
     * @param maxX maximaler Ostwert
     * @param maxY maximaler Nordwert
     * @return georeferenziertes Bild
     * @throws IOException bei Netzwerk- oder Dienstfehlern
     */
    public GeoImage fetch(double minX, double minY, double maxX, double maxY) throws IOException {
        double res = profile.getOrthoResolution();
        double w = maxX - minX;
        double h = maxY - minY;
        double maxDim = Math.max(w, h);
        if (maxDim / res > MAX_SIZE) {
            res = maxDim / MAX_SIZE;
        }
        int width = Math.max(1, (int) Math.ceil(w / res));
        int height = Math.max(1, (int) Math.ceil(h / res));
        // Rechteck exakt auf das Pixelraster ausrichten
        double x1 = minX + width * res;
        double y0 = maxY - height * res;

        String url = WfsClient.appendQuery(profile.getOrthoWmsUrl(), getMapParams(minX, y0, x1, maxY, width, height));
        HttpClient.Response resp = HttpClient.create(new java.net.URL(url)).connect();
        try {
            String type = resp.getContentType();
            if (resp.getResponseCode() != 200 || type == null || !type.toLowerCase(Locale.ROOT).startsWith("image")) {
                throw new WfsException("Orthophoto-WMS liefert kein Bild (HTTP " + resp.getResponseCode() + ", "
                        + type + "): " + snippet(resp.fetchContent()));
            }
            BufferedImage img;
            try (InputStream in = resp.getContent()) {
                img = ImageIO.read(in);
            }
            if (img == null) {
                throw new WfsException("Bildformat des Orthophoto-WMS wird nicht unterstützt: " + type);
            }
            double effRes = (x1 - minX) / img.getWidth();
            return new GeoImage(img, minX, maxY, effRes);
        } finally {
            resp.disconnect();
        }
    }

    private Map<String, String> getMapParams(double minX, double minY, double maxX, double maxY, int width, int height) {
        Map<String, String> q = new LinkedHashMap<>();
        boolean v13 = profile.getOrthoWmsVersion().startsWith("1.3");
        q.put("SERVICE", "WMS");
        q.put("VERSION", profile.getOrthoWmsVersion());
        q.put("REQUEST", "GetMap");
        q.put("LAYERS", profile.getOrthoLayers());
        q.put("STYLES", "");
        q.put(v13 ? "CRS" : "SRS", crs.getCode());
        // Projizierte EPSG-Systeme wie 25832/25833 haben die Achsfolge Ost/Nord, auch in WMS 1.3.0
        q.put("BBOX", fmt(minX) + "," + fmt(minY) + "," + fmt(maxX) + "," + fmt(maxY));
        q.put("WIDTH", Integer.toString(width));
        q.put("HEIGHT", Integer.toString(height));
        q.put("FORMAT", profile.getOrthoFormat());
        return q;
    }

    /**
     * Erzeugt eine WMS-URL-Vorlage für einen JOSM-Imagery-Layer.
     * @param url Basis-URL des Dienstes
     * @param layers Layernamen
     * @param format Bildformat
     * @param transparent transparenter Hintergrund
     * @return URL mit JOSM-Platzhaltern {proj}, {bbox}, {width}, {height}
     */
    public static String josmWmsTemplate(String url, String layers, String format, boolean transparent) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("SERVICE", "WMS");
        q.put("VERSION", "1.3.0");
        q.put("REQUEST", "GetMap");
        q.put("LAYERS", layers);
        q.put("STYLES", "");
        q.put("FORMAT", format);
        q.put("TRANSPARENT", transparent ? "TRUE" : "FALSE");
        String base = WfsClient.appendQuery(url, q);
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "CRS={proj}&WIDTH={width}&HEIGHT={height}&BBOX={bbox}";
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + " …" : t;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
