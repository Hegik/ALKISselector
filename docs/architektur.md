# Architektur

## Überblick

```
             ┌──────────────┐    ┌─────────────────┐    ┌───────────────┐
 WFS ──────▶ │ source/      │──▶ │ compare/        │──▶ │ decision/     │ ──▶ gui/ReviewDialog
 (ALKIS)     │ WfsClient    │    │ OsmMatcher      │    │ Candidate-    │      (Nutzer entscheidet)
             │ GmlParser    │    │ GeometryComp.   │    │ Analyzer      │            │
             └──────────────┘    └─────────────────┘    └───────┬───────┘            ▼
 JOSM-DataSet ───── OsmSnapshot (Lesesperre) ──────────────────▶│              ApplyAction
 WMS ────────────── ortho/ OrthoFetcher → EdgeMap → EdgeSupportScorer          (Undo-fähig)
 (Orthophoto)
```

| Paket | Inhalt |
|---|---|
| `config` | `ServiceProfile` (Dienste und Übersetzung, JSON), `ProfileStore` (JOSM-Einstellungen, Import/Export), `TagMapping` (Funktion → building), `AttributeRule`/`AttributeMapping` (Attribut → Tag), `AddressParser`, `ExcludeFilter`, `AlkisSettings` (Schwellenwerte) |
| `source` | `WfsClient` (GetFeature mit Paging, WFS 2.0/1.1), `GmlBuildingParser` (StAX, schemaunabhängig, GML 2/3, 2D/3D, Innenringe), `CrsTransformer` (JOSM-Projektionen) |
| `compare` | `OsmMatcher` (OSM-Gebäude sammeln, Zuordnung, „nur OSM“), `GeometryComparator` (IoU, Überdeckung, Hausdorff; JTS) |
| `ortho` | `OrthoFetcher` (WMS-GetMap im metrischen CRS), `EdgeMap` (Sobel), `EdgeSupportScorer` (Kanten-Score) |
| `decision` | `CandidateAnalyzer` (Pipeline), `Candidate`, `Recommendation`, `ApplyAction` (Neuanlage/Ersetzen), `DecisionLog` (CSV) |
| `gui` | `ReviewDialog` (Seitenfenster), `AlkisPreviewLayer` (farbige Umrisse), `AlkisSelectMapMode` (Einzelklick), `AlkisPreferenceSetting`, `AnalysisTask` (Hintergrund), `AlkisController` |

Abhängigkeiten: JOSM ≥ 19613, Plugins `utilsplugin2` (Replace Geometry) und `jts`. JSON über
das in JOSM enthaltene `jakarta.json`. Weitere Bibliotheken werden nicht benötigt.

## Pipeline je Analyse

1. **Laden:** Der Kartenausschnitt wird ins Dienst-CRS transformiert (z. B. EPSG:25832) und per
   WFS-GetFeature seitenweise geladen (`COUNT`/`STARTINDEX`). Die Obergrenze liegt bei 20 000 Objekten
   und 1 km² (einstellbar).
2. **Attribute:** Ausschlussfilter (Bauteile, unterirdische Objekte), dann Gebäudetabelle und
   Attributregeln. Das Ergebnis sind Tag-Vorschläge und Hinweise.
3. **OSM-Vergleich:** Alle Geometrien werden im metrischen Arbeits-CRS verglichen (JTS).
   - *Partner* = OSM-Gebäude, dessen Überdeckung mit der kleineren Fläche ≥ 0,3 ist
   - kein Partner → **neu**
   - 1 Partner, IoU ≥ 0,95 und Hausdorff ≤ 0,5 m → **identisch**
   - 1 Partner (einfacher Weg, nur diesem ALKIS-Gebäude zugeordnet), IoU ≥ 0,3 → **abweichend**
   - mehrere Partner, Multipolygon, n:1-Zuordnung oder geringe IoU → **komplex**
   - OSM-Gebäude im Bereich ohne ALKIS-Gegenstück → **nur OSM** (Hinweis)
4. **Luftbild** (nur neu/abweichend): Pro Gebäude wird ein Ausschnitt mit 10 cm/px geladen, bis zu
   4 Abrufe laufen parallel. Siehe unten.
