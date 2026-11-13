# Codekarte Panoptes

**Stand: Version 0.1.43.2, Commit a6cc889c**

Wegweiser durch den Code. Zuerst hier suchen, dann gezielt die genannte Datei öffnen.
Die Karte nennt Dateien und Klassen, keine Zeilennummern. Kurze Pfade gelten ab
`app/src/main/java/at/greenchili/surfmate/` (Java/Kotlin) bzw. `app/src/main/assets/`.

`CodekarteAktuellTest` hält die Karte ehrlich, siehe „Karte pflegen“ am Ende. Regeln für Agenten: `CLAUDE.md`, `AGENTS.md` (Codex).

## 1. Überblick

Panoptes ist eine Community-App für BYD-Autos (DiLink 3/4/5). Paketname und interne Namen bleiben
`at.greenchili.surfmate`, sichtbar heißt alles Panoptes. Grundlage ist der MIT-lizenzierte Overdrive-Code,
englische Klassen wie `SurveillanceEngineGpu` stammen daher, deutsche Namen sind Eigenbau.

| Teil | Läuft wo | Einstieg |
|---|---|---|
| App (Android, Kotlin/Java) | App-Prozess, eigene UID | `SurfMateApplication`, `MainActivity`, `DaemonLauncher` |
| Daemons | `app_process` als `shell` (uid 2000), zwei als `system` (uid 1000) | `CameraDaemon`, `AccSentryDaemon`, `SentryDaemon`, `TelegramBotDaemon` |
| Weboberfläche | Statische Seiten, vom `CameraDaemon` ausgeliefert | `assets/web/local/`, `assets/web/shared/` |
| Server-API | `HttpServer` im `CameraDaemon`, Port 8180 | `HttpServer.java`, Abschnitt 4 |
| Brücke (Handy übers Internet) | im Kamera-Daemon, Noise-verschlüsselt | `bruecke/`, `BrueckeApiHandler` |
| Updater | In-App, plus Skript am Auto | `AppUpdateDienst`, `deploy/update-on-car.sh` |
| Auto-Skripte | Am Auto als `shell` | `deploy/sm-watchdog.sh`, `deploy/update-on-car.sh` |

**Bereiche** (Zuständigkeit, eine Änderung bleibt in einem Bereich): 1 Fahrzeugzugriff `byd/`, `VehicleControlApiHandler`, `FahrzeugAktionen`; 2 Kamera/Wächter `CameraDaemon`, `AccSentryDaemon`, `surveillance/`; 3 Auswertungen `trips/`, `charging/`, `wallbox/`; 4 Fernzugang `auth/`, `bruecke/`, `WebHandyGuard`; 5 Start/Updates `DaemonStartPlan`, `launcher/`, `appupdate/`; 6 Navigation (0.1.38.10) `planer/`, `reichweite/`, `telenav/`, `navmap/`, `navi.html`, `NaviApiHandler`.

### Prozesse

Die App startet die Daemons über adb-Shell (`launcher/`). Jeder Daemon ruft zuerst `DaemonBootstrap`
auf und läuft als `CLASSPATH=<apk> app_process`, bewacht von einem kleinen Watchdog-Skript, das
`DaemonLauncher` schreibt. Daten liegen im Daemon-Ordner unter `DaemonStorage`.

| Prozess | UID | Aufgabe | Port |
|---|---|---|---|
| `CameraDaemon` | 2000 | Kamera, Wächter, HTTP-Server, Telemetrie, die meisten Dienste | 8180 (HTTP), 19976 (`TcpCommandServer`), 19977 (`SurveillanceIpcServer`), 19978 (`AacIngestServer`) |
| `AccSentryDaemon` | 2000 | ACC beobachten, Bildschirm, Wächter per IPC an/aus, MCU wach halten | |
| `SentryDaemon` | 1000 | ACC-Lock, WakeLock, Netz-Freigaben | 19979 |
| `BydEventDaemon` | 1000 | BYD-Ereignisse (Radar, Karosserie) an Klienten | 19978 (Klient: `BydEventClient`) |
| `TelegramBotDaemon` | 2000 | Telegram-Bot, Befehle unter `daemon/telegram/` | 19980 |

`AacIngestServer` und `BydEventDaemon` tragen beide 19978 im Code. Weitere interne Ports: `TelenavIpcServer` 19982, `RemoteDevViewBridgeService` 19981, `CellularRelay` 8221, `WebSocketStreamServer` 8987.

App-seitige Hilfsdienste stehen in `services/`, darunter `VehicleActuatorService` (schreibt mit echter App-UID, Daemon-Seite ist `VehicleActuatorBridge`) und `KeepAliveAccessibilityService`.

`ui/daemon/` und `daemon/management/` zeigen und steuern die Daemons aus der App. Start des Stapels: `ui/daemon/DaemonStartPlan.kt` (EINE Quelle: 45 s Boot-Schutz ab Geräte-Boot; 0.1.38.10 Stufen Zugang(nur mit Tailscale) → Web → Wächter → Rest, aus den Einstellungen) mit `ui/daemon/StartAblauf.kt` (Ablauf ohne feste Pausen) und `updater/HardResetTor.kt` (Stufe −1, hält Starts während des Hard-Resets an, nur den Zugang nie; der Hard-Reset beendet den Weg-1-tailscaled nicht mehr, Protokollzeile `TAILSCALED_ALT_LAEUFT`). 0.1.38.10 Anzeige je Dienst beim Start: `ui/daemon/StartStatus.kt` (eine Stelle je Stufe Zugang/Oberfläche/Wächter/Rest, App-Prozess) → Zeilen im Ladebildschirm (`ui/StartAnzeige.kt`, WebViewFragment) und Karte „Start“ (`web/shared/start-karte.js`, `GET /api/start/status` beantwortet die WebView selbst). 0.1.43 (S2): Angezeigt wird nur, was gewollt laufen soll (`DaemonStartPlan.anzeige`): Wächter nur mit `surveillance.surveillanceEnabled` (dieselbe Einstellung wie Chip „Wächter aus“, Status-Feld `gewollt`), Rest nur mit Telegram, nichts mit Stopp-Marke des Nutzers; „startet nicht“ nur, wenn ein gewollter Dienst nicht läuft. Der Ablauf selbst bleibt gleich. Menü (`app-shell.js`): Seiten mit `fern: true` (Fernansicht) erscheinen am Display nicht als Reiter. 0.1.38.9: Prozesse beenden/prüfen nur eigene (`launcher/EigeneProzesse.kt`), Erkennung fremder Werkzeug-Apps `launcher/FremdApps.kt` (Hinweis am Ladebildschirm, Protokollzeile `FREMDE_WERKZEUG_APP`), Web-Schreibaufrufe asynchron (`httpRequestAsyncWeb` in `WebViewFragment`).

## 2. Pakete

Jedes Paket steht hier, auch Unterpakete. Pfade ab dem Wurzelpaket. Lose Dateien im Wurzelpaket:
`SurfMateApplication`, `BlockerActivity`, `DeterrentActivity`.

