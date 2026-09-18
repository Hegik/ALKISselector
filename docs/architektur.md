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
| `decision` | `CandidateAnalyzer` (Pipeline), `Candidate`, `Recommendation`, `NeighbourFitter`/`NeighbourWays` (Anpassung an Nachbargebäude), `ApplyAction` (Neuanlage/Ersetzen), `DecisionLog` (CSV) |
| `gui` | `ReviewDialog` (Seitenfenster), `AlkisPreviewLayer` (farbige Umrisse), `AlkisSelectMapMode` (Einzelklick), `AlkisPreferenceSetting`, `AnalysisTask` (Hintergrund), `AlkisController` |

Abhängigkeiten: JOSM ≥ 19613 und das Plugin `jts`. JSON über
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

## Anpassung an Nachbargebäude (NeighbourFitter)

Neue Gebäude grenzen oft an vorhandene OSM-Gebäude (Garage oder Überdachung am Haus,
Reihenhäuser). Weicht das OSM-Nachbargebäude leicht von ALKIS ab, würde die unveränderte
ALKIS-Geometrie es überlappen oder einen schmalen Spalt lassen. Vor dem Anlegen passiert daher
Folgendes:

1. **Abschneiden:** Der Teil, in den ein vorhandenes OSM-Gebäude hineinragt, wird abgezogen. Das
   geschieht nur, wenn höchstens 30 % der Fläche wegfallen und die Fläche zusammenhängend bleibt.
   Sonst wird nichts abgeschnitten, und der Kandidat bekommt eine Konfliktmeldung. Abschalten lässt
   sich das unter *Einstellungen → Schwellenwerte*.
2. **Gemeinsame Punkte** (Toleranz 0,5 m, einstellbar): Eckpunkte nahe einem Nachbarknoten verwenden
   diesen Knoten. Eckpunkte nahe einer Nachbarkante werden als neuer Knoten **in diese Kante
   eingefügt**. Das schließt kleine Spalten und verbindet die Gebäude topologisch.
3. **Gemeinsame Kante:** Verlaufen zwei aufeinanderfolgende Punkte entlang desselben Nachbargebäudes,
   werden dessen Zwischenknoten übernommen. Die Grenze ist dann identisch.
4. **T-Stoß:** Knoten eines Nachbargebäudes, die höchstens 0,5 m neben einer neuen Kante liegen
   (z. B. ein Nachbar endet an der Hauswand), werden in diese Kante eingebaut. Die Kante verläuft
   dann durch den Nachbarknoten, ohne Spickel und ohne Spalt. Alle Knoten einer Kante werden
   gemeinsam geprüft. Würde die Kante dadurch in ein Nachbargebäude hineingezogen, werden nur die
   unkritischen Knoten eingebaut.
5. **Kontrolle:** Bleibt eine Überlappung ab 0,1 m², lautet die Empfehlung *Diskrepanz*. Übernommen
   wird dann nur mit Umschalt+Enter.

Die Anpassung wird bei der Analyse für die Vorschau berechnet. Die kräftige Linie zeigt die
angepasste Form, die dünne gestrichelte Linie den ursprünglichen ALKIS-Umriss. Bei der Übernahme
wird mit dem aktuellen Datenstand erneut gerechnet, sodass auch eben übernommene Nachbarn
berücksichtigt werden. Die Nachbargebäude selbst werden nur um die eingefügten Knoten ergänzt,
nie verschoben.

**Beim Ersetzen abweichender Gebäude** gilt dasselbe, gerechnet gegen alle Nachbarn außer dem zu
ersetzenden Gebäude selbst. Zusätzlich bleiben **Verbindungen erhalten**: Knoten des alten Gebäudes,
die zu anderen Wegen gehören (Nachbargebäude, Fußwege, Zufahrten, Mauern), Mitglied einer Relation
sind oder Tags tragen (z. B. `entrance=*`), werden nicht verschoben. Liegen sie höchstens 0,5 m vom
neuen Umriss entfernt, werden sie an ihrer Position in den Umriss eingebaut. Sonst lautet die
Empfehlung *Diskrepanz*, mit Hinweis auf die Verbindung, die verloren ginge.

Geprüft mit echten Daten im Münsteraner Kreuzviertel (`RealOsmNeighbourOnlineTest`):

- 17 neue Gebäude grenzten an OSM-Gebäude, 8 davon wurden abgeschnitten. Nach der Übernahme
  überlappte keines ein OSM-Gebäude.
- 29 abweichende Gebäude wurden ersetzt, ohne Überlappung und ohne verlorene Verbindung. 2 wurden
  als Konflikt zurückgehalten, weil eine Zufahrt bzw. ein Nebengebäude 0,5–0,6 m neben dem
  ALKIS-Umriss angebunden war.

## Übernahme (ApplyAction)

- **Neu:** Die Knoten stammen aus der Nachbaranpassung: vorhandene Knoten wiederverwenden, Knoten in
  Nachbarkanten einfügen, sonst neu anlegen. Daraus entsteht ein geschlossener Weg oder bei
  Innenhöfen ein Multipolygon. Die Tags sind die ausgewählten Vorschläge.
- **Abweichend:** Die Knotenliste des bestehenden Wegs wird durch die angepasste ALKIS-Geometrie
  ersetzt (`ChangeNodesCommand`). ID, Historie, Tags und Relationen bleiben erhalten. Alte Knoten, die
  nur zu diesem Gebäude gehören, werden an die neuen Positionen verschoben und wiederverwendet
  (Knotenhistorie bleibt). Überzählige werden gelöscht, verbundene Knoten bleiben unangetastet.
  Tags werden nur ergänzt, abweichende Werte bleiben unangetastet, außer der Nutzer wählt sie
  ausdrücklich an.
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
| `NeighbourFitterTest`, `ApplyNeighbourTest` | Überdachung am Haus: Abschneiden, Spalt schließen, Zwischenknoten, T-Stoß, Konflikt, gemeinsame Knoten im JOSM-Datensatz, ein Undo-Schritt |
| `ApplyReplaceNeighbourTest` | Reihenhaus ersetzen: gemeinsame Wand bleibt, Eingang mit Fußweg bleibt verbunden, keine Überlappung, ein Undo-Schritt |
| `RealOsmNeighbourOnlineTest` (`-Donline=true`) | echte OSM-Daten gegen ALKIS: Neuanlagen und Ersetzungen ohne Überlappung, ohne verlorene Verbindungen und ohne unverbundene Nachbarknoten neben den neuen Kanten |
| `OnlinePipelineTest` (`-Donline=true`) | Ende-zu-Ende mit NRW-WFS/DOP inkl. Neuanlage, Ersetzen und Undo |
| `CalibrationOnlineTest` (`-Donline=true`) | Trefferquote/Fehlalarmrate je Schwelle, siehe `evaluierung.md` |