5. **Empfehlung:** Score ≥ Schwelle (Standard 0,70) → *Übernahme empfohlen*, sonst *Diskrepanz*.
   Ohne Luftbild → *ungeprüft*. Bei „abweichend“ zusätzlich: Passt die OSM-Geometrie besser zum
   Luftbild, lautet die Empfehlung *Diskrepanz*.

## Luftbildabgleich (EdgeSupportScorer)

Ein Luftbild enthält selbst keine Gebäudegeometrie. Bewertet wird deshalb, ob entlang des Umrisses
**Kanten im Bild** liegen:

1. Graustufen, 3×3-Glättung, Sobel-Gradient. Kante heißt: Gradientenbetrag ≥ max(40, 80. Perzentil).
2. Der Umriss wird alle 0,25 m abgetastet. Ein Abtastpunkt gilt als *gestützt*, wenn im Band
   ±0,3 m quer zum Umriss ein Kantenpixel liegt, dessen Gradient höchstens 30° von der
   Umriss-Normalen abweicht (also eine *parallele* Kante).
3. **Score** = Anteil gestützter Punkte.

Drei physikalisch begründete Korrekturen:

| Effekt | Lösung |
|---|---|
| Normale Orthophotos bilden Dächer je nach Höhe versetzt ab (Lagefehler) | Versatzsuche ±1,5 m (grob 0,3 m, fein 1 px). Der beste Versatz wird angezeigt |
| ALKIS erfasst Außenwände, das Luftbild zeigt die Dachkante | Umriss in Stufen von 0,3 m bis 0,9 m nach außen puffern (eckige Ecken) |
| Brandwände bei Reihen- und Blockbebauung sind von oben unsichtbar | Umrissabschnitte an angrenzenden ALKIS-Objekten (auch Bauteile) werden maskiert |

Bei „abweichend“ wird die OSM-Geometrie **beim selben Versatz und Dachüberstand** bewertet wie
ALKIS. Sonst würde die Versatzsuche eine Verschiebung der OSM-Geometrie kaschieren (im Test:
OSM 1,5 m versetzt → 0,41 statt 0,68).

## Übernahme (ApplyAction)

- **Neu:** Knoten werden erzeugt. Vorhandene Gebäudeknoten im Abstand ≤ 5 cm werden
  wiederverwendet, damit aneinandergrenzende Gebäude gemeinsame Knoten haben. Daraus entsteht ein
  geschlossener Weg oder bei Innenhöfen ein Multipolygon. Die Tags sind die ausgewählten Vorschläge.
- **Abweichend:** Die ALKIS-Geometrie wird als temporärer Weg angelegt, dann ersetzt
  `ReplaceGeometryUtils.buildReplaceWayCommand` die Geometrie des bestehenden Wegs. ID, Historie,
  Tags und Relationen bleiben erhalten. Tags werden nur ergänzt, abweichende Werte bleiben
  unangetastet, außer der Nutzer wählt sie ausdrücklich an.
- Alle Teilschritte bilden **einen** `SequenceCommand`, also einen Undo-Schritt.
- Die Quelle wird als Changeset-Tag `source` gesetzt.
- Voraussetzung: Die analysierte Datenebene ist die aktive Ebene.

## Tests

| Test | Inhalt |
|---|---|
| `GmlBuildingParserTest` | echte NRW-Antwort, Innenringe, MultiSurface, 3D, GML 2, Achsentausch, Exception-Report |
| `AddressParserTest` | u. a. „46 a“, „14, 16“, „1-3“, „Straße des 17. Juni 5“, mehrere Straßen |
| `AttributeMappingTest` | Tabelle, Ausschluss, Regeln, Konflikte, JSON-Roundtrip, kompaktes Profil |
| `OsmMatcherTest` | alle Klassen inkl. n:1 und Multipolygon |
| `EdgeSupportScorerTest` | synthetische Bilder: passend, versetzt, falsch, leer |
| `CrsTransformerTest` | UTM ↔ WGS84, URL-Aufbau |
| `OnlinePipelineTest` (`-Donline=true`) | Ende-zu-Ende mit NRW-WFS/DOP inkl. Neuanlage, Ersetzen und Undo |
| `CalibrationOnlineTest` (`-Donline=true`) | Trefferquote/Fehlalarmrate je Schwelle, siehe `evaluierung.md` |