| Paket | Inhalt |
|---|---|
| `abrp/` | ABRP-Telemetrie (optional), SoH-Schätzung `SohEstimator` |
| `ai/` | YOLO-Erkennung `YoloDetector` |
| `analytics/` | Mitzählen `Mitzaehlen` |
| `appupdate/` | In-App-Updater (Quelle, Freigabe, Zertifikat, Fern-Update) |
| `audio/` | App-Audio-Mitschnitt |
| `auth/` | Login, PIN, Kopplungen, Web-Sitzung |
| `automation/`, `automation/action/`, `automation/condition/`, `automation/type/`, `automation/value/` | Regeln (Auslöser, Bedingungen, Aktionen) |
| `battery/` | Lade-Qualität, Akkudaten |
| `bericht/` | Monatsbericht |
| `ble/` | BLE-Zählung |
| `blitzer/` | Blitzer-Warnung |
| `bridge/` | Fahrzeugdaten für App-Oberfläche (nicht die Handy-Brücke) |
| `bruecke/` | Panoptes-Brücke zum Handy |
| `byd/`, `byd/bodywork/`, `byd/cloud/`, `byd/cloud/crypto/`, `byd/dilink5/`, `byd/light/`, `byd/radar/`, `byd/routing/` | BYD-Zugriff: Daten lesen, Befehle, Cloud, DiLink 5. `byd/cloud/crypto/`: Zugangsdaten über `CredentialCipher`; 0.1.38.6 legt neben dem Klartext (bleibt für den Rückfall auf 0.1.38.5) eine geprüfte Schattenkopie `enc_<feld>` an (`ZugangsMigration.schatten`, Abgleich beim Daemon-Start), 0.1.38.8 auch `auth.deviceSecret`, `hotspot.password`, Brücken-Datei (a, g); Klartext erst nach 0.1.38.8 löschen; Fernklima: `KlimaZustand` ist die eine Bewertung (frischer Cloud-`airRunState` > Befehl > unbekannt, `KlimaLaeuft`), `FernKlima` (rein) hält den erfolgreichen Cloud-Start als Sitzung „laut Befehl“ (Router `fernKlimaSitzung()`, `climate.fern` in `/api/vehicle/state`, Telegram `GET_FERNKLIMA`); `climate.acOn` fehlt bei Zündung aus, nie „Klima aus“ ohne Beleg; `StoppKnopf` (rein) entscheidet den roten Stopp-Knopf (`climate.stopp {zeigen,grund,weg}`, nur bei Beleg, Fern-Sitzung → Stopp über die Cloud), Web und Handy-App `heizen_stoppen` lesen ihn |
| `camera/`, `camera/dilink5/` | Kamera-Zugriff, Panorama, Dashcam-Pipeline; Kamera-Eingang erkennen `AutoArtErkennung` (K2: libais/Android/Modell, nur Vorschlag, `GET /api/surveillance/autoart`) |
| `camping/` | Camping-Modus |
| `charging/` | Ladesitzungen, Tarife |
| `client/` | IPC-Klienten zum Daemon |
| `communication/` | Sprechverbindung zum Auto |
| `community/`, `community/config/`, `community/sync/` | Community-Server (Regeln teilen, Straßenzustand) |
| `config/` | Gesamt-Konfiguration `UnifiedConfigManager`, Sicherung, Geheimnisse |
| `daemon/`, `daemon/camera/`, `daemon/management/`, `daemon/proxy/`, `daemon/sentry/`, `daemon/telegram/` | Daemon-Hauptklassen und Helfer |
| `einrichtung/` | Selbsttest, Einrichtungs-Helfer |
| `einstellungen/` | Einstellungs-Katalog und Schema |
| `fahrtenbuch/` | Fahrtenbuch verschlüsselt abholen (Postfach) |
| `genai/` | „Frag Panoptes“ (KI-Assistent) |
| `geo/` | Geokodierung, Orts-Zwischenspeicher |
| `gesundheit/` | Gesundheitswächter |
| `gewohnheiten/` | Gewohnheiten erkennen, Regel vorschlagen |
| `ha/` | Home Assistant |
| `handy/` | Handy-Signatur, Ratenlimit, Push |
| `ladekurve/` | Ladekurve aufzeichnen und teilen |
| `laden/` | Lade-Vorlieben |
| `ladeplan/` | Ladeplan |
| `launcher/` | Start von Daemons, Tailscale, WireGuard; Tacho-Cast |
| `logging/` | Logs |
| `manager/` | BYD-Systemdienste |
| `monitor/` | Fahrzeug-Überwachung: ACC, Laden, SoC-Verlauf |
| `mqtt/` | MQTT-Veröffentlichung |
| `navmap/`, `navmap/nav/`, `navmap/oem/` | Karte, Navigation, Weitergabe eigener Abbiegehinweise an den Daemon; `ClusterMapProjector` sperrt Single-OS zentral vor jedem API-/ACC-/Restore-Start; Einstieg aus `navi.html` über `AndroidBridge.openNavigation`/`fuehrungStand`/`fuehrungBeenden` und `Fuehrung` (Auftrag ohne Route und Position, Vorschau erst, geführt nach Tipp) |
| `network/` | Hotspot, Mobilfunk-Relais |
| `neuigkeiten/` | „Was ist neu“ |
| `notifications/`, `notifications/push/`, `notifications/sinks/` | Meldungen und Zustellwege |
| `notzugang/` | Notzugang |
| `od/` | Binding an `libod.so` |
| `onboarding/`, `onboarding/schritte/` | Einrichtungs-Assistent (nativ); 0.1.38.11 „Einrichtung unterbrechen“ immer sichtbar (`AssistentHost.unterbrechen` → `system.html#einrichtung`), Seiten-Ebene mit Kopfzeile + Zurück-Taste, verwaistes Ebenen-Fragment nach Neuaufbau weg; `ui/NeuaufbauZiel`: nach Neuaufbau nie die alte Oberfläche |
| `orte/` | Gespeicherte Orte |
| `overlay/` | Status-Band, Meldungs-Overlay, Einrichtungs-Dialog |
| `parking/`, `parking/signage/` | Parken, Schilder lesen |
| `planer/` | Routenplaner-Anbindung |
| `power/` | 12-V-Spannung, Standby, Warmhalten |
| `proximity/` | Annäherung per Radar |
| `receiver/` | Broadcast-Empfänger |
| `recording/` | Aufnahmemodus, Ereignis-Clips, Kamera im Stand schließen (`KameraParkRegel`: P + Zündung an + nichts sonst, 120 s; Opt-in-Schalter `kamera.parkFreigeben`, Standard aus, auf DiLink 4/5 verborgen; Haken `RecordingModeManager.parkFreigabePruefen`, `StreamingApiHandler.resolveBlindSpotLifecycle`; 0.1.38.11: Zündung aus + kein Verbraucher 60 s → zu, ohne Schalter, Leerlauf ohne Stall-Neustart) |
| `reichweite/` | Reichweiten-Wacht |
| `remote/` | Fernansicht |
| `rueckmeldung/` | Fehler melden, Auto-Angaben fürs Mitzählen |
| `roadsense/`, `roadsense/config/`, `roadsense/detect/`, `roadsense/label/`, `roadsense/overlay/`, `roadsense/sidecar/`, `roadsense/source/`, `roadsense/store/`, `roadsense/sync/`, `roadsense/warn/` | Straßenzustand |
| `server/` | HTTP-Server, API-Handler, Guards, IPC |
| `services/` | Android-Dienste der App |
| `shell/` | Shell-Rechte, Hidden-API |
| `storage/` | Speicher, Aufbewahrung |
| `streaming/` | Video-Stream |
| `surveillance/` | Wächter-Kern (siehe `DETECTION-INVARIANTS.md`); 0.1.38.8 Standard an: `WaechterStandardHinweis` (rein: einmal je versionCode fragen, Marker, nie still einschalten, `/api/surveillance/standard-an` braucht die Rechtshinweis-Version), `WaechterStandardKanaele` (Telegram mit Knopf `/waechter standard`, Push `system.waechter`, Display-Hinweis), Takt `AppUpdateDienst.waechterHinweisTakt` |
| `tacho/`, `tacho/oem/` | Tacho-Fähigkeitstest, App-Freigabe sowie streng gegatete OEM-Abbiegepfeile über AutoContainer |
| `telegram/`, `telegram/config/`, `telegram/event/`, `telegram/impl/`, `telegram/model/` | Telegram: Versand, Konfiguration, Kopplung |
| `telemetry/` | Overlay-Daten für Aufnahmen |
| `telenav/` | Telenav-Anbindung |
| `trips/` | Fahrten, Fahrtenbuch-Daten |
| `uebernahme/` | Overdrive-Übernahme |
| `ui/`, `ui/about/`, `ui/adapter/`, `ui/daemon/`, `ui/dashboard/`, `ui/dialog/`, `ui/fragment/`, `ui/fragment/settings/`, `ui/model/`, `ui/navigation/`, `ui/privacy/`, `ui/setup/`, `ui/util/`, `ui/vehicle/`, `ui/view/`, `ui/viewmodel/`, `ui/widget/` | Native Oberfläche, vieles davon WebView-Hülle; 0.1.39 A1: die alte native Oberfläche (Dashboard, Aufnahmen-Liste, Player, Einstellungen, alte Leiste) ist gelöscht, im Nav-Graphen nur Web-Start, Projektion, BYD-Cloud (`AltDesignGeloeschtTest`); vorher 0.1.38.14 unerreichbar – kein „Erweitert (alt)“, events.html bleibt im Web, nach Neuaufbau zurück ins Web (`NeuaufbauZiel.altesZiel`, nur Projektion bleibt nativ); 0.1.39 `ui/WebZurueck`: Android-Zurück geht eine Web-Seite zurück, solange es Verlauf gibt (eigener Verlauf als Rückfall zu `canGoBack()`), erst auf der Startseite `moveTaskToBack` |
| `updater/` | Installationsablauf nach Update |
| `util/` | Hilfen, `DaemonStorage` |
| `wallbox/` | Wallbox-Anbindung |
| `weather/` | Außentemperatur |
| `weckwort/`, `sprache/` | Weckwort-Messung Stufe 1 (nur Zahlen, Standard aus, keine Befehle); ab 0.1.44 `sprache/` Sprachbefehle offline (feste Kommandos DE/EN, Rückfrage bei Klima, Weckwort Stufe 2; `docs/SPRACHBEFEHLE.md`) |
| `wireguard/` | WireGuard-Konfiguration |

## 3. Thema → Datei → Einstieg

