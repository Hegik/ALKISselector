// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Kantenbild eines Orthophotos: Graustufen → Gauß-Glättung → Sobel-Gradient.
 * <p>
 * Gespeichert werden Gradientenbetrag und -richtung je Pixel sowie eine adaptive Schwelle, ab der
 * ein Pixel als Kante gilt (Perzentil des Gradientenbetrags, mindestens {@link #MIN_MAGNITUDE}).
 */
public final class EdgeMap {

    /** Absolute Mindestschwelle des Gradientenbetrags (bei 8-Bit-Grauwerten), damit homogene Bilder keine Kanten liefern. */
    public static final double MIN_MAGNITUDE = 40;
    /** Anteil der Pixel, die höchstens als Kante gelten. */
    public static final double EDGE_FRACTION = 0.2;

    private final int width;
    private final int height;
    private final float[] gx;
    private final float[] gy;
    private final float[] mag;
    private final double threshold;

    private EdgeMap(int width, int height, float[] gx, float[] gy, float[] mag, double threshold) {
        this.width = width;
        this.height = height;
        this.gx = gx;
        this.gy = gy;
        this.mag = mag;
        this.threshold = threshold;
    }

    /**
     * Berechnet das Kantenbild.
     * @param img Eingabebild (beliebiger Typ)
     * @return Kantenbild
     */
    public static EdgeMap compute(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] gray = new float[w * h];
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            gray[i] = 0.299f * ((p >> 16) & 0xff) + 0.587f * ((p >> 8) & 0xff) + 0.114f * (p & 0xff);
        }
        float[] smooth = gauss3(gray, w, h);
        float[] gx = new float[w * h];
        float[] gy = new float[w * h];
        float[] mag = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float a = smooth[i - w - 1];
                float b = smooth[i - w];
                float c = smooth[i - w + 1];
                float d = smooth[i - 1];
                float f = smooth[i + 1];
                float g = smooth[i + w - 1];
                float hh = smooth[i + w];
                float k = smooth[i + w + 1];
                float sx = (c + 2 * f + k) - (a + 2 * d + g);
                float sy = (g + 2 * hh + k) - (a + 2 * b + c);
                gx[i] = sx;
                gy[i] = sy;
                mag[i] = (float) Math.sqrt(sx * sx + sy * sy);
            }
        }
        return new EdgeMap(w, h, gx, gy, mag, adaptiveThreshold(mag));
    }

    private static float[] gauss3(float[] src, int w, int h) {
        // 3x3-Binomialfilter (1 2 1) – ausreichend, um JPEG-Artefakte und Rauschen zu dämpfen
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int xl = Math.max(0, x - 1);
                int xr = Math.min(w - 1, x + 1);
                tmp[y * w + x] = (src[y * w + xl] + 2 * src[y * w + x] + src[y * w + xr]) / 4f;
            }
        }
        for (int y = 0; y < h; y++) {
            int yu = Math.max(0, y - 1);
            int yd = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                out[y * w + x] = (tmp[yu * w + x] + 2 * tmp[y * w + x] + tmp[yd * w + x]) / 4f;
            }
        }
        return out;
    }

    private static double adaptiveThreshold(float[] mag) {
        if (mag.length == 0) {
            return MIN_MAGNITUDE;
        }
        float[] sorted = mag.clone();
        Arrays.sort(sorted);
        double p = sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * (1 - EDGE_FRACTION)))];
        return Math.max(MIN_MAGNITUDE, p);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** @return Schwelle des Gradientenbetrags, ab der ein Pixel als Kante gilt */
    public double getThreshold() {
        return threshold;
    }

    /**
     * Prüft, ob am Pixel eine Kante liegt, deren Gradient annähernd in Richtung der Normalen zeigt
     * (d. h. die Kante verläuft parallel zum Umrisssegment).
     * @param x Spalte
     * @param y Zeile
     * @param nx Normalenrichtung x (Einheitsvektor, Bildkoordinaten)
     * @param ny Normalenrichtung y (Einheitsvektor, Bildkoordinaten)
     * @param minCos minimaler Betrag des Kosinus zwischen Gradient und Normale
     * @return ob eine passende Kante vorliegt
     */
    public boolean isAlignedEdge(int x, int y, double nx, double ny, double minCos) {
        if (x < 1 || y < 1 || x >= width - 1 || y >= height - 1) {
            return false;
        }
        int i = y * width + x;
        float m = mag[i];
        if (m < threshold) {
            return false;
        }
        double cos = Math.abs(gx[i] * nx + gy[i] * ny) / m;
        return cos >= minCos;
    }
}
