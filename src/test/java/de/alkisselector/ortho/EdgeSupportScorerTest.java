// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.ortho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Tests mit synthetischen „Luftbildern“: 40 × 40 m bei 0,1 m/px, ein Gebäude als helles Rechteck
 * von 12 × 8 m auf dunklem, verrauschtem Hintergrund.
 */
class EdgeSupportScorerTest {

    private static final double RES = 0.1;
    private static final double MIN_X = 1000;
    private static final double MAX_Y = 2040;
    private static final EdgeSupportScorer SCORER = new EdgeSupportScorer(0.3, 1.5, 0.25);

    /** Gebäude im Bild: x 1014..1026, y 2016..2024 */
    private static BufferedImage image(boolean withBuilding, long seed) {
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 400; x++) {
                int v = 70 + rnd.nextInt(16);
                img.setRGB(x, y, new Color(v, v + 10, v).getRGB());
            }
        }
        if (withBuilding) {
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(190, 120, 110));
            g.fillRect(140, 160, 120, 80); // Pixel für x 1014..1026, y 2024..2016
            g.dispose();
        }
        return img;
    }

    private static List<double[]> rect(double x, double y, double w, double h) {
        return List.of(new double[] {x, y, x + w, y, x + w, y + h, x, y + h, x, y});
    }

    private static OrthoResult score(BufferedImage img, List<double[]> rings) {
        GeoImage gi = new GeoImage(img, MIN_X, MAX_Y, RES);
        return SCORER.score(rings, gi, EdgeMap.compute(img));
    }

    @Test
    void matchingOutlineScoresHigh() {
        OrthoResult r = score(image(true, 1), rect(1014, 2016, 12, 8));
        assertTrue(r.isValid());
        assertTrue(r.getScore() > 0.9, "Score " + r.getScore());
        assertTrue(r.getOffset() < 0.25, "Versatz " + r.getOffset());
    }

    @Test
    void smallOffsetIsCompensatedAndReported() {
        // Umriss 1 m nach Osten verschoben (z. B. Dachversatz im Luftbild)
        OrthoResult r = score(image(true, 2), rect(1015, 2016, 12, 8));
        assertTrue(r.getScore() > 0.9, "Score " + r.getScore());
        assertEquals(-1.0, r.getOffsetX(), 0.15);
        assertEquals(0.0, r.getOffsetY(), 0.15);
    }

    @Test
    void largeOffsetScoresLow() {
        OrthoResult r = score(image(true, 3), rect(1017.5, 2016, 12, 8));
        assertTrue(r.getScore() < 0.8, "Score " + r.getScore());
    }

    @Test
    void wrongShapeScoresLow() {
        // ALKIS-Umriss doppelt so tief wie das Gebäude im Luftbild (z. B. abgerissener Anbau)
        OrthoResult r = score(image(true, 4), rect(1014, 2008, 12, 16));
        assertTrue(r.getScore() < 0.8, "Score " + r.getScore());
    }

    @Test
    void emptyImageScoresNearZero() {
        OrthoResult r = score(image(false, 5), rect(1014, 2016, 12, 8));
        assertTrue(r.getScore() < 0.3, "Score " + r.getScore());
    }

    @Test
    void edgeMapThresholdIgnoresNoise() {
        EdgeMap m = EdgeMap.compute(image(false, 6));
        assertTrue(m.getThreshold() >= EdgeMap.MIN_MAGNITUDE);
        assertFalse(m.isAlignedEdge(200, 200, 1, 0, 0.5));
    }

    @Test
    void tinyOutlineIsReportedAsInvalid() {
        OrthoResult r = score(image(true, 7), List.of(new double[] {1014, 2016, 1014.0001, 2016, 1014, 2016}));
        assertFalse(r.isValid());
    }
}
