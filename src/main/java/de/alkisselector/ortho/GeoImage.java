// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

import java.awt.image.BufferedImage;

/**
 * Ein nordorientiertes Rasterbild mit Georeferenz im metrischen Arbeits-CRS.
 * Pixel (0,0) ist die linke obere Ecke bei ({@code minX}, {@code maxY}).
 */
public final class GeoImage {

    private final BufferedImage image;
    private final double minX;
    private final double maxY;
    private final double resolution;

    /**
     * @param image Bild
     * @param minX Ostwert der linken Bildkante
     * @param maxY Nordwert der oberen Bildkante
     * @param resolution Pixelgröße in Metern
     */
    public GeoImage(BufferedImage image, double minX, double maxY, double resolution) {
        this.image = image;
        this.minX = minX;
        this.maxY = maxY;
        this.resolution = resolution;
    }

    public BufferedImage getImage() {
        return image;
    }

    public double getMinX() {
        return minX;
    }

    public double getMaxY() {
        return maxY;
    }

    /** @return Pixelgröße in Metern */
    public double getResolution() {
        return resolution;
    }

    /**
     * @param x Ostwert
     * @return Spalte (Pixelmitte bei x.5)
     */
    public double toPixelX(double x) {
        return (x - minX) / resolution;
    }

    /**
     * @param y Nordwert
     * @return Zeile
     */
    public double toPixelY(double y) {
        return (maxY - y) / resolution;
    }
}
