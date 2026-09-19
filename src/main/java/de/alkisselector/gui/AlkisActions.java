// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.preferences.PreferenceDialog;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;

import de.alkisselector.config.ProfileStore;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.decision.DecisionLog;
import de.alkisselector.ortho.OrthoFetcher;

/**
 * Menüaktionen des Plugins.
 */
public final class AlkisActions {

    /** Gemeinsame Instanz (Menü, Review-Dialog, Tastenkürzel V). */
    public static final ToggleViewAction TOGGLE_VIEW = new ToggleViewAction();

    private AlkisActions() {
        // Sammlung
    }

    /** Analysiert den sichtbaren Kartenausschnitt (Stapel-Review). */
    public static final class AnalyzeViewAction extends JosmAction {
        /** Erzeugt die Aktion. */
        public AnalyzeViewAction() {
            super("Ausschnitt analysieren", "alkisselector",
                    "ALKIS-Gebäude im sichtbaren Ausschnitt laden, mit OSM und Luftbild abgleichen",
                    Shortcut.registerShortcut("alkisselector:analyze", "ALKIS: Ausschnitt analysieren",
                            KeyEvent.VK_K, Shortcut.CTRL_SHIFT),
                    true, "alkisselector/analyze", true);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            AlkisController.getInstance().analyzeView();
        }

        @Override
        protected void updateEnabledState() {
            setEnabled(getLayerManager().getEditDataSet() != null);
        }
    }

    /** Schaltet die Karte für den ausgewählten Kandidaten zwischen „alt“ (heute) und „neu“ (Vorschau) um. */
    public static final class ToggleViewAction extends JosmAction {
        /** Erzeugt die Aktion. */
        public ToggleViewAction() {
            super("Alt/Neu umschalten", "dialogs/refresh",
                    "Karte zwischen heutigem Stand (alt) und Zustand nach der Übernahme (neu) umschalten",
                    Shortcut.registerShortcut("alkisselector:toggleview", "ALKIS: Ansicht alt/neu umschalten",
                            KeyEvent.VK_V, Shortcut.DIRECT),
                    false, "alkisselector/toggleview", false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            AlkisController.getInstance().toggleViewMode();
        }
    }

    /** Verwirft die aktuelle Analyse. */
    public static final class ClearAction extends JosmAction {
        /** Erzeugt die Aktion. */
        public ClearAction() {
            super("Analyse verwerfen", "dialogs/delete", "Vorschau-Layer und Review-Liste leeren",
                    Shortcut.registerShortcut("alkisselector:clear", "ALKIS: Analyse verwerfen", KeyEvent.CHAR_UNDEFINED,
                            Shortcut.NONE),
                    false, "alkisselector/clear", false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            AlkisController.getInstance().clear();
        }
    }

    /** Art der WMS-Ebene, die hinzugefügt wird. */
    public enum WmsKind {
        /** Luftbild. */
        ORTHO("Orthophoto als Ebene anzeigen", "download", "Luftbild des aktiven Profils als Hintergrund anzeigen",
                "alkisselector:ortho", "Orthophoto"),
        /** ALKIS-Karte zum visuellen Abgleich (50 % Deckkraft, immer über dem Luftbild). */
        ALKIS_MAP("ALKIS-Karte als Ebene anzeigen", "alkisselector",
                "ALKIS-Karte (WMS) des aktiven Profils mit 50 % Deckkraft über dem Luftbild anzeigen",
                "alkisselector:alkismap", "ALKIS-Karte"),
        /** Flurstücksgrenzen (nur Anzeige). */
        PARCELS("Flurstücke als Ebene anzeigen", "dialogs/edit",
                "Flurstücksgrenzen des aktiven Profils als Hintergrund anzeigen (werden nicht übernommen)",
                "alkisselector:parcels", "Flurstücke");

        final String name;
        final String icon;
        final String tooltip;
        final String shortcutId;
        final String label;

        WmsKind(String name, String icon, String tooltip, String shortcutId, String label) {
            this.name = name;
            this.icon = icon;
            this.tooltip = tooltip;
            this.shortcutId = shortcutId;
            this.label = label;
        }
    }

