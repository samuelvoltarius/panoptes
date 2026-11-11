# Bekannte rote Tests

Diese Liste gehört zu den Release-Notizen („So wurde geprüft“). Sie nennt die Tests, die beim Freigabestand
fehlschlagen, und warum. Maßstab für eine Freigabe ist: kein Test ist neu rot gegenüber dem Vorgänger.

## Stand 0.1.41

| Bereich | Rot | Übersprungen |
|---|---:|---:|
| App (`:app:testDebugUnitTest`) | 0 | 24 (Windows), 12 (Linux) |
| Launcher (`:panoptes-launcher:testDebugUnitTest`) | 0 | 0 |

Übersprungen heißt: Der Test prüft etwas, das es auf dem Testrechner nicht gibt (zum Beispiel POSIX-Dateirechte
unter Windows), und meldet sich deshalb ab statt rot zu werden. Auf Linux und Android laufen diese Prüfungen streng.

## Verlauf

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
