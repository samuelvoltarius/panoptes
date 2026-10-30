# Codekarte Panoptes

**Stand: Version 0.1.32.1, Commit d7dbb3f0**

Wegweiser durch den Code. Zuerst hier suchen, dann gezielt die genannte Datei öffnen.
Die Karte nennt Dateien und Klassen, keine Zeilennummern. Kurze Pfade gelten ab
`app/src/main/java/at/greenchili/surfmate/` (Java/Kotlin) bzw. `app/src/main/assets/`.

`CodekarteAktuellTest` hält die Karte ehrlich, siehe „Karte pflegen“ am Ende.

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

`AacIngestServer` und `BydEventDaemon` tragen beide 19978 im Code. Weitere interne Ports:
`TelenavIpcServer` 19982, `RemoteDevViewBridgeService` 19981, `CellularRelay` 8221, `WebSocketStreamServer` 8987.

App-seitige Hilfsdienste stehen in `services/`, darunter `VehicleActuatorService` (schreibt mit echter App-UID,
Daemon-Seite ist `VehicleActuatorBridge`) und `KeepAliveAccessibilityService`.

`ui/daemon/` und `daemon/management/` zeigen und steuern die Daemons aus der App.

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
| `byd/`, `byd/bodywork/`, `byd/cloud/`, `byd/cloud/crypto/`, `byd/dilink5/`, `byd/light/`, `byd/radar/`, `byd/routing/` | BYD-Zugriff: Daten lesen, Befehle, Cloud, DiLink 5 |
| `camera/`, `camera/dilink5/` | Kamera-Zugriff, Panorama, Dashcam-Pipeline |
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
| `navmap/`, `navmap/nav/` | Karte, Navigation |
| `network/` | Hotspot, Mobilfunk-Relais |
| `neuigkeiten/` | „Was ist neu“ |
| `notifications/`, `notifications/push/`, `notifications/sinks/` | Meldungen und Zustellwege |
| `notzugang/` | Notzugang |
| `od/` | Binding an `libod.so` |
| `onboarding/`, `onboarding/schritte/` | Einrichtungs-Assistent (nativ) |
| `orte/` | Gespeicherte Orte |
| `overlay/` | Status-Band, Meldungs-Overlay, Einrichtungs-Dialog |
| `parking/`, `parking/signage/` | Parken, Schilder lesen |
| `planer/` | Routenplaner-Anbindung |
| `power/` | 12-V-Spannung, Standby, Warmhalten |
| `proximity/` | Annäherung per Radar |
| `receiver/` | Broadcast-Empfänger |
| `recording/` | Aufnahmemodus, Ereignis-Clips |
| `reichweite/` | Reichweiten-Wacht |
| `remote/` | Fernansicht |
| `rueckmeldung/` | Fehler melden, Auto-Angaben fürs Mitzählen |
| `roadsense/`, `roadsense/config/`, `roadsense/detect/`, `roadsense/label/`, `roadsense/overlay/`, `roadsense/sidecar/`, `roadsense/source/`, `roadsense/store/`, `roadsense/sync/`, `roadsense/warn/` | Straßenzustand |
| `server/` | HTTP-Server, API-Handler, Guards, IPC |
| `services/` | Android-Dienste der App |
| `shell/` | Shell-Rechte, Hidden-API |
| `storage/` | Speicher, Aufbewahrung |
| `streaming/` | Video-Stream |
| `surveillance/` | Wächter-Kern (siehe `DETECTION-INVARIANTS.md`) |
| `tacho/` | Tacho-Fähigkeitstest, Navi-App am Tacho |
| `telegram/`, `telegram/config/`, `telegram/event/`, `telegram/impl/`, `telegram/model/` | Telegram: Versand, Konfiguration, Kopplung |
| `telemetry/` | Overlay-Daten für Aufnahmen |
| `telenav/` | Telenav-Anbindung |
| `trips/` | Fahrten, Fahrtenbuch-Daten |
| `uebernahme/` | Overdrive-Übernahme |
| `ui/`, `ui/about/`, `ui/adapter/`, `ui/daemon/`, `ui/dashboard/`, `ui/dialog/`, `ui/fragment/`, `ui/fragment/settings/`, `ui/model/`, `ui/navigation/`, `ui/privacy/`, `ui/setup/`, `ui/util/`, `ui/vehicle/`, `ui/view/`, `ui/viewmodel/`, `ui/widget/` | Native Oberfläche, vieles davon WebView-Hülle |
| `updater/` | Installationsablauf nach Update |
| `util/` | Hilfen, `DaemonStorage` |
| `wallbox/` | Wallbox-Anbindung |
| `weather/` | Außentemperatur |
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
| Kopplungen und Rechte | `Kopplungen` (Arten, Rechte `RECHT_*`), `KopplungsAnfragen`, `KopplungsMelder`, `PairingCodes`, `WeiteresGeraet` | `Kopplungen` |
| ExternGuard | `ExternGuard` (Erlaubnisliste Kopplungen), `WebHandyGuard` (Web-Sitzung Handy), `AuthMiddleware` (Login, Loopback) | Abschnitt 5 |
| Login, PIN, JWT | `AuthManager`, `PinManager`, `WebSitzung`, `TelegramAnmeldung`, `AuthApiHandler` | `AuthManager` |
| Push (Handy, iPhone) | `PushSink`, `VapidSigner` (fester Projekt-Kontakt), `capabilities.push` in `SystemApiHandler` | `VapidSigner` |
| Handy-App-API | `HandyApiHandler`, `HandyClipsApiHandler`, `HandySignatur`, `HandyRatenlimit` | `HandyApiHandler` |
| Brücke | `Bruecke`, `BrueckeVerbindung`, `BrueckeWeiterleitung`, `BrueckeLive`, `NoiseIK` | `BrueckeWeiterleitung` |
| Update-Hinweis am Display | `UpdateHinweis` (reine Regeln, auch Einwilligung Mobilfunk je Download), Karte in `MessageOverlayService`, Schalter `update.hinweis`, `update.mobilfunkEinmal` | `UpdateHinweis` |
| Updater | `AppUpdateDienst`, `UpdateQuelle`, `Freigabe`, `UpdateRegel`, `ApkZertifikat`, `FernUpdate`, `AutostartNachUpdate`, `UpdateLifecycle`, `AppUpdater` (Erbe; Versionsanzeige, Daemons stoppen, Installation abgekoppelt starten) | `AppUpdateDienst` |
| Tacho | `TachoFaehigkeit`, `TachoNaviApp`, `TachoApiHandler`, `ClusterProjectionController`, `ClusterCast`, `DiLink5ClusterCast` | `TachoFaehigkeit` |
| Fähigkeiten je Auto | `VehicleCapabilities` (belegte Werte bleiben über Neustarts gemerkt), `/api/system/capabilities` | `VehicleCapabilities` |
| Fahrzeugbefehle | `VehicleControlApiHandler`, `byd/routing/`, `VehicleActuatorBridge`, `VehicleActuatorService` | `VehicleControlApiHandler` |
| Ereignis-Clips | `EreignisClip`, `EreignisErkenner` (rein), `EreignisMonitor`, `EreignisAblage`, `EreignisMeldung`, `EreignisReceiver` | `EreignisErkenner` |
| Aufnahmen, Index | `RecordingsApiHandler`, `RecordingsIndex`, `RecordingModeManager`, `ClipExport`, `HandyClipsApiHandler`, `WaechterStandbild` | `RecordingsIndex` |
| Aufbewahrung, Speicher | `Aufbewahrung`, `StorageManager`, `ExternalStorageCleaner` | `Aufbewahrung` |
| Kartensuche im Internet | `ForwardGeocoder`, `OverpassPoiClient`, `KartenSuchePrivat` (Schalter `karte.sucheInternet`, Position nur grob) | `KartenSuchePrivat` |
| Mitzählen | `Mitzaehlen` (Schalter `zaehlen.an`, Standard aus), `AutoAngaben`, `web/shared/zaehlen-frage.js` | `Mitzaehlen` |
| Fehler melden | `FehlerMelden`, `RueckmeldungApiHandler`, Seite `fehler-melden.html` | `FehlerMelden` |
| Einstellungs-Katalog | `Katalog`, `LadenKatalog`, `Feld`, `Einstellungen`, `Energie`, `EinstellungenApiHandler` | `Katalog` |
| Gesamt-Konfiguration | `UnifiedConfigManager`, `Geheimnisse`, `QualitySettingsApiHandler` | `UnifiedConfigManager` |
| Einrichtung (Schnellstart, Liste „Noch einrichten“) | `EinrichtungApiHandler` (`SCHNELLSTART`, `PFLICHT`), `onboarding/schritte/`, `HandySchritt`, `Selbsttest`, `SetupGuideDialog` | `EinrichtungApiHandler` |
| i18n | `assets/web/i18n/`, `assets/server-i18n/`, `Messages`, `LocaleManager`, `web/shared/lang-picker.js`, `res/values*/strings.xml`, `tools/sprache/` | Abschnitt 6 |
| Web-Seiten | `assets/web/local/`, Menü in `web/shared/app-shell.js`, Auslieferung in `HttpServer` | Abschnitt 4 |
| Telegram | `TelegramBotDaemon`, `daemon/telegram/` (Befehle, `CommandRouter`), `TelegramNotifier`, `TelegramSpool`, `UnifiedTelegramConfig`, `TelegramApiHandler` | `CommandRouter` |
| Meldungen | `NotificationStore`, `notifications/sinks/`, `NotificationApiHandler` | `NotificationStore` |
| Routenplaner | `PlanerVerbindung`, `PlanerRoute`, `AutoZustand`, `PlanerBackoff`, `Einstellungen` | `PlanerVerbindung` |
| Laden, Ladeplan | `LadenApiHandler`, `LadenVorlieben`, `LadeplanDienst`, `ChargingSessionManager`, `TariffManager` | `LadenApiHandler` |
| Camping | `Camping`, `CampingRegler`, `CampingPlan`, `RouterHeizweg` | `Camping` |
| Wallbox | `WallboxDienst`, `WallboxKonfig`, `WallboxSuche`, Adapter in `wallbox/` | `WallboxDienst` |
| Home Assistant | `HaApiHandler`, `HaDienst`, `HaEreignisse`, `HaZustand`, `HaMdns` | `HaApiHandler` |
| Regeln (Automation) | `Automations`, `AutomationQueue`, `Actions`, `Conditions`, `AutomationApiHandler` | `Automations` |
| Fernzugang | `TailscaleLauncher`, `TailscaledProzesse`, `WireGuardLauncher`, `Notzugang`, `assets/notzugang/notzugang.sh` | `TailscaleLauncher` |
| Native Bibliotheken | `app/src/main/cpp/CMakeLists.txt` (`surveillance`, `dilink5_camera`, `fast_cam_release_guard`, `od`) | `CMakeLists.txt` |
| Lizenzen | `LICENSE`, `THIRD_PARTY_NOTICES.md`, `LICENSES/` | |
| Fachbegriffe | `docs/GLOSSAR.md` (Pflicht für sichtbare Texte) | |

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
| `/api/vehicle`, `/api/vehicle/lock`, `/api/vehicle/flash`, `/api/system/ivi-reboot`, `/api/system/background-access` | `VehicleControlApiHandler` | Login; Extern nur `state`, `status` lesen |
| `/api/quick-controls` | `QuickControlsApiHandler` | Login |
| `/api/keymap` | `KeymapApiHandler` | Login |
| `/api/tyres/` | `TyreLimitsApiHandler` | Login |
| `/api/bydcloud` | `BydCloudApiHandler` | Login; Einrichten für Handy-Web gesperrt |
| `/api/gps` | `GpsApiHandler` | Login; Extern lesen, siehe `GpsFreigabe` |
| `/api/tacho/` | `TachoApiHandler` | Login |
| `/api/positions` | `PositionsApiHandler` | Login |
| `/api/apps/` | `AppsApiHandler` | Login |
| `/api/launcher/` | `LauncherApiHandler` | Login; Extern `/api/launcher/v1/summary` lesen |
| `/api/about/vehicle-info` | `AboutApiHandler` | Login |

