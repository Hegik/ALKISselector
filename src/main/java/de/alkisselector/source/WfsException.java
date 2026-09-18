// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.source;

import java.io.IOException;

/**
 * Fehler bei der Abfrage eines WFS- oder WMS-Dienstes (HTTP-Fehler oder OGC-Exception-Report).
 */
public class WfsException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Fehlermeldung
     */
    public WfsException(String message) {
        super(message);
    }

    /**
     * @param message Fehlermeldung
     * @param cause Ursache
     */
    public WfsException(String message, Throwable cause) {
        super(message, cause);
    }
}