| Thema | Dateien | Einstieg |
|---|---|---|
| Wächter (scharf, Erkennung) | `surveillance/`, `GpuSurveillancePipeline`, `SurveillanceEngineGpu`, `MotionPipelineV2`, nativ `motion_pipeline_v2.cpp` | `DETECTION-INVARIANTS.md` zuerst lesen |
| Wächter-Auslöser (Wahl a/b/c) | `WaechterAusloeser`, `TriggerModus`, `Laenderprofil` | `WaechterAusloeser` |
| Wächter-Zustand für Web | `GuardStateReporter`, `SurveillanceApiHandler` | `/api/surveillance/status` |
| Abschreckung am Display | `ScreenDeterrent`, `DeterrentActivity`, `AbschreckungsRegel`, `ScreenDeterrentVideo` | `ScreenDeterrent` |
| 12 V, Wächter-Stopp | `GuardStopPolicy` (rein), `GuardStopMonitor` (Laufzeit), `GuardStopNotice`, `Battery12vEvaluator` (Sägezahn: Stopp über den Nachladezyklus, siehe `Battery12vSaegezahnTest`), `Starterbatterie12v`, `AkkuChemie`, `BatteryVoltageMonitorV2`, `SocCutoffMonitor` | `GuardStopPolicy` |
| Standby, Weg 2 | `Weg2Regel`, `Weg2Waechter`, `StandWeckRegel`, `Di5ParkedPowerHold` | `Weg2Regel` |
| Gesundheitswächter | `GesundheitsDienst`, `Gesundheit`, `AusfuehrerWaechter`, `deploy/sm-watchdog.sh` | `GesundheitsDienst` |
| Rechte-Selbstheilung | `RechteHeilung` (rein: `dumpsys`/`appops get` lesen, nur `pm grant`/`appops set … allow`), `RechteHeilungsDienst` (5 min nach Start, dann täglich; Rest an Display und Telegram, Katalog `rechte_heilung`), Feld `rechte` in `/api/surveillance/gesundheit`; Watchdog-Selbstheilung `WatchdogSelbstpruefung` (in Kamera-, Wächter- und Telegram-Daemon alle 30 s: `start_*.sh` mit Anhänge-Modus, `sh -n`, Watchdog lebt; sonst `DaemonLauncher.buildSelbstheilSkript`: Skript neu, `sh <skript> anhaengen <pid>`, nie ein zweiter Daemon; nach 3 Fehlversuchen Telegram, Katalog `selbstheilung`; App heilt beim Öffnen; `update-on-car.sh` bestätigt erst mit `skripte_befund` = ok) | `RechteHeilung` |
| Kopplungen und Rechte | `Kopplungen` (Arten, Rechte `RECHT_*`), `KopplungsAnfragen`, `KopplungsMelder`, `PairingCodes`, `WeiteresGeraet` | `Kopplungen` |
| Sparsam | `KurzSpeicher` (geteilte Antwort für `/api/vehicle/state` und SSE), `ZugriffsLog` (`/api/system/zugriffe`), Takt `energie.parkLeseSekunden` | `ZugriffsLog` |
| Befehls-Log (wer, was, wann) | `BefehlsLog` (dauerhaft, Ordner `befehlslog`, zwei Ringdateien `pflicht`/`andere`, 30 Tage, `/api/system/befehle`), `SchreibProtokoll` (Zeile im Daemon-Log mit Kopplung und Aktion), Haken in `VehicleCommandRouter` und `Kopplungen`; Anzeige Karte „Protokoll“ in `system.html` (`web/shared/befehlslog.js`, Gruppe Verbindungen) | `BefehlsLog` |
| Abschaltbericht (Telegram) | `AbschaltBericht` (rein plus Nachlauf 90 s nach Zündung aus, ohne Ort; Schalter `telegram.abschaltBericht` Standard an, Schwellen `telegram.abschaltBerichtMinMeter`/`telegram.abschaltBerichtMinMinuten`; trägt auch den Wächter-Merker nach; Bericht nur nach echtem an → aus im selben Prozess, nie nach Start/Update, einmal je Fahrt, Kategorie PARKING, Schloss über `sperrStand` mit Nachfassen), Haken in `CameraDaemon` neben `ZurueckAmAuto` | `AbschaltBericht` |
| Fahrt-Statistik | `VerbrauchTemperatur` (Kurve je 5 °C, Spalte `ext_temp_bekannt`, alte 0 °C = unbekannt), `Standverlust` (aus `acc_events`, Spalte `waechter_scharf`), Anzeige `web/shared/fahrt-statistik.js` im Reiter Statistik von `trips.html`; MQTT nach Grafana: `docs/GRAFANA.md`, `docs/grafana/` | `Standverlust` |
| ExternGuard | `ExternGuard` (Erlaubnisliste Kopplungen), `WebHandyGuard` (Web-Sitzung Handy und 0.1.39 PC, gleicher Scope), `AuthMiddleware` (Login, Loopback); 0.1.39 `PC_SCHREIBEN` für Art `pc`, PC schreibt sonst nur `WebHandyGuard.PC_WEB_SCHREIBEN` (nie Fahrzeug, Web-Sitzung und Kopplung) | Abschnitt 5 |
| Login, PIN, JWT | `AuthManager`, `PinManager`, `WebSitzung`, `TelegramAnmeldung`, `AuthApiHandler`; `AuthZugangIpc` (0.1.38.8: Schlüssel per IPC-Befehl `AUTH_ZUGANG` nur an App-uid und uid 1000, nie shell/tailscaled); `IpcAufrufer`: alle übrigen 19977-Befehle nur von uid 1000, App-uid oder shell außer tailscaled (frei nur `UPDATE_GPS`, `IMU_BATCH`), Notschalter-Datei `ipc_pruefung_aus` im Daemon-Verzeichnis) | `AuthManager` |
| Push (Handy, iPhone) | `PushSink`, `VapidSigner` (fester Projekt-Kontakt), `capabilities.push` in `SystemApiHandler` | `VapidSigner` |
| Handy-App-API | `HandyApiHandler`, `HandyClipsApiHandler`, `HandySignatur`, `HandyRatenlimit`; 0.1.38.8 Besitzer-Handy `BesitzerApiHandler` (Koppeln per Code mit fester Art/Rechten, Anfragen, junge Kopplung entfernen, Besitzer weitergeben/abgeben, eigene Rechte, Kerndienst starten, Wächter), `BesitzerMeldung` (Stufe für `/api/einstellungen`, Meldungen ohne Werte; PC nie über `KOPPLUNG`); 0.1.39 `BesitzerSignatur` (heikle Besitzer-POSTs nur mit Biometrie-Signatur, Zweck bindet die Nutzdaten, Schlüssel muss `Kopplungen.schluesselBesitzer` sein, Tests `BesitzerSignaturTest`); 0.1.39 PC über die Brücke: `PcZugang` (Schalter `bruecke.pcAnmeldung` Standard aus und `nieExtern`, aus = alle PCs widerrufen; höchstens 5, „Nur heute“ 12 h, 20 Anfragen/s, Tagesgrenze, Takt 10 s), `WebManifest` | `HandyApiHandler` |
| Aufsperren mit Passkey (Web-App) | `WebAuthn` (reine Prüfung, feste COSE-Form statt CBOR-Leser), `PasskeyFenster` (nur im Speicher), `HandySignatur.pruefeAufsperrenWebauthn`, Einrichten nur direkt am Auto (`AuthMiddleware.isDirektAmAuto`), Tests `WebAufsperrenTest`, Grenzen in `WEB_AUFSPERREN_STAND.md` | `WebAuthn` |
| Fahrzeug-Aktionen (0.1.38.8: Hupe/Licht/Warnblinker/Auto finden/Aufsperren als Nutzerwahl) | `FahrzeugAktionen` (Schalter Abschnitt `fahrzeugAktionen`: `autoFinden`, `licht`, `warnblinker`, `aufsperren`, `annaeherung`, alle Standard AUS, im Katalog Gruppe Auto mit Warnsatz; Router-Wächter `VehicleCommandRouter.checkFahrzeugAktion` in beiden Eingängen: `find-car`, `flash`, `hazard` EIN, `unlock-handy` laufen nur in `FahrzeugAktionen.ausloesen` mit Schalter, Rückfrage `bestaetigt`, Ratenlimit 3/10 min + 10/h bzw. Aufsperren 3/10 min + 6/h, Befehls-Log `aktion.<funktion>.<quelle>`, Telegram CRITICAL + Push `vehicle.security.aktion`; Eingänge: Web `VehicleControlApiHandler.aktionWeb` (Display/Login oder Handy-Web mit Recht `signal`, nie Kopplung/Regel/KI/Taste), Handy `auto_finden`/`licht`/`warnblinker` (Recht `signal`), Handy-Aufsperren wie bisher plus Schalter, Wächter-Abschreckung eigene Quelle ohne Schalter; Abschalten immer: Handy `aktionen_aus`/`annaeherung_aus`, Telegram `/aktionen aus` (`AktionenCommandHandler`, IPC `FAHRZEUGAKTIONEN`); Überführung `ueberfuehren` beim Kamera-Daemon-Start: Aufsperren einmal an, wenn eine Kopplung das Recht hat, und einmal Hinweis mit den Regeln aus `betroffeneRegeln`; läuft später eine Regel auf die Sperre, je Funktion ein Hinweis am Tag), Test `FahrzeugAktionenTest`; Automatik „Aufsperren beim Annähern“ `Annaeherung` (Handy `naehe_anmelden` mit Biometrie-Signatur über den Fingerabdruck des Nähe-Schlüssels, `annaeherung` signiert mit Standort, kurzer BLE-Scan nur auf Anfrage `BleNaehe` + `BleNaeheReceiver` ≤ 20 s und 1/30 s, 20/h; Bedingungen Abstand ≤ 25 m, Genauigkeit ≤ 20 m, Bake, Auto aus, verriegelt, kein Wächter-Ereignis, einmal je Annäherung; nach 60 s ohne Tür wieder zu mit Zurücklesen, sonst Telegram „Auto ist offen“ + `/aktionen zusperren`; Selbst-Abschaltung bei Kopplung weg, Schlüssel abgelaufen, 3 falschen Signaturen, misslungenem Zusperren; Aufsperr-Befehl nur in `HandyApiHandler.AnnaeherungEcht`; Vertrag ANNAEHERUNG_VERTRAG im Notizordner), Test `AnnaeherungTest` | `FahrzeugAktionen` |
| Brücke | `Bruecke`, `BrueckeVerbindung`, `BrueckeWeiterleitung`, `BrueckeLive`, `NoiseIK` | `BrueckeWeiterleitung` |
| Update-Hinweis am Display | `UpdateHinweis` (reine Regeln, auch Einwilligung Mobilfunk je Download; 0.1.38.8 `klein` = nur 4. Stelle neu → still von selbst in „korrekturen“/„automatisch“, groß mit Sammelliste `zusammenfassung`; einmalige Umstellung „melden“ → „korrekturen“, Marke `.appupdate-klein`, Test `UpdateKleinTest`; Nachlauf: Autostart endgültig aus → eine Meldung, Launcher-Update stellt den gewählten Startbildschirm wieder her `LauncherHome` nachUpdate, Test `NachUpdateTest`), Karte in `MessageOverlayService`, Schalter `update.hinweis`, `update.mobilfunkEinmal` | `UpdateHinweis` |
| Updater | `AppUpdateDienst`, `UpdateQuelle`, `Freigabe`, `UpdateRegel`, `ApkZertifikat`, `FernUpdate`, `AutostartNachUpdate`, `UpdateLifecycle`, `UpdateMeldung` (0.1.38.1: „installiert und läuft“ erst nach grüner Selbstprüfung, Launcher verfügbar/installiert, je Version einmal, Telegram UPDATE + Push `system.updates`), `AppUpdater` (Erbe; Versionsanzeige, Daemons stoppen, Installation abgekoppelt starten); zweites Paket Launcher-APK (`Freigabe.Launcher` aus der version.json, `LauncherRegel` (0.1.38.10 Mobilfunk = eine Quelle `UpdateRegel.wartetWlan`, Vertrag `LauncherMobilVertragTest`), `LauncherPaket`: `pm install -r`/`uninstall`/`am start`, nie Standard-Home), Karte `web/shared/launcher-karte.js` in `system.html`; 0.1.38.2 von außen (Telegram, Handy, Cockpit, Handy-Web) für Panoptes und Launcher `FernUpdate.launcherStand`/`launcherStarten`, `AppUpdateDienst.installierenOderVormerken`, Vertrag `docs/UPDATE_API_CLIENTS.md`; Launcher als Standard-Startbildschirm (0.1.38.7, Opt-in `launcher.startbildschirm`, Standard aus): `LauncherHome` (Shell: `set-home-activity` zurück auf BYD, `pm disable-user`; Komponenten schaltet die Shell auf API 29 nicht, den Alias `.HomeAlias` schaltet der Launcher selbst), `HomeWaechter`/`HomeWaechterRegel` (Rückfall: Schalter aus, Totmann 5 min, 2 Neustarts in 60 s), Wege `/api/launcher/v1/home`, `home/behalten`, `home/aus`; Launcher: `HomeSchalter`, `HomeAbgleich` | `AppUpdateDienst` |
| Fahrerdisplay-Appwahl | `TachoFaehigkeit`, `TachoNaviApp` (gemeinsame Freigabepolicy für Web/nativ/Automation; Freigabe ohne Probe nach Tacho-Hinweis; außerhalb von P nur Apps mit „auch beim Fahren“ (ab 0.1.38.1: nach dem EINMALIGEN Haftungshinweis jede freigegebene App; davor ab Werk `NAVI_PAKETE` und Musik/Radio/Podcast `AUDIO_PAKETE` bzw. erkannt über `AppLauncher.istAudioApp`, sonst je App nach eigenem Hinweis `FAHRT_HINWEIS_VERSION`, Video-Apps gekennzeichnet), andere stoppen beim Verlassen von P; `tachoNormal` = Knopf „Tacho wieder normal“, auch auf der Startseite), `TachoApiHandler`, Web auf der Seite `display.html` (Apps & Display → Display) + `web/shared/tacho-auswahl.js`, `tacho-appwahl.js`, `ProjectionFragment`, `ClusterProjectionController`, `ClusterCast`, `DiLink5ClusterCast`; geprüfte Totwinkel-Layer-Platzierung und der standardmäßig abgeschaltete stationäre ADB-Testhook in `BsNativeLayer` und `BsOverlayFailureTestHook`; Vertrag und physische Abnahme in `TACHO_APPWAHL_ABNAHME.md` (freie Apps auf DiLink 5 bis zum belegten Totwinkel-Restore fail-closed); Mehrfach-App-Analyse und hart inaktiver Policy-/Transaktionsunterbau in `TACHO_MEHRFACH_APPS_ANALYSE.md`, `TachoMehrfachLayout`, `TachoMehrfachZulassung`, `TachoMehrfachWechselPlan`, `TachoMehrfachPrioritaet`; Panoptes-Tacho-Ansicht als eigenes Ziel derselben Appwahl (`TachoAnsicht`, `TachoAnsichtActivity`, `TachoAnsichtManoever`, TCP-Befehl `tachoAnsicht` in `TcpCommandServer`, nur mittlerer Bereich, kein Video; fünf Designs Klar/Sport/Nacht/Horizont/Route aus einem Renderer `web/shared/tacho-designs.js`, ab 0.1.42 dazu 15 Galerie-Designs in `web/shared/tacho-galerie.js` (`TachoAnsicht.GALERIE`, Filter und Live-Vorschau in `tacho-auswahl.js`), am Fahrerdisplay als WebView ohne Netz in `tacho-ansicht.html`, in der Web-Oberfläche als Vorschau in `vehicle-control.html`; Routenskizze relativ zum Auto aus `NavGuidanceEngine`; ab 0.1.41 eigenes Design `eigen` aus `TachoEigen` (Erlaubnislisten, Tempo Pflicht, Kontrast ≥ 4,5:1), Editor „Tacho gestalten“ unter „Erweitert“ in `display.html` + `web/shared/tacho-gestalten.js`, ab 0.1.44 freier Editor (Format Version 2, Regeln `TachoFrei` = `PanoptesTacho.pruefeFrei`), siehe `TACHO_GESTALTEN.md`); „Was zeigt der Tacho?“ als EINE Auswahl `TachoAuswahl` (Designs, Navi- und freigegebene Apps, normal; startet nur über `TachoNaviApp.setze`/`tachoNormal`), Web `web/shared/tacho-auswahl.js` in `vehicle-control.html`, Recht `tacho` für Handy/Cockpit, nicht im Launcher, einmaliger Hinweis `tachoNavi.hinweisVerstanden` vor der ersten Freigabe bzw. dem ersten Start, „Was kann mein Tacho?“ (lesender Fähigkeitstest) auch als Knopf auf `display.html` (`web/shared/tacho-faehigkeit.js`), Rückmeldung zum Tacho-Test `TachoRueckmeldung` (nur mit Einwilligung je Sendung über `FehlerMelden`) | `TachoNaviApp` |
| Ladebelegung in der Navi | `navi.html` Umkreissuche, Stoppliste und Ladestopp über `web/shared/belegung.js` (Position nur auf 2 Nachkommastellen, Radius +1 km, enger nachfragen bei Ausdünnung, Feinsortierung im Gerät, „Stand vor x min“, ohne Daten keine Anzeige; Reiter „Günstigste in der Nähe“ rechnet Ladung + Hin- und Rückweg im Gerät, Lademenge `laden.standardKwh`, Verbrauch `verbrauchKwh100` aus `/status`; erster Preis ist der eigene „Laut Säule“-Preis je Betreiber und Stromart, `preis_eigen` setzt `NaviApiHandler.mitEigenenPreisen` über `LadePreisVorschlag.eigenePreiseEintragen`, nur im Auto); `NaviApiHandler` rundet `umkreis` zusätzlich; Ladestopp unterwegs `StoppBelegung` (`standVorS`); Zwischenstopps Start → Z1…Z5 → Ziel (↑/↓/×, Aufenthalt, „hier laden“, `via` wie Planer `via_lesen`) und Etappen aus `zwischenziele` + `stopps` über `web/shared/navi-etappen.js` (`NaviZwischenstoppsTest`) | `NaviApiHandler` |
| OEM-Navigationspfeile | `OemNavigationClient` → `TcpCommandServer` → `OemNavigationBridge`, `OemNavigationTransport` (Erlaubt-Liste nur 5/0 und 4, nie 1000 oder 2/3), `NaviInfoPayload`, `OemTurnIcon`; Opt-in `navMap.oemTurnGuidance`, Grenzen in `OEM_NAVIGATIONSPFEILE.md` | `OemNavigationBridge` |
| Fähigkeiten je Auto | `VehicleCapabilities` (belegte Werte bleiben über Neustarts gemerkt), `/api/system/capabilities` | `VehicleCapabilities` |
| Fahrzeugbefehle | `VehicleControlApiHandler`, `byd/routing/`, `VehicleActuatorBridge`, `VehicleActuatorService` | `VehicleControlApiHandler` |
| Ereignis-Clips | `EreignisClip`, `EreignisErkenner` (rein), `EreignisMonitor`, `EreignisAblage`, `EreignisMeldung`, `EreignisReceiver`; Auslöser je Clip in `AusloeserInfo` (Datei `<clip>.ausloeser.json` ohne Ort, auch für manuelle Clips, Feld `ausloeser` in `/api/recordings` und `/api/handy/v1/clips`, geht mit dem Clip) | `EreignisErkenner` |
| Daemon-Logs | `LogArchiv` (0.1.38.10: Hard-Reset verschiebt alle Logs und räumt EINMAL auf, im Aufräumen ein `stat` je Art; Ordner `logarchiv`: altes Log wird vor jedem Start umbenannt, je Art 7 Tage und 50 MB, `logs.tage`/`logs.mb`, bei wenig Platz enger), Shell-Seite in den Wächter-Skripten von `DaemonLauncher` und in `UpdateLifecycle`; Ereignis-Ringdatei `daemon_launcher.log` (`LauncherEreignisse`: Skript schreiben, Watchdog beenden, Post-Update-Reset, adb-Abbruch, Selbstheilung; 256 KB + `.1`; Zeilen der App gehen mit dem nächsten `AdbShellExecutor.executeScript` mit) | `LogArchiv` |
| Aufnahmen, Index | `RecordingsApiHandler`, `RecordingsIndex`, `RecordingModeManager`, `ClipExport`, `HandyClipsApiHandler`, `WaechterStandbild` | `RecordingsIndex` |
| Aufbewahrung, Speicher | `Aufbewahrung`, `StorageManager`, `ExternalStorageCleaner` | `Aufbewahrung` |
| Ziel aus Karten-Link | `ZielLink` (Google, Waze inkl. Geohash, `geo:`, OSM, Apple; Kurzlink nur auf Wunsch per HEAD, nur der Location-Kopf), `POST /api/navi/link` in `NaviApiHandler`, Suchfeld in `navi.html` | `ZielLink` |
| Kartensuche, Führungsroute | `ForwardGeocoder` (nur Kern `/api/navi/orte` + `/api/navi/link`, G3), Tempolimits der Route (0.1.44: Planer-Feld `tempolimits` je Bein → `NavRoute.tempolimits`, `Tempolimits` rein, `NavGuidanceEngine.tempolimitKmh`, `TempolimitMelder` meldet an den Kern; nur belegt, ohne Route kein Wert, ODbL in `THIRD_PARTY_NOTICES.md`), `ValhallaRouteClient` (Kern `/api/navi/fuehrung`, fuehrung/1, kein Schlüssel), `OverpassPoiClient`, `KartenSuchePrivat` (Schalter `karte.sucheInternet`, Position nur grob) | `KartenSuchePrivat` |
| Dateien Handy ↔ Auto | `Dateiablage` (eigener Ordner, max. 200 MB, Teile zu 2 MB, keine APKs, `NOFOLLOW_LINKS`, Löschen einzeln), `DateienApiHandler`, Seite `dateien.html`, Recht `dateien`; SD/USB `Wechselspeicher` (nur gemeldete Wechselspeicher, keine Symlinks, ohne Dashcam-Ordner, Löschen/Kopieren nur mit Rückfrage, für Kopplungen zu) | `Dateiablage` |
| Display-Werkzeuge (0.1.37) | Hintergrundbild `HintergrundRegel` (20 MB, nur jpg/png/webp, Inhalt geprüft; Video kein belegter Weg) und `Hintergrund` (App-Prozess, Android-Hintergrunddienst); Display-Sperre `ValetRegel` (rein: nur in P, weicht bei R/Fahrt, Kamera/Anruf, Neustart), `ValetSperreDienst` (Daemon, nur im Speicher, 5 Fehlversuche → Meldung `display.valet`), `ValetOverlay` (App-Prozess); Hinweis „Bildschirm wird geteilt“ `TeilenHinweis`, `TeilenHinweisRegel`; alles über die HMAC-Brücke `RemoteDevViewBridgeService`; Apps aus/an `AppSperre` (nur Nutzer-Apps, harte Sperrliste) in `AppsApiHandler`; Rückfrage-Marken `Rueckfrage`; Oberfläche `web/shared/display-werkzeuge.js` auf `display.html` (dort auch die Launcher-Karte) | `ValetRegel` |
| Bildschirm reinigen | `web/shared/reinigung.js` (30 s alle Berührungen schlucken, nur in P, lang drücken beendet, liest nur `/status`), Knopf in `system.html` | `reinigung.js` |
| Mitzählen | `Mitzaehlen` (Schalter `zaehlen.an`, Standard aus), `AutoAngaben`, `web/shared/zaehlen-frage.js` | `Mitzaehlen` |
| Weckwort-Messung | `WeckwortMessRegeln` (Schalter `sprache.weckwortMessung`, Standard aus), `WeckwortMessung` (Mikrofon, nur Zahlen) im eigenen Prozess `:weckwort` über `WeckwortMessDienst`, `WeckwortMessAnzeige`, `VoskErkenner`; Start nur über `WeckwortSteuerung` aus der JS-Brücke in `WebViewFragment`, Karte in `testfunktionen.html`, `WeckwortMessApiHandler` | `WeckwortMessung` |
| Fehler melden; Community-Karte (0.1.38.8: WhatsApp-Gruppe als Knopf + lokaler QR-Code, der Link steht nur in `app/src/main/assets/web/shared/community-karte.js`, Karte auf `fehler-melden.html` und `about.html`) | `FehlerMelden`, `RueckmeldungApiHandler`, Seite `fehler-melden.html`; 0.1.38.10 Modellabdeckung: Häkchen „Modell und Firmware mitschicken“ (Standard aus, `abdeckung`/`firmware` nur dann im Körper, `AutoAngaben.firmware`), Hilfe-Seite `auto-funktionen.html` (nur Anzeige) | `FehlerMelden`, `CommunityKarteTest`, `ModellMeldenTest` |
| Einstellungs-Katalog | `Katalog`, `LadenKatalog`, `Feld`, `Einstellungen`, `Energie`, `EinstellungenApiHandler` | `Katalog` |
| Gesamt-Konfiguration | `UnifiedConfigManager`, `Geheimnisse`, `QualitySettingsApiHandler` | `UnifiedConfigManager` |
| Einrichtung (Schnellstart, Liste „Noch einrichten“, 0.1.44 drei Empfehlungskarten `EMPFOHLEN` + eingeklappte „Weitere Schritte“, Selbsttest-Status `freiwillig`, 0.1.38.3 „noch einmal durchgehen“/Schritt öffnen: `wiederholen`, `POST /api/einrichtung/durchgehen|oeffnen`) | `EinrichtungApiHandler` (`SCHNELLSTART`, `PFLICHT`), `onboarding/schritte/` (0.1.38 freiwillige Schritte in `NeueSchritte.kt`), `HandySchritt`, `Selbsttest`, `SetupGuideDialog` | `EinrichtungApiHandler` |
| i18n | `assets/web/i18n/`, `assets/server-i18n/`, `Messages`, `LocaleManager`, `web/shared/lang-picker.js`, `res/values*/strings.xml`, `tools/sprache/` | Abschnitt 6 |
| Web-Seiten | `assets/web/local/`, Menü in `web/shared/app-shell.js`, Startprofile in `web/shared/home-layout.js` und `OBERFLAECHEN_MVP.md`, Auslieferung in `HttpServer`; Abschlussregeln der Startseite in `HomeDashboard0133Test`, Release-Nachträge in `ABSCHLUSS_0.1.33.md` und `RELEASE_0.1.34.md` | Abschnitt 4 |
| Telegram | `TelegramBotDaemon`, `daemon/telegram/` (Befehle, `CommandRouter`; 0.1.38.2 Hauptmenü `/menue` in `SystemCommandHandler`, Text ohne „/“ zeigt es, fester Knopf „☰ Menü“, `setMyCommands`; Updates `UpdateCommandHandler` mit Knöpfen `up:…`, Rückfrage vor jedem Start, nur über `/api/appupdate/fern`; 0.1.38.8 `KopplungenCommandHandler` `/kopplungen` mit Einmal-Griffen und Rückfrage), `TelegramNotifier` (`notifyUpdateNeu`: „neue Version“ mit Knopf), `TelegramSpool`, `UnifiedTelegramConfig`, `TelegramApiHandler`; `PollKonflikt` (0.1.38.7: HTTP 409 = Bot läuft auch woanders, Status `pollKonflikt`, telegram.html sagt es und verwirft eine PIN, die der Kern nicht mehr kennt); 0.1.38.8 `/aktionen` (Stand, `aus`, `automatik aus`, nie an); 0.1.38.8 `DienstHinweis` (Antwort, wenn der Hauptdienst nicht antwortet: in den ersten 5 min nach Bot-Start/Kopplung „startet noch“, bei Merker `camera_daemon.disabled` „ausgeschaltet“, sonst Ausfall ohne Fachwort) und `ErreichbarCommandHandler` (`/erreichbar`, Frage nach der Kopplung, setzt `autoStartAccOff`; Web-Gegenstück im Schritt „Fertig“ von `telegram.html`, Handy-Kopplung bietet in `system.html` die Brücke an) | `CommandRouter` |
| Meldungen | `NotificationStore`, `notifications/sinks/`, `NotificationApiHandler` | `NotificationStore` |
| Routenplaner | `PlanerVerbindung`, `PlanerRoute`, `AutoZustand`, `PlanerBackoff`, `Einstellungen`, Handy-Weg `HandyRoute` (route/route_stand; ab 0.1.38.6 `route {plan_id}` = genau diesen Plan per `an-auto {plan_id}` übernehmen, 410 → `plan_abgelaufen`) und `HandyPlanVorschau` (Handy-Aktion `plan_vorschau`/`plan_vorschau_stand`, Recht `route`: Planer `POST plan` mit Auto-Standort, `ausgabe:kompakt`, Ergebnis `plan-ergebnis/1` samt `plan_id` unverändert unter `ergebnis`, plan_id 2 h nur im RAM, 10 Planungen/min; Standort nie vom Handy); Fahrerdisplay-Karte über `ClusterMapProjector` nur nach zentralem, positivem `TachoFaehigkeit.karte`-Plattformurteil (Single-OS/Dienst/Shell/DiLink 5), bewusst nicht nach einer vor Aktivierung oft noch fehlenden VirtualDisplay-ID | `PlanerVerbindung` |
| Laden, Ladeplan | `LadenApiHandler`, `LadenVorlieben`, `LadeplanDienst`, `ChargingSessionManager`, `TariffManager` | `LadenApiHandler` |
| Ladekosten laut Säule | `LadeAbrechnung` (abgerechnete kWh: Eintrag laut Säule, sonst Akku-kWh plus Ladeverlust-Zuschlag `laden.verlustAc`/`laden.verlustDc`, nur beim Lesen), `LadeKosten`, Spalten `saeule_kwh`/`saeule_betrag` in `SocHistoryDatabase`; Eintrag per `POST /api/charging/{id}/saeule`; „Kosten ändern“ zählt als Betrag laut Säule; Fahrtpreis über `rateAbgerechnet` in `TripAnalyticsManager.satz`; einmaliger Hinweis `web/shared/ladeverlust-hinweis.js` (`laden.verlustHinweis`) | `LadeAbrechnung` |
| Ladesäule erkennen | `LadesaeuleZuordnung` (rein: gerundete Planer-Anfrage, Zuordnung ≤ 150 m, Stromart, kW × 1,15, „auto“ nur bei genau einer Säule < 60 m, Datenschutz, gemerkte Säule), `LadesaeuleDienst` (eine Anfrage je Ladeende, Knopf „nachtragen“, Schalter `laden.saeuleNachschlagen`), `LadePreisVorschlag` (Vorschlag „~“ je Betreiber und Stromart), Spalten `station_id`/`station_name`/`betreiber`/`station_art`/`zuordnung`/`station_kandidaten` in `SocHistoryDatabase`; `GET`/`POST /api/charging/{id}/station`, `POST /api/charging/stationen/nachtragen`; Oberfläche `web/shared/ladesaeule.js`; eigener Preis `LadePreisVorschlag` je Betreiber, Land UND Stromart, Land über `LadeLand` (ausdrücklich oder nur aus Kernbereich AT/DE/CH, sonst kein eigener Preis) | `LadesaeuleZuordnung` |
| Camping | `Camping`, `CampingRegler`, `CampingPlan`, `RouterHeizweg` | `Camping` |
| Tiermodus | `Tiermodus` (Laufzeit, Klima über `RouterHeizweg`, kein eigener Regler), `TiermodusRegel` (rein), `TiermodusApiHandler`, Seite `tiermodus.html`, Telegram `/tier` (`TierCommandHandler`: nur Stand und Gesehen), Handy `aktion: tiermodus` (Recht `klima`); ruht Erschütterung und Abschreckung | `TiermodusRegel` |
| Wallbox | `WallboxDienst`, `WallboxKonfig`, `WallboxSuche`, Adapter in `wallbox/`; Sitzungs-kWh der Brücke nur als Protokollzeile je AC-Ladung (`WallboxEnergieProtokoll`, nie im SoH-Anker) | `WallboxDienst` |
| Home Assistant | `HaApiHandler`, `HaDienst`, `HaEreignisse`, `HaZustand`, `HaMdns`; smarte Geräte (Vertrag v1.1 Teil D, Weg A, kein HA-Token im Auto) `HaGeraete` (rein: Sperrliste Alarm/Sirene/Melder, Domänen je Stufe, Freigaben Standard aus), `HaGeraeteSpeicher` (Datei ha_geraete.json, 0600); Stufe 2 Tor/Schloss steuern: `HaSteuern` (rein: Befehl b-id an genau die HA-Kopplung, Echo, 30 s Ablauf, Ratenlimit), `Heimkommen` (rein: Tor beim Heimkommen, nie bei Unklarheit), `HaSteuerDienst` (Takt, Befehls-Log + Meldung ohne Namen) | `HaApiHandler` |
| Regeln (Automation) | `Automations`, `AutomationQueue`, `Actions`, `Conditions`, `AutomationApiHandler` | `Automations` |
| KI-Sperre (REGEL A+) | `KiSperre` ist eine Erlaubnisliste, zentral vor jedem KI-Vorschlag, beim Ausführen und bei KI-Entwürfen: die KI darf über Regeln nur Klima, Sitzheizung, Sonnenrollo, Anzeigen und reinen Ablauf auslösen, rekursiv über Aktionsgruppe, Wenn/Schleife und „Regel einschalten“; neue Aktionen in `Actions` sind für die KI automatisch gesperrt (`KiSperreTest`); nur `GenAiAction`/`GenAiAutomation` rufen sie, Nutzerwege nie | `KiSperre` |
| Assistenzsysteme abschalten | `AssistenzHinweis` (Haftungshinweis wie Tiermodus: `HINWEIS_VERSION`, nur Version und Zeit; Ausschalten erst nach Bestätigung, Grund `hinweis_fehlt`, Einschalten immer, AEB nur ein `nur_einschalten`), Sperre in `VehicleCommandRouter.checkAssistenzHinweis` (beide Eingänge), Speichern in `AutomationApiHandler`, `KeymapApiHandler`, `CommunityApiHandler`; Log `BefehlsLog.assistenzAus` mit Herkunft; Web `web/shared/assistenz-hinweis.js` (Dialog, Kennzeichnung in `automations.html`); KI bleibt über `KiSperre` gesperrt; DMS/DDAW/ADDW ohne BYD-Weg, nicht gebaut | `AssistenzHinweis` |
| Fernzugang | `TailscaleLauncher`, `TailscaledProzesse`, `WireGuardLauncher`, `Notzugang`, `assets/notzugang/notzugang.sh` | `TailscaleLauncher` |
| Native Bibliotheken | `app/src/main/cpp/CMakeLists.txt` (`surveillance`, `dilink5_camera`, `fast_cam_release_guard`, `od`) | `CMakeLists.txt` |
| Lizenzen, Fachbegriffe | `LICENSE`, `THIRD_PARTY_NOTICES.md`, `LICENSES/`; `docs/GLOSSAR.md` (Pflicht für sichtbare Texte) | |
| Demo-Profil (Musterdaten für Bilder) | `DemoProfil` (aktiv nur Emulator bzw. Screenshot-Build UND `adb shell setprop debug.panoptes.demo 1`; Musterdaten fest unter `assets/demo/`, nichts gelesen oder geschrieben aus echten Daten; solange aktiv alles Schreibende und alle Fahrzeug-/Kamerawege 403), Band „Musterdaten“ in `web/shared/app-shell.js`, Musterkarte Wien ohne Netz `web/shared/demo-karte-wien.json` in `panoptes-map.js` (ab 0.1.38.5 mit Korridor Linz–Wien); 0.1.38.5 Muster auch für Planer (`POST /api/navi/plan` feste Antwort plan-ergebnis/1 Linz → Wien, `unterwegs`), Wächter/Aufnahmen/Dashcam (gezeichnete Bilder und Clips unter `assets/demo/medien/`, Generator `tools/demo_muster_medien.py`), Launcher-Zusammenfassung und Handy-App (Koppeln nur simuliert: `beantworteKopplung` vor `AuthApiHandler`, Demo-Schlüssel `beantworteGeraet` vor `AuthMiddleware`, nie ein echter Handler) | `DemoProfil` |