### Einstellungen, Konto, Kopplung

| Route | Handler | Rechte |
|---|---|---|
| `/api/einstellungen` | `EinstellungenApiHandler` | Login; Extern lesen und schreiben, Felder `nurLesendExtern` nur lesen |
| `/api/kopplung/`, `/api/kopplungen`, `/api/sitzung/` | `EinstellungenApiHandler` | Login; Extern nur `/api/sitzung/erneuern`, `/api/sitzung/abmelden` |
| `/api/kopplung/weiteres-geraet` | `WeiteresGeraetApiHandler` | Login |
| `/api/planer/`, `/api/test/blitzer` | `EinstellungenApiHandler` | Login; Extern `/api/planer/identitaet` lesen |
| `/api/einrichtung` | `EinrichtungApiHandler` | Login |
| `/api/settings/` | `QualitySettingsApiHandler` | Login; Handy-Web ohne geheime Abschnitte |
| `/api/backup/` | `ConfigBackupApiHandler` | Login; für Handy-Web gesperrt |
| `/api/telegram/` | `TelegramApiHandler` | Login |
| `/api/telegram-anmeldung/` | `HttpServer` selbst | nur voller Login |
| `/api/cloud-uebergabe` | `CloudUebergabeApiHandler` | Login |
| `/api/uebernahme/` | `UebernahmeApiHandler` | Login |
| `/api/i18n/lang` | `HttpServer` selbst | Login |

