// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.decision;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.config.AlkisSettings;
import de.alkisselector.ortho.OrthoResult;

/**
 * Protokolliert jede Nutzerentscheidung als CSV-Zeile. Das Protokoll ist die Datengrundlage für die
 * Evaluierung (Konfusionsmatrix Empfehlung ↔ Entscheidung, Kalibrierung des Schwellenwerts,
 * Bearbeitungszeit).
 */
public final class DecisionLog {

    /** Kopfzeile der CSV-Datei. */
    public static final String HEADER = "zeitpunkt;profil;alkis_id;funktion;klasse;iou;hausdorff_m;ortho_score;"
            + "osm_ortho_score;versatz_m;empfehlung;entscheidung;dauer_ms;hinweise";

    private DecisionLog() {
        // Hilfsklasse
    }

    /** @return Pfad der Protokolldatei im JOSM-Benutzerverzeichnis */
    public static Path getFile() {
        File dir = new File(Config.getDirs().getUserDataDirectory(true), "alkisselector");
        return new File(dir, "entscheidungen.csv").toPath();
    }

    /**
     * Schreibt eine Entscheidung ins Protokoll (Fehler werden nur geloggt).
     * @param session Sitzung
     * @param c Kandidat
     * @param decision Entscheidung des Nutzers
     */
    public static void log(AnalysisSession session, Candidate c, Candidate.Status decision) {
        if (!AlkisSettings.isDecisionLogEnabled()) {
            return;
        }
        try {
            write(getFile(), line(session, c, decision));
        } catch (IOException e) {
            Logging.warn("ALKISselector: Entscheidungsprotokoll nicht schreibbar: " + e.getMessage());
        }
    }

    static synchronized void write(Path file, String line) throws IOException {
        Files.createDirectories(file.getParent());
        boolean isNew = !Files.exists(file);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (isNew) {
                w.write(HEADER);
                w.write('\n');
            }
            w.write(line);
            w.write('\n');
        }
    }

    static String line(AnalysisSession session, Candidate c, Candidate.Status decision) {
        long duration = c.getShownSince() > 0 ? System.currentTimeMillis() - c.getShownSince() : -1;
        OrthoResult o = c.getOrtho();
        OrthoResult oo = c.getOsmOrtho();
        String funktion = c.getBuilding() != null ? c.getBuilding().getAttributes().get("funktion") : "";
        return String.join(";",
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                csv(session.getProfile().getName()),
                csv(c.getId()),
                csv(funktion),
                c.getMatchClass().name(),
                num(c.getMatch().getIou()),
                num(c.getMatch().getHausdorff()),
                o != null ? num(o.getScore()) : "",
                oo != null ? num(oo.getScore()) : "",
                o != null && o.isValid() ? num(o.getOffset()) : "",
                c.getRecommendation() != null ? c.getRecommendation().name() : "",
                decision.name(),
                Long.toString(duration),
                csv(String.join(" | ", c.getHints())));
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        if (t.contains(";") || t.contains("\"")) {
            return '"' + t.replace("\"", "\"\"") + '"';
        }
        return t;
    }
}
