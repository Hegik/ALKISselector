# Eigene WFS/WMS-Dienste einbinden

Das Plugin ist nicht an ein Bundesland gebunden. Ein **Dienstprofil** legt fest, woher Gebäude,
Luftbild und Flurstücke kommen und wie ALKIS-Attribute übersetzt werden. Profile werden unter
*ALKIS → Einstellungen … → Dienstprofile* bearbeitet und über das Menü *ALKIS → Aktives Profil*
gewechselt.

## Schnellstart: Profil importieren

1. *Einstellungen → ALKISselector → Dienstprofile → Importieren …*
2. Eine JSON-Datei wählen, z. B. [`beispielprofil-sachsen.json`](beispielprofil-sachsen.json).
3. Mit **WFS prüfen** und **Orthophoto-WMS prüfen** die Erreichbarkeit testen, dann *OK*.

Enthält eine Profildatei keine eigenen Abschnitte `tagMapping`, `attributeRules` und
`excludeFilters`, werden automatisch die Standardeinstellungen für das AdV-Produkt
**„ALKIS vereinfacht“** übernommen. Das Produkt bieten viele Länder in gleicher Struktur an.
Ein Minimalprofil braucht deshalb nur die Dienst-URLs.

## Profil von Hand anlegen

*Neu* legt ein Profil mit den Standardeinstellungen für „ALKIS vereinfacht“ an. Dann ausfüllen:

### ALKIS-Gebäude (WFS)

| Feld | Bedeutung | Beispiel NRW |
|---|---|---|
| URL | Basis-URL des WFS ohne Parameter (vorhandene Parameter bleiben erhalten) | `https://www.wfs.nrw.de/geobasis/wfs_nw_alkis_vereinfacht` |
| WFS-Version | `2.0.0` (mit Paging) oder `1.1.0` (ohne Paging) | `2.0.0` |
| Objektart | TypeName der Gebäude aus den Capabilities | `ave:GebaeudeBauwerk` |
| Koordinatensystem | **metrisches** CRS des Dienstes (UTM) | `EPSG:25832` (West), `EPSG:25833` (Ost) |
| Achsen vertauschen | nur nötig, wenn der Dienst Nord/Ost statt Ost/Nord liefert | aus |
| Objekte je Abfrage | Seitengröße, begrenzt oft auch serverseitig | `1000` |
| Attribut mit Objekt-ID | eindeutige Kennung (sonst `gml:id`) | `oid` |

Die GML-Antwort wird schemaunabhängig gelesen: Jedes einfache Unterelement eines Features wird zu
einem Attribut (lokaler Name → Text), Flächen werden aus `gml:Polygon`/`gml:PolygonPatch` gelesen.
Dienste mit **eigenem Schema** funktionieren daher ebenfalls, z. B. Berlin (`alkis_gebaeude:gebaeude`, siehe mitgeliefertes Profil).
Es müssen dann nur die Attributnamen in Tabelle und Regeln angepasst werden.

### Orthophoto (WMS)

| Feld | Bedeutung | NRW | Sachsen |
|---|---|---|---|
| URL | WMS-Basis-URL | `https://www.wms.nrw.de/geobasis/wms_nw_dop` | `https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest` |
| Layer | Layername(n), kommagetrennt | `nw_dop_rgb` | `sn_dop_020` |
| Bildformat | `image/jpeg` (klein) oder `image/png` | `image/jpeg` | `image/jpeg` |
| Auflösung | Bodenauflösung der Auswertung in m/Pixel, ungefähr die native DOP-Auflösung | `0.1` | `0.2` |

Der WMS muss das Koordinatensystem des Profils unterstützen. Das Bild wird darin angefordert,
damit Pixel und Meter linear zusammenhängen. Ohne Orthophoto-Dienst ist die Empfehlung immer
„ungeprüft“. Übernehmen lässt sich trotzdem, mit Umschalt+Enter.

