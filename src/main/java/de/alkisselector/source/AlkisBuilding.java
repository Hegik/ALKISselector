// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ein ALKIS-Gebäude mit Geometrie im (metrischen) Koordinatensystem des Dienstes und seinen Attributen.
 */
public final class AlkisBuilding {

    private final String id;
    private final List<Polygon> polygons;
    private final Map<String, String> attributes;

    /**
     * @param id eindeutige Kennung (z. B. ALKIS-OID)
     * @param polygons Flächen (mindestens eine)
     * @param attributes Sachattribute
     */
    public AlkisBuilding(String id, List<Polygon> polygons, Map<String, String> attributes) {
        this.id = id;
        this.polygons = Collections.unmodifiableList(polygons);
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    public String getId() {
        return id;
    }

    /** @return Flächen des Gebäudes */
    public List<Polygon> getPolygons() {
        return polygons;
    }

    /** @return Sachattribute (lokaler Elementname → Text) */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** @return ob das Gebäude als einfacher geschlossener Weg (eine Fläche ohne Löcher) darstellbar ist */
    public boolean isSimple() {
        return polygons.size() == 1 && polygons.get(0).getHoles().isEmpty();
    }

    /** @return Hüllrechteck {minX, minY, maxX, maxY} */
    public double[] getEnvelope() {
        double[] env = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Polygon p : polygons) {
            double[] r = p.getOuter();
            for (int i = 0; i + 1 < r.length; i += 2) {
                env[0] = Math.min(env[0], r[i]);
                env[1] = Math.min(env[1], r[i + 1]);
                env[2] = Math.max(env[2], r[i]);
                env[3] = Math.max(env[3], r[i + 1]);
            }
        }
        return env;
    }

    @Override
    public String toString() {
        return "AlkisBuilding[" + id + ", " + attributes.get("funktion") + "]";
    }

    /**
     * Eine Fläche aus Außenring und optionalen Innenringen. Ringe sind als
     * {@code x0, y0, x1, y1, ...} gespeichert und geschlossen (erster = letzter Punkt).
     */
    public static final class Polygon {
        private final double[] outer;
        private final List<double[]> holes;

        /**
         * @param outer Außenring
         * @param holes Innenringe
         */
        public Polygon(double[] outer, List<double[]> holes) {
            this.outer = outer;
            this.holes = Collections.unmodifiableList(holes);
        }

        public double[] getOuter() {
            return outer;
        }

        public List<double[]> getHoles() {
            return holes;
        }
    }
}