    /** Hält die ALKIS-Karte über den Luftbildern. */
    private static WmsLayerOrder layerOrder;

    private static synchronized WmsLayerOrder layerOrder() {
        if (layerOrder == null) {
            layerOrder = new WmsLayerOrder(MainApplication.getLayerManager());
        }
        return layerOrder;
    }

    /** Fügt einen WMS des aktiven Profils (Orthophoto, ALKIS-Karte, Flurstücke) als Ebene hinzu. */
    public static final class AddWmsLayerAction extends JosmAction {
        private final WmsKind kind;

        /**
         * @param kind Art der Ebene
         */
        public AddWmsLayerAction(WmsKind kind) {
            super(kind.name, kind.icon, kind.tooltip,
                    Shortcut.registerShortcut(kind.shortcutId, "ALKIS: " + kind.name, KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                    false, kind.shortcutId.replace(':', '/'), false);
            this.kind = kind;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ServiceProfile p = ProfileStore.getInstance().getActiveProfile();
            String url;
            String layers;
            switch (kind) {
            case PARCELS:
                url = p.getParcelWmsUrl();
                layers = p.getParcelLayers();
                break;
            case ALKIS_MAP:
                url = p.getAlkisMapUrl();
                layers = p.getAlkisMapLayers();
                break;
            default:
                url = p.getOrthoWmsUrl();
                layers = p.getOrthoLayers();
                break;
            }
            if (url.isBlank() || layers.isBlank()) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        "Im Profil „" + p.getName() + "“ ist kein WMS für „" + kind.label + "“ eingetragen "
                                + "(Einstellungen → ALKISselector → Dienstprofile).",
                        "ALKISselector", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            boolean transparent = kind != WmsKind.ORTHO;
            String template = OrthoFetcher.josmWmsTemplate(url, layers, transparent ? "image/png" : p.getOrthoFormat(),
                    transparent);
            ImageryInfo info = new ImageryInfo(kind.label + " – " + p.getName(), template, "wms", null, null);
            info.setServerProjections(Arrays.asList("EPSG:3857", "EPSG:4326", p.getCrs()));
            if (!p.getSourceTag().isBlank()) {
                info.setAttributionText(p.getSourceTag());
            }
            ImageryLayer layer = ImageryLayer.create(info);
            if (kind == WmsKind.ALKIS_MAP) {
                layerOrder().addAlkisMap(layer);
            } else {
                MainApplication.getLayerManager().addLayer(layer);
            }
        }

        @Override
        protected void updateEnabledState() {
            setEnabled(MainApplication.isDisplayingMapView());
        }
    }

    /** Öffnet den Ordner mit dem Entscheidungsprotokoll. */
    public static final class OpenLogAction extends JosmAction {
        /** Erzeugt die Aktion. */
        public OpenLogAction() {
            super("Entscheidungsprotokoll öffnen", "open", "Ordner mit der CSV-Datei für die Evaluierung öffnen",
                    Shortcut.registerShortcut("alkisselector:log", "ALKIS: Protokoll öffnen", KeyEvent.CHAR_UNDEFINED,
                            Shortcut.NONE),
                    false, "alkisselector/log", false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Path file = DecisionLog.getFile();
            File dir = file.getParent().toFile();
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String err = OpenBrowser.displayUrl(dir.toURI());
            if (err != null) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(), "Protokolldatei: " + file,
                        "ALKISselector", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    /** Öffnet die Plugin-Einstellungen. */
    public static final class PreferencesAction extends JosmAction {
        /** Erzeugt die Aktion. */
        public PreferencesAction() {
            super("Einstellungen …", "preference", "Dienstprofile, Tag-Übersetzung und Schwellenwerte bearbeiten",
                    Shortcut.registerShortcut("alkisselector:prefs", "ALKIS: Einstellungen", KeyEvent.CHAR_UNDEFINED,
                            Shortcut.NONE),
                    false, "alkisselector/prefs", false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            PreferenceDialog dlg = new PreferenceDialog(MainApplication.getMainFrame());
            dlg.selectPreferencesTabByClass(AlkisPreferenceSetting.class);
            dlg.setVisible(true);
        }
    }
}
