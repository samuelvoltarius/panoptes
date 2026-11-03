# Rot-Liste Panoptes: Abschluss nach 0.1.33

Stand: 01.11.2026. Release-Basis ist `c2e1767a`; der kombinierte Test- und
Produktnachtrag baut auf `d3058300` auf. Release-Tag, Release-Branch und das am
Fahrzeug installierte Artefakt wurden nicht verändert. Ein weiterer lokaler
Folgecommit behebt den endlosen USB-Freigabe-Wartezustand auf normalen
Android-Handys und begrenzt ihn am BYD-Infotainment.

## Ergebnis

| Art | Release-Basis | Kombinierter Abschluss |
|---|---:|---:|
| Windows-/Plattform-bedingt | 17 | 17 |
| Veraltete Erwartung | 43 | 0 |
| Echte Produktlücke | 5 | 0 |
| Fachliche Entscheidung | 3 | 0 |
| **Summe** | **68** | **17** |

Frisch gebaute Windows-Vollsuite: **4.057 Tests, 17 fehlgeschlagen,
20 übersprungen, 0 neu rot**. Gegenüber der Release-Basis sichern vier neue
Tests die Web-Startseite und zwei weitere die Handy-/Infotainment-Erkennung
des Einrichtungsassistenten.

## Geschlossene Produktlücken

1. Der Kamera-Watchdog löst APK- und Bibliothekspfad vor jedem Daemonstart neu
   über den Paketmanager auf.
2. Die Web-Startseite führt Fahrzeugabfragen einzeln aus, pausiert verborgen
   und entfernt alte Tür-, Fenster-, Klima- und Reifenwerte nach zwei Fehlern.
3. Dynamische Schloss- und Cloudtexte werden nicht mehr durch i18n-Hydration
   überschrieben.
4. Der ausdrücklich aktivierte, bereits erzeugte KI-Tipp erscheint auf der
   Startseite, ohne beim Anzeigen einen KI-Anbieter aufzurufen.
5. Geschätzte Lademenge ist wie geschätzte Ladeleistung mit `~` markiert.
6. Der Einrichtungsassistent erkennt normale Android-Handys ohne lokalen
   Infotainment-ADB-Dienst und erklärt den richtigen Installationsort, statt
   endlos auf einen unmöglichen Dialog zu warten. Am BYD-Display endet der
   Versuch nach 20 Sekunden mit konkreter Hilfe und einem echten Neuversuch.

## Aktualisierte Strukturverträge

Die Tests für Startseite, Fahrzeugzustand, Info-Seite, Web-Shell und GenAI
prüfen jetzt die heutige Struktur statt entfernte Elemente des alten
Dashboards. Dabei bleiben die fachlichen Sicherungen erhalten: Priorität von
Fehler/Laden/Fahrt, frische Quellen, fail-closed Zustände, lokalisierte Texte,
Mindest-Tippflächen und ausdrücklich aktivierte KI-Ausgabe.

## Entschiedene Darstellungsfragen

- Negative Laufweite ist für große Displaywerte bis `-0,03em` erlaubt. Pixel-
  Tracking bleibt verboten; die große Camping-Uhr ist als einzige
  viewport-relative Ausnahme bis `-0,5vh` begrenzt.
- `…` ist beim Aufnahmen-Zähler ein laufender Ladeplatzhalter. Ein endgültig
  unbekannter Wert bleibt `—`; der Test unterscheidet beide Zustände.
- Der belegte Dolphin-Meshname `bodypaint_A_CarPaint_1_0` führt zum Hint
  `bodypaint_a`. Der allgemeinere Hint `bodypaint` traf auch
  `bodypaint_rim_RIM_Color_0` und hätte Felgen mitlackiert.

## 17 verbleibende Plattformfälle

- `SohEstimatorDurabilityTest`: vier Fälle. Drei scheitern unter Windows am
  Verzeichnis-Sync per `FileChannel`, einer an nicht verfügbaren
  POSIX-Dateirechten.
- `SocHistoryDatabaseChargingAccountingContractTest`: fünf Fälle mit demselben
  Verzeichnis-Sync-Mechanismus.
- `BatteryChartHoverGeometryTest#bothPerformanceScriptCopiesStayIdentical` und
  `RecordingIdentityClientContractTest#webMirrorsUseStableKeysAndServerActions`:
  Git-Symlinks liegen im Windows-Arbeitsbaum als Textdateien vor.
- `TailscaleDashboardServeContractTest#sharedGuardHasValidPosixShellSyntax`:
  `/bin/sh` ist in diesem Windows-Lauf nicht vorhanden.
- `RecordingFileFingerprintTest`, `RecordingIdentityTest`,
  `RecordingsIndexVolumeMutationTest` sowie zwei Fälle in
  `RecordingDirectoryRegistryTest`: Unix-Pfade, Pfadtrenner oder
  Laufwerkswurzel unterscheiden sich auf Windows.

Diese 17 Fälle sind keine nachgewiesenen Produktfehler. Ein vollständiger
Linux-Lauf bleibt der notwendige Gegenbeleg; sie dürfen bis dahin nicht aus der
Suite gelöscht oder pauschal übersprungen werden.

## Nicht durchgeführt

Kein Push, Merge, Tag, Signieren, Deploy und keine Aktion am Fahrzeug. Echte
iPhone-/Android-Passkey-Läufe, echtes Aufsperren sowie automatisches
Wiederzusperren bleiben außerhalb dieses Testabschlusses; letzteres ist nicht
implementiert und darf nicht als Sicherung versprochen werden.
