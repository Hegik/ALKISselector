// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.stream.JsonGenerator;

/**
 * Verwaltet die Dienstprofile in den JOSM-Einstellungen und ermöglicht Import/Export als JSON-Datei.
 */
public final class ProfileStore {

    private static final String PREF_PROFILES = "alkisselector.profiles";
    private static final String PREF_ACTIVE = "alkisselector.profile.active";

    private static final ProfileStore INSTANCE = new ProfileStore();

    private final List<ServiceProfile> profiles = new CopyOnWriteArrayList<>();
    private String activeName;
    private boolean loaded;

    private ProfileStore() {
        // Singleton
    }

    /** @return die globale Instanz */
    public static ProfileStore getInstance() {
        return INSTANCE;
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        String json = Config.getPref().get(PREF_PROFILES, "");
        if (!json.isBlank()) {
            try {
                profiles.addAll(parse(new StringReader(json)));
            } catch (JsonException | IllegalArgumentException e) {
                Logging.warn("ALKISselector: gespeicherte Profile nicht lesbar, verwende Standardprofile");
                Logging.warn(e);
            }
        }
        if (profiles.isEmpty()) {
            profiles.addAll(DefaultProfiles.all());
        }
        activeName = Config.getPref().get(PREF_ACTIVE, profiles.get(0).getName());
    }

    /** @return Kopie der Profilliste */
    public List<ServiceProfile> getProfiles() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    /**
     * @return das aktive Profil (nie {@code null}; fällt auf das erste Profil zurück)
     */
    public ServiceProfile getActiveProfile() {
        ensureLoaded();
        for (ServiceProfile p : profiles) {
            if (p.getName().equals(activeName)) {
                return p;
            }
        }
        return profiles.get(0);
    }

    /**
     * Ersetzt alle Profile und speichert sie in den Einstellungen.
     * @param newProfiles neue Profilliste (nicht leer)
     * @param active Name des aktiven Profils
     */
    public synchronized void setProfiles(List<ServiceProfile> newProfiles, String active) {
        ensureLoaded();
        if (newProfiles.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Profil erforderlich");
        }
        profiles.clear();
        profiles.addAll(newProfiles);
        activeName = active;
        save();
    }

    /**
     * Setzt das aktive Profil.
     * @param name Profilname
     */
    public synchronized void setActive(String name) {
        ensureLoaded();
        activeName = name;
        Config.getPref().put(PREF_ACTIVE, name);
    }

    private void save() {
        Config.getPref().put(PREF_PROFILES, toJsonString(profiles, false));
        Config.getPref().put(PREF_ACTIVE, activeName);
    }

    // ------------------------------------------------------------------ Import / Export

    /**
     * Liest Profile aus einer JSON-Datei. Die Datei darf ein einzelnes Profil (Objekt) oder eine
     * Liste von Profilen (Array) enthalten.
     * @param file Datei
     * @return gelesene Profile
     * @throws IOException bei Lesefehlern
     */
    public static List<ServiceProfile> importFile(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        } catch (JsonException e) {
            throw new IOException("Keine gültige Profildatei: " + e.getMessage(), e);
        }
    }

    /**
     * Schreibt Profile als formatierte JSON-Datei.
     * @param file Zieldatei
     * @param list zu exportierende Profile
     * @throws IOException bei Schreibfehlern
     */
    public static void exportFile(Path file, List<ServiceProfile> list) throws IOException {
        Files.write(file, toJsonString(list, true).getBytes(StandardCharsets.UTF_8));
    }

    static List<ServiceProfile> parse(Reader r) {
        List<ServiceProfile> result = new ArrayList<>();
        try (JsonReader jr = Json.createReader(r)) {
            JsonStructure s = jr.read();
            if (s instanceof JsonArray) {
                for (JsonValue v : (JsonArray) s) {
                    result.add(ServiceProfile.fromJson(v.asJsonObject()));
                }
            } else if (s instanceof JsonObject) {
                result.add(ServiceProfile.fromJson((JsonObject) s));
            }
        }
        return result;
    }

    static String toJsonString(List<ServiceProfile> list, boolean pretty) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        list.forEach(p -> a.add(p.toJson()));
        StringWriter sw = new StringWriter();
        Map<String, ?> cfg = pretty ? Collections.singletonMap(JsonGenerator.PRETTY_PRINTING, true) : Collections.emptyMap();
        try (Writer w = sw; JsonWriter jw = Json.createWriterFactory(cfg).createWriter(w)) {
            jw.writeArray(a.build());
        } catch (IOException e) {
            throw new JsonException(e.getMessage(), e);
        }
        return sw.toString();
    }
}