### ALKIS-Karte (WMS, optional)

Die amtliche ALKIS-Darstellung zum visuellen Abgleich mit dem Luftbild (*ALKIS → ALKIS-Karte als
Ebene anzeigen*). Die Ebene wird mit **50 % Deckkraft** angelegt, die Deckkraft lässt sich in der
Ebenenliste ändern. Sie liegt **immer über dem Luftbild**: Wird danach ein Luftbild geladen oder
werden die Ebenen umsortiert, rückt sie automatisch wieder direkt über das oberste Luftbild.

| Feld | NRW |
|---|---|
| URL | `https://www.wms.nrw.de/geobasis/wms_nw_alkis` |
| Layer | `adv_alkis_flurstuecke,adv_alkis_gebaeude,adv_alkis_bauw_einricht` |

Der Dienst sollte transparentes PNG liefern, damit das Luftbild durchscheint.

### Flurstücke (WMS, optional)

Werden nur als Hintergrundebene angezeigt (*ALKIS → Flurstücke als Ebene anzeigen*) und **nie**
übernommen, denn Flurstücksgrenzen sind im Gelände nicht sichtbar und werden in OSM nicht erfasst.

## Attribute & Tags

- **Ausschlussfilter:** `Attribut ~ regulärer Ausdruck`. Standard: `gebnutzbez ~ ^Bauteil$` und
  `rellage ~ unter der Erdoberfläche`.
- **Gebäudetabelle:** Wert der Gebäudefunktion → `building=*`. Nachgeschlagen wird in den
  Attributen `funktion`, dann `gebnutzbez`. Der Wert `-` bedeutet „kein eigenständiges Gebäude“
  (z. B. *Durchfahrt im Gebäude*). Unbekannte Funktionen ergeben den Standardwert `yes` und einen
  Hinweis im Dialog.
- **Attributregeln:**

  | Typ | Wirkung | Standard |
  |---|---|---|
  | Gebäudeart | `building=*` über die Tabelle | `funktion` |
  | Ganzzahl | positive Zahl übernehmen | `anzahlgs → building:levels` |
  | Adresse | Lagebezeichnung → `addr:street` + `addr:housenumber` | `lagebeztxt` |
  | Direkt | Wert unverändert übernehmen | `name → name` |
  | Wertetabelle | eigene Übersetzung, z. B. `Satteldach=gabled; Flachdach=flat` | – |

Adressen: „Hittorfstraße 46 a“ → `46a`, „Am Schlossgarten 14, 16“ → `14;16`. Enthält die
Lagebezeichnung mehrere Straßen, wird keine Adresse übernommen und stattdessen ein Hinweis
angezeigt. Existiert im Umkreis von 50 m bereits ein OSM-Objekt mit derselben Adresse, wird die
Adresse abgewählt.

## Profil exportieren und teilen

*Exportieren …* schreibt das aktive Profil als formatierte JSON-Datei. So lassen sich Profile für
weitere Länder im Team oder im Repository teilen.

## Checkliste für ein neues Bundesland

- [ ] Lizenz der ALKIS- und DOP-Daten auf OSM-Kompatibilität prüfen, z. B. dl-de/zero-2.0 (ja)
      oder dl-de/by-2.0 (nur mit ausdrücklicher Erlaubnis bzw. Wiki-Eintrag). Den Stand für alle
      Länder zeigt [bundeslaender.md](bundeslaender.md).
- [ ] WFS mit Gebäuden finden (Stichwort „ALKIS vereinfacht“) und das CRS notieren.
- [ ] DOP-WMS finden und prüfen, ob das gleiche CRS unterstützt wird.
- [ ] Profil anlegen, mit *prüfen* testen und einen kleinen Ausschnitt analysieren.
- [ ] Gebäudefunktionen ohne Tabellentreffer (Hinweis im Dialog) in der Tabelle ergänzen.
