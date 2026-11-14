# Bekannte rote Tests

Diese Liste gehört zu den Release-Notizen („So wurde geprüft“). Sie nennt die Tests, die beim Freigabestand
fehlschlagen, und warum. Maßstab für eine Freigabe ist: kein Test ist neu rot gegenüber dem Vorgänger.

## Stand 0.1.43.7

| Bereich | Rot | Übersprungen |
|---|---:|---:|
| App (`:app:testDebugUnitTest`) | 0 | 24 (Windows); Linux in CI ohne Rot, nicht eigens gezählt |
| Launcher (`:panoptes-launcher:testDebugUnitTest`) | 0 | 0 |

Übersprungen heißt: Der Test prüft etwas, das es auf dem Testrechner nicht gibt (zum Beispiel POSIX-Dateirechte
unter Windows), und meldet sich deshalb ab statt rot zu werden. Auf Linux und Android laufen diese Prüfungen streng.

## Verlauf

- 0.1.43.7: 5696 Tests in der App und 331 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.6.
- 0.1.43.6: 5688 Tests in der App und 330 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.5.
- 0.1.43.5: 5678 Tests in der App und 329 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.4.
- 0.1.43.4: 5663 Tests in der App und 327 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.3.
  Der Kamera-Fix R1 (statische Seiten über `pathOnly`) änderte die Schreibweise der Routen; fünf Tests, die den Quelltext
  wörtlich lesen, prüfen jetzt die neue Schreibweise (gleicher Inhalt).
- 0.1.43.3: 5629 Tests in der App und 257 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.2.
- 0.1.43.2: 5608 Tests in der App und 247 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.1.
  Beim Zusammenführen erwartete ein Test aus 0.1.43.1 die Menüzeile „Bildschirm im Auto“ noch ohne die Stufe des Modus
  „Einfach“; er prüft jetzt die zusammengeführte Zeile (`StartKarteGewolltTest.fernansichtReiter`).
- 0.1.43.1: 5570 Tests in der App und 237 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.43.
- 0.1.43: 5557 Tests in der App und 231 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.42.
  Ein Launcher-Test erwartete noch das alte Raster-Layout; nach dem freien Editor prüft er dieselbe Abstandsregel am
  neuen Layout (`LauncherBedienungTest.grosseBedienungFaktor`).
- 0.1.42: 5484 Tests in der App und 175 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.41.
- 0.1.41: 5460 Tests in der App und 165 im Launcher, keiner rot. Übersprungen sind dieselben 24 (Windows) wie in 0.1.40.
  Ein neuer Launcher-Test las den Quelltext mit Windows-Zeilenenden und fand sein Suchmuster nicht. Er liest jetzt
  zeilenendenunabhängig (`LauncherKombiSeitenblickTest`).
- 0.1.40: 5438 Tests in der App und 108 im Launcher, keiner rot. Unter Windows 24 übersprungen: die 6 neuen Watchdog-Tests
  brauchen `/bin/sh` und laufen nur unter Linux. Gemeinsamer statischer Zustand der Ladequellen-Erkennung wird jetzt zwischen
  den Tests zurückgesetzt (`LadequellenZuruecksetzen`).
- 0.1.39: 5428 Tests in der App und 92 im Launcher, keiner rot; 18 übersprungen (Windows), 12 (Linux).
- 0.1.38.10: 5322 Tests in der App und 83 im Launcher, keiner rot, unter Windows und Linux. Ein Prozess-Test des Launchers
  (`SelbstheilungTest`) kann auf einem stark ausgelasteten Windows-Rechner wegen Zeitlimits scheitern; er ist im Freigabelauf grün.
- 0.1.38.9: Die 17 Tests, die bis 0.1.38.8 dauerhaft rot waren, waren reine Windows-Fälle (Verzeichnis-fsync,
  Pfadtrenner, Git-Symlink, Shell). Unter Linux waren sie grün. Sie sind repariert, auf allen Plattformen grün.