## 4. API-Routen

Alle laufen durch `HttpServer`. Rechte-Spalte: **Login** = `AuthMiddleware`. **Extern** = was Kopplungen
dürfen (Abschnitt 5). „Handy-Web“ = Web-Sitzung der Handy-App (`WebHandyGuard`). Steht in der Spalte kein
Extern, ist die Route für Kopplungen gesperrt.

### Ohne Login

`/auth/status`, `/auth/token`, `/auth/kopplung`, `/auth/wallbox-eingabe`, `/auth/logout`, `/login.html`,
`/manifest.json`, `/sw.js`, Symbole, statische Teile unter `/shared/`. Handler `AuthApiHandler`.

### Aufnahmen, Kamera, Wächter

| Route | Handler | Rechte |
|---|---|---|
| `/api/recordings`, `/video/`, `/thumb/`, `/api/events/` | `RecordingsApiHandler` | Login; Extern nur Liste, Tage, Zahlen und `/video/id/`, `/thumb/id/` mit Recht `kamera` |
| `/api/aufnahmen/behalten`, `/api/aufnahmen/freigeben`, `/api/ereignisse/` | `EinstellungenApiHandler` | Login |
| `/api/waechter/standbild/` | `WaechterStandbild` | Extern mit Recht `standbild` (oder `kamera`) |
| `/api/surveillance`, `/api/surveillance/safe-locations` | `SurveillanceApiHandler`, `SafeLocationApiHandler` | Login; Extern nur `status`, `gesundheit` lesen |
| `/api/stream`, `/api/bs/`, `/api/camview/` | `StreamingApiHandler` | Login |
| `/api/oem-dashcam/` | `OemDashcamApiHandler` | Login |
| `/api/parking`, `/parking/asset/` | `ParkingApiHandler` | Login |
| `/snapshot/`, `/status`, `/api/start/`, `/api/view/`, `/api/stop/`, `/api/stopall`, `/api/recording/mode`, `/h264/`, `/view/` | `HttpServer` selbst | Login |

