# Regeln für Codex und andere Agenten in diesem Repo

- Zuerst `CLAUDE.md` und `docs/CODEKARTE.md` lesen. Die Karte sagt, wo was liegt.
- Releases nur über die Koordination: Kern-Zweig → Integrator → Freeze-Wort → Tag `rel-x` bzw. `vx` → Deploy.
  Nie selbst taggen, nie Tags oder `rel-*`-Zweige verschieben.
- `main` nie umschreiben, kein Sammel-Commit, kein force-push. Neue Arbeit immer auf einem eigenen Zweig vom
  aktuellen `rel-*`.
- Commit nur mit `git commit -F <datei>`, nie mit `-a`. Gitleaks und Park-Prüfung vor jedem Push.
- Nichts am echten Auto. REGEL A+: nie Hupe, Blinker, Licht oder Alarm auslösen.
- Keine GPS-Daten, Testorte nie Salzburg, Secrets nie ins Repo.
- REGEL 50+: Unsere Nutzer sind 50+ und keine PC-Profis. Einfache, kurze Sprache ohne Fachwörter, große Knöpfe.
- Volle Testsuite: 0 neu rot gegen die aktuelle Rot-Liste (Karte, Abschnitt 7).
- Ergebnisse und Fragen gehen an die Koordination. Die Regeln stehen in `KOORDINATION.md` im Notizordner
  `%APPDATA%\Claude\side-session-notes\local_e8aa25bc-de0a-46be-937e-3a775f5995b7\`.
