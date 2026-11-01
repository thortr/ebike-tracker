# eBike Tracker

Android-App zum Aufzeichnen von eBike-Fahrten: GPS-Strecke, Tempo, Höhe, Puls vom Bluetooth-Brustgurt und die Daten der Bosch-Schnittstelle (Live Data Interface).

## Was die App aufzeichnet

| Wert | Quelle |
| --- | --- |
| Strecke, Höhe, GPS-Tempo | GPS des Handys, Höhe über das Barometer, wenn vorhanden |
| Puls | Bluetooth-Pulsgurt (Standard-Herzfrequenzprofil) |
| Deine Tretleistung, Trittfrequenz | Bosch eBike |
| Tempo und Distanz vom Radsensor | Bosch eBike (genauer als GPS) |
| Akkustand, Kilometerstand | Bosch eBike |

Motorleistung und Unterstützungsstufe stellt Bosch über die Schnittstelle nicht bereit.

## APK bekommen

Bei jedem Hochladen baut GitHub die App automatisch (Reiter **Actions**, dauert etwa 4 Minuten).
Danach liegt die Datei `eBike-Tracker.apk` unter **Releases** (rechte Spalte auf der Startseite des Repositorys).

Auf dem Handy: Release-Seite öffnen, `eBike-Tracker.apk` herunterladen, antippen, Installation aus dieser Quelle erlauben.
Neue Versionen lassen sich über die alte installieren, gespeicherte Fahrten bleiben erhalten.

## Einrichtung

1. App öffnen und Standort, Bluetooth („Geräte in der Nähe“) und Benachrichtigungen erlauben.
2. **Einstellungen → Pulsgurt suchen.** Den Gurt vorher von adidas Running trennen.
3. **Einstellungen → Bosch eBike:** eBike einschalten, Seite offen lassen. In der Flow-App: eBike → Zahnrad → Komponenten → Neues Gerät hinzufügen, dein Handy auswählen und am eBike bestätigen.
4. **Einstellungen → Akku-Optimierung ausschalten**, damit Android die Aufzeichnung nicht anhält.

Voraussetzung für die Bosch-Daten: Smart System mit Antriebs-Software v19 oder neuer.

## Export

Jede Fahrt lässt sich als GPX (mit Puls, Leistung, Trittfrequenz; für Strava, Komoot, Garmin Connect) oder als CSV für Excel exportieren. Die Dateien landen in `Download/eBike Tracker`.

## Hinweise

- Bosch bezeichnet die Schnittstelle als experimentell. Handys sind als Zubehör offiziell nicht vorgesehen; die App tritt wie ein Fahrradcomputer auf.
- Es kann nur ein Zubehör gleichzeitig verbunden sein.
- Die Signaturdatei `app/signing.p12` liegt bewusst im Projekt, damit jede Version mit demselben Schlüssel signiert wird.
