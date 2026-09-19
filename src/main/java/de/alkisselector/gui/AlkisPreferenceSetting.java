// License: GPL v2 or later. Siehe LICENSE.
package de.alkisselector.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;

import de.alkisselector.config.AlkisSettings;
import de.alkisselector.config.AttributeRule;
import de.alkisselector.config.DefaultProfiles;
import de.alkisselector.config.ExcludeFilter;
import de.alkisselector.config.ProfileStore;
import de.alkisselector.config.ServiceProfile;
import de.alkisselector.config.TagMapping;
import de.alkisselector.ortho.OrthoFetcher;
import de.alkisselector.source.WfsClient;

/**
 * Einstellungsreiter des Plugins: Dienstprofile, Attribut-/Tag-Übersetzung und Schwellenwerte.
 * Bearbeitet werden Kopien der Profile; gespeichert wird erst mit „OK“.
 */
public class AlkisPreferenceSetting extends DefaultTabPreferenceSetting {

    private final List<ServiceProfile> working = new ArrayList<>();
    private ServiceProfile current;
    private final DefaultComboBoxModel<ServiceProfile> profileModel = new DefaultComboBoxModel<>();
    private final JComboBox<ServiceProfile> profileCombo = new JComboBox<>(profileModel);
    private boolean switching;

    // Profilfelder
    private final JTextField name = new JTextField(25);
    private final JTextField description = new JTextField(25);
    private final JTextField sourceTag = new JTextField(25);
    private final JTextField wfsUrl = new JTextField(25);
    private final JComboBox<String> wfsVersion = new JComboBox<>(new String[] {"2.0.0", "1.1.0"});
    private final JTextField typeName = new JTextField(20);
    private final JTextField crs = new JTextField(12);
    private final JCheckBox swapAxes = new JCheckBox("Achsen vertauschen (Nord/Ost)");
    private final JSpinner pageSize = new JSpinner(new SpinnerNumberModel(1000, 1, 100_000, 100));
    private final JTextField idAttribute = new JTextField(12);
    private final JTextField orthoUrl = new JTextField(25);
    private final JComboBox<String> orthoVersion = new JComboBox<>(new String[] {"1.3.0", "1.1.1"});
    private final JTextField orthoLayers = new JTextField(20);
    private final JComboBox<String> orthoFormat = new JComboBox<>(new String[] {"image/jpeg", "image/png"});
    private final JSpinner orthoResolution = new JSpinner(new SpinnerNumberModel(0.1, 0.02, 2.0, 0.05));
    private final JTextField parcelUrl = new JTextField(25);
    private final JTextField parcelLayers = new JTextField(20);
    private final JTextField alkisMapUrl = new JTextField(25);
    private final JTextField alkisMapLayers = new JTextField(20);

    // Attribute & Tags
    private final RuleTableModel ruleModel = new RuleTableModel();
    private final FilterTableModel filterModel = new FilterTableModel();
    private final MappingTableModel mappingModel = new MappingTableModel();
    private final JTextField lookupAttributes = new JTextField(20);
    private final JTextField defaultBuilding = new JTextField(8);

    // Schwellenwerte
    private final Map<AlkisSettings.Setting, JSpinner> thresholdSpinners = new LinkedHashMap<>();
    private final JCheckBox logEnabled = new JCheckBox("Entscheidungsprotokoll (CSV) für die Evaluierung schreiben");
    private final JCheckBox clipOverlaps = new JCheckBox(
            "Überlappungen neuer Gebäude mit vorhandenen OSM-Gebäuden abschneiden");

