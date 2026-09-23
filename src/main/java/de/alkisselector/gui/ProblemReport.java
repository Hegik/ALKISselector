// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.io.OsmWriter;
import org.openstreetmap.josm.io.OsmWriterFactory;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.compare.OsmBuilding;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.config.TagProposal;
import de.alkisselector.decision.AnalysisSession;
import de.alkisselector.decision.Candidate;
import de.alkisselector.ortho.OrthoResult;

/**
 * Problemmeldung aus dem Review-Dialog: hält den Zustand des ausgewählten Eintrags so fest, dass
 * sich der Fall später nachvollziehen und in einem Test nachstellen lässt. Je Meldung entsteht ein
 * Ordner mit {@code bericht.txt} (Kommentar und alle Werte des Eintrags), {@code daten.osm} (OSM-Daten
 * der Ebene mit heruntergeladenen Bereichen und ungespeicherten Änderungen) und {@code karte.png}.
 */
final class ProblemReport {

    private ProblemReport() {
        // Hilfsklasse
    }

    /** @return Ordner, unter dem die Meldungen abgelegt werden */
    static Path getDirectory() {
        return new File(new File(Config.getDirs().getUserDataDirectory(true), "alkisselector"), "meldungen").toPath();
    }

    /**
     * Schreibt eine Meldung (im EDT aufrufen, das Kartenbild wird aus der aktuellen Ansicht gezeichnet).
     * @param session aktuelle Sitzung oder {@code null}
     * @param c ausgewählter Eintrag oder {@code null}
     * @param comment Beschreibung des Nutzers
     * @return Ordner der Meldung
     * @throws IOException wenn nicht geschrieben werden kann
     */
    static Path write(AnalysisSession session, Candidate c, String comment) throws IOException {
        Path dir = getDirectory().resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bericht.txt"), report(session, c, comment), StandardCharsets.UTF_8);
        DataSet ds = session != null ? session.getDataSet() : MainApplication.getLayerManager().getEditDataSet();
        if (ds != null) {
            writeOsm(ds, dir.resolve("daten.osm"));
        }
        writeMap(dir.resolve("karte.png"));
        return dir;
    }

    private static String report(AnalysisSession session, Candidate c, String comment) {
        StringBuilder sb = new StringBuilder();
        line(sb, "Zeit", LocalDateTime.now().toString());
        line(sb, "JOSM", Version.getInstance().getVersionString());
        sb.append("\n== Kommentar\n").append(comment == null ? "" : comment.strip()).append("\n");
        if (session != null) {
            ServiceProfile p = session.getProfile();
            sb.append("\n== Profil\n");
            line(sb, "Name", p.getName());
            line(sb, "WFS", p.getWfsUrl() + " (" + p.getBuildingTypeName() + ")");
            line(sb, "CRS", p.getCrs());
            line(sb, "Ausgeblendet (Bauteile usw.)", String.valueOf(session.getExcludedCount()));
            line(sb, "Außerhalb geladener Bereich", String.valueOf(session.getOutsideCount()));
            line(sb, "WFS abgeschnitten", String.valueOf(session.isTruncated()));
            DataSet ds = session.getDataSet();
            sb.append("\n== Heruntergeladene Bereiche\n");
            for (Bounds b : ds.getDataSourceBounds()) {
                sb.append(b.toBBox().toStringCSV(",")).append("\n");
            }
        }
        if (c != null) {
            candidate(sb, c);
        } else {
            sb.append("\n(kein Eintrag ausgewählt)\n");
        }
        if (session != null) {
            sb.append("\n== Alle Einträge (Klasse, Empfehlung, Status, ID, Titel)\n");
            for (Candidate x : session.getCandidates()) {
                sb.append(x == c ? "> " : "  ").append(x.getMatchClass()).append(" | ")
                        .append(x.getRecommendation() != null ? x.getRecommendation().getLabel() : "-").append(" | ")
                        .append(x.getStatus()).append(" | ").append(x.getId()).append(" | ").append(x.getTitle())
                        .append("\n");
            }
        }
        sb.append("\n== Letzte Warnungen und Fehler im JOSM-Log\n");
        for (String s : Logging.getLastErrorAndWarnings()) {
            sb.append(s).append("\n");
        }
        return sb.toString();
    }