### Fahrzeug

| Route | Handler | Rechte |
|---|---|---|
| `/api/vehicle`, `/api/vehicle/lock`, `/api/vehicle/flash`, `/api/system/ivi-reboot`, `/api/system/background-access` | `VehicleControlApiHandler` | Login; Extern nur `state`, `status` lesen; 0.1.38.8 `find-car`, `flash`, `warnblinker` (EIN) nur über `FahrzeugAktionen` (Schalter + `bestaetigt`, Handy-Web nur mit Recht `signal`), `aktionen` = Stand der Schalter; Handy-Web schreibend je Recht der Kopplung (`WebSitzungRechte`, Abschnitt 5) |
| `/api/quick-controls` | `QuickControlsApiHandler` | Login |
| `/api/keymap` | `KeymapApiHandler` | Login |
| `/api/assistenz/hinweis` | `AssistenzHinweis` | Login; Stand lesen, Haftungshinweis bestätigen; für Kopplungen zu |
| `/api/tyres/` | `TyreLimitsApiHandler` | Login |
| `/api/bydcloud` | `BydCloudApiHandler` | Login; Einrichten für Handy-Web gesperrt |
| `/api/gps` | `GpsApiHandler` | Login; Extern lesen, siehe `GpsFreigabe` |
| `/api/tacho/` | `TachoApiHandler` | Login; `navi/fahrt` und `groesse` (Größe im Fahrerdisplay, nur im Stand, eine Quelle `blindspot.clusterSizeProfile`, nie geraten) auch für Handy-Web gesperrt; `/api/tacho/auswahl` und `/api/tacho/normal` auch für Kopplungen der Art `handy`/`cockpit` mit Recht `tacho`, Handy-Web nur `auswahl` mit Recht `tacho`; `eigen` (0.1.41 Tacho gestalten, speichern nur im Stand) für Handy-Web gesperrt |
| `/api/positions` | `PositionsApiHandler` | Login |
| `/api/apps/` | `AppsApiHandler` | Login; `/api/apps/verwaltung` für Handy-Web gesperrt |
| `/api/display/` | `DisplayApiHandler` | Login; für Handy-Web gesperrt; `sperre/aus` am Display nur mit PIN |
| `/api/launcher/` | `LauncherApiHandler` | Login; Extern `/api/launcher/v1/summary` lesen; `vehicle/*` → 404; `apps/launch` mit `mode:"neben"` = Split neben dem Launcher, `mode:"split_ende"` (0.1.39) = Split verlassen, Launcher im Vollbild (`AppLauncher.splitBeenden`, nur der eigene Launcher-Task, nur von der Launcher-APK mit Panoptes-Signatur); einziger Fahrzeugweg `klima` (`LauncherKlimaBefehl`: sechs Klima-Aktionen, ab 0.1.38.6 dazu 14 feste Komfort-Aktionen: Sitzheizung/-lüftung je Sitz, Lenkrad, Umluft aus dem gelesenen Ist-Stand, Auto-Modus/Entfrosten/Nur-Gebläse als an/aus-Paar; nie Hupe, Licht, Blinker, Auf-/Zusperren, Fenster, Dach, Kofferraum, Spiegel, Sitzspeicher; Test `LauncherKomfortWegTest`); 0.1.44 (D1) zweiter enger Fahrzeugweg `fenster` (`LauncherFensterBefehl`: nur vl/vr/hl/hr/dach auf/zu, nur im Stand, Ratenlimit, Router-Fensterbefehl wie vehicle-control; Test `LauncherFensterBefehlTest`) und 0.1.44 (P4) zwei LESE-Wege nur `GET` und nur von der Launcher-APK mit Panoptes-Signatur: `haus` (`LauncherHaus`: Anzeigefelder der von der HA-Integration gemeldeten Geräte aus `HaGeraeteSpeicher`, höchstens 6 gewählte, kein Schalten, keine Zugangsdaten) und `sitz` (`LauncherSitz`: gespeicherte Sitzpositionen und die gerade passende aus `BodyworkSeatProbe.readFullBundle`, Zwischenspeicher 30 s, nie anwenden; Test `LauncherHausSitzTest`; Launcher `HausKachel.kt`, `Fahrerprofile.kt`, Test `LauncherP4Test`) und Summary `koerper` (Türen/Fenster/Dach, nur bekannte Werte); 0.1.44 (L1) `umgebung` GET/POST nur Launcher-APK (`LiveUmgebung`: Skizzen-Daten aus YOLO auf dem KI-Spur-Mosaik, ab Werk aus, nur bei sichtbarer Kachel, Vorrang Wächter, Leistungs- und Wärmesperre; Tests `LiveUmgebungTest`, Launcher `LauncherL1Test`) und `update/installieren` sowie „Tacho wieder normal“ `tacho/normal` nur von der Launcher-APK mit Panoptes-Signatur (`LauncherAufrufer`); 0.1.38 Layout `layout` (GET/PUT), `layout/standard`, `layout/apps` in der Datei launcher_layout.json (`LauncherLayoutSpeicher`, Prüfung `LauncherLayoutPruefung`, ab 0.1.44 E2 Version 2 mit feiner Lage/Stil/Text, Test `LauncherEditorKernTest`, 64 KB, neueste gewinnt, 409), Extern nur Art `handy`/`cockpit` mit Recht `launcher`, Handy-Web nur mit Recht `launcher`, nie Automationen; Seite `launcher.html` |
| `/api/about/vehicle-info` | `AboutApiHandler` | Login |

