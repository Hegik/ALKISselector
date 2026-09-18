# Evaluierung

## 1. Kalibrierung des Luftbild-Schwellenwerts (Stand 18.09.2026)

**Ziel:** Ein Default für die Schwelle, ab der das Plugin die Übernahme empfiehlt.

**Aufbau** (`CalibrationOnlineTest`, `./gradlew test -Donline=true --tests "*Calibration*"`):

- Zwei Gebiete à 300 × 300 m in Münster: **Innenstadt** (Block- und Reihenbebauung) und
  **Gievenbeck** (Einfamilienhäuser). Daten: ALKIS vereinfacht und DOP 10 cm von Geobasis NRW.
- **Positive Fälle:** ALKIS-Umrisse aller Gebäude ab 15 m², ohne Bauteile. Sie existieren real.
- **Negative Fälle:** dieselben Umrisse **um 5 m verschoben** (außerhalb der Versatzsuche) und
  **um 40 % vergrößert**. Dazu gibt es im Luftbild kein passendes Gebäude. Das ist eine
  Näherung für abgerissene, veränderte oder falsch erfasste Gebäude.
- Insgesamt 1169 Bewertungen (rund 390 Gebäude × 3).

**Ergebnis** mit Versatzsuche ±1,5 m, Dachüberstand bis 0,9 m und Brandwand-Maske. TPR =
Trefferquote (echte Gebäude bestätigt), FPR = Fehlalarmrate (falsche Umrisse bestätigt):

| Schwelle | Innenstadt TPR / FPR | Gievenbeck TPR / FPR | gesamt TPR / FPR |
|---|---|---|---|
| 0,50 | 96,2 % / 47,1 % | 92,7 % / 57,6 % | 93,9 % / 54,1 % |
| 0,55 | 93,1 % / 35,9 % | 87,7 % / 40,5 % | 89,5 % / 38,9 % |
| 0,60 | 88,5 % / 23,9 % | 81,6 % / 26,2 % | 83,9 % / 25,4 % |
| 0,65 | 77,7 % / 16,2 % | 74,7 % / 18,3 % | 75,7 % / 17,6 % |
| **0,70** | **70,0 % / 11,2 %** | **61,3 % / 13,3 %** | **64,2 % / 12,6 %** |
| 0,75 | 62,3 % / 6,9 % | 49,4 % / 9,8 % | 53,7 % / 8,9 % |
| 0,80 | 52,3 % / 4,6 % | 43,3 % / 7,3 % | 46,3 % / 6,4 % |
| 0,85 | 43,8 % / 3,9 % | 35,2 % / 5,8 % | 38,1 % / 5,1 % |
| 0,90 | 32,3 % / 2,3 % | 23,8 % / 4,2 % | 26,6 % / 3,6 % |

**Entwicklung des Verfahrens** (gesamt, Schwelle 0,80 / 0,65):

| Variante | TPR / FPR bei 0,80 | TPR / FPR bei 0,65 |
|---|---|---|
| reine Versatzsuche | 37,1 % / 5,2 % | 67,0 % / 14,7 % |
| + Brandwand-Maske und Dachüberstand | 46,3 % / 6,4 % | 75,7 % / 17,6 % |

**Schlussfolgerungen**

- Der ursprünglich angenommene Wert von 0,80 wäre sehr zurückhaltend: Nur knapp die Hälfte der
  realen Gebäude würde empfohlen.
- Das beste Youden-J (TPR − FPR ≈ 0,58) liegt bei 0,60–0,65.
- **Gewählter Default: 0,70.** Im hybriden Modell ist eine fälschliche Empfehlung teurer als eine
  fehlende, denn für nicht empfohlene Gebäude reicht Umschalt+Enter. Deshalb liegt der Default
  etwas oberhalb des Youden-Optimums. Einstellbar unter *Einstellungen → Schwellenwerte*.
- Ein reiner Kantenabgleich trennt **mäßig gut**. Hauptursachen für Fehlbewertungen sind Bäume
  über Gebäuden, Schatten, flache Nebengebäude mit geringem Kontrast und niedrige Dächer. Mögliche
  Verbesserungen: Farb- und Helligkeitssegmentierung (Flächen statt Kanten), nDOM oder
  Gebäudehöhen, Kombination mit einer Segmentierung (optional OpenCV bzw. ein ML-Modell, wie in
  der Projektskizze als Erweiterung genannt).
- Einschränkung: Die Negativfälle sind synthetisch. Echte Abrisse (leeres Grundstück) dürften
  leichter zu erkennen sein als ein verschobener Umriss, der auf Nachbargebäude trifft. Die
  Fehlalarmrate ist daher eher konservativ geschätzt.

## 2. Vorgehen für die Vorher-/Nachher-Evaluierung in einer Gemeinde

1. **Gemeinde wählen** (NRW), das Gebiet in Ausschnitte ≤ 1 km² teilen.
2. **Vorher:** OSM-Daten laden und je Ausschnitt analysieren. Die Kopfzeile des Review-Dialogs
   und die Layer-Info liefern die Klassenverteilung (neu / identisch / abweichend / komplex /
   nur OSM). Das dokumentiert die OSM-Vollständigkeit gegenüber ALKIS.
3. **Bearbeiten:** Alle Einträge im Review-Dialog entscheiden. Das Plugin protokolliert
   automatisch (*ALKIS → Entscheidungsprotokoll öffnen*, Datei `entscheidungen.csv` im
   JOSM-Benutzerverzeichnis):

   `zeitpunkt; profil; alkis_id; funktion; klasse; iou; hausdorff_m; ortho_score; osm_ortho_score; versatz_m; empfehlung; entscheidung; dauer_ms; hinweise`

4. **Auswerten:**
   - **Konfusionsmatrix** Empfehlung (`UEBERNAHME_EMPFOHLEN` / `DISKREPANZ`) gegen Entscheidung
     (`UEBERNOMMEN` / `VERWORFEN`). Der Nutzer ist die Referenz, er sieht das Luftbild.
   - **Kalibrierkurve:** Übernahmequote je Score-Klasse (`ortho_score` in 0,05-Schritten).
   - **Zeitaufwand:** `dauer_ms` je Entscheidung, getrennt nach empfohlen und nicht empfohlen.
     Vergleich mit dem manuellen Abzeichnen einer Stichprobe.
   - **Nachher:** erneute Analyse nach dem Upload. Neu und abweichend sollten stark abnehmen,
     der Anteil identisch sollte steigen.
5. **Qualität:** JOSM-Validator vor dem Upload. Erwartung: keine neuen Warnungen zu
   überlappenden Gebäuden oder doppelten Knoten.

Hinweis: Der Upload größerer Mengen ist ein Import und erfordert vorab die Abstimmung nach den
OSM Import Guidelines (siehe README).
