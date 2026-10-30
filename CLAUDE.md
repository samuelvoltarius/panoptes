# Panoptes: Regeln für dieses Repo

## Codekarte zuerst

**Vor jeder Code-Suche zuerst `docs/CODEKARTE.md` lesen.** Sie sagt, wo was liegt, welche Routen welcher Handler
bedient, welche Rechte gelten und wo die Fallen sind. Das spart das Durchsuchen des ganzen Codes.

**Wer Dateien anlegt, verschiebt oder löscht oder Routen ändert, zieht die Karte im selben Commit nach.**
`CodekarteAktuellTest` läuft in der Suite mit und schlägt sonst fehl. Beim Versionssprung die Stand-Zeile der Karte
anpassen.

## Arbeitsweise

- Commit mit `git commit -F <datei>`, nie `git commit -a`. Push nur hinter Gitleaks. Den Park-Hook nie umgehen.
- Keine Secrets, keine GPS-Daten, keine IPs oder privaten Hosts im Repo: Es geht als Snapshot öffentlich.
- Suite-Maßstab ist 0 neu rot gegen die Rot-Liste des Basis-Stands, siehe Karte Abschnitt 7.
- Community-App: Entscheidungen gelten für alle BYD-Autos (DiLink 3/4/5), nicht nur für ein Testauto.
- Am Auto nichts auslösen, was außen sichtbar oder hörbar ist. Daten an Server nur mit ausdrücklicher Zustimmung.