    /** Erzeugt den Einstellungsreiter. */
    public AlkisPreferenceSetting() {
        super("alkisselector", "ALKISselector", "ALKIS-Gebäude aus frei konfigurierbaren Diensten nach OSM übernehmen");
        ((JSpinner.DefaultEditor) pageSize.getEditor()).getTextField().setColumns(7);
        ((JSpinner.DefaultEditor) orthoResolution.getEditor()).getTextField().setColumns(5);
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        working.clear();
        ProfileStore.getInstance().getProfiles().forEach(p -> working.add(new ServiceProfile(p)));
        String active = ProfileStore.getInstance().getActiveProfile().getName();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dienstprofile", buildProfilePanel());
        tabs.addTab("Attribute & Tags", buildMappingPanel());
        tabs.addTab("Schwellenwerte", buildThresholdPanel());

        switching = true;
        profileModel.removeAllElements();
        working.forEach(profileModel::addElement);
        switching = false;
        ServiceProfile sel = working.stream().filter(p -> p.getName().equals(active)).findFirst().orElse(working.get(0));
        profileCombo.setSelectedItem(sel);
        load(sel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(tabs, BorderLayout.CENTER);
        createPreferenceTabWithScrollPane(gui, panel);
    }

    // ------------------------------------------------------------------ Reiter „Dienstprofile“

    private JComponent buildProfilePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(new JLabel("Aktives Profil:"));
        top.add(profileCombo);
        p.add(top, GBC.eol().fill(GBC.HORIZONTAL));
        top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(button("Neu", this::newProfile));
        top.add(button("Duplizieren", this::duplicateProfile));
        top.add(button("Löschen", this::deleteProfile));
        top.add(button("Importieren …", this::importProfiles));
        top.add(button("Exportieren …", this::exportProfile));
        p.add(top, GBC.eol().fill(GBC.HORIZONTAL));
        profileCombo.addActionListener(e -> {
            if (!switching && profileCombo.getSelectedItem() != null && profileCombo.getSelectedItem() != current) {
                store();
                load((ServiceProfile) profileCombo.getSelectedItem());
            }
        });

        section(p, "Allgemein");
        row(p, "Name:", name);
        row(p, "Beschreibung / Lizenz:", description);
        row(p, "Changeset-Tag source=", sourceTag);

        section(p, "ALKIS-Gebäude (WFS)");
        row(p, "URL:", wfsUrl);
        row(p, "WFS-Version:", wfsVersion);
        row(p, "Objektart (TypeName):", typeName);
        row(p, "Koordinatensystem (metrisch):", crs);
        row(p, "", swapAxes);
        row(p, "Objekte je Abfrage:", pageSize);
        row(p, "Attribut mit Objekt-ID:", idAttribute);
        row(p, "", button("WFS prüfen", this::checkWfs));

        section(p, "Orthophoto (WMS) für die Plausibilisierung");
        row(p, "URL:", orthoUrl);
        row(p, "WMS-Version:", orthoVersion);
        row(p, "Layer:", orthoLayers);
        row(p, "Bildformat:", orthoFormat);
        row(p, "Auflösung (m/Pixel):", orthoResolution);
        row(p, "", button("Orthophoto-WMS prüfen", this::checkOrtho));

        section(p, "Flurstücke (WMS, nur Anzeige – werden nie übernommen)");
        row(p, "URL:", parcelUrl);
        row(p, "Layer:", parcelLayers);

        section(p, "ALKIS-Karte (WMS, zum Abgleich – 50 % Deckkraft, immer über dem Luftbild)");
        row(p, "URL:", alkisMapUrl);
        row(p, "Layer:", alkisMapLayers);
        p.add(new JPanel(), GBC.eol().fill(GBC.BOTH));
        return p;
    }

    // ------------------------------------------------------------------ Reiter „Attribute & Tags“

