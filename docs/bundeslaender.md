# ALKIS-Gebäude in OSM: Rechtslage je Bundesland

Stand: 21.09.2026. Maßgeblich ist, ob die **ALKIS-Gebäude** (Vektordaten aus dem WFS) in OSM
übernommen werden dürfen. Die Lizenz muss mit der ODbL vereinbar sein, oder die Behörde hat OSM eine
ausdrückliche Erlaubnis erteilt. Grundlage sind die Einträge auf
[Contributors#Germany](https://wiki.openstreetmap.org/wiki/Contributors#Germany) und
[DE:Permissions](https://wiki.openstreetmap.org/wiki/DE:Permissions). Freigaben, die nur das
Luftbild betreffen, reichen nicht aus.

Das ist keine Rechtsberatung. Vor einer größeren Übernahme den aktuellen Stand im Wiki prüfen.

Mitgeliefert werden nur Profile für Länder, in denen die Nutzung **eindeutig** erlaubt ist. Nutzt ein
Profil einen anderen Gebäude-Dienst (eigenes, importiertes oder geändertes Profil), zeigt das Plugin in
den Einstellungen und vor der ersten Analyse den Hinweis, dass die Nutzung unter Umständen nicht
erlaubt ist.

| Land | Nutzbar | Lizenz / Grundlage | Profil |
|---|---|---|---|
| Baden-Württemberg | **nein** | Freigabe des LGL (06/2025) nur für das Luftbild DOP20, nicht für ALKIS | – |
| Bayern | **nein** | CC BY 4.0 ohne Lizenz-Addendum; BayernAtlas ausdrücklich unzulässig | – |
| Berlin | ja | ALKIS Gebäude, TrueDOP, ALKIS-Karte: dl-de/zero-2.0 (laut Capabilities); zusätzlich Zusatzvereinbarung vom 03.06.2019 | Berlin (Geoportal Berlin) |
| Brandenburg | ja | dl-de/by-2.0, Quellenvermerk an anderer Stelle laut AGNB der LGB (04/2020) | Brandenburg (LGB) |
| Bremen | **nein** | Anfrage beim Landesamt GeoInformation seit 02/2023 unbeantwortet | – |
| Hamburg | ja | dl-de/by-2.0, LGV: Quellenangabe „HH LGV ALKIS &lt;Jahr&gt;“ im Changeset reicht ([2021](https://wiki.openstreetmap.org/wiki/DE:Permissions/LGV_Hamburg_2021)) | Hamburg (LGV) |
| Hessen | ja | dl-de/zero-2.0 (seit 02/2022, § 24 HVGG) | Hessen (HVBG) |
| Mecklenburg-Vorpommern | ja | CC BY 4.0 mit Erlaubnis für OSM inkl. Verzicht auf die DRM-Klausel ([LAiV-FAQ](https://www.laiv-mv.de/Geoinformation/FAQ/), 26.09.2025) | Mecklenburg-Vorpommern (LAiV) |
| Niedersachsen | **unklar** | CC BY 4.0, LGLN: Nennung auf der Contributors-Seite geeignet ([05.09.2025](https://wiki.openstreetmap.org/wiki/DE:Niedersachsen/Geoportal)); Verzicht auf die DRM-Klausel fehlt | – |
| Nordrhein-Westfalen | ja | dl-de/zero-2.0 (seit 01.03.2020) | NRW (Geobasis NRW) |
| Rheinland-Pfalz | ja | dl-de/by-2.0 mit Erlaubnis der VermKV ([25.09.2025](https://wiki.openstreetmap.org/wiki/DE:Permissions/2025-lvermggeo-rlp)) | Rheinland-Pfalz (LVermGeo) |
| Saarland | **nein** | Freigabe (08/2026) nur für die Schummerung des DGM | – |
| Sachsen | ja | dl-de/by-2.0 mit Erlaubnis des GeoSN für alle DL-DE-Daten ([06.03.2026](https://wiki.openstreetmap.org/wiki/GeoSN_Open_Data)) | Sachsen (GeoSN) |
| Sachsen-Anhalt | **unklar** | dl-de/by-2.0, [Erlaubnis](https://wiki.openstreetmap.org/wiki/DE:Permissions/Geobasisdaten_Sachsen-Anhalt) vom 09.07.2021 nennt nur WMS zum Abzeichnen; keine automatischen Importe | – |
| Schleswig-Holstein | **nein** | keine Freigabe des LVermGeo SH bekannt | – |
| Thüringen | **nein** | Freigabe des TLBG (09/2024) nur für das Luftbild | – |

## Hinweise zu einzelnen Ländern

- **Berlin:** Der Gebäude-WFS hat ein eigenes Schema (`alkis_gebaeude:gebaeude`). Das Profil übersetzt
  `bezgfk` (Gebäudefunktion), `aog` (Geschosse), `namlag` (Lagebezeichnung) und `nam` (Name) und
  schließt `AX_Bauteil` aus. Die ALKIS-Karte erscheint erst ab etwa 1:2500. Flurstücke als eigene Ebene
  bietet der Dienst nicht an.
  **Zertifikat:** `gdi.berlin.de` nutzt die Wurzel *Telekom Security TLS RSA Root 2023*. Java (auch
  JDK 25) kennt sie noch nicht, JOSM meldet dann `PKIX path building failed`. Abhilfe unter Windows:
  JOSM mit `-Djavax.net.ssl.trustStoreType=Windows-ROOT` starten (Windows-Zertifikatsspeicher), sonst
  das Wurzelzertifikat mit `keytool -importcert` in den Java-Truststore aufnehmen.
- **Brandenburg:** Im Kreis der Berliner/Brandenburger Aktiven gilt der Konsens, derzeit keine
  großflächigen Übernahmen von Gebäudeumringen anzustreben
  ([Brandenburg/Geoportal](https://wiki.openstreetmap.org/wiki/Brandenburg/Geoportal#Importe)).
- **Hessen:** Automatische Importe werden in der hessischen Community voraussichtlich nicht akzeptiert
  ([Hessen/Geodaten online](https://wiki.openstreetmap.org/wiki/Hessen/Geodaten_online)). Der
  WFS mit Gebäuden ist `www.gds.hessen.de/wfs2/…/alkis/vereinf/wfs`, nicht der `ogc-free-data`-Dienst.
- **Niedersachsen (kein Profil):** CC BY 4.0 verlangt für OSM außerdem den Verzicht auf die Klausel zu
  technischen Schutzmaßnahmen. Das LGLN hat diesen Verzicht nicht ausdrücklich erklärt (anders als M-V).
  Die deutsche Community (FOSSGIS) behandelt die Daten nach der Anzeige vom 10.09.2025 zwar als
  nutzbar, eindeutig ist die Rechtslage für die Gebäude-Vektordaten aber nicht.
- **Sachsen-Anhalt (kein Profil):** Die Erlaubnis nennt ausdrücklich nur die WMS-Dienste zum
  Abzeichnen, das Wiki schließt automatische Importe aus. Die Übernahme von WFS-Gebäuden ist davon
  nicht eindeutig gedeckt.
- **Quellenangaben** mit Jahr (Brandenburg, Hamburg, Rheinland-Pfalz) werden beim
  Anlegen des Profils mit dem aktuellen Jahr gefüllt und im Profil gespeichert. In einem neuen Jahr
  unter *Einstellungen → Dienstprofile* anpassen.

In allen Ländern gilt unabhängig von der Lizenz: Eine Übernahme in größerem Umfang ist ein Import im
Sinne der [Import Guidelines](https://wiki.openstreetmap.org/wiki/Import/Guidelines).

## Profile nachträglich hinzufügen

Die Standardprofile werden nur angelegt, wenn noch keine Profile gespeichert sind. Wer das Plugin
schon benutzt hat, holt die neuen Länder mit *Einstellungen → Dienstprofile → Standardprofile
ergänzen* nach. Vorhandene Profile mit gleichem Namen bleiben dabei unverändert.