### Laden, Fahrten, Energie

| Route | Handler | Rechte |
|---|---|---|
| `/api/trips` | `TripApiHandler` | Login; Extern `/api/trips`, `summary`, `range`, `datei` lesen |
| `/api/charging` | `ChargingApiHandler` | Login |
| `/api/laden` | `LadenApiHandler` | Login; Extern lesen, Vorlieben und Nachfrage schreiben |
| `/api/ladeplan` | `LadeplanApiHandler` | Login; Extern lesen und schreiben, Freigabe nur am Auto |
| `/api/camping` | `CampingApiHandler` | Login; Extern lesen und schreiben |
| `/api/wallbox/` | `WallboxApiHandler` | Login; Extern `status` lesen |
| `/api/abrp/` | `AbrpApiHandler` | Login |
| `/api/performance` | `PerformanceApiHandler` | Login |

### Anbindungen

| Route | Handler | Rechte |
|---|---|---|
| `/api/handy/v1/` | `HandyApiHandler` | nur Kopplung Art `handy`, Rechte je Aktion im Handler |
| `/api/handy/v1/clips` | `HandyClipsApiHandler` | Art `handy` mit Recht `kamera` |
| `/api/ha/v1/` | `HaApiHandler` | Extern `zustand`, `regeln`, `ereignisse`, `aktion` |
| `/api/bruecke` | `BrueckeApiHandler` | Login; für Handy-Web gesperrt |
| `/api/mqtt/` | `MqttApiHandler` | Login; Extern `telemetry` lesen |
| `/api/community/` | `CommunityApiHandler` | Login |
| `/api/genai/` | `GenAiApiHandler` | Login |
| `/api/gewohnheiten` | `GewohnheitenApiHandler` | Login; Extern `karte`, `antwort` |
| `/api/automations`, `/api/action-groups` | `AutomationApiHandler` | Login |
| `/api/notifications`, `/api/push` | `NotificationApiHandler` | Login |
| `/api/navi/` | `NaviApiHandler` | Login; Extern `unterwegs` lesen |
| `/api/navmap/` | `NavMapApiHandler` | Login |
| `/api/orte` | `OrteApiHandler` | Login |
| `/api/roadsense/` | `RoadSenseApiHandler` | Login |
| `/api/ble/` | `BleApiHandler` | Login |