    private static void candidate(StringBuilder sb, Candidate c) {
        sb.append("\n== Ausgewählter Eintrag\n");
        line(sb, "ID", c.getId());
        line(sb, "Titel", c.getTitle());
        line(sb, "Klasse", String.valueOf(c.getMatchClass()));
        line(sb, "Empfehlung", c.getRecommendation() != null ? c.getRecommendation().name() + " – "
                + c.getRecommendation().getLabel() : "-");
        line(sb, "Status", String.valueOf(c.getStatus()));
        line(sb, "Ersetzt Geometrie", String.valueOf(c.isReplacement()));
        line(sb, "Angleichen nötig", String.valueOf(c.isAlignRequired()));
        if (c.getMatch() != null) {
            line(sb, "IoU", fmt(c.getMatch().getIou()));
            line(sb, "Hausdorff (m)", fmt(c.getMatch().getHausdorff()));
            line(sb, "Zuordnungshinweis", c.getMatch().getNote());
            StringBuilder partners = new StringBuilder();
            for (OsmBuilding o : c.getMatch().getPartners()) {
                partners.append(o.getPrimitive().getPrimitiveId()).append(" ");
            }
            line(sb, "OSM-Partner", partners.toString().strip());
        }
        line(sb, "Luftbild ALKIS", ortho(c.getOrtho()));
        line(sb, "Luftbild OSM", ortho(c.getOsmOrtho()));
        line(sb, "Vorbedingungen", ids(c.getPrerequisites()));
        line(sb, "Abhängige", ids(c.getDependents()));
        if (c.getAppliedCommand() != null) {
            line(sb, "Übernahme-Befehl", c.getAppliedCommand().getDescriptionText());
        }
        sb.append("\n-- Hinweise\n");
        for (String h : c.getAllHints()) {
            sb.append("- ").append(h).append("\n");
        }
        sb.append("\n-- Tags (übernehmen, Schlüssel=Wert, Herkunft)\n");
        for (TagProposal t : c.getTags()) {
            sb.append(t).append("\n");
        }
        if (c.getBuilding() != null) {
            sb.append("\n-- ALKIS-Attribute\n");
            for (Map.Entry<String, String> e : c.getBuilding().getAttributes().entrySet()) {
                sb.append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
            }
        }
        if (c.getGeometry() != null) {
            sb.append("\n-- ALKIS-Geometrie (WKT, Arbeits-CRS)\n").append(c.getGeometry().toText()).append("\n");
        }
        if (c.getFit() != null) {
            sb.append("\n-- Angepasste Geometrie (WKT, Arbeits-CRS)\n")
                    .append(c.getFit().getGeometry() != null ? c.getFit().getGeometry().toText() : "-").append("\n");
            sb.append("Verlorene Verbindungen: ").append(c.getFit().getLostConnections()).append("\n");
        }
        if (c.getOsmOnly() != null) {
            sb.append("\n-- OSM-Geometrie (WKT, Arbeits-CRS)\n").append(c.getOsmOnly().getGeometry().toText()).append("\n");
        }
    }

    private static String ortho(OrthoResult o) {
        if (o == null) {
            return "-";
        }
        if (!o.isValid()) {
            return "ungültig: " + o.getError();
        }
        return String.format(Locale.ROOT, "Score %.2f, Versatz %.2f/%.2f m, %d Stützpunkte", o.getScore(),
                o.getOffsetX(), o.getOffsetY(), o.getSamples());
    }

    private static String ids(List<Candidate> list) {
        StringBuilder sb = new StringBuilder();
        for (Candidate x : list) {
            sb.append(x.getId()).append(" (").append(x.getStatus()).append(") ");
        }
        return sb.toString().strip();
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "-" : String.format(Locale.ROOT, "%.3f", d);
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value == null ? "-" : value).append("\n");
    }

    private static void writeOsm(DataSet ds, Path file) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(w);
             OsmWriter osm = OsmWriterFactory.createOsmWriter(pw, false, "0.6")) {
            ds.getReadLock().lock();
            try {
                osm.write(ds);
            } finally {
                ds.getReadLock().unlock();
            }
        } catch (IOException | RuntimeException e) {
            Logging.warn("ALKISselector: OSM-Daten für die Meldung nicht geschrieben: " + e);
        }
    }

    private static void writeMap(Path file) {
        if (!MainApplication.isDisplayingMapView()) {
            return;
        }
        try {
            MapView mv = MainApplication.getMap().mapView;
            BufferedImage img = new BufferedImage(Math.max(1, mv.getWidth()), Math.max(1, mv.getHeight()),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            // MapView zeichnet nur den Clip-Bereich; ohne Clip bricht das Zeichnen ab
            g.setClip(0, 0, img.getWidth(), img.getHeight());
            try {
                mv.paint(g);
            } finally {
                g.dispose();
            }
            ImageIO.write(img, "png", file.toFile());
        } catch (IOException | RuntimeException e) {
            Logging.warn("ALKISselector: Kartenbild für die Meldung nicht geschrieben: " + e);
        }
    }
}
