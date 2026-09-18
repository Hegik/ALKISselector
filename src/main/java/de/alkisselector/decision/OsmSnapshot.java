// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.compare.OsmMatcher;
import de.alkisselector.source.CrsTransformer;

/**
 * Momentaufnahme der für die Analyse benötigten OSM-Daten (Gebäude und Adressen), die unter
 * Lesesperre erstellt und danach im Hintergrund ausgewertet wird.
 */
public final class OsmSnapshot {

    private final List<OsmBuilding> buildings;
    private final List<AddressEntry> addresses;
    private final List<NeighbourFitter.NeighbourWay> neighbourWays;
    private final Map<OsmPrimitive, List<NeighbourFitter.KeepNode>> keepNodes;

    private OsmSnapshot(List<OsmBuilding> buildings, List<AddressEntry> addresses,
            List<NeighbourFitter.NeighbourWay> neighbourWays, Map<OsmPrimitive, List<NeighbourFitter.KeepNode>> keepNodes) {
        this.buildings = buildings;
        this.addresses = addresses;
        this.neighbourWays = neighbourWays;
        this.keepNodes = keepNodes;
    }

    /**
     * Erstellt die Momentaufnahme (setzt selbst die Lesesperre).
     * @param ds Datensatz
     * @param crs Arbeits-CRS
     * @param bounds Bereich (sollte etwas größer als der Analysebereich sein)
     * @return Momentaufnahme
     */
    public static OsmSnapshot create(DataSet ds, CrsTransformer crs, Bounds bounds) {
        ds.getReadLock().lock();
        try {
            List<OsmBuilding> b = OsmMatcher.collect(ds, crs, bounds);
            List<AddressEntry> a = new ArrayList<>();
            BBox bbox = new BBox(bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon(), bounds.getMaxLat());
            List<OsmPrimitive> prims = new ArrayList<>();
            prims.addAll(ds.searchNodes(bbox));
            prims.addAll(ds.searchWays(bbox));
            prims.addAll(ds.searchRelations(bbox));
            for (OsmPrimitive p : prims) {
                String hn = p.get("addr:housenumber");
                String street = p.get("addr:street");
                if (hn != null && street != null && p.isUsable() && p.getBBox().isValid()) {
                    EastNorth en = crs.toProjected(p.getBBox().getCenter());
                    a.add(new AddressEntry(p, street, hn, en));
                }
            }
            Map<OsmPrimitive, List<NeighbourFitter.KeepNode>> keep = new IdentityHashMap<>();
            for (OsmBuilding ob : b) {
                if (ob.getPrimitive() instanceof Way) {
                    keep.put(ob.getPrimitive(), NeighbourWays.keepNodes((Way) ob.getPrimitive(), crs));
                }
            }
            return new OsmSnapshot(b, a, NeighbourWays.collect(ds, crs, bounds), keep);
        } finally {
            ds.getReadLock().unlock();
        }
    }

    /** @return leere Momentaufnahme (für Tests) */
    public static OsmSnapshot empty() {
        return new OsmSnapshot(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap());
    }

    /**
     * @param buildings Gebäude
     * @param addresses Adressen
     * @return Momentaufnahme aus vorgegebenen Daten (für Tests)
     */
    public static OsmSnapshot of(List<OsmBuilding> buildings, List<AddressEntry> addresses) {
        return new OsmSnapshot(buildings, addresses, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * @param building OSM-Gebäude
     * @return Knoten des Gebäudes, die mit anderen Wegen verbunden sind oder Tags tragen
     */
    public List<NeighbourFitter.KeepNode> getKeepNodes(OsmPrimitive building) {
        return keepNodes.getOrDefault(building, Collections.emptyList());
    }

    /** @return geschlossene Gebäudeumrisse mit Knoten (für die Anpassung an Nachbargebäude) */
    public List<NeighbourFitter.NeighbourWay> getNeighbourWays() {
        return neighbourWays;
    }

    public List<OsmBuilding> getBuildings() {
        return buildings;
    }

    public List<AddressEntry> getAddresses() {
        return addresses;
    }

    /**
     * Sucht ein vorhandenes OSM-Objekt mit derselben Adresse im Umkreis.
     * @param street Straße
     * @param housenumber Hausnummer
     * @param x Ostwert
     * @param y Nordwert
     * @param radius Suchradius in m
     * @param ignore Objekt, das nicht berücksichtigt wird (z. B. das zu ersetzende Gebäude), darf {@code null} sein
     * @return gefundener Eintrag oder {@code null}
     */
    public AddressEntry findAddress(String street, String housenumber, double x, double y, double radius, OsmPrimitive ignore) {
        for (AddressEntry e : addresses) {
            if (e.primitive == ignore || !e.street.equalsIgnoreCase(street) || !e.housenumber.equalsIgnoreCase(housenumber)) {
                continue;
            }
            if (Math.hypot(e.position.east() - x, e.position.north() - y) <= radius) {
                return e;
            }
        }
        return null;
    }

    /**
     * Ein OSM-Objekt mit Adresse.
     */
    public static final class AddressEntry {
        final OsmPrimitive primitive;
        final String street;
        final String housenumber;
        final EastNorth position;

        /**
         * @param primitive OSM-Objekt
         * @param street Straße
         * @param housenumber Hausnummer
         * @param position Position im Arbeits-CRS
         */
        public AddressEntry(OsmPrimitive primitive, String street, String housenumber, EastNorth position) {
            this.primitive = primitive;
            this.street = street;
            this.housenumber = housenumber;
            this.position = position;
        }

        public OsmPrimitive getPrimitive() {
            return primitive;
        }
    }
}