### System, Netz, Sonstiges

| Route | Handler | Rechte |
|---|---|---|
| `/api/system/` | `SystemApiHandler` | Login; Extern `capabilities`, `daemon` lesen |
| `/api/appupdate/` | `AppUpdateApiHandler` | Login; Extern `status` lesen; Handy-Web nur mit Recht `update`; `/api/appupdate/hinweis` nur am Auto oder voller Login |
| `/api/update/` | `HttpServer` selbst | stillgelegt, antwortet 410 |
| `/api/rueckmeldung/` | `RueckmeldungApiHandler` | Login; für Kopplungen doppelt gesperrt |
| `/api/neuigkeiten` | `NeuigkeitenApiHandler` | Login; Extern lesen |
| `/api/bericht/` | `BerichtApiHandler` | Login; Extern `monat` lesen |
| `/api/hotspot` | `HotspotApiHandler` | Login; für Handy-Web gesperrt |
| `/api/wireguard` | `WireGuardApiHandler` | Login; für Handy-Web gesperrt |
| `/api/tailscale/https` | `TailscaleHttpsApiHandler` | Login; für Handy-Web gesperrt |
| `/api/notzugang` | `NotzugangApiHandler` | Login; für Handy-Web gesperrt |
| `/api/storage/external` | `ExternalStorageApiHandler` | Login |
| `/api/models/` | `ModelsApiHandler` | Login |
| `/api/audio/`, `/api/audio/library/raw` | `AudioApiHandler` | Login |
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

