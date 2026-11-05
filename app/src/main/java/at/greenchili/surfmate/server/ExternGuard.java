package at.greenchili.surfmate.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Regel A + B für gekoppelte Geräte (JWT mit scope "kopplung", z. B. EV-Cockpit, Home Assistant):
 * <b>Erlaubnisliste</b> statt Sperrliste (Befund HA-Sitzung/Koordination 21.10.2026: mit der alten
 * Sperrliste erreichte ein Kopplungs-Token schreibend /api/vehicle/*, quick-controls/fire, keymap/fire,
 * automations/test …). Alles, was hier nicht ausdrücklich steht, ist für Kopplungs-Tokens gesperrt (403) –
 * neue Pfade sind damit automatisch zu.
 *
 * <ul>
 *   <li>Lesen: Telemetrie, Übersicht, Fahrzeugzustand (nur GET), GPS, Fähigkeiten, Wächter-Status und
 *       Gesundheit, F1, Fahrtenbuch-Liste, Planer-Kennung, Einstellungen + Schema, Daemon-Status.</li>
 *   <li>Schreiben: nur {@code /api/einstellungen} (je Feld {@code nurLesendExtern} im Handler) und
 *       {@code /api/sitzung/erneuern}.</li>
 *   <li>Nie: Fahrzeugbefehle, fire/test-Pfade, Kopplungs-/Zugangsverwaltung, Fernzugang, Backup,
 *       Updates, Streams/WebSockets.</li>
 * </ul>
 */
public final class ExternGuard {

    private ExternGuard() {}

    /** Genau diese Pfade dürfen gelesen werden (GET/HEAD), ohne Unterpfade. */
    static final Set<String> LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/mqtt/telemetry",
            "/api/launcher/v1/summary",
            "/api/gewohnheiten/karte",   // 0.1.23 Gewohnheiten: Cockpit-Feld (dieselbe Karte wie am Display)
            "/api/vehicle/state",
            "/api/vehicle/status",
            "/api/gps",
            "/api/system/capabilities",
            "/api/system/daemon",
            "/api/surveillance/status",
            "/api/surveillance/gesundheit",
            "/api/navi/unterwegs",
            "/api/trips",
            "/api/trips/summary",
            "/api/trips/range",       // 0.1.20 Handy-App: Reichweiten-Schätzung, keine Koordinaten
            "/api/trips/datei",       // 0.1.28 P9: Fahrtenbuch als Datei (CSV/Druck), keine Orte – geht nur ans Gerät
            "/api/wallbox/status",    // 0.1.20 Handy-App/Cockpit: Wallbox-Zustand, NUR lesen (Steuern bleibt gesperrt)
            "/api/planer/identitaet",
            "/api/einstellungen",
            "/api/einstellungen/schema",
            "/api/laden",   // Lade-Vorlieben fürs Cockpit (LADEN_VERTRAG A4)
            "/api/laden/nachfrage",   // Ladenachfrage: Cockpit zeigt an (LADENACHFRAGE_VERTRAG)
            "/api/laden/kosten",      // „Was kosten 100 km?“ (0.1.10.1)
            "/api/laden/ladungen",    // Ladungsliste ohne Koordinaten (0.1.10.1)
            "/api/appupdate/status",
            "/api/neuigkeiten",
            "/api/bericht/monat",
            "/api/ha/v1/zustand",
            "/api/ha/v1/regeln",
            "/api/ha/v1/ereignisse",
            "/api/camping",   // 0.1.16: Camping-Status (Panoptes-Funktion, keine Innentemperatur)
            "/api/ladeplan",  // 0.1.20: Ladeplan lesen (Cockpit/Handy)
            // Wächter-Aufnahmen fürs Cockpit (0.1.9): Liste + Tage + Zahlen, NUR lesen
            "/api/recordings",
            "/api/recordings/dates",
            "/api/recordings/stats",
            "/api/demo",   // 0.1.38.2 Demo-Profil: nur ob das Band „Musterdaten“ gezeigt wird
            "/api/web/manifest"   // 0.1.39 PC über die Brücke: Prüfsummen der Web-Dateien dieses Panoptes (öffentlich)
    )));

    /**
     * 0.1.39 PC über die Brücke (PLAN_BRUECKE_PC_0139 P1): genau diese Schreibpfade unter {@code /api/handy/v1} auch für
     * Kopplungen der Art {@code pc} – eigener Brücken-Schlüssel, Abmelden von der Brücke, Web-Sitzung. Alles andere aus
     * {@link #HANDY_SCHREIBEN} (Aktionen, Passkey, Push, Dateien, Besitzer-Pfade) bleibt für den PC zu.
     */
    static final Set<String> PC_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/handy/v1/bruecke",
            "/api/handy/v1/bruecke/aus",
            "/api/handy/v1/websitzung")));

    /** 0.1.30 Kern Sicherheit: ein Standbild je Wächter-Ereignis (nur JPEG, nie Clip). */
    public static final String STANDBILD_PFAD = "/api/waechter/standbild/";

    /**
     * Lesen mit genau einer Aufnahme-ID (32 hex): Clip (mit Range) und Vorschaubild; 0.1.30 Standbild je
     * Wächter-Ereignis (ereignisId, 32 hex). Welches Recht dafür nötig ist, sagt {@link #noetigesRecht}.
     */
    static final String[] LESEN_MIT_ID = {"/video/id/", "/thumb/id/", STANDBILD_PFAD};

    /**
     * 0.1.30 Kern Sicherheit: Pfade mit Kamerabildern oder Clips. Für Kopplungen nur mit Recht „kamera“ (Standard
     * aus, nie automatisch gesetzt) – Liste, Tage, Zahlen, Clip, Vorschaubild; dazu die Handy-Clips (prüft der
     * Handler schon selbst, hier zusätzlich).
     */
    static final String[] KAMERA_LESEN = {"/api/recordings", "/video/id/", "/thumb/id/", "/api/handy/v1/clips"};

    /**
     * 0.1.30 Kern Sicherheit: Art {@code dienst} (fremder Dienst, z. B. Xaventra) – geringste Rechte: nur
     * Wächter-Ereignisse hören, Wächter-Zustand lesen, Standbild holen (Recht prüft {@link #noetigesRecht}),
     * Sitzung erneuern/abmelden. Keine Telemetrie, kein GPS, keine Aufnahmen-Liste, kein Clip.
     */
    static final Set<String> DIENST_LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/ha/v1/ereignisse",
            "/api/surveillance/status")));
    static final Set<String> DIENST_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/sitzung/erneuern",
            "/api/sitzung/abmelden")));
    private static final java.util.regex.Pattern AUFNAHME_ID = java.util.regex.Pattern.compile("[0-9a-f]{32}");

    /** Genau diese Pfade dürfen schreiben (POST/PUT). */
    static final Set<String> SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/einstellungen",
            "/api/sitzung/erneuern",
            "/api/sitzung/abmelden",
            "/api/laden",   // Cockpit pflegt die Vorlieben im Auto (GRUNDSATZ)
            "/api/laden/nachfrage",   // Cockpit darf beantworten (Koordination 22.10.), Auto bleibt die Quelle
            "/api/laden/nachfrage/spaeter",
            "/api/gewohnheiten/antwort",   // 0.1.23 Gewohnheiten: Cockpit darf die Karte beantworten (Auto bleibt die Quelle)
            "/api/laden/nachfrage/kein-preis",
            "/api/ha/v1/aktion",
            // 0.1.16 Camping: Panoptes-Funktion (Regel A: in Stufe 2a keine Fahrzeugwirkung)
            "/api/camping",
            "/api/camping/rueckmeldung",
            "/api/camping/plan",   // 0.1.17: Cockpit plant Start/Ende (Panoptes-Funktion)
            "/api/ladeplan",   // 0.1.20: Plan ändern; Freigabe/Ladepunkt nie (Handler: nur_am_auto)
            "/api/camping/weiter"
    )));

    /**
     * 0.1.18 Handy-Vertrag §1: genau diese Schreibpfade NUR für Kopplungen der Art {@code handy} (Rechte prüft der
     * Handler je Aktion). Cockpit/HA bleiben hier 403; {@code /api/vehicle/*} bleibt für alle Kopplungen zu.
     */
    static final Set<String> HANDY_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/handy/v1/geraet",
            "/api/handy/v1/passkey/start",        // 0.1.33 Web-Aufsperren: nur bei offenem Fenster am Auto
            "/api/handy/v1/passkey",
            "/api/handy/v1/passkey/nur-passkey",  // Einbahnstraße: alten Schlüssel-Weg dieser Kopplung sperren
            "/api/handy/v1/aktion",
            "/api/handy/v1/gewohnheit",   // 0.1.23 Gewohnheiten: Karte lesen/beantworten
            "/api/handy/v1/push",
            "/api/handy/v1/push/aus",
            "/api/handy/v1/bruecke",   // 0.1.20 Panoptes-Brücke: Handy-Schlüssel + Zugang (BRUECKE_VERTRAG §2.4)
            "/api/handy/v1/bruecke/aus",
            "/api/handy/v1/websitzung",   // 0.1.20 Weboberfläche in der Handy-App (HANDY_WEB_SITZUNG_VERTRAG)
            "/api/handy/v1/clips/behalten",   // 0.1.28 P5: im Auto behalten / Frist (Recht „kamera“ prüft der Handler)
            "/api/navi/unterwegs/ablehnen",   // 0.1.38.6 (= UNTERWEGS_ABLEHNEN): Neuplanungs-Vorschlag verwerfen, nur mit Recht „route“ (noetigesRecht)
            "/api/navi/umkreis"   // 0.1.38.10 (= NAVI_UMKREIS): Säulen in der Nähe, Position setzt das Auto, nur mit Recht „route“
    )));

    /**
     * 0.1.38.10 (Koordination 05.11., handy-048): Favoriten des Autos ohne Koordinaten ({@code GET}, Art {@code handy},
     * Recht {@code route}) und Säulen im Umkreis der eigenen, gerundeten Auto-Position ({@code POST}, das Handy schickt
     * keine Position). Beide nur mit Recht „route“ ({@link #noetigesRecht}).
     */
    static final String ORTE = "/api/orte", NAVI_UMKREIS = "/api/navi/umkreis";

    /**
     * 0.1.38.6 „Reicht es noch?“: Der Fahrer (auch in der Handy-App) verwirft den Neuplanungs-Vorschlag. Nur für
     * Kopplungen der Art {@code handy} und nur mit dem bestehenden Recht {@code route}; kein neues Recht, keine neue
     * Aktion. Wirkung: Vorschlag weg, 30 min kein neuer, Planer bekommt {@code auto-status abgelehnt}.
     */
    static final String UNTERWEGS_ABLEHNEN = "/api/navi/unterwegs/ablehnen";

    /** 0.1.28 P5: Lesepfade unter /api/handy/v1 nur für {@code handy} (Clip-Liste; Recht „kamera“ prüft der Handler). */
    static final Set<String> HANDY_LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/handy/v1/update",
            "/api/handy/v1/clips",
            "/api/handy/v1/protokoll",   // 0.1.34 Befehls-Log: eigene Einträge, alle nur mit Recht „protokoll“
            "/api/orte"   // 0.1.38.10 (= ORTE): nur Favoriten, ohne Koordinaten (OrteApiHandler), nur mit Recht „route“
    )));

    /**
     * Dateien Handy ↔ Auto: nur Kopplungen der Art {@code handy}, und nur mit Recht {@code dateien}
     * ({@link #noetigesRecht}). Lesen ist nur die Liste; Holen und Löschen gehen per POST (landen im Befehls-Log).
     */
    static final Set<String> DATEIEN_LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/dateien")));
    static final Set<String> DATEIEN_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/dateien/start", "/api/dateien/teil", "/api/dateien/fertig", "/api/dateien/abbrechen",
            "/api/dateien/holen", "/api/dateien/loeschen")));

    /**
     * 0.1.37 „Was zeigt der Tacho?“: Auswahl lesen/setzen und „Tacho wieder normal“, nur für Kopplungen der Art
     * {@code handy} und {@code cockpit} und nur mit Recht {@code tacho} ({@link #noetigesRecht}). Starten prüft
     * danach dieselbe Freigabe-Policy wie am Auto ({@code TachoNaviApp}).
     */
    static final String TACHO_AUSWAHL = "/api/tacho/auswahl", TACHO_NORMAL = "/api/tacho/normal";

    static boolean tachoErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"handy".equals(kopplungsArt) && !"cockpit".equals(kopplungsArt)) return false;
        String p = normal(pathOnly);
        if (p == null || method == null) return false;
        if (TACHO_AUSWAHL.equals(p)) return "GET".equals(method) || "POST".equals(method);
        return TACHO_NORMAL.equals(p) && "POST".equals(method);
    }

    /**
     * 0.1.38 Launcher-Layout (LAUNCHER_LAYOUT_API.md): nur Kopplungen der Art {@code handy} oder {@code cockpit}, und
     * nur mit Recht {@code launcher} ({@link #noetigesRecht}, Standard aus). Home Assistant und Dienste nie.
     */
    public static final String LAUNCHER_LAYOUT_PFAD = "/api/launcher/v1/layout";
    static final Set<String> LAUNCHER_LESEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            LAUNCHER_LAYOUT_PFAD, LAUNCHER_LAYOUT_PFAD + "/apps")));
    /**
     * 0.1.38 (Koordination): Launcher-Stand lesen und „Öffnen“ aus der Ferne mit demselben Recht. Der Handler lässt
     * für Kopplungen und die Web-Sitzung nur {@code aktion: oeffnen} durch; installieren/entfernen bleiben am Auto
     * bzw. beim vollen Login.
     */
    public static final String LAUNCHER_STAND_PFAD = "/api/appupdate/launcher";

    /**
     * 0.1.38.2 Updates von außen (UPDATE_API_CLIENTS.md): Stand lesen und Panoptes bzw. Launcher aktualisieren, nur
     * Kopplungen der Art {@code handy} oder {@code cockpit}. Lesen auch ohne Recht ({@code darf:false}); Starten prüft
     * {@link AppUpdateApiHandler} (Recht {@code update}, Bestätigung, Regeln des Updaters). Home Assistant, Dienste nie.
     */
    static boolean updateErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"handy".equals(kopplungsArt) && !"cockpit".equals(kopplungsArt)) return false;
        String p = normal(pathOnly);
        if (p == null || method == null || !AppUpdateApiHandler.FERN_PFAD.equals(p)) return false;
        return "GET".equals(method) || "POST".equals(method);
    }

    static boolean launcherErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"handy".equals(kopplungsArt) && !"cockpit".equals(kopplungsArt)) return false;
        String p = normal(pathOnly);
        if (p == null || method == null) return false;
        if ("GET".equals(method)) return LAUNCHER_LESEN.contains(p) || LAUNCHER_STAND_PFAD.equals(p);
        if ("PUT".equals(method)) return LAUNCHER_LAYOUT_PFAD.equals(p);
        return "POST".equals(method) && ((LAUNCHER_LAYOUT_PFAD + "/standard").equals(p) || LAUNCHER_STAND_PFAD.equals(p));
    }

    /**
     * 0.1.39 HA-Geräte (HA_VERTRAG v1.1 Teil D): nur Kopplungen der Art {@code homeassistant}, GET (Domänen +
     * Freigaben) und POST (Kandidaten melden), dazu das Recht {@code geraete} ({@link #noetigesRecht}, Standard aus).
     */
    public static final String HA_GERAETE_PFAD = "/api/ha/v1/geraete";
    /** 0.1.38.8 Stufe 2: Echo der Integration auf einen Befehl, nur mit Recht {@code geraete_steuern}. */
    public static final String HA_GERAETE_ECHO_PFAD = "/api/ha/v1/geraete/echo";

    static boolean haGeraeteErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"homeassistant".equals(kopplungsArt)) return false;
        String p = normal(pathOnly);
        return HA_GERAETE_PFAD.equals(p) && ("GET".equals(method) || "POST".equals(method))
                || HA_GERAETE_ECHO_PFAD.equals(p) && "POST".equals(method);
    }

    /** true, wenn die Anfrage eines gekoppelten Geräts abgelehnt werden muss. */
    public static boolean blocks(String method, String pathOnly) {
        return !erlaubt(method, pathOnly);
    }

    /** Wie {@link #blocks(String, String)}, mit der Art der Kopplung (Handy-Pfade nur für {@code handy}). */
    public static boolean blocks(String method, String pathOnly, String kopplungsArt) {
        if (at.greenchili.surfmate.auth.Kopplungen.ART_DIENST.equals(kopplungsArt)) {
            return !dienstErlaubt(method, pathOnly);
        }
        // 0.1.39 Art pc: eigene Brücken-/Web-Pfade, sonst schreibend nur, was auch die PC-Web-Sitzung darf
        // (eine Liste: WebHandyGuard.PC_WEB_SCHREIBEN) – nie Camping, HA-Aktion, Ladeplan o. ä. aus SCHREIBEN
        if (at.greenchili.surfmate.auth.Kopplungen.ART_PC.equals(kopplungsArt)) {
            if (pcErlaubt(method, pathOnly, kopplungsArt)) return false;
            if (WebHandyGuard.pcBlocks(method, pathOnly)) return true;
            return !erlaubt(method, pathOnly);
        }
        if (erlaubt(method, pathOnly)) return false;
        if (cockpitErlaubt(method, pathOnly, kopplungsArt)) return false;
        if (tachoErlaubt(method, pathOnly, kopplungsArt)) return false;
        if (launcherErlaubt(method, pathOnly, kopplungsArt)) return false;
        if (updateErlaubt(method, pathOnly, kopplungsArt)) return false;
        if (haGeraeteErlaubt(method, pathOnly, kopplungsArt)) return false;
        return !handyErlaubt(method, pathOnly, kopplungsArt);
    }

    /**
     * 0.1.21 BYD-Cloud-Übergabe (CLOUD_UEBERGABE_VERTRAG §5): genau {@code POST /api/cloud-uebergabe/anfrage},
     * {@code GET|POST /api/cloud-uebergabe/r-<16 hex>} – nur für Kopplungen der Art {@code cockpit}. Erlauben/Ablehnen
     * ({@code …/erlauben|ablehnen}) und die Liste offener Rückfragen bleiben für Kopplungen zu.
     */
    static boolean cockpitErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"cockpit".equals(kopplungsArt) || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.contains("..") || p.contains("//")) return false;
        if ("/api/cloud-uebergabe/anfrage".equals(p)) return "POST".equals(method);
        // 0.1.25 „weiteres Gerät koppeln“ (KOPPLUNG_WEITERES_GERAET_VERTRAG): Anfrage + Status; Entscheiden nie
        if ("/api/kopplung/weiteres-geraet".equals(p)) return "POST".equals(method);
        if (p.matches("/api/kopplung/weiteres-geraet/r-[0-9a-f]{16}")) return "GET".equals(method);
        return p.matches("/api/cloud-uebergabe/r-[0-9a-f]{16}") && ("GET".equals(method) || "POST".equals(method));
    }

    /** 0.1.30: Art {@code dienst} – nur {@link #DIENST_LESEN}, Standbild und {@link #DIENST_SCHREIBEN}. */
    static boolean dienstErlaubt(String method, String pathOnly) {
        String p = normal(pathOnly);
        if (p == null || method == null) return false;
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return DIENST_LESEN.contains(p) || istStandbild(p);
        }
        return "POST".equals(method) && DIENST_SCHREIBEN.contains(p);
    }

    /**
     * 0.1.30 Kern Sicherheit: Recht, das eine Kopplung für diesen (bereits erlaubten) Pfad zusätzlich braucht.
     * {@code kamera} für Kamerabilder/Clips ({@link #KAMERA_LESEN}); {@code standbild} für das Standbild je
     * Ereignis ({@code kamera} genügt dort auch); sonst null.
     */
    public static String noetigesRecht(String pathOnly) {
        String p = normal(pathOnly);
        if (p == null) return null;
        if (istStandbild(p)) return at.greenchili.surfmate.auth.Kopplungen.RECHT_STANDBILD;
        if (p.equals("/api/dateien") || p.startsWith("/api/dateien/")) {
            return at.greenchili.surfmate.auth.Kopplungen.RECHT_DATEIEN;
        }
        if (p.equals(TACHO_AUSWAHL) || p.equals(TACHO_NORMAL)) return at.greenchili.surfmate.auth.Kopplungen.RECHT_TACHO;
        if (p.equals(UNTERWEGS_ABLEHNEN) || p.equals(ORTE) || p.equals(NAVI_UMKREIS)) {
            return at.greenchili.surfmate.auth.Kopplungen.RECHT_ROUTE;
        }
        if (p.equals(HA_GERAETE_PFAD)) return at.greenchili.surfmate.auth.Kopplungen.RECHT_GERAETE;
        if (p.equals(HA_GERAETE_ECHO_PFAD)) return at.greenchili.surfmate.auth.Kopplungen.RECHT_GERAETE_STEUERN;
        if (p.equals(LAUNCHER_LAYOUT_PFAD) || p.startsWith(LAUNCHER_LAYOUT_PFAD + "/") || p.equals(LAUNCHER_STAND_PFAD)) {
            return at.greenchili.surfmate.auth.Kopplungen.RECHT_LAUNCHER;
        }
        for (String k : KAMERA_LESEN) {
            if (p.equals(k) || p.startsWith(k.endsWith("/") ? k : k + "/")) {
                return at.greenchili.surfmate.auth.Kopplungen.RECHT_KAMERA;
            }
        }
        return null;
    }

    /** Hat die Kopplung das nötige Recht? {@code kamera} schließt {@code standbild} ein, nie umgekehrt. */
    public static boolean rechtReicht(String noetig, java.util.function.Predicate<String> hat) {
        if (noetig == null) return true;
        if (hat == null) return false;
        if (hat.test(noetig)) return true;
        return at.greenchili.surfmate.auth.Kopplungen.RECHT_STANDBILD.equals(noetig)
                && hat.test(at.greenchili.surfmate.auth.Kopplungen.RECHT_KAMERA);
    }

    /**
     * 403-Antwort, wenn ein Recht fehlt: {@code {"ok":false,"grund":"nicht_erlaubt","recht":"kamera","text":"…"}}.
     * {@code grund} wie bei den Handy-Clips (die Handy-App erkennt es schon); der Satz sagt dem Nutzer, wo er das
     * Recht einschaltet (Gekoppelte Geräte).
     */
    public static org.json.JSONObject rechtFehltAntwort(String recht) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            boolean standbild = at.greenchili.surfmate.auth.Kopplungen.RECHT_STANDBILD.equals(recht);
            boolean dateien = at.greenchili.surfmate.auth.Kopplungen.RECHT_DATEIEN.equals(recht);
            boolean launcher = at.greenchili.surfmate.auth.Kopplungen.RECHT_LAUNCHER.equals(recht);
            boolean route = at.greenchili.surfmate.auth.Kopplungen.RECHT_ROUTE.equals(recht);
            boolean geraete = at.greenchili.surfmate.auth.Kopplungen.RECHT_GERAETE.equals(recht)
                    || at.greenchili.surfmate.auth.Kopplungen.RECHT_GERAETE_STEUERN.equals(recht);
            if (geraete) {
                o.put("ok", false).put("grund", "nicht_erlaubt").put("recht", recht).put("text", Messages.text(
                        "errors.geraete_recht_fehlt", "Geräte-Recht fehlt – in Panoptes unter Gekoppelte Geräte einschalten."));
                return o;
            }
            String key = route ? "errors.route_recht_fehlt" : launcher ? "errors.launcher_recht_fehlt" : dateien ? "dateien.recht_fehlt"
                    : standbild ? "errors.standbild_recht_fehlt" : "errors.kamera_recht_fehlt";
            String fallback = route
                    ? "Route-Recht fehlt – in Panoptes unter Gekoppelte Geräte einschalten."
                    : launcher
                    ? "Launcher-Recht fehlt – in Panoptes unter Gekoppelte Geräte einschalten."
                    : dateien
                    ? "File permission missing – switch it on in Panoptes under Paired devices."
                    : standbild
                    ? "Standbild-Recht fehlt – in Panoptes unter Gekoppelte Geräte einschalten."
                    : "Kamera-Recht fehlt – in Panoptes unter Gekoppelte Geräte einschalten.";
            String text = Messages.text(key, fallback);
            o.put("ok", false).put("grund", "nicht_erlaubt").put("recht", recht).put("text", text);
        } catch (Exception ignored) { }
        return o;
    }

    static boolean istStandbild(String p) {
        return p.startsWith(STANDBILD_PFAD) && AUFNAHME_ID.matcher(p.substring(STANDBILD_PFAD.length())).matches();
    }

    private static String normal(String pathOnly) {
        if (pathOnly == null) return null;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return null;
        return p;
    }

    /**
     * 0.1.38.8 Handy-App „Fahrt als Luftlinie“: GPS-Spur einer Fahrt ({@code /api/trips/<Ziffern>/gps}), nur lesen, nur
     * Handy-Kopplungen. Kein eigenes Recht: wer die Fahrtenliste mit Start und Ziel sieht, sieht auch die Strecke.
     */
    static final java.util.regex.Pattern TRIP_GPS = java.util.regex.Pattern.compile("/api/trips/[0-9]{1,18}/gps");

    static boolean handyErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!"handy".equals(kopplungsArt) || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return false;
        // 0.1.26.1: Update-Stand lesen (HANDY_API_VERTRAG §8); 0.1.28: Clip-Liste (CLIP_VERTRAG_0128)
        // 0.1.38.8 Besitzer-Handy: eigene Pfade, das Recht „besitzer“ prüft BesitzerApiHandler
        if ("GET".equals(method)) {
            return HANDY_LESEN.contains(p) || DATEIEN_LESEN.contains(p) || TRIP_GPS.matcher(p).matches()
                    || BesitzerApiHandler.LESEN.contains(p);
        }
        return "POST".equals(method) && (HANDY_SCHREIBEN.contains(p) || DATEIEN_SCHREIBEN.contains(p)
                || BesitzerApiHandler.SCHREIBEN.contains(p));
    }

    /** 0.1.39: Art {@code pc} – nur {@link #PC_SCHREIBEN} (POST) zusätzlich zur allgemeinen Liste. */
    static boolean pcErlaubt(String method, String pathOnly, String kopplungsArt) {
        if (!at.greenchili.surfmate.auth.Kopplungen.ART_PC.equals(kopplungsArt)) return false;
        String p = normal(pathOnly);
        return p != null && "POST".equals(method) && PC_SCHREIBEN.contains(p);
    }

    public static boolean erlaubt(String method, String pathOnly) {
        if (pathOnly == null || method == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return false;
        boolean lesen = "GET".equals(method) || "HEAD".equals(method);
        if (lesen) {
            if (LESEN.contains(p)) return true;
            for (String v : LESEN_MIT_ID) {
                if (p.startsWith(v) && AUFNAHME_ID.matcher(p.substring(v.length())).matches()) return true;
            }
            return false;
        }
        if ("POST".equals(method) || "PUT".equals(method)) return SCHREIBEN.contains(p);
        return false;
    }
}
