// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector;

import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.Http1Client;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Minimale JOSM-Umgebung für Unit-Tests (Einstellungen im Speicher).
 */
public final class TestSupport {

    private static boolean initialized;

    private TestSupport() {
        // Hilfsklasse
    }

    /** Initialisiert die JOSM-Einstellungen und den HTTP-Client einmalig. */
    public static synchronized void initPreferences() {
        if (!initialized) {
            Config.setPreferencesInstance(new MemoryPreferences());
            Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
            HttpClient.setFactory(Http1Client::new);
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
            initialized = true;
        }
    }
}
