// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

/**
 * Ein vorgeschlagenes OSM-Tag, das aus einem ALKIS-Attribut abgeleitet wurde. Der Nutzer kann den
 * Wert im Review-Dialog ändern oder das Tag abwählen.
 */
public final class TagProposal {

    private final String key;
    private String value;
    private final String origin;
    private boolean selected = true;
    /** Wert, den das bestehende OSM-Objekt für diesen Key bereits hat (oder {@code null}). */
    private String existingValue;

    /**
     * @param key OSM-Key
     * @param value vorgeschlagener Wert
     * @param origin Herkunft, z. B. {@code funktion=Wohngebäude}
     */
    public TagProposal(String key, String value, String origin) {
        this.key = key;
        this.value = value;
        this.origin = origin;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getOrigin() {
        return origin;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getExistingValue() {
        return existingValue;
    }

    /**
     * Hinterlegt den vorhandenen OSM-Wert. Weicht er vom Vorschlag ab, wird der Vorschlag
     * abgewählt, damit bestehende Tags nie ungefragt überschrieben werden.
     * @param existingValue vorhandener Wert oder {@code null}
     */
    public void setExistingValue(String existingValue) {
        this.existingValue = existingValue;
        if (isConflict()) {
            selected = false;
        } else if (existingValue != null) {
            // identischer Wert: nichts zu tun
            selected = false;
        }
    }

    /** @return ob der vorhandene OSM-Wert vom Vorschlag abweicht */
    public boolean isConflict() {
        return existingValue != null && !existingValue.equals(value);
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }
}