### Einstellungen, Konto, Kopplung

| Route | Handler | Rechte |
|---|---|---|
| `/api/einstellungen`, `/api/einstellungen/anzeige` (0.1.38.7: drei Werte fürs Auto-Display, Takt 8 s, nur bei sichtbarer Seite: Darstellung, Startansicht, `anzeige.thema` u. a.; Thema meldet `theme.js` per `AndroidBridge.setKernThema` an die App, dort `ui/util/KernThema.kt` vor der Native-Wahl) | `EinstellungenApiHandler` | Login; Extern lesen und schreiben, Felder `nurLesendExtern` nur lesen |
| `/api/kopplung/`, `/api/kopplungen`, `/api/sitzung/` | `EinstellungenApiHandler` | Login; Extern nur `/api/sitzung/erneuern`, `/api/sitzung/abmelden`; `/api/sitzung/rechte` (GET, 0.1.38.10) sagt der Oberfläche, welche Fahrzeug-Pfade die Web-Sitzung nicht darf (`app-shell.js` blendet aus); `kopplung/code` liefert ab 0.1.38.3 `brueckeEin` und `brueckeEinmalGrund` (`aus`/`kein_internet`/null), `system.html` sagt neben dem QR-Code, ob er unterwegs geht (QR-Feld `bruecke`) |
| `/api/kopplung/weiteres-geraet` | `WeiteresGeraetApiHandler` | Login |
| `/api/kopplung/passkey/`, `/api/kopplung/schluessel-besitzer` | `EinstellungenApiHandler` | Passkey nur direkt am Autobildschirm, Login aus der Ferne reicht nicht; 0.1.39 Schlüssel bestätigen nur Display/Login (keine Kopplung, keine Handy-Web-Sitzung): später gemeldeten Geräteschlüssel per Fingerabdruck für Besitzer-Aktionen bestätigen |
| `/api/planer/`, `/api/test/blitzer` | `EinstellungenApiHandler` | Login; Extern `/api/planer/identitaet` lesen |
| `/api/einrichtung` | `EinrichtungApiHandler` | Login |
| `/api/dateien` | `DateienApiHandler` | Login; Extern nur Art `handy` mit Recht `dateien`; Handy-Web nur mit Recht `dateien` |
| `/api/settings/` | `QualitySettingsApiHandler` | Login; Handy-Web ohne geheime Abschnitte |
| `/api/backup/` | `ConfigBackupApiHandler` | Login; für Handy-Web gesperrt; Geräteschlüssel ab 0.1.38.8 nur mit `?geraeteschluessel=true` |
| `/api/telegram/` | `TelegramApiHandler` | Login |
| `/api/telegram-anmeldung/` | `HttpServer` selbst | nur voller Login |
| `/api/cloud-uebergabe` | `CloudUebergabeApiHandler` | Login |
| `/api/uebernahme/` | `UebernahmeApiHandler` | Login |
| `/api/i18n/lang` | `HttpServer` selbst | Login |

### Laden, Fahrten, Energie

| Route | Handler | Rechte |
|---|---|---|
| `/api/trips` | `TripApiHandler` | Login; Extern `/api/trips`, `summary`, `range`, `datei` lesen, Handy auch `{id}/gps` (`?max=N`, gerundet); `verbrauch-temperatur`, `standverlust` nur Login |
| `/api/charging` | `ChargingApiHandler` | Login |
| `/api/laden` | `LadenApiHandler` | Login; Extern lesen, Vorlieben und Nachfrage schreiben |
| `/api/ladeplan` | `LadeplanApiHandler` | Login; Extern lesen und schreiben, Freigabe nur am Auto |
| `/api/camping` | `CampingApiHandler` | Login; Extern lesen und schreiben |
| `/api/tiermodus` | `TiermodusApiHandler` | Login; Einschalten nur direkt am Auto; für Kopplungen und Automationen gesperrt |
| `/api/wallbox/` | `WallboxApiHandler` | Login; Extern `status` lesen |
| `/api/abrp/` | `AbrpApiHandler` | Login |
| `/api/performance` | `PerformanceApiHandler` | Login |

### Anbindungen

| Route | Handler | Rechte |
|---|---|---|
| `/api/handy/v1/` | `HandyApiHandler` | nur Kopplung Art `handy`, Rechte je Aktion im Handler; `passkey`, `passkey/start` nur bei offenem Fenster, `passkey/nur-passkey` sperrt den alten Schlüssel-Weg; `kopplung/*`, `besitzer/*`, `dienst`, `waechter` nur mit `besitzer` + `bestaetigt` (`BesitzerApiHandler`), ab 0.1.39 `kopplung/code`, `anfragen/entscheiden` (erlauben), `kopplung/entfernen`, `besitzer/*` zusätzlich mit Signatur (`BesitzerSignatur`, Gründe `signatur_noetig`/`schluessel_fehlt`/`schluessel_unbestaetigt`/`signatur*`); Art `pc` nur `bruecke`, `bruecke/aus`, `websitzung`; `/api/web/manifest` (`WebManifest`, sha256 von `/shared/` und `/i18n/` aus der APK, jede Kopplung lesend) |
| `/api/handy/v1/clips` | `HandyClipsApiHandler` | Art `handy` mit Recht `kamera` |
| `/api/handy/v1/protokoll` | `HandyApiHandler` | GET, Art `handy`: eigene Einträge, alle nur mit Recht `protokoll`; max. 200, eigenes Ratenlimit |
| `/api/ha/v1/`, `/api/ha/geraete` | `HaApiHandler` | Extern `zustand`, `regeln`, `ereignisse`, `aktion`; `geraete` (GET/POST, nur homeassistant + Recht `geraete`), `geraete/echo` (POST, Recht `geraete_steuern`); `/api/ha/geraete` Anzeige, `/freigabe`, `/befehl`, `/automatik` nur Login, Öffnen/Aufsperren nur am Autobildschirm |
| `/api/bruecke` | `BrueckeApiHandler` | Login; für Handy-Web gesperrt |
| `/api/mqtt/` | `MqttApiHandler` | Login; Extern `telemetry` lesen |
| `/api/community/` | `CommunityApiHandler` | Login |
| `/api/genai/` | `GenAiApiHandler` | Login |
| `/api/gewohnheiten` | `GewohnheitenApiHandler` | Login; Extern `karte`, `antwort` |
| `/api/automations`, `/api/action-groups` | `AutomationApiHandler` | Login |
| `/api/notifications`, `/api/push` | `NotificationApiHandler` | Login |
| `/api/navi/` | `NaviApiHandler` (0.1.39 `fuehrung` → Planer `fuehrung/1`, Schlüssel wie `plan`; 0.1.44 `POST /api/navi/tempolimit {kmh|null}` nur vom Auto selbst, setzt `TempolimitStand`, Quelle osm) | Login; Extern `unterwegs` lesen; 0.1.38.10 Art `handy` mit `route`: `umkreis` (POST, Position setzt das Auto) |
| `/api/navmap/` | `NavMapApiHandler` | Login |
| `/api/orte` | `OrteApiHandler` | Login; 0.1.38.10 Art `handy` mit `route`: GET nur Favoriten ohne Koordinaten, als Ziel `{ort_id}` (`HandyRoute.aufloesen`, Koordinaten nur zum Planer) |
| `/api/roadsense/` | `RoadSenseApiHandler` | Login |
| `/api/ble/` | `BleApiHandler` | Login |

