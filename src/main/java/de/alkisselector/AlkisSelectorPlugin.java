// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector;

import java.awt.event.KeyEvent;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

import de.alkisselector.config.ProfileStore;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.gui.AlkisActions;
import de.alkisselector.gui.AlkisController;
import de.alkisselector.gui.AlkisPreferenceSetting;
import de.alkisselector.gui.AlkisSelectMapMode;
import de.alkisselector.gui.ReviewDialog;

/**
 * Einstiegspunkt des ALKISselector-Plugins: registriert Menü, Kartenmodus, Seitenfenster und Einstellungen.
 */
public class AlkisSelectorPlugin extends Plugin {

    private final AlkisSelectMapMode mapMode = new AlkisSelectMapMode();

    /**
     * Wird von JOSM beim Laden des Plugins aufgerufen.
     * @param info Plugin-Informationen
     */
    public AlkisSelectorPlugin(PluginInformation info) {
        super(info);
        MainMenu mainMenu = MainApplication.getMenu();
        JMenu menu = mainMenu.addMenu("ALKIS", "ALKIS", KeyEvent.VK_K, mainMenu.getDefaultMenuPos(), "Plugin/ALKISselector");
        MainMenu.add(menu, new AlkisActions.AnalyzeViewAction());
        MainMenu.add(menu, mapMode);
        MainMenu.add(menu, new AlkisActions.ClearAction());
        menu.addSeparator();
        MainMenu.add(menu, new AlkisActions.AddWmsLayerAction(false));
        MainMenu.add(menu, new AlkisActions.AddWmsLayerAction(true));
        menu.addSeparator();
        JMenu profiles = new JMenu("Aktives Profil");
        menu.add(profiles);
        menu.addMenuListener(new ProfileMenuUpdater(profiles));
        MainMenu.add(menu, new AlkisActions.PreferencesAction());
        MainMenu.add(menu, new AlkisActions.OpenLogAction());
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            ReviewDialog dialog = new ReviewDialog();
            newFrame.addToggleDialog(dialog);
            AlkisController.getInstance().setDialog(dialog);
            newFrame.addMapMode(new IconToggleButton(mapMode));
        } else {
            AlkisController.getInstance().setDialog(null);
        }
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new AlkisPreferenceSetting();
    }

    /** Füllt das Untermenü „Aktives Profil“ beim Öffnen des Menüs. */
    private static final class ProfileMenuUpdater implements MenuListener {
        private final JMenu profiles;

        ProfileMenuUpdater(JMenu profiles) {
            this.profiles = profiles;
        }

        @Override
        public void menuSelected(MenuEvent e) {
            profiles.removeAll();
            ButtonGroup group = new ButtonGroup();
            String active = ProfileStore.getInstance().getActiveProfile().getName();
            for (ServiceProfile p : ProfileStore.getInstance().getProfiles()) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(p.getName(), p.getName().equals(active));
                item.setToolTipText(p.getDescription());
                item.addActionListener(ev -> ProfileStore.getInstance().setActive(p.getName()));
                group.add(item);
                profiles.add(item);
            }
        }

        @Override
        public void menuDeselected(MenuEvent e) {
            // nichts
        }

        @Override
        public void menuCanceled(MenuEvent e) {
            // nichts
        }
    }
}