    private JComponent buildMappingPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        section(p, "Attributregeln (ALKIS-Attribut → OSM-Tag)");
        JTable rules = new JTable(ruleModel);
        rules.getColumnModel().getColumn(0).setMaxWidth(40);
        rules.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(AttributeRule.Type.values())));
        p.add(scroll(rules, 130), GBC.eol().fill(GBC.BOTH).weight(1, 0.3));
        p.add(buttons(button("Regel hinzufügen", () -> ruleModel.add(new AttributeRule(true, "", "", AttributeRule.Type.DIRECT))),
                button("Regel entfernen", () -> ruleModel.remove(rules.getSelectedRow()))), GBC.eol());
        p.add(new JLabel("<html><small>Wertetabelle (nur Typ „Wertetabelle“): <code>ALKIS-Wert=OSM-Wert; …</code>. "
                + "Adressen werden aus der Lagebezeichnung in addr:street/addr:housenumber zerlegt.</small></html>"), GBC.eol());

        section(p, "Ausschlussfilter (Objekte werden nicht angeboten)");
        JTable filters = new JTable(filterModel);
        p.add(scroll(filters, 70), GBC.eol().fill(GBC.BOTH).weight(1, 0.15));
        p.add(buttons(button("Filter hinzufügen", () -> filterModel.add(new ExcludeFilter("", ""))),
                button("Filter entfernen", () -> filterModel.remove(filters.getSelectedRow()))), GBC.eol());

        section(p, "Gebäudeart: ALKIS-Funktion → building=*  (Wert „-“ = kein eigenständiges Gebäude)");
        JPanel lookup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        lookup.add(new JLabel("Nachschlagen in Attributen:"));
        lookup.add(lookupAttributes);
        lookup.add(new JLabel("Standardwert:"));
        lookup.add(defaultBuilding);
        p.add(lookup, GBC.eol());
        JTable mapping = new JTable(mappingModel);
        mapping.setAutoCreateRowSorter(true);
        p.add(scroll(mapping, 200), GBC.eol().fill(GBC.BOTH).weight(1, 0.55));
        p.add(buttons(button("Eintrag hinzufügen", () -> mappingModel.add("", "yes")),
                button("Eintrag entfernen", () -> {
                    int row = mapping.getSelectedRow();
                    mappingModel.remove(row >= 0 ? mapping.convertRowIndexToModel(row) : -1);
                }),
                button("Standardtabelle laden", () -> mappingModel.setEntries(DefaultProfiles.loadDefaultTagTable()))), GBC.eol());
        return p;
    }

    // ------------------------------------------------------------------ Reiter „Schwellenwerte“

    private JComponent buildThresholdPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        section(p, "Luftbildabgleich");
        threshold(p, "Kanten-Übereinstimmung für Empfehlung (0–1):", AlkisSettings.ORTHO_THRESHOLD, 0, 1, 0.05);
        threshold(p, "Toleranzband um den Umriss (m):", AlkisSettings.ORTHO_TOLERANCE, 0.05, 2, 0.05);
        threshold(p, "Maximaler Versatz (m):", AlkisSettings.ORTHO_MAX_OFFSET, 0, 5, 0.25);
        threshold(p, "Maximaler Dachüberstand (m):", AlkisSettings.ORTHO_MAX_OVERHANG, 0, 2, 0.3);
        section(p, "Vergleich mit OSM");
        threshold(p, "IoU für „identisch“ (0–1):", AlkisSettings.IDENTICAL_IOU, 0.5, 1, 0.01);
        threshold(p, "Max. Abweichung für „identisch“ (m):", AlkisSettings.IDENTICAL_HAUSDORFF, 0, 5, 0.1);
        threshold(p, "Überdeckung für Zuordnung (0–1):", AlkisSettings.PARTNER_OVERLAP, 0.05, 1, 0.05);
        threshold(p, "Min. IoU für „abweichend“ (0–1):", AlkisSettings.DEVIATING_MIN_IOU, 0, 1, 0.05);
        section(p, "Übernahme und Analyse");
        threshold(p, "An Nachbargebäude anschließen bis (m):", AlkisSettings.FIT_TOLERANCE, 0, 2, 0.1);
        clipOverlaps.setSelected(AlkisSettings.isClipOverlaps());
        p.add(clipOverlaps, GBC.eol().insets(10, 2, 0, 2));
        threshold(p, "Suchradius für vorhandene Adressen (m):", AlkisSettings.ADDRESS_SEARCH_RADIUS, 0, 500, 10);
        threshold(p, "Maximale Ausschnittsfläche (km²):", AlkisSettings.MAX_AREA_KM2, 0.01, 25, 0.25);
        logEnabled.setSelected(AlkisSettings.isDecisionLogEnabled());
        p.add(logEnabled, GBC.eol().insets(0, 10, 0, 0));
        p.add(new JLabel("<html><small>Protokolldatei: " + de.alkisselector.decision.DecisionLog.getFile() + "</small></html>"),
                GBC.eol());
        p.add(buttons(button("Standardwerte", () -> thresholdSpinners.forEach((s, sp) -> sp.setValue(s.getDefault())))),
                GBC.eol().insets(0, 10, 0, 0));
        p.add(new JPanel(), GBC.eol().fill(GBC.BOTH));
        return p;
    }

    private void threshold(JPanel p, String label, AlkisSettings.Setting s, double min, double max, double step) {
        double v = Math.max(min, Math.min(max, s.get()));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(v, min, max, step));
        ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setColumns(6);
        thresholdSpinners.put(s, sp);
        row(p, label, sp);
    }

    // ------------------------------------------------------------------ Laden / Speichern

    private void load(ServiceProfile p) {
        current = p;
        name.setText(p.getName());
        description.setText(p.getDescription());
        sourceTag.setText(p.getSourceTag());
        wfsUrl.setText(p.getWfsUrl());
        wfsVersion.setSelectedItem(p.getWfsVersion());
        typeName.setText(p.getBuildingTypeName());
        crs.setText(p.getCrs());
        swapAxes.setSelected(p.isSwapAxes());
        pageSize.setValue(p.getPageSize());
        idAttribute.setText(p.getIdAttribute());
        orthoUrl.setText(p.getOrthoWmsUrl());
        orthoVersion.setSelectedItem(p.getOrthoWmsVersion());
        orthoLayers.setText(p.getOrthoLayers());
        orthoFormat.setSelectedItem(p.getOrthoFormat());
        orthoResolution.setValue(p.getOrthoResolution());
        parcelUrl.setText(p.getParcelWmsUrl());
        parcelLayers.setText(p.getParcelLayers());
        alkisMapUrl.setText(p.getAlkisMapUrl());
        alkisMapLayers.setText(p.getAlkisMapLayers());
        ruleModel.setRules(p.getAttributeRules());
        filterModel.setFilters(p.getExcludeFilters());
        lookupAttributes.setText(String.join(", ", p.getTagMapping().getLookupAttributes()));
        defaultBuilding.setText(p.getTagMapping().getDefaultValue());
        mappingModel.setEntries(p.getTagMapping().getTable());
    }

    private void store() {
        ServiceProfile p = current;
        if (p == null) {
            return;
        }
        p.setName(name.getText().strip());
        p.setDescription(description.getText().strip());
        p.setSourceTag(sourceTag.getText().strip());
        p.setWfsUrl(wfsUrl.getText().strip());
        p.setWfsVersion((String) wfsVersion.getSelectedItem());
        p.setBuildingTypeName(typeName.getText().strip());
        p.setCrs(crs.getText().strip());
        p.setSwapAxes(swapAxes.isSelected());
        p.setPageSize(((Number) pageSize.getValue()).intValue());
        p.setIdAttribute(idAttribute.getText().strip());
        p.setOrthoWmsUrl(orthoUrl.getText().strip());
        p.setOrthoWmsVersion((String) orthoVersion.getSelectedItem());
        p.setOrthoLayers(orthoLayers.getText().strip());
        p.setOrthoFormat((String) orthoFormat.getSelectedItem());
        p.setOrthoResolution(((Number) orthoResolution.getValue()).doubleValue());
        p.setParcelWmsUrl(parcelUrl.getText().strip());
        p.setParcelLayers(parcelLayers.getText().strip());
        p.setAlkisMapUrl(alkisMapUrl.getText().strip());
        p.setAlkisMapLayers(alkisMapLayers.getText().strip());
        p.getAttributeRules().clear();
        p.getAttributeRules().addAll(ruleModel.rules);
        p.getExcludeFilters().clear();
        p.getExcludeFilters().addAll(filterModel.filters);
        TagMapping tm = new TagMapping();
        for (String a : lookupAttributes.getText().split(",")) {
            if (!a.isBlank()) {
                tm.getLookupAttributes().add(a.strip());
            }
        }
        tm.setDefaultValue(defaultBuilding.getText());
        for (String[] e : mappingModel.entries) {
            if (!e[0].isBlank() && !e[1].isBlank()) {
                tm.getTable().put(e[0].strip(), e[1].strip());
            }
        }
        p.setTagMapping(tm);
        profileCombo.repaint();
    }

    @Override
    public boolean ok() {
        store();
        // Namen eindeutig machen
        List<String> names = new ArrayList<>();
        for (ServiceProfile p : working) {
            String n = p.getName().isBlank() ? "Profil" : p.getName();
            String unique = n;
            for (int i = 2; names.contains(unique); i++) {
                unique = n + " (" + i + ")";
            }
            p.setName(unique);
            names.add(unique);
        }
        ProfileStore.getInstance().setProfiles(new ArrayList<>(working), current.getName());
        thresholdSpinners.forEach((s, sp) -> s.put(((Number) sp.getValue()).doubleValue()));
        AlkisSettings.setDecisionLogEnabled(logEnabled.isSelected());
        AlkisSettings.setClipOverlaps(clipOverlaps.isSelected());
        return false;
    }

    // ------------------------------------------------------------------ Profilaktionen

    private void newProfile() {
        store();
        ServiceProfile p = new ServiceProfile();
        p.setName("Neues Profil");
        DefaultProfiles.addStandardAlkisSettings(p);
        addAndSelect(p);
    }

    private void duplicateProfile() {
        store();
        ServiceProfile p = new ServiceProfile(current);
        p.setName(current.getName() + " (Kopie)");
        addAndSelect(p);
    }

    private void deleteProfile() {
        if (working.size() <= 1) {
            JOptionPane.showMessageDialog(profileCombo, "Das letzte Profil kann nicht gelöscht werden.");
            return;
        }
        ServiceProfile p = current;
        current = null;
        working.remove(p);
        switching = true;
        profileModel.removeElement(p);
        switching = false;
        ServiceProfile next = working.get(0);
        profileCombo.setSelectedItem(next);
        load(next);
    }

    private void addAndSelect(ServiceProfile p) {
        working.add(p);
        switching = true;
        profileModel.addElement(p);
        profileCombo.setSelectedItem(p);
        switching = false;
        load(p);
    }

    private void importProfiles() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("ALKISselector-Profile (*.json)", "json"));
        if (fc.showOpenDialog(profileCombo) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            List<ServiceProfile> imported = ProfileStore.importFile(fc.getSelectedFile().toPath());
            if (imported.isEmpty()) {
                JOptionPane.showMessageDialog(profileCombo, "Die Datei enthält keine Profile.");
                return;
            }
            store();
            imported.forEach(this::addAndSelect);
        } catch (IOException e) {
            Logging.warn(e);
            JOptionPane.showMessageDialog(profileCombo, e.getMessage(), "Import fehlgeschlagen", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportProfile() {
        store();
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("ALKISselector-Profile (*.json)", "json"));
        fc.setSelectedFile(new java.io.File(current.getName().replaceAll("[^\\p{L}\\p{N}_-]+", "_") + ".json"));
        if (fc.showSaveDialog(profileCombo) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = fc.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            file = file.resolveSibling(file.getFileName() + ".json");
        }
        try {
            ProfileStore.exportFile(file, List.of(current));
        } catch (IOException e) {
            Logging.warn(e);
            JOptionPane.showMessageDialog(profileCombo, e.getMessage(), "Export fehlgeschlagen", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void checkWfs() {
        store();
        ServiceProfile p = new ServiceProfile(current);
        runCheck(() -> new WfsClient(p).checkCapabilities());
    }

    private void checkOrtho() {
        store();
        ServiceProfile p = new ServiceProfile(current);
        runCheck(() -> {
            // kleiner Testausschnitt in der Mitte eines Musterrechtecks ist nicht bekannt – daher Capabilities prüfen
            Map<String, String> q = new LinkedHashMap<>();
            q.put("SERVICE", "WMS");
            q.put("REQUEST", "GetCapabilities");
            String caps = WfsClient.fetchText(WfsClient.appendQuery(p.getOrthoWmsUrl(), q));
            List<String> missing = new ArrayList<>();
            for (String l : p.getOrthoLayers().split(",")) {
                if (!caps.contains(">" + l.strip() + "<")) {
                    missing.add(l.strip());
                }
            }
            boolean crsOk = caps.contains(de.alkisselector.source.CrsTransformer.normalize(p.getCrs()));
            return "WMS erreichbar. " + (missing.isEmpty() ? "Layer gefunden." : "Layer nicht gefunden: " + missing + ".")
                    + (crsOk ? "" : " Achtung: " + p.getCrs() + " wird in den Capabilities nicht genannt.")
                    + " Beispiel-URL: " + OrthoFetcher.josmWmsTemplate(p.getOrthoWmsUrl(), p.getOrthoLayers(), p.getOrthoFormat(), false);
        });
    }

    private interface Check {
        String run() throws IOException;
    }

    private void runCheck(Check check) {
        Component parent = profileCombo;
        MainApplication.worker.submit(() -> {
            String msg;
            int type = JOptionPane.INFORMATION_MESSAGE;
            try {
                msg = check.run();
            } catch (IOException | RuntimeException e) {
                msg = "Fehler: " + e.getMessage();
                type = JOptionPane.ERROR_MESSAGE;
            }
            String m = msg;
            int t = type;
            javax.swing.SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(parent, "<html><body style='width:400px'>" + m + "</body></html>", "Dienst prüfen", t));
        });
    }

    // ------------------------------------------------------------------ Layout-Helfer

    private static void section(JPanel p, String title) {
        JLabel l = new JLabel("<html><b>" + title + "</b></html>");
        p.add(l, GBC.eol().insets(0, 10, 0, 3));
    }

    private static void row(JPanel p, String label, JComponent field) {
        p.add(new JLabel(label), GBC.std().insets(10, 0, 5, 2));
        if (field instanceof JTextField || field instanceof JComboBox) {
            p.add(field, GBC.eol().fill(GBC.HORIZONTAL).insets(0, 0, 0, 2));
        } else {
            // Zahlenfelder, Checkboxen und Buttons in natürlicher Größe, linksbündig
            p.add(field, GBC.eol().anchor(GBC.WEST).insets(0, 0, 0, 2));
        }
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JPanel buttons(JButton... bs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (JButton b : bs) {
            p.add(b);
        }
        return p;
    }

    private static JScrollPane scroll(JTable t, int height) {
        t.setFillsViewportHeight(true);
        t.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane s = new JScrollPane(t);
        s.setPreferredSize(new java.awt.Dimension(500, height));
        return s;
    }

    // ------------------------------------------------------------------ Tabellenmodelle

    /** Attributregeln. */
    private static final class RuleTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Aktiv", "ALKIS-Attribut", "OSM-Key", "Typ", "Wertetabelle"};
        final List<AttributeRule> rules = new ArrayList<>();

        void setRules(List<AttributeRule> r) {
            rules.clear();
            r.forEach(x -> rules.add(new AttributeRule(x)));
            fireTableDataChanged();
        }

        void add(AttributeRule r) {
            rules.add(r);
            fireTableDataChanged();
        }

        void remove(int row) {
            if (row >= 0 && row < rules.size()) {
                rules.remove(row);
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : c == 3 ? AttributeRule.Type.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            AttributeRule x = rules.get(r);
            switch (c) {
            case 0: return x.isEnabled();
            case 1: return x.getAlkisAttribute();
            case 2: return x.getOsmKey();
            case 3: return x.getType();
            default:
                StringBuilder sb = new StringBuilder();
                x.getValueTable().forEach((k, v) -> sb.append(sb.length() > 0 ? "; " : "").append(k).append('=').append(v));
                return sb.toString();
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            AttributeRule x = rules.get(r);
            switch (c) {
            case 0: x.setEnabled(Boolean.TRUE.equals(v)); break;
            case 1: x.setAlkisAttribute(String.valueOf(v).strip()); break;
            case 2: x.setOsmKey(String.valueOf(v).strip()); break;
            case 3: if (v instanceof AttributeRule.Type) {
                    x.setType((AttributeRule.Type) v);
                }
                break;
            default:
                x.getValueTable().clear();
                for (String pair : String.valueOf(v).split(";")) {
                    int i = pair.indexOf('=');
                    if (i > 0) {
                        x.getValueTable().put(pair.substring(0, i).strip(), pair.substring(i + 1).strip());
                    }
                }
            }
            fireTableRowsUpdated(r, r);
        }
    }

    /** Ausschlussfilter. */
    private static final class FilterTableModel extends AbstractTableModel {
        private static final String[] COLS = {"ALKIS-Attribut", "Regulärer Ausdruck (Treffer = ausschließen)"};
        final List<ExcludeFilter> filters = new ArrayList<>();

        void setFilters(List<ExcludeFilter> f) {
            filters.clear();
            f.forEach(x -> filters.add(new ExcludeFilter(x.getAttribute(), x.getRegex())));
            fireTableDataChanged();
        }

        void add(ExcludeFilter f) {
            filters.add(f);
            fireTableDataChanged();
        }

        void remove(int row) {
            if (row >= 0 && row < filters.size()) {
                filters.remove(row);
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return filters.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return c == 0 ? filters.get(r).getAttribute() : filters.get(r).getRegex();
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0) {
                filters.get(r).setAttribute(String.valueOf(v).strip());
            } else {
                filters.get(r).setRegex(String.valueOf(v));
            }
            fireTableRowsUpdated(r, r);
        }
    }

    /** Gebäudetabelle. */
    private static final class MappingTableModel extends AbstractTableModel {
        private static final String[] COLS = {"ALKIS-Wert", "building="};
        final List<String[]> entries = new ArrayList<>();

        void setEntries(Map<String, String> m) {
            entries.clear();
            m.forEach((k, v) -> entries.add(new String[] {k, v}));
            fireTableDataChanged();
        }

        void add(String k, String v) {
            entries.add(new String[] {k, v});
            fireTableDataChanged();
        }

        void remove(int row) {
            if (row >= 0 && row < entries.size()) {
                entries.remove(row);
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return entries.get(r)[c];
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            entries.get(r)[c] = String.valueOf(v);
            fireTableRowsUpdated(r, r);
        }
    }
}