### System, Netz, Sonstiges

| Route | Handler | Rechte |
|---|---|---|
| `/api/system/` | `SystemApiHandler` | Login; Extern `capabilities`, `daemon` lesen; `zugriffe` nie für Kopplungen und Handy-Web; `befehle` nie für Kopplungen, Handy-Web nur eigene (alle mit `protokoll`) |
| `/api/demo` | `DemoProfil` | Login; Extern lesen; nur der Stand `aktiv` für das Band |
| `/api/weckwort-messung`, `/api/sprache/v1/befehl` | `WeckwortMessApiHandler` (nur direkt am Autobildschirm, nur lesen); `SprachApiHandler` (nur die Panoptes-App direkt am Auto, Fahrzeugbefehle nur mit Rückfrage-Marke) | siehe links |
| `/api/appupdate/` | `AppUpdateApiHandler` | Login; Extern `status` lesen; Handy-Web nur mit Recht `update`; 0.1.38.2 `/api/appupdate/fern` (GET Stand, POST Panoptes/Launcher starten bzw. vormerken) für Art `handy`/`cockpit` und Handy-Web, Starten nur mit Recht `update` und `bestaetigt`; `/api/appupdate/hinweis` und `/api/appupdate/launcher` (schreibend) nur am Auto oder voller Login; `/api/appupdate/launcher` lesen und nur `oeffnen` auch für Kopplung `handy`/`cockpit` bzw. Handy-Web mit Recht `launcher` |
| `/api/update/` | `HttpServer` selbst | stillgelegt, antwortet 410 |
| `/api/rueckmeldung/` | `RueckmeldungApiHandler` | Login; für Kopplungen doppelt gesperrt; 0.1.38.14 `fehler-telegram` (POST, nur lokal) für Telegram `/fehler` (`FehlerCommandHandler`); UI-Hänger: `ui/UiHaengerWaechter` |
| `/api/neuigkeiten` | `NeuigkeitenApiHandler` | Login; Extern lesen |
| `/api/bericht/` | `BerichtApiHandler` | Login; Extern `monat` lesen |
| `/api/hotspot` | `HotspotApiHandler` | Login; für Handy-Web gesperrt |
| `/api/wireguard` | `WireGuardApiHandler` | Login; für Handy-Web gesperrt |
| `/api/tailscale/https` | `TailscaleHttpsApiHandler` | Login; für Handy-Web gesperrt |
| `/api/notzugang`, `/api/zugang/code` | `NotzugangApiHandler`, `ZugangCodeApiHandler` | Login; für Handy-Web gesperrt; `zugang/code` (0.1.38.14, Karte „Dein Zugangscode“ in `system.html`) nur Display-WebView der App (App-UID), no-store |
| `/api/storage/external` | `ExternalStorageApiHandler` | Login |
| `/api/models/` | `ModelsApiHandler` | Login |
| `/api/audio/`, `/api/audio/library/raw` | `AudioApiHandler` | Login |
| `/api/audio/engine-sound`, `/api/audio/avas-tone` | `VehicleSoundApiHandler` | Login; nur bestätigte Fahrzeugfähigkeit und P, Stop bleibt sicher erreichbar |
| `/api/communicate/` | `RemoteCommunicationApiHandler` | Login |
| `/api/dev-view/` | `RemoteDevViewApiHandler` | Login; für Handy-Web gesperrt |

### Debug

Lesend, schreibende Wege sperrt `DebugSchreibsperre` im ausgelieferten APK.

| Route | Handler |
|---|---|
| `/api/debug/autoservice` | `AutoServiceDebugApiHandler` |
| `/api/debug/car-property` | `CarPropertyDebugApiHandler` |
| `/api/debug/light/` | `LightDebugApiHandler` |
| `/api/debug/seat/` | `SeatDebugApiHandler` |
| `/api/debug/telenav/`, `/api/telenav/` | `TelenavDebugApiHandler` |
| `/api/debug/adas/` | `AdasDebugApiHandler` |
| `/api/debug/bs-spike` | `HttpServer` selbst |

Weitere Handler ohne eigene Route in `HttpServer`: `AudioTestApiHandler`.

### WebSockets (für Kopplungen gesperrt)

`/ws` (`H.264`), `/ws/communicate` (`RemoteCommunicationWebSocket`), `/ws/cabin-audio` (`CabinAudioWebSocket`),
`/ws/genai` (`GenAiVoiceWebSocket`), `/ws/genai/chat` (`GenAiChatWebSocket`), `/ws/dev-view`
(`RemoteDevViewWebSocketStream`).

### Web-Seiten

Unter `assets/web/local/`, ausgeliefert durch `HttpServer`; gemeinsames Menü in `web/shared/app-shell.js`. `SECTIONS` dort ist die eine Seitenliste (7 Bereiche, `teil` = ein Reiter für mehrere Seiten, `VERSTECKT` = ohne Menü); Leiste, Reiter, Titel und Suche lesen nur sie (`MenueEineQuelleTest`). **Modus „Einfach / Alle Funktionen“ (E4, 0.1.44):** jede Seite in `SECTIONS` hat `stufe` (`a` braucht jeder, `b` mancher, `c` Experten). Im Modus „Einfach“ zeigen Leiste und Reiter nur `a`-Seiten (die offene Seite immer) und in der Leiste gut sichtbar „Alle Funktionen“; Einstellungen (`einstellungen.js`) blenden Katalog-Felder mit `erweitert` aus und zeigen stattdessen „Alle Funktionen zeigen“. Quelle: Katalog-Feld `anzeige.umfang` (`einfach`/`alle`, ohne Wert `alle`), an die Oberfläche über `/api/system/profile` → `umfang`, umschalten per `PUT /api/einstellungen` und Karte `system.html#umfang`. Festgeschrieben einmal beim ersten App-Start (`PreferencesManager.festschreibenUmfang`, gleiche Bestandserkennung wie Tailscale): Neuinstallation `einfach`, Bestand `alle` – nie still umschalten. Fixrunde 0.1.43.2: Die App schreibt mit Zurücklesen und Wiederholung; zusätzlich schreibt der Kern bei frischer Neuinstallation (`Erststart.frisch`: Einrichtung `neu` und nicht fertig, oder noch keine Spur früherer Nutzung) „einfach“ selbst fest, nie bei Bestand; dieselbe Erkennung blendet „Neu in …“ bei Neuinstallation aus (`NeuigkeitenApiHandler`). Die Suche hat Stichwörter in 5 Sprachen für Einstellungen ohne Schema-Feld (`STICHWOERTER`: Benachrichtigung, Pfeile, Waze, Overlay). Seitensuche und Direktlinks finden alle Seiten, Feld-Treffer zeigen ihr Feld auch im Modus „Einfach“ (`pn.sprungFeld`). Notzugang und Fernzugang (`system.html`), Launcher-Seite (`Alles zurück auf BYD`) und Rechtshinweise (Über) sind `a`. Test `ModusEinfachTest`.

`about.html`, `abrp.html`, `apps.html`, `assistant.html`, `auto-funktionen.html`, `automations.html`, `bericht.html`, `byd-cloud.html`, `camping.html`,
`charging.html`, `communicate.html`, `dateien.html`, `display.html`, `events.html`, `fehler-melden.html`, `index.html`, `key-mapping.html`, `ladeplan.html`, `launcher.html`, `launcher-fahrzeug.html`,
`live-view.html`, `login.html`, `meldungen.html`, `mqtt.html`, `navi.html`, `network.html`, `notifications.html`,
`orte.html`, `parking.html`, `performance.html`, `recording.html`, `remote-dev-view.html`, `road-sense.html`,
`seat-positions.html`, `surveillance.html`, `system.html`, `tacho-ansicht.html` (nur Fahrerdisplay), `telegram.html`, `testfunktionen.html`, `tiermodus.html`, `trips.html`,
`vehicle-control-3d-test.html`, `vehicle-control.html`, `waechter.html`, `wallbox.html`, `wireguard.html`

`waechter.html` ist die Übersicht, `surveillance.html` sind die Wächter-Einstellungen. `testfunktionen.html` bleibt absichtlich hinter sieben schnellen Tipps auf die Version unter „Über“ verborgen. `web/shared/zusatzknoepfe.js` (0.1.38) hängt Knöpfe auf schon vorhandene Endpunkte ein: Not-Aus Sprechen, Ladekurve, Zugriffe je Gerät. `vehicle-control-3d-test.html` ist ein interner 3D-Diagnose-Sandkasten ohne Menüeintrag und keine normale Oberfläche. `launcher-fahrzeug.html` zeigt nur das 3D-Fahrzeug aus dem Modellkatalog für die Fahrzeug-Kachel der Launcher-APK (`:panoptes-launcher`), ohne Menü und ohne Daten außer Modell und Farbe.

## 5. Rechte in Kürze

Reihenfolge in `HttpServer`: `AuthMiddleware` prüft Login, dann `ExternGuard` (nur scope `kopplung`), dann
`WebHandyGuard` (nur scope `handyweb`), dann `WebSitzungRechte` (nur `handyweb`), dann `SchreibProtokoll`. `BefehlsLog.beginne` steht vor den Wächtern, damit
auch Ablehnungen mitkommen; die Zeile schreibt `BefehlsLog.abschliessen` im `finally`.

- `ExternGuard` ist eine **Erlaubnisliste**. Neue Pfade sind für Kopplungen automatisch zu. Wer öffnen will,
  trägt in `LESEN`, `SCHREIBEN`, `HANDY_SCHREIBEN`, `HANDY_LESEN` oder `PC_SCHREIBEN` ein und ergänzt `ExternGuardErlaubnislisteTest`.
- Art `dienst` bekommt nur `DIENST_LESEN`, `DIENST_SCHREIBEN` und das Standbild.
- `noetigesRecht` regelt `kamera` und `standbild`. `kamera` schließt `standbild` ein, nie umgekehrt.
- `WebHandyGuard` ist eine Sperrliste: Zugang, Netz, Sicherung, Updates; ab 0.1.38.7 auch alle Auslöser von außen Sicht-/Hörbarem (`aussenAusloeser`: flash, find-car, unlock, hazard, BYD-Cloud-Test, Taste/Schnellknopf/Regel testen, Aktionsgruppe, Licht-Diagnose; Test `WebHandySignalSperreTest`); ab 0.1.38.8 nur flash, find-car, warnblinker per POST mit Recht `signal` (`signalErlaubt`), der Rest bleibt zu. Neue geheime Bereiche dort eintragen,
  neue geheime Konfig-Abschnitte in `GEHEIME_ABSCHNITTE`. Ab 0.1.38.10 regelt `WebSitzungRechte` die schreibenden Fahrzeug-Wege der Web-Sitzung (`/api/vehicle`, Camping, Navi, Fahrzeug-Ton, IVI-Neustart) als Erlaubnisliste je Recht, eine Quelle mit der Handy-App (`fuerHandyAktion`): Fenster, Fahrzeug-Einstellungen und System nie, außen Sicht-/Hörbares mit Rückfrage (409 `aktion_bestaetigen`), Kofferraum nur mit `aufsperren` und Passkey (Zweck `kofferraum`); neue Pfade dort einordnen (`WebSitzungRechteTest`).
