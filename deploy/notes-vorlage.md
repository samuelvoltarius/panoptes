**Erprobungsversion** · *Test version* — Panoptes {VERSION}

Panoptes ist die kostenlose Community-App für BYD-Autos mit DiLink 3, 4 und 5. Sie wird im Alltag erprobt, bisher vor allem auf einem BYD Dolphin Surf (DiLink 3). Auf anderen Modellen kann manches fehlen oder anders sein.

**Installationsanleitung:** https://byd.serveone.at/panoptes/#installieren

| | |
|---|---|
| Datei | `{APK}` ({GROESSE} Bytes) |
| SHA-256 | `{SHA256}` |
| Signaturzertifikat (SHA-256) | `287c31e78288a440be4d23eb59f58e415d77aca54def67e0e6bf271f1959be81` (APK-Signaturschema v3) |
| Herkunftsnachweis (Lineage) | `02d5a37b…8006` → `287c31e7…be81` |
| Quellcode | genau dieser Stand: Tag `v{VERSION}` |

**Neu in {VERSION}**
{NEU_DE}
- **Panoptes Launcher {LAUNCHER_VERSION}** (`{LAUNCHER_APK}`, SHA-256 `{LAUNCHER_SHA256}`){LAUNCHER_NEU}

**So wurde geprüft**
- Automatische Tests: {TESTS_APP} in der App und {TESTS_LAUNCHER} im Launcher, davon {ROT} rot und {SKIP} übersprungen (Test passt nicht zum Testrechner). Liste und Gründe: [docs/ROT_LISTE.md](https://github.com/samuelvoltarius/panoptes/blob/v{VERSION}/docs/ROT_LISTE.md).
- Vor der Freigabe zusätzlich: Suche nach Geheimnissen (Gitleaks) und nach privaten Ortsdaten im ganzen Quellcode, Prüfung der Signatur und der Prüfsumme.
- Erprobt im Auto: BYD Dolphin Surf, DiLink 3 (Android 10, eingebauter Browser Chromium 74). {ERPROBUNG}
- DiLink 4 und 5 sind nicht selbst erprobt. Was dort geht, wissen wir nur aus Rückmeldungen der Community.

**Bekannte Fehler und Einschränkungen**
{EINSCHRAENKUNGEN_DE}

Fehler gefunden? In Panoptes unter Hilfe & Info → Fehler melden oder in der Community-Gruppe.

Privates Freizeitprojekt, kostenlos und ohne Gewähr, nicht mit BYD verbunden. Nutzung auf eigenes Risiko; bitte die [Hinweise](https://byd.serveone.at/panoptes/hinweise.html) lesen. Lizenz: eigener Code MIT; die APK enthält Teile unter GPL-3.0 (fast_cam_capture) und AGPL-3.0 (YOLO-Erkennungsmodelle). Siehe [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) und [LICENSES/](LICENSES/).

---

**EN:** Panoptes {VERSION}, the free community app for BYD cars with DiLink 3, 4 and 5 (test version). Installation guide: https://byd.serveone.at/panoptes/en/#install. Check the SHA-256 above. New: {NEU_EN} **How it was checked:** {TESTS_APP} automated tests in the app and {TESTS_LAUNCHER} in the launcher, {ROT} failing, {SKIP} skipped (list: docs/ROT_LISTE.md); secret and location scan over the whole source, signature and checksum checked. Tested in a BYD Dolphin Surf, DiLink 3 (Android 10, built-in browser Chromium 74); DiLink 4 and 5 only from community reports. **Known limitations:** {EINSCHRAENKUNGEN_EN}
