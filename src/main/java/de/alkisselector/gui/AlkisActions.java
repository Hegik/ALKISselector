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

    /** Fügt den Orthophoto- oder Flurstücks-WMS des aktiven Profils als Hintergrundebene hinzu. */
    public static final class AddWmsLayerAction extends JosmAction {
        private final boolean parcels;

        /**
         * @param parcels {@code true} = Flurstücke, {@code false} = Orthophoto
         */
        public AddWmsLayerAction(boolean parcels) {
            super(parcels ? "Flurstücke als Ebene anzeigen" : "Orthophoto als Ebene anzeigen",
                    parcels ? "dialogs/edit" : "download",
                    parcels ? "Flurstücksgrenzen des aktiven Profils als Hintergrund anzeigen (werden nicht übernommen)"
                            : "Luftbild des aktiven Profils als Hintergrund anzeigen",
                    Shortcut.registerShortcut(parcels ? "alkisselector:parcels" : "alkisselector:ortho",
                            parcels ? "ALKIS: Flurstücke anzeigen" : "ALKIS: Orthophoto anzeigen",
                            KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                    false, parcels ? "alkisselector/parcels" : "alkisselector/ortho", false);
            this.parcels = parcels;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            ServiceProfile p = ProfileStore.getInstance().getActiveProfile();
            String url = parcels ? p.getParcelWmsUrl() : p.getOrthoWmsUrl();
            String layers = parcels ? p.getParcelLayers() : p.getOrthoLayers();
            if (url.isBlank() || layers.isBlank()) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        "Im Profil „" + p.getName() + "“ ist kein " + (parcels ? "Flurstücks" : "Orthophoto") + "-WMS eingetragen.",
                        "ALKISselector", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String template = OrthoFetcher.josmWmsTemplate(url, layers, parcels ? "image/png" : p.getOrthoFormat(), parcels);
            ImageryInfo info = new ImageryInfo((parcels ? "Flurstücke – " : "Orthophoto – ") + p.getName(), template,
                    "wms", null, null);
            info.setServerProjections(Arrays.asList("EPSG:3857", "EPSG:4326", p.getCrs()));
            if (!p.getSourceTag().isBlank()) {
                info.setAttributionText(p.getSourceTag());
            }
            MainApplication.getLayerManager().addLayer(ImageryLayer.create(info));
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
