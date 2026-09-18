// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming-Parser für WFS-Antworten (GML 3.x und GML 2) mit Flächengeometrien.
 * <p>
 * Der Parser ist bewusst schemaunabhängig: Jedes Kindelement von {@code wfs:member},
 * {@code gml:featureMember} oder {@code gml:featureMembers} gilt als Feature. Einfache
 * Kindelemente des Features werden als Attribute übernommen (lokaler Name → Text), Flächen
 * werden aus {@code gml:Polygon} bzw. {@code gml:PolygonPatch} gelesen – unabhängig davon, ob sie
 * in {@code MultiSurface}, {@code Surface} oder direkt im Geometrie-Attribut stehen.
 */
public final class GmlBuildingParser {

    private static final Pattern WS = Pattern.compile("\\s+");

    private final boolean swapAxes;

    /**
     * @param swapAxes Koordinatenpaare vertauschen (für Dienste, die Nord/Ost liefern)
     */
    public GmlBuildingParser(boolean swapAxes) {
        this.swapAxes = swapAxes;
    }

    /**
     * Liest alle Features mit Flächengeometrie.
     * @param in XML-Eingabe
     * @param idAttribute Attribut mit der Objektkennung (Fallback: {@code gml:id})
     * @return gelesene Gebäude (Features ohne Fläche werden übersprungen)
     * @throws WfsException bei OGC-Exception-Reports oder ungültigem XML
     */
    public List<AlkisBuilding> parse(InputStream in, String idAttribute) throws WfsException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader r = null;
        try {
            r = f.createXMLStreamReader(in);
            return read(r, idAttribute);
        } catch (XMLStreamException e) {
            throw new WfsException("Antwort des Dienstes ist kein gültiges XML: " + e.getMessage(), e);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException e) {
                    // ignorieren
                }
            }
        }
    }

    private List<AlkisBuilding> read(XMLStreamReader r, String idAttribute) throws XMLStreamException, WfsException {
        List<AlkisBuilding> result = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        Deque<Integer> dims = new ArrayDeque<>();

        FeatureState feature = null;
        StringBuilder text = null;       // Textpuffer für Koordinaten bzw. Attribute
        boolean exception = false;
        StringBuilder exceptionText = new StringBuilder();

        while (r.hasNext()) {
            int ev = r.next();
            switch (ev) {
            case XMLStreamConstants.START_ELEMENT: {
                String local = r.getLocalName();
                String parent = stack.peek();
                int dim = dims.isEmpty() ? 2 : dims.peek();
                String sd = r.getAttributeValue(null, "srsDimension");
                if (sd != null) {
                    try {
                        dim = Integer.parseInt(sd.trim());
                    } catch (NumberFormatException e) {
                        // Vorgabe beibehalten
                    }
                }
                if ("ExceptionReport".equals(local) || "ServiceExceptionReport".equals(local)) {
                    exception = true;
                }
                if (feature == null && parent != null
                        && ("member".equals(parent) || "featureMember".equals(parent) || "featureMembers".equals(parent))) {
                    feature = new FeatureState(stack.size(), gmlId(r));
                } else if (feature != null) {
                    int rel = stack.size() - feature.depth; // 1 = Eigenschaft des Features
                    if (rel == 1) {
                        feature.property = local;
                        feature.propertyHasChildren = false;
                        text = new StringBuilder();
                    } else if (rel > 1) {
                        feature.propertyHasChildren = true;
                        if ("Polygon".equals(local) || "PolygonPatch".equals(local)) {
                            feature.outer = null;
                            feature.holes = new ArrayList<>();
                        } else if ("exterior".equals(local) || "outerBoundaryIs".equals(local)) {
                            feature.inHole = false;
                        } else if ("interior".equals(local) || "innerBoundaryIs".equals(local)) {
                            feature.inHole = true;
                        } else if ("LinearRing".equals(local)) {
                            feature.ring = new ArrayList<>();
                        } else if ("posList".equals(local) || "pos".equals(local) || "coordinates".equals(local)) {
                            text = new StringBuilder();
                        }
                    }
                }
                stack.push(local);
                dims.push(dim);
                break;
            }
            case XMLStreamConstants.CHARACTERS:
            case XMLStreamConstants.CDATA:
                if (text != null) {
                    text.append(r.getText());
                }
                if (exception) {
                    exceptionText.append(r.getText());
                }
                break;
            case XMLStreamConstants.END_ELEMENT: {
                String local = stack.pop();
                int dim = dims.pop();
                if (feature != null) {
                    int rel = stack.size() - feature.depth; // nach dem Entfernen: 0 = Feature, 1 = Eigenschaft
                    if (rel <= 0) {
                        // Ende des Features
                        if (!feature.polygons.isEmpty()) {
                            String id = feature.attributes.get(idAttribute);
                            if (id == null || id.isBlank()) {
                                id = feature.gmlId;
                            }
                            if (id == null) {
                                id = "feature-" + (result.size() + 1);
                            }
                            result.add(new AlkisBuilding(id, feature.polygons, feature.attributes));
                        }
                        feature = null;
                        text = null;
                    } else if (rel == 1) {
                        // Ende einer Eigenschaft
                        if (!feature.propertyHasChildren && text != null) {
                            String v = text.toString().strip();
                            if (!v.isEmpty()) {
                                feature.attributes.merge(local, v, (a, b) -> a + ";" + b);
                            }
                        }
                        text = null;
                    } else {
                        if (("posList".equals(local) || "pos".equals(local)) && text != null && feature.ring != null) {
                            addCoordinates(feature.ring, text, dim);
                            text = null;
                        } else if ("coordinates".equals(local) && text != null && feature.ring != null) {
                            addGml2Coordinates(feature.ring, text);
                            text = null;
                        } else if ("LinearRing".equals(local) && feature.ring != null) {
                            double[] ring = toClosedRing(feature.ring);
                            if (ring != null) {
                                if (feature.inHole) {
                                    if (feature.holes != null) {
                                        feature.holes.add(ring);
                                    }
                                } else {
                                    feature.outer = ring;
                                }
                            }
                            feature.ring = null;
                        } else if (("Polygon".equals(local) || "PolygonPatch".equals(local)) && feature.outer != null) {
                            feature.polygons.add(new AlkisBuilding.Polygon(feature.outer, feature.holes));
                            feature.outer = null;
                            feature.holes = null;
                        }
                    }
                }
                break;
            }
            default:
                break;
            }
        }
        if (exception) {
            String msg = WS.matcher(exceptionText.toString()).replaceAll(" ").strip();
            throw new WfsException("Der Dienst meldet einen Fehler: " + (msg.isEmpty() ? "(ohne Text)" : msg));
        }
        return result;
    }

    private static String gmlId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("id".equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private void addCoordinates(List<double[]> ring, CharSequence text, int dim) throws WfsException {
        String t = text.toString().strip();
        if (t.isEmpty()) {
            return;
        }
        String[] parts = WS.split(t);
        int d = dim >= 2 ? dim : 2;
        if (parts.length % d != 0) {
            throw new WfsException("Ungültige Koordinatenliste (Anzahl " + parts.length + " nicht durch " + d + " teilbar)");
        }
        try {
            for (int i = 0; i < parts.length; i += d) {
                addPoint(ring, Double.parseDouble(parts[i]), Double.parseDouble(parts[i + 1]));
            }
        } catch (NumberFormatException e) {
            throw new WfsException("Ungültige Koordinate im GML: " + e.getMessage(), e);
        }
    }

    private void addGml2Coordinates(List<double[]> ring, CharSequence text) throws WfsException {
        String t = text.toString().strip();
        if (t.isEmpty()) {
            return;
        }
        try {
            for (String tuple : WS.split(t)) {
                String[] xy = tuple.split(",");
                if (xy.length >= 2) {
                    addPoint(ring, Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
                }
            }
        } catch (NumberFormatException e) {
            throw new WfsException("Ungültige Koordinate im GML: " + e.getMessage(), e);
        }
    }

    private void addPoint(List<double[]> ring, double a, double b) {
        ring.add(swapAxes ? new double[] {b, a} : new double[] {a, b});
    }

    /**
     * Entfernt aufeinanderfolgende doppelte Punkte und schließt den Ring.
     * @return Ring als {@code x0,y0,...} oder {@code null}, wenn weniger als drei verschiedene Punkte
     */
    static double[] toClosedRing(List<double[]> pts) {
        List<double[]> clean = new ArrayList<>(pts.size() + 1);
        for (double[] p : pts) {
            if (clean.isEmpty() || !same(clean.get(clean.size() - 1), p)) {
                clean.add(p);
            }
        }
        if (clean.size() > 1 && same(clean.get(0), clean.get(clean.size() - 1))) {
            clean.remove(clean.size() - 1);
        }
        if (clean.size() < 3) {
            return null;
        }
        clean.add(clean.get(0));
        double[] out = new double[clean.size() * 2];
        for (int i = 0; i < clean.size(); i++) {
            out[2 * i] = clean.get(i)[0];
            out[2 * i + 1] = clean.get(i)[1];
        }
        return out;
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6;
    }

    /** Zustand des gerade gelesenen Features. */
    private static final class FeatureState {
        final int depth;
        final String gmlId;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<AlkisBuilding.Polygon> polygons = new ArrayList<>();
        String property;
        boolean propertyHasChildren;
        List<double[]> ring;
        double[] outer;
        List<double[]> holes;
        boolean inHole;

        FeatureState(int depth, String gmlId) {
            this.depth = depth;
            this.gmlId = gmlId;
        }
    }
}