- `Geheimnisse` maskiert Tokens und Schlüssel beim Lesen der Gesamt-Konfiguration.
- `Kopplungen` setzt `kamera` und `standbild` nie automatisch, neue Kopplungen auch nie `aufsperren`, `wallbox`, `laden` (0.1.38.10, Bestand mit `web` behält es einmalig, Rechte-Stand 4), `update`, `protokoll`, `dateien`, `tacho`, `launcher`, `signal` (auch Bestand nie `protokoll`, `dateien`, `tacho`, `launcher`, `signal`; `launcher` und ab 0.1.38.2 `update` trägt außer Handy nur das Cockpit, `COCKPIT_RECHTE`; `geraete` und getrennt `geraete_steuern` nur Home Assistant, `HA_RECHTE` mit `einstellungen`, Standard aus). 0.1.38.8: `besitzer` (Bündel `BESITZER_BUENDEL` push/web/route/update/einstellungen, nie Fahrzeug-Aktionen; `BESITZER_SELBST` schaltet er nur für sich selbst ein; `NUR_DISPLAY_RECHTE` = Fahrzeug-Aktionen + `geraete_steuern` vergibt nur der Autobildschirm, Fehler `recht_nur_display`) bekommt automatisch nur die erste Display-Kopplung (0.1.38.9: beim Update kein automatischer Besitzer, sondern einmal die Frage „Welches Gerät ist dein Handy?“, `/api/kopplung/besitzer-frage`, nur Display/Login); `einstellungen` Standard aus, Bestand behält Schreibrecht (`einstellungenBestand`); `Feld.nieExtern` (Update-Quelle/-Kanal/-Token, URLs, memberToken) nie über Kopplungen; Art `pc` mit `PC_RECHTE`. 0.1.39: `schluesselBesitzer` (Schlüssel darf Besitzer-Aktionen signieren) nur beim Koppeln (`KOPPEL_FENSTER_MS` 10 min), Passkey am Auto, Bestand (Marke `stand0139sig`) oder Bestätigen am Display; `schluesselLoeschen` nimmt es weg.
  Beim Erlauben gibt es sie nur per Häkchen plus Rückfrage in `system.html`, Display und Telegram senden keine.
- Am Auto gilt: Fahrzeugbefehle laufen nie über `/api/einstellungen`. Außen Sicht-/Hörbares (Hupe, Licht, Warnblinker EIN, Auto finden, Aufsperren) läuft nur durch `FahrzeugAktionen.ausloesen` (Schalter + Rückfrage im Moment); Regeln, MQTT, HA, Tasten, KI und Telegram lösen nie aus, der Router sperrt sie (`aktion_nur_nutzer`).

## 6. Dateien mit Fallen

| Datei | Falle | So geht es |
|---|---|---|
| `HttpServer.java` | Größte Konfliktstelle: Parallele Zweige hängen ihre Route an dieselbe Stelle. | Beim Merge beide Zeilen behalten. Reihenfolge zählt: `startsWith` trifft den ersten passenden Zweig, spezielle Pfade müssen vor allgemeinen stehen. |
| `SurveillanceApiHandler.java`, `VehicleControlApiHandler.java`, `StreamingApiHandler.java` | Sehr groß. | Gezielt mit Grep suchen, nie ganz lesen. |
| `assets/web/i18n/*.json`, `assets/server-i18n/*.json` | Jeder hängt neue Schlüssel ans Dateiende bzw. Gruppenende, das gibt Merge-Konflikte und vergessene Kommas. Im Arbeitsbaum Windows-Zeilenenden, im Repo LF. | Neue Schlüssel in die fachliche Gruppe, immer alle 5 Sprachen (`de`, `en`, `fr`, `it`, `nl`). Nach dem Merge alle 10 Dateien als JSON prüfen. |
| `server-i18n/it.json`, `fr.json` | Apostroph bricht das Java-MessageFormat. | Platzhalter-Texte mit `MessageFormatSafe`; siehe `ServerI18nPlaceholderRenderingTest`. |
| `web/shared/core.js` | Lädt nur die gewählte Sprache, Englisch erst bei Lücken. | `I18nVollstaendigTest` verlangt vollständige Kataloge. |
| `deploy/update-on-car.sh` | Liegt doppelt, muss byte-gleich sein mit `assets/deploy/update-on-car.sh`. | Immer beide ändern (`AppUpdateTest` prüft). |
| `app/build.gradle.kts` | Debug-Build ist der Auslieferungs-Build, nicht debuggable. `ENTWICKLUNG` statt `BuildConfig.DEBUG`. | `NichtDebuggableTest` prüft. |
| Java-Quelltext allgemein, `assets/web` | Auto = Android 10, minSdk 28. Instanzmethoden wie `InputStream.readAllBytes()` fehlen dort. **Web-UI-Ziel = Chrome 74** (Firmware-WebView, kein Update): kein `?.`, `??`, `replaceAll`, `inset`, `:is()`, Selektorliste mit `:focus-visible`; `min()`, `clamp()`, `color-mix()`, `appearance` nur mit Rückfall davor; dünnes Glas (`backdrop-filter`, Hintergrund unter 0,9) braucht direkt danach einen `@supports not (backdrop-filter …)`-Block mit dichterer Fläche. | `MinSdkApiTest` verbietet sie; `Chromium74WebTest`, `CssInsetRueckfallTest` und `BackdropErsatzTest` prüfen alle Web-Dateien. |
| `surveillance/` | Fehlalarme vermeiden hat Vorrang, Änderungen brauchen die Invarianten. | `DETECTION-INVARIANTS.md` |
| `Einstellungen` | Texte im Schema kommen aus den Katalogen. | `SchemaTexteAlleSprachenTest` |
| `DaemonLauncher.kt` | Watchdog-Skripte stecken als Zeichenketten im Code; Pfade sind am Auto heikel. Ein Log nie mit `>` öffnen oder auf null kürzen. | Nicht nebenbei umbauen. Logs über `LogArchiv` (`LogArchivWatchdogTest`). Skripte nie direkt ins Ziel schreiben: eigene Temp-Datei, `sh -n` + Zeilenzahl, dann `mv` (`buildSkriptEinsetzenCommand`, `SkriptAtomarSchreibenTest`). |
| `BefehlsLog.java` | Liest aus dem Rumpf nur Felder der Erlaubnisliste. Ein neues Feld kann Werte, Schlüssel oder Orte ins Log bringen. | Nur Kennwörter und Namen, nie Werte; `BefehlsLogTest` prüft Geheimnisse und Koordinaten. |
| Interne Namen | Paket, Config-Schlüssel, API-Pfade, Ports nie umbenennen. | Sonst Update- und Aussperr-Gefahr. |

## 7. Bauen, Testen, Signieren

Voraussetzung: JDK, Android-SDK, `local.properties` mit `sdk.dir`. Neuer Worktree: Datei aus dem
Hauptcheckout kopieren, sie ist nicht im Git.

```bash
./gradlew --offline :app:assembleDebug
```

Nur arm64. Der Debug-Build ist die Auslieferung und kommt danach durch die Signatur.

**Einzeltest:**

```bash
./gradlew --offline :app:testDebugUnitTest --tests "*ExternGuardErlaubnislisteTest"
```

**Volle Suite:**

```bash
rm -rf app/build/test-results/testDebugUnitTest
./gradlew --offline --continue :app:testDebugUnitTest
```

Die Suite ist nie ganz grün. Auswertung: Aus `app/build/test-results/testDebugUnitTest/TEST-*.xml` alle
`failure`/`error` als `Klasse#methode` sortiert auflisten und gegen die Rot-Liste des Basis-Stands vergleichen
(liegt im Koordinations-Ordner, `<version>_rot_<commit>.txt`). Maßstab ist **0 neu rot**, nicht 0 rot. Vor einem
`clean` auswerten. Der lokale Testnachtrag nach dem Freeze von 0.1.33 ist in `ROT_LISTE_BEFUNDE.md`
klassifiziert; die Freeze-Zahlen bleiben der historische Stand des Release-Zeitpunkts. Öffentlich: `docs/ROT_LISTE.md` (ab 0.1.38.9), Release-Notizen „So wurde geprüft“ aus der Vorlage `deploy/notes-vorlage.md`; Interna (Zweige, Rollen) nie dort, nur in `INTERN_*.md`.

**Signieren** (nur Release, Ablage siehe `deploy/signatur/README.md`):

```bash
powershell -File deploy/signatur/mit-release-schluessel.ps1 bash deploy/signatur/apk-signieren.sh <ein.apk> <aus.apk>
```

Nur Schema v3 mit `deploy/signatur/panoptes.lineage`. `deploy/signatur/apk-signieren.sh` bricht ab, wenn der
Unterzeichner nicht stimmt. Ein Schlüsselverlust sperrt alle Installationen aus.

**Git-Regeln:** Commit mit `git commit -F <datei>`, nie `-a`. Push nur hinter Gitleaks. Der Park-Hook prüft jeden
Commit und Push auf private Koordinaten; bei Treffer den Wert ersetzen, nie umgehen.

**Deploy:** `deploy/run-update.sh` startet von außen, `deploy/update-on-car.sh` läuft am Auto mit Sperre und
30-Minuten-Totmannschalter, `deploy/rollback-on-car.sh` ist der Rückfall, `deploy/autostart-check.sh` prüft die
Autostart-Freigabe, `deploy/release-veroeffentlichen.sh` veröffentlicht. CI: `.github/workflows/release.yml` baut
bei Tag `v*` und schreibt mit `deploy/version-notizen.py` `notizen` und bei großen Versionen `notizenSeitGross` (≤ 8, optional `seitGross` im Eintrag) aus `neuigkeiten.json` in die version.json. Nur die Deploy-Sitzung spielt etwas aufs Auto.

## 8. Karte pflegen

Wer Dateien anlegt, verschiebt, löscht oder Routen ändert, zieht diese Karte im selben Commit nach.
`CodekarteAktuellTest` schlägt fehl, wenn

- ein Paket hier nicht in Backticks mit Schrägstrich am Ende steht,
- eine Klasse `*ApiHandler` fehlt,
- ein `/api/…`-Pfad aus `HttpServer.java` fehlt,
- eine Seite aus `assets/web/local/` fehlt,
- eine genannte Datei, ein Ordner oder eine Klasse nicht mehr existiert,
- die Stand-Zeile nicht zur `versionName` in `app/build.gradle.kts` passt,
- Zeilennummern, IPs, private Hosts, Pfade oder Koordinaten auftauchen,
- die Karte länger als 450 Zeilen wird.

Beim Versionssprung: Stand-Zeile anpassen, Commit = letzter Stand vor dem Versions-Commit.
