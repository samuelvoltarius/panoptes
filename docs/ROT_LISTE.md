# Bekannte rote Tests

Diese Liste gehört zu den Release-Notizen („So wurde geprüft“). Sie nennt die Tests, die beim Freigabestand
fehlschlagen, und warum. Maßstab für eine Freigabe ist: kein Test ist neu rot gegenüber dem Vorgänger.

## Stand 0.1.38.9

| Bereich | Rot | Übersprungen |
|---|---:|---:|
| App (`:app:testDebugUnitTest`) | 0 | 18 (Windows), 12 (Linux) |
| Launcher (`:panoptes-launcher:testDebugUnitTest`) | 0 | 0 |

Übersprungen heißt: Der Test prüft etwas, das es auf dem Testrechner nicht gibt (zum Beispiel POSIX-Dateirechte
unter Windows), und meldet sich deshalb ab statt rot zu werden. Auf Linux und Android laufen diese Prüfungen streng.

## Verlauf

- 0.1.38.9: Die 17 Tests, die bis 0.1.38.8 dauerhaft rot waren, waren reine Windows-Fälle (Verzeichnis-fsync,
  Pfadtrenner, Git-Symlink, Shell). Unter Linux waren sie grün. Sie sind repariert, auf allen Plattformen grün.
