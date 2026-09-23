# ALKISselector – JOSM-Plugin

Halbautomatische Übernahme von **ALKIS-Gebäuden** in OpenStreetMap. Das Plugin lädt Gebäude aus einem
frei konfigurierbaren **WFS**, vergleicht sie mit den OSM-Daten, prüft sie gegen ein **Orthophoto
(WMS)** und gibt eine Empfehlung. Der Nutzer entscheidet per Tastendruck. Hochgeladen wird nichts
automatisch.

Entstanden als Prototyp im GIS-Geländepraktikum. Architektonisches Vorbild ist
[areaselector](https://github.com/JOSM/areaselector), im Gegensatz dazu ist das Plugin aber nicht an
einen Dienst oder ein Land gebunden.

## Funktionen

| Funktion | Beschreibung |
|---|---|
| **Stapel-Review** | Menü *ALKIS → Ausschnitt analysieren* (Strg+Umschalt+K): alle Gebäude im Kartenausschnitt |
| **Einzelklick** | Modus *ALKIS-Gebäude auswählen* (Strg+Alt+K): Klick auf ein Gebäude |
| **OSM-Vergleich** | Einstufung *neu / identisch / abweichend / komplex / nur OSM* (IoU, Hausdorff-Distanz) |
| **Luftbild-Check** | Kantenabgleich mit dem Orthophoto, berücksichtigt Bildversatz, Dachüberstand und Brandwände |
| **Hybride Entscheidung** | Enter = übernehmen, Umschalt+Enter = trotz Diskrepanz, Entf = verwerfen, Leertaste = überspringen |
| **Vergleich alt/neu** | Neue Geometrie kräftig, bisherige OSM-Geometrie blau gestrichelt, Verschiebungen als Pfeile, neue/gelöschte Knoten markiert. **V** schaltet die Karte zwischen „alt“ (heute) und „neu“ (nach der Übernahme) um |
| **Anpassung an Nachbarn** | Neue Gebäude schließen ohne Überlappung und ohne Spalt an vorhandene OSM-Gebäude an (gemeinsame Knoten, Überstand wird abgeschnitten) |
| **Neuanlage mit Attributen** | `building=*` aus der Gebäudefunktion, Adresse aus der Lagebezeichnung, Name, Geschosse; im Dialog editierbar |
| **Geführte Reihenfolge** | ALKIS ist maßgeblich: Weicht ein angrenzendes OSM-Gebäude ab, wird es vor dem Anbau eines neuen Objekts an ALKIS angeglichen (Empfehlung „An ALKIS angleichen“). Gebäude mit gemeinsamen Ecken werden vollständig gemeinsam angeglichen, damit keine Winkel verzerrt werden |
| **Geometrie ersetzen** | Bei abweichenden Gebäuden bleiben ID, Historie und Tags erhalten, Tags werden nur ergänzt. Verbindungen zu angrenzenden Gebäuden, Wegen und Eingängen bleiben erhalten. Am Haus endende Zäune, Mauern und Wege werden bis zur ALKIS-Fassade verlängert oder gekürzt, damit die Wand keinen Knick bekommt |
| **ALKIS-Karte** | ALKIS-WMS als Ebene zum visuellen Abgleich: 50 % Deckkraft, liegt automatisch immer über dem Luftbild |
| **Flurstücke** | Nur als Hintergrundebene, werden nie übernommen |
| **Profile** | Beliebige WFS/WMS-Dienste. Mitgeliefert für alle Länder, deren ALKIS-Gebäude in OSM verwendet werden dürfen: Berlin, Brandenburg, Hamburg, Hessen, Mecklenburg-Vorpommern, NRW, Rheinland-Pfalz, Sachsen ([Übersicht](docs/bundeslaender.md)). Bei anderen Diensten erscheint ein Hinweis, dass die Nutzung unter Umständen nicht erlaubt ist |
| **Evaluierung** | Jede Entscheidung landet als CSV-Zeile im Entscheidungsprotokoll |

Jede Übernahme ist **ein** Undo-Schritt (Strg+Z). Die Quelle wird als Changeset-Tag `source=*`
gesetzt, nicht am Objekt.

## Bauen und Starten

Voraussetzung: Internetzugang. Ein JDK 21 wird von Gradle gesucht oder automatisch geladen, eine
eigene Gradle-Installation ist nicht nötig.

```bash
./gradlew build        # Plugin bauen + Unit-Tests  → build/libs/alkisselector.jar
./gradlew runJosm      # JOSM mit Plugin in eigenem Testprofil (build/josm-home) starten
./gradlew runJosm -Pjosm.args="--language=de --download=51.9612,7.6075,51.9635,7.6120"
./gradlew test -Donline=true   # zusätzlich Online-Tests gegen die Dienste aller Profile inkl. Kalibrierung
```

In **PowerShell** Argumente mit Punkt in einfache Anführungszeichen setzen, sonst trennt PowerShell
am Punkt:

```powershell
./gradlew runJosm '-Pjosm.args=--language=de --download=51.9612,7.6075,51.9635,7.6120'
./gradlew test '-Donline=true'
```

Der Online-Test für **Berlin** scheitert mit dem Standard-Truststore von Java mit `PKIX path building
failed`, weil Java das Wurzelzertifikat von `gdi.berlin.de` nicht kennt (siehe
[bundeslaender.md](docs/bundeslaender.md)). Unter Windows den Zertifikatsspeicher des Systems nutzen:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'; ./gradlew test '-Donline=true'
```

Installation in ein normales JOSM: `alkisselector.jar` in den JOSM-Plugin-Ordner kopieren und in den
Einstellungen unter *Erweiterungen* aktivieren. Das Plugin `jts` wird benötigt und von JOSM
automatisch angeboten.

## Arbeitsablauf

1. OSM-Daten für das Gebiet herunterladen.
2. *ALKIS → Ausschnitt analysieren*. Das Plugin lädt ALKIS, vergleicht und prüft das Luftbild.
3. Im Seitenfenster **ALKIS-Abgleich** Eintrag für Eintrag entscheiden. Die Karte zoomt jeweils
   formatfüllend auf das Gebäude. Tags lassen sich vor der Übernahme in der Tabelle ändern oder abwählen.
4. Mit dem JOSM-Validator prüfen und wie gewohnt manuell hochladen.

> **Wichtig:** Werden ALKIS-Daten in größerem Umfang übernommen, gilt das als Import im Sinne der
> [OSM Import Guidelines](https://wiki.openstreetmap.org/wiki/Import/Guidelines). Das bedeutet:
> Dokumentation im Wiki, Abstimmung mit der deutschen Community (Forum) und ein eigener Import-Account.
> Die Lizenz der jeweiligen ALKIS-Daten muss OSM-kompatibel sein. Welche Länder das erfüllen, steht in
> [docs/bundeslaender.md](docs/bundeslaender.md). Baden-Württemberg, Bayern, Bremen, Saarland,
> Schleswig-Holstein und Thüringen haben derzeit keine Freigabe für ALKIS, in Niedersachsen und
> Sachsen-Anhalt ist sie nicht eindeutig.

## Dokumentation

- [docs/architektur.md](docs/architektur.md): Aufbau des Plugins und Algorithmen
- [docs/bundeslaender.md](docs/bundeslaender.md): Rechtslage je Bundesland und mitgelieferte Profile
- [docs/eigene-dienste-einbinden.md](docs/eigene-dienste-einbinden.md): eigene WFS/WMS konfigurieren
- [docs/evaluierung.md](docs/evaluierung.md): Kalibrierung des Luftbild-Schwellenwerts, Vorgehen für die Evaluierung

## Lizenz

GPL v2 oder später (wie JOSM), siehe [LICENSE](LICENSE).