Unter `assets/web/local/`, ausgeliefert durch `HttpServer`; gemeinsames Menü in `web/shared/app-shell.js`.

`about.html`, `abrp.html`, `assistant.html`, `automations.html`, `bericht.html`, `byd-cloud.html`, `camping.html`,
`charging.html`, `communicate.html`, `events.html`, `fehler-melden.html`, `index.html`, `key-mapping.html`, `ladeplan.html`,
`live-view.html`, `login.html`, `meldungen.html`, `mqtt.html`, `navi.html`, `network.html`, `notifications.html`,
`orte.html`, `parking.html`, `performance.html`, `recording.html`, `remote-dev-view.html`, `road-sense.html`,
`seat-positions.html`, `surveillance.html`, `system.html`, `telegram.html`, `testfunktionen.html`, `trips.html`,
`vehicle-control-3d-test.html`, `vehicle-control.html`, `waechter.html`, `wallbox.html`, `wireguard.html`

`waechter.html` ist die Übersicht, `surveillance.html` sind die Wächter-Einstellungen.

## 5. Rechte in Kürze

Reihenfolge in `HttpServer`: `AuthMiddleware` prüft Login, dann `ExternGuard` (nur scope `kopplung`), dann
`WebHandyGuard` (nur scope `handyweb`), dann `SchreibProtokoll`.

- `ExternGuard` ist eine **Erlaubnisliste**. Neue Pfade sind für Kopplungen automatisch zu. Wer öffnen will,
  trägt in `LESEN`, `SCHREIBEN`, `HANDY_SCHREIBEN` oder `HANDY_LESEN` ein und ergänzt `ExternGuardErlaubnislisteTest`.
- Art `dienst` bekommt nur `DIENST_LESEN`, `DIENST_SCHREIBEN` und das Standbild.
- `noetigesRecht` regelt `kamera` und `standbild`. `kamera` schließt `standbild` ein, nie umgekehrt.
- `WebHandyGuard` ist eine Sperrliste: Zugang, Netz, Sicherung, Updates. Neue geheime Bereiche dort eintragen,
  neue geheime Konfig-Abschnitte in `GEHEIME_ABSCHNITTE`.
- `Geheimnisse` maskiert Tokens und Schlüssel beim Lesen der Gesamt-Konfiguration.
- `Kopplungen` setzt `kamera` und `standbild` nie automatisch, neue Kopplungen auch nie `aufsperren`, `wallbox`, `update`.
  Beim Erlauben gibt es sie nur per Häkchen plus Rückfrage in `system.html`, Display und Telegram senden keine.
- Am Auto gilt: Fahrzeugbefehle laufen nie über `/api/einstellungen`. Aufsperren braucht die Bestätigung im
  Moment, keine Automation.

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
| Java-Quelltext allgemein | Auto = Android 10, minSdk 28. Instanzmethoden wie `InputStream.readAllBytes()` fehlen dort. | `MinSdkApiTest` verbietet sie. |
| `surveillance/` | Fehlalarme vermeiden hat Vorrang, Änderungen brauchen die Invarianten. | `DETECTION-INVARIANTS.md` |
| `Einstellungen` | Texte im Schema kommen aus den Katalogen. | `SchemaTexteAlleSprachenTest` |
| `DaemonLauncher.kt` | Watchdog-Skripte stecken als Zeichenketten im Code; Pfade sind am Auto heikel. | Nicht nebenbei umbauen. |
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
`clean` auswerten.

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
bei Tag `v*`. Nur die Deploy-Sitzung spielt etwas aufs Auto.

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
