package at.greenchili.surfmate.server;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.config.VehicleModelSelection;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.Kartenquelle;
import at.greenchili.surfmate.einstellungen.TriggerModus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Einrichtungsassistent, Vertrag §0 (WIZARD_AUFTRAG_KERN.md, 0.1.10):
 * <ul>
 *   <li>{@code GET /api/einrichtung} – Schrittliste, Status, Zwischenstand</li>
 *   <li>{@code POST /api/einrichtung/schritt {id, antwort}} – Antwort speichern (Pflicht ohne Antwort: 422)</li>
 *   <li>{@code POST /api/einrichtung/spaeter {id}} – nur Nicht-Pflicht-Schritte (Pflicht: 422)</li>
 *   <li>{@code POST /api/einrichtung/neu} – alle Merker zurück, gewählte Werte bleiben als Vorschlag</li>
 *   <li>{@code POST /api/einrichtung/selbsttest} – Teil G</li>
 *   <li>{@code POST /api/einrichtung/rueckfall_verschieben {bereich}} – 0.1.11: Rückfall-Aufnahmen auf den gewählten
 *       Datenträger verschieben (202 gestartet; 422 bereich|intern_gewaehlt|datentraeger_fehlt; 409 laeuft_schon);
 *       Fortschritt im GET unter noch_offen[rueckfall_aufnahmen].verschieben</li>
 * </ul>
 * Der Zustand liegt im Daemon (Abschnitt {@code einrichtung}), damit Display und Web denselben Stand sehen.
 * Antworten auf Pflichtschritte laufen durch dieselbe Prüfung wie {@code PUT /api/einstellungen}
 * (Katalog: Optionen, CH-Sperre, Bestätigungs-Zeitstempel). Nichts wird am Auto ausgelöst.
 */
public final class EinrichtungApiHandler {

    public static final int VERSION = 1;
    static final String ABSCHNITT = "einrichtung";

    /** Feste Reihenfolge (§0). */
    public static final List<String> SCHRITTE = Arrays.asList(
            "sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe", "auto", "kameras",
            "waechter", "betrieb", "karte", "daten", "blitzer", "updates", "strompreis",
            "extra_telegram",
            // 0.1.24: Handy & PC über die Panoptes-Brücke verbinden – der Standardweg, ohne Tailscale
            "extra_handy",
            "extra_planer", "extra_cockpit", "extra_overdrive",
            // 0.1.22: Gewohnheiten merken (Regel 2: Frage mit Erklärung; Bestand → „Noch offen“, nie still an)
            "extra_gewohnheiten",
            // 0.1.38: neue Funktionen aus 0.1.37, alle freiwillig („Noch einrichten“, nie Pflicht, nie Schnellstart)
            "extra_display", "extra_launcher", "extra_tacho", "extra_abschaltbericht", "extra_ladesaeule",
            "extra_tiermodus", "extra_assistenz",
            // 0.1.24: „Für Fortgeschrittene“ ans Ende (Tailscale-Fernzugang; 0.1.14 Notzugang, Standard aus, Regel 1)
            "extra_fernzugang", "extra_notzugang",
            "fertig");

    /** 0.1.24: Schritte unter „Für Fortgeschrittene“ (brauchen Tailscale; Laien kommen ohne aus). */
    public static final Set<String> FORTGESCHRITTEN = new HashSet<>(Arrays.asList("extra_fernzugang", "extra_notzugang"));

    /**
     * Overdrive-Übernahme (Kern 5): liefert true, wenn Overdrive-Daten gefunden wurden. Kern 5 setzt den Haken
     * (OverdriveQuelle); bis dahin entfällt der Schritt {@code extra_overdrive}.
     */
    public static volatile java.util.function.BooleanSupplier overdriveErkennung = () -> false;

    /**
     * 0.1.38.8: Haken für den Einrichtungs-Schritt {@code extra_display} – true, wenn am Auto ein eigenes
     * Hintergrundbild gesetzt wurde (Daemon prüft die abgelegte Bilddatei). Standard false (Tests ohne Daemon),
     * echte Prüfung setzt {@code UebernahmeApiHandler.anmelden()} beim Start.
     */
    public static volatile java.util.function.BooleanSupplier hintergrundErkennung = () -> false;

    /**
     * 0.1.38.8: Haken für {@code extra_launcher} – true, wenn die Panoptes-Launcher-APK installiert ist. Gecacht
     * (nicht je Abruf, kein {@code pm} pro GET); echte Prüfung setzt {@code UebernahmeApiHandler.anmelden()} beim Start.
     */
    public static volatile java.util.function.BooleanSupplier launcherErkennung = () -> false;

    /**
     * 0.1.28 P10: Wahl „Wächter stoppt …“ → {Akku unter %, Stunden geparkt}. „normal“ = Katalog-Standard (20 %, 24 h).
     */
    public static final Map<String, int[]> STOPP;
    static {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("frueh", new int[]{30, 12});
        m.put("normal", new int[]{20, 24});
        m.put("lang", new int[]{15, 48});
        STOPP = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Pflicht = ohne Antwort geht es nicht weiter, kein „Später“ (B4). 0.1.32 Schnellstart: nur noch, was rechtlich
     * bzw. technisch ohne Antwort nicht geht. Rest läuft mit sicherem Standard, bis die Liste „Noch einrichten“ dran ist.
     */
    public static final Set<String> PFLICHT = new HashSet<>(Arrays.asList(
            // 0.1.38.5 (Einrichtung verschlanken): nur noch rechtlich (sprache, haftung) bzw. technisch (usb_freigabe)
            // Unverzichtbares. waechter ist ein Zusatzdienst (Standard aus), updates laufen mit sinnvollem Standard –
            // beide nur noch optional unter „Noch einrichten“, nicht mehr Pflicht.
            "sprache", "haftung", "usb_freigabe"));

    /** Bis 0.1.31 Pflicht: Eine vorhandene Antwort zählt beim Bestand weiter als erledigt, nichts wird neu gefragt. */
    static final Set<String> FRUEHER_PFLICHT = new HashSet<>(Arrays.asList(
            "sprache", "haftung", "usb_freigabe", "auto", "waechter", "betrieb", "karte", "updates"));

    /**
     * 0.1.38.5 (Einrichtung verschlanken): „läuft schnell“ = 3 Bildschirme bis Panoptes läuft –
     * Sprache, Kurz-Haftung, und EIN gebündelter Freigabe-Schritt (autostart + ueberlagerung + rechte + usb_freigabe,
     * nativ auf einem Bildschirm „Panoptes darf loslegen“). Danach läuft Panoptes. Alles andere – kameras, waechter,
     * updates, betrieb, karte, daten, blitzer, strompreis und alle extra_* – ist nur noch optional unter
     * „Noch einrichten“ (jederzeit nachholbar) bzw. läuft mit sinnvollem Standard. Kameras bleiben nachholbar
     * ({@link #sicht(JSONObject, JSONObject, JSONObject, JSONObject)} blendet sie bei 0 Kameras aus).
     */
    public static final Set<String> SCHNELLSTART = new HashSet<>(Arrays.asList(
            "sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe", "fertig"));

    /** Gab es schon in 0.1.9 (für Bestandsnutzer nicht erneut, B2). */
    /** Prüf-Schritte: die App stellt sie fest und meldet {geprueft:true, ok}; ok:false lässt den Schritt offen. */
    static final Set<String> PRUEF = new HashSet<>(Arrays.asList(
            "autostart", "ueberlagerung", "rechte", "usb_freigabe"));

    /** Frühester plausibler nachgereichter Haftungs-Zeitpunkt (App-Vorlauf vor dem Dienst). */
    static final long ZEIT_MIN_MS = 1_767_225_600_000L;   // 2026-01-01T00:00Z

    static final Set<String> BESTAND_019 = new HashSet<>(Arrays.asList(
            "autostart", "ueberlagerung", "kameras", "extra_fernzugang"));

    public static final String ERLEDIGT = "erledigt", OFFEN = "offen", SPAETER = "spaeter", ENTFAELLT = "entfaellt";

    private static final Set<String> SPRACHEN = new HashSet<>(LocaleManager.SUPPORTED);

    private EinrichtungApiHandler() {}

    // ================================================================ HTTP

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.equals("/api/einrichtung") && method.equals("GET")) {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject z = zustand(root);
            if (root.optJSONObject(ABSCHNITT) == null) {
                speichere(z);   // Bestand/Neu einmal festhalten
                // 0.1.38.5: Updates werden nicht mehr gefragt – sinnvoller Standard für die Neuinstallation einmal setzen.
                if (standardsNeuEinrichtung(root, z)) root = UnifiedConfigManager.loadConfig();
            }
            JSONObject gespeichert = root.optJSONObject(ABSCHNITT);
            JSONObject st = gespeichert == null ? null : gespeichert.optJSONObject("selbsttest");
            JSONObject antwort = sicht(root, z, faehigkeiten(), st);
            // USB/SD fehlt → Aufnahmen intern (still, nie Popup); Datenträger zurück → „N Aufnahmen liegen intern“
            try {
                JSONArray rf = at.greenchili.surfmate.storage.StorageManager.getInstance().rueckfallStatus();
                for (int i = 0; i < rf.length(); i++) antwort.getJSONArray("noch_offen").put(rf.get(i));
            } catch (Throwable ignored) {
            }
            // 0.1.24 Zustimmung zur Brücke: Bestand mit eingeschalteter Brücke einmal nachfragen (Brücke bleibt an)
            try {
                JSONObject bz = brueckeZustimmungOffen(at.greenchili.surfmate.bruecke.Bruecke.get().ein(),
                        at.greenchili.surfmate.bruecke.Zustimmung.get().erteilt());
                if (bz != null) antwort.getJSONArray("noch_offen").put(bz);
            } catch (Throwable ignored) {
            }
            // Kern 5 (LADENACHFRAGE): „Noch offen: N Ladungen ohne Preis“ – die Lade-DB kann fehlen
            try {
                at.greenchili.surfmate.monitor.SocHistoryDatabase db = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
                if (db != null) {
                    int n = at.greenchili.surfmate.charging.LadeNachfrage.liste(db.chargingSessionsSeit(
                            at.greenchili.surfmate.charging.LadeNachfrage.SEIT, 300), root, null, null, false).getInt("anzahl");
                    JSONObject e = at.greenchili.surfmate.charging.LadeNachfrage.nochOffen(n);
                    if (e != null) antwort.getJSONArray("noch_offen").put(e);
                    // nach der Overdrive-Übernahme: alte Ladungen ohne Preis (Nachbepreisung über /api/laden/preis)
                    JSONObject alt = at.greenchili.surfmate.charging.LadeNachfrage.nochOffenAlt(
                            at.greenchili.surfmate.charging.LadeNachfrage.ohnePreisVorSeit(db.chargingSessionsSeit(0, 500)));
                    if (alt != null) antwort.getJSONArray("noch_offen").put(alt);
                }
            } catch (Throwable ignored) {
            }
            // Akkukapazität: eingetragener Wert passt zu keiner Katalogvariante (Altwert aus Overdrive?) – nie still korrigiert
            try {
                JSONObject v = root.optJSONObject("vehicle");
                if (v != null && v.has("nominalKwh")) {
                    String mid = v.optString("modelId", "");
                    JSONObject k = at.greenchili.surfmate.charging.KapazitaetPlausibel.offen(v.optDouble("nominalKwh", 0),
                            mid, ModelsApiHandler.modellEintrag(mid));
                    if (k != null) antwort.getJSONArray("noch_offen").put(k);
                }
            } catch (Throwable ignored) {
            }
            HttpResponse.sendJson(out, antwort.toString());
            return true;
        }
        if (!method.equals("POST")) return false;
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"json\"}");
            return true;
        }
        JSONObject root = UnifiedConfigManager.forceReload();
        Antwort a;
        switch (p) {
            case "/api/einrichtung/schritt":
                a = schritt(root, req.optString("id", ""), req.optJSONObject("antwort"),
                        felder(root), AuthMiddleware.isExternRequest(), System.currentTimeMillis());
                break;
            case "/api/einrichtung/spaeter":
                a = spaeter(root, req.optString("id", ""));
                break;
            case "/api/einrichtung/erledigt":
                // 0.1.38.8: „Erledigt / nicht nötig“ je optionalem Punkt (für Schritte, die sich nicht am Config-Zustand
                // prüfen lassen, z. B. Launcher/Hintergrund/Tiermodus/Assistenz). Blendet den Punkt dauerhaft aus.
                a = erledigt(root, req.optString("id", ""));
                break;
            case "/api/einrichtung/neu":
                a = neu(root);
                break;
            case "/api/einrichtung/durchgehen":
                // 0.1.38.3 („Wizard und Kamera muss jederzeit auch danach wieder einstellbar sein“)
                a = wiederholen(root, null);
                break;
            case "/api/einrichtung/oeffnen":
                a = wiederholen(root, req.optString("id", ""));
                break;
            case "/api/einrichtung/rueckfall_verschieben": {
                // 0.1.11: Rückfall-Aufnahmen auf den gewählten Datenträger verschieben (nur auf Wunsch, im Hintergrund)
                String fehler = at.greenchili.surfmate.storage.StorageManager.getInstance()
                        .rueckfallVerschiebenStarten(req.optString("bereich", ""));
                if (fehler == null) {
                    HttpResponse.sendJson(out, 202, new JSONObject().put("success", true).put("gestartet", true).toString());
                } else {
                    HttpResponse.sendJson(out, "laeuft_schon".equals(fehler) ? 409 : 422,
                            new JSONObject().put("success", false).put("error", fehler).toString());
                }
                return true;
            }
            case "/api/einrichtung/selbsttest":
                JSONObject st = selbsttest(root);
                JSONObject res = new JSONObject().put("success", true).put("selbsttest", st);
                HttpResponse.sendJson(out, res.toString());
                return true;
            default:
                return false;
        }
        if (a.code == 200) {
            boolean ok = true;
            for (Map.Entry<String, Map<String, Object>> s : a.schreiben.entrySet()) {
                ok &= UnifiedConfigManager.updateValues(s.getKey(), s.getValue());
            }
            if (a.locale != null) LocaleManager.set(a.locale);
            if (!ok) {
                HttpResponse.sendJson(out, 500, "{\"success\":false,\"error\":\"speichern\"}");
                return true;
            }
            JSONObject neuRoot = UnifiedConfigManager.forceReload();
            a.body.put("einrichtung", sicht(neuRoot, zustand(neuRoot), faehigkeiten(), null));
        }
        HttpResponse.sendJson(out, a.code, a.body.toString());
        return true;
    }

    private static void speichere(JSONObject z) {
        try {
            Map<String, Object> w = new HashMap<>();
            for (String k : new String[] {"version", "art", "neuZaehler", "schritte", "gruende", "werte"}) {
                if (z.has(k)) w.put(k, z.get(k));
            }
            UnifiedConfigManager.updateValues(ABSCHNITT, w);
        } catch (Exception ignored) {
        }
    }

    /**
     * 0.1.38.5 (Einrichtung verschlanken, Entscheidung b der Koordination): Updates werden im ersten Start nicht mehr
     * gefragt, sondern einmal sinnvoll vorbelegt – Kanal „stabil“ (Standard der {@code UpdateRegel}) + Korrekturen automatisch,
     * Mobilfunk-Download aus (einzeln am Hinweis erlaubbar). Nur bei einer Neuinstallation (art=neu) und nur, was noch
     * nicht gesetzt ist; der Bestand behält seine Wahl. Kein Datenabfluss nach außen – nur die Versionsabfrage.
     * Gibt true zurück, wenn etwas geschrieben wurde.
     */
    static boolean standardsNeuEinrichtung(JSONObject root, JSONObject z) {
        Map<String, Object> w = updateStandardNeu(root, z);
        if (w.isEmpty()) return false;
        try {
            return UnifiedConfigManager.updateValues("update", w);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Rein und testbar: die Update-Standardwerte, die eine Neuinstallation einmal bekommt (leer = nichts schreiben).
     * Nur bei art=neu; nur Felder, die noch nicht gesetzt sind (der Bestand behält seine Wahl).
     */
    public static Map<String, Object> updateStandardNeu(JSONObject root, JSONObject z) {
        Map<String, Object> w = new LinkedHashMap<>();
        if (root == null || z == null || !"neu".equals(z.optString("art"))) return w;
        JSONObject u = root.optJSONObject("update");
        if (u == null || !u.has("pruefen")) w.put("pruefen", true);
        // 0.1.38.6: „automatisch“ heißt jetzt „Alles automatisch“; neue Einrichtungen bekommen wie der Schritt
        // „updates“ nur „Korrekturen automatisch, Neues melden“
        if (u == null || !u.has("modus")) w.put("modus", at.greenchili.surfmate.appupdate.UpdateRegel.MODUS_KORREKTUREN);
        if (u == null || !u.has("mobilfunk")) { w.put("mobilfunk", false); w.put("mobilfunkEinmal", true); }
        return w;
    }

    private static List<Feld> felder(JSONObject root) {
        return Katalog.felder(EinstellungenApiHandler.kontext(root));
    }

    private static JSONObject faehigkeiten() {
        try {
            return VehicleCapabilities.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Teil G: echte Prüfungen im Daemon, Ergebnis bleibt im Zustand (GET zeigt es). */
    static JSONObject selbsttest(JSONObject root) {
        try {
            JSONObject f = faehigkeiten();
            JSONObject sicht = sicht(root, zustand(root), f, null);
            JSONObject st = at.greenchili.surfmate.einrichtung.Selbsttest.bewerte(
                    at.greenchili.surfmate.einrichtung.Selbsttest.messen(root, sicht, f));
            Map<String, Object> w = new HashMap<>();
            w.put("selbsttest", st);
            UnifiedConfigManager.updateValues(ABSCHNITT, w);
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ Zustand (rein, testbar)

    /**
     * Gespeicherter Zustand oder – beim ersten Aufruf – der Anfangszustand. Bestand (B2): Pflichtschritte
     * mit vorhandener Antwort gelten als erledigt, Schritte aus 0.1.9 entfallen, neue Schritte sind offen.
     * Neu hinzugekommene Schritt-IDs (spätere Versionen) sind immer offen.
     */
    public static JSONObject zustand(JSONObject root) throws Exception {
        JSONObject gespeichert = root == null ? null : root.optJSONObject(ABSCHNITT);
        JSONObject z = gespeichert == null ? anfang(root) : new JSONObject(gespeichert.toString());
        JSONObject s = z.optJSONObject("schritte");
        if (s == null) { s = new JSONObject(); z.put("schritte", s); }
        // 0.1.14: ein in einer späteren Version neu hinzugekommener Nicht-Pflicht-Schritt (z. B. extra_notzugang)
        // öffnet bei einer schon fertigen Einrichtung nie wieder den Assistenten → „später“ (Noch offen)
        boolean warFertig = ERLEDIGT.equals(s.optString("fertig"));
        for (String id : SCHRITTE) {
            if (!s.has(id)) s.put(id, warFertig && !PFLICHT.contains(id) && !"fertig".equals(id) ? SPAETER : OFFEN);
        }
        if (!z.has("gruende")) z.put("gruende", new JSONObject());
        // 0.1.38.3: während „Noch einmal durchgehen“ bleiben die wieder geöffneten Schritte offen
        if (z.optBoolean("wiederholung", false) && z.optJSONObject("vorher") != null) { /* nichts nachziehen */ }
        else if ("bestand".equals(z.optString("art"))) bestandNachpruefen(root, s);
        else schnellstartNachziehen(s);
        if (!z.has("werte")) z.put("werte", new JSONObject());
        z.put("version", VERSION);
        return z;
    }

    /**
     * 0.1.10.1 (Alfreds Auto, 22.10.): Bestand bekommt den Assistenten nur für FEHLENDE Pflichtantworten.
     * Bei jedem Lesen: offene Pflichtschritte mit vorhandener Antwort → erledigt; neue Nicht-Pflicht-Schritte → später
     * (Liste „Noch offen“, kein Assistent). Wirkt auch auf einen schon gespeicherten Zustand. Nach „neu“ (art=neu) nicht.
     */
    static void bestandNachpruefen(JSONObject root, JSONObject s) throws Exception {
        for (String id : SCHRITTE) {
            if ("fertig".equals(id) || !OFFEN.equals(s.optString(id))) continue;
            if (FRUEHER_PFLICHT.contains(id) && antwortVorhanden(root, id)) s.put(id, ERLEDIGT);
            else if (!PFLICHT.contains(id) && !BESTAND_019.contains(id)) s.put(id, SPAETER);
        }
        if (OFFEN.equals(s.optString("fertig")) && alleAnderenErledigt(s)) s.put("fertig", ERLEDIGT);
    }

    /** 0.1.32: Was nicht zum Schnellstart gehört, öffnet nie den Assistenten, auch nicht aus einem älteren Zwischenstand. */
    static void schnellstartNachziehen(JSONObject s) throws Exception {
        for (String id : SCHRITTE) {
            if (!SCHNELLSTART.contains(id) && OFFEN.equals(s.optString(id))) s.put(id, SPAETER);
        }
    }

    static boolean istBestand(JSONObject root) {
        if (root == null) return false;
        JSONObject h = root.optJSONObject("hinweise");
        JSONObject u = root.optJSONObject("update");
        JSONObject k = root.optJSONObject("karte");
        JSONObject v = root.optJSONObject("vehicle");
        JSONObject sv = root.optJSONObject("surveillance");
        return (h != null && h.has("version")) || (u != null && u.has("pruefen")) || (k != null && k.has("quelle"))
                || (sv != null && sv.has("triggerMode"))
                || (v != null && VehicleModelSelection.isResolvedSelection(v.optString("modelId", ""),
                        v.optString("modelSource", "")));
    }

    static JSONObject anfang(JSONObject root) throws Exception {
        boolean bestand = istBestand(root);
        JSONObject s = new JSONObject();
        JSONObject g = new JSONObject();
        for (String id : SCHRITTE) {
            String st = OFFEN;
            if (bestand) {
                if (FRUEHER_PFLICHT.contains(id) && antwortVorhanden(root, id)) {
                    st = ERLEDIGT;
                } else if (PFLICHT.contains(id)) {
                    st = OFFEN;
                } else if (BESTAND_019.contains(id)) {
                    st = ENTFAELLT;
                    g.put(id, "einr.grund.bestand");
                } else if (!"fertig".equals(id)) {
                    st = SPAETER;   // neu hinzugekommen: „Noch offen“, nicht der Assistent
                }
            }
            s.put(id, st);
        }
        if (!bestand) schnellstartNachziehen(s);
        if (bestand && !alleAnderenErledigt(s)) s.put("fertig", OFFEN);
        else if (bestand) s.put("fertig", ERLEDIGT);
        return new JSONObject().put("version", VERSION).put("art", bestand ? "bestand" : "neu")
                .put("neuZaehler", 0).put("schritte", s).put("gruende", g).put("werte", new JSONObject());
    }

    private static boolean alleAnderenErledigt(JSONObject s) {
        for (String id : SCHRITTE) {
            if (!"fertig".equals(id) && OFFEN.equals(s.optString(id))) return false;
        }
        return true;
    }

    /** Liegt für einen Pflichtschritt schon eine Antwort in der Config (Bestand)? */
    static boolean antwortVorhanden(JSONObject root, String id) {
        if (root == null) return false;
        switch (id) {
            case "sprache": {
                // 0.1.10.1: auch migrierte bzw. vor dem Merker gesetzte Sprache zählt (Alfreds Bestand)
                JSONObject n = root.optJSONObject("nativeShell");
                return n != null && (n.optBoolean(LocaleManager.K_GEWAEHLT, false)
                        || n.optBoolean(LocaleManager.K_MIGRIERT, false)
                        || SPRACHEN.contains(n.optString("locale", "")));
            }
            case "haftung": {
                JSONObject h = root.optJSONObject("hinweise");
                return h != null && Katalog.HINWEISE_VERSION.equals(h.optString("version", ""));
            }
            case "usb_freigabe":
                return true;   // dieser Endpunkt antwortet nur, wenn der freigegebene Dienst läuft
            case "auto": {
                JSONObject v = root.optJSONObject("vehicle");
                return v != null && VehicleModelSelection.isResolvedSelection(v.optString("modelId", ""),
                        v.optString("modelSource", ""));
            }
            case "waechter": {
                JSONObject sv = root.optJSONObject("surveillance");
                return sv != null && TriggerModus.gueltig(sv.optString("triggerMode", ""));
            }
            case "betrieb":
                return true;   // Bestand lief mit gewählter (bzw. bestätigter) Betriebsart
            case "karte": {
                JSONObject k = root.optJSONObject("karte");
                return k != null && k.has("quelle");
            }
            case "updates": {
                // 0.1.10.1 (Alfreds Auto): Ein Bestand lief schon mit seinen Update-Einstellungen bzw. den Standards
                // (prüfen an, Mobilfunk an, melden; Koordination 22.10.: Bestand darf keine Updates verlieren).
                // Nur für den Bestand ausgewertet (anfang/bestandNachpruefen) – also beantwortet.
                return true;
            }
            default:
                return false;
        }
    }

    /** Das §0-Format für GET. */
    /** 0.1.24: „Noch offen“-Eintrag, wenn die Brücke an ist, die Zustimmung aber fehlt; sonst null. */
    static JSONObject brueckeZustimmungOffen(boolean brueckeEin, boolean erteilt) throws Exception {
        if (!brueckeEin || erteilt) return null;
        return new JSONObject().put("id", "bruecke_zustimmung").put("textKey", "einr.hinweis.bruecke_zustimmung")
                .put("ziel", "system.html#kopplung");
    }

    public static JSONObject sicht(JSONObject root, JSONObject z, JSONObject faehigkeiten, JSONObject selbsttest)
            throws Exception {
        JSONObject s = z.getJSONObject("schritte");
        JSONObject g = z.optJSONObject("gruende");
        if (g == null) g = new JSONObject();
        // Teil E: ohne Kameras entfällt die Zuordnung (wird nur aus echten Daten abgeleitet)
        if (faehigkeiten != null && faehigkeiten.optJSONObject("kameras") != null
                && faehigkeiten.getJSONObject("kameras").has("anzahl")
                && faehigkeiten.getJSONObject("kameras").optInt("anzahl", -1) == 0
                && (OFFEN.equals(s.optString("kameras")) || SPAETER.equals(s.optString("kameras")))) {
            s.put("kameras", ENTFAELLT);
            g.put("kameras", "einr.grund.keine_kameras");
        }
        if (OFFEN.equals(s.optString("usb_freigabe"))) s.put("usb_freigabe", ERLEDIGT);   // Dienst antwortet
        // 0.1.38.3 (Community, Seal U DM-i: „Kameras übersprungen, danach nicht möglich“): Kameras sind immer
        // nachholbar. Ein Bestand (BESTAND_019) bekommt den Schritt nicht mehr als „entfällt“, sondern unter
        // „Noch einrichten“; erledigt ist er erst mit einer eigenen Zuordnung (echtEingerichtet: manualOverride).
        if (ENTFAELLT.equals(s.optString("kameras")) && "einr.grund.bestand".equals(g.optString("kameras"))) {
            s.put("kameras", SPAETER);
            g.remove("kameras");
        }
        // 0.1.38: „Tacho“ nur bei Autos, deren Tacho fremde Bilder zeigen kann (Single-OS kann es nie, TachoFaehigkeit)
        if (faehigkeiten != null && faehigkeiten.optBoolean("singleOs", false)
                && (OFFEN.equals(s.optString("extra_tacho")) || SPAETER.equals(s.optString("extra_tacho")))) {
            s.put("extra_tacho", ENTFAELLT);
            g.put("extra_tacho", "einr.grund.kein_tacho");
        }
        // 0.1.26: auch ein gemerktes „später“ entfällt, wenn keine Overdrive-Daten da sind
        String od = s.optString("extra_overdrive");
        if ((OFFEN.equals(od) || SPAETER.equals(od)) && !overdriveVorhanden()) {
            s.put("extra_overdrive", ENTFAELLT);
            g.put("extra_overdrive", "einr.grund.kein_overdrive");
        }
        // 0.1.26 (Alfreds Auto): „später“ bzw. „offen“ überdeckt nie, was am Auto wirklich eingerichtet ist
        Set<String> echt = new HashSet<>(echtEingerichtet(root, felderOhneIo(root), planerSchluesselDa(root)));
        // 0.1.38.8: nicht aus der Config prüfbare Schritte über Daemon-Haken (Standard false in Tests)
        if (hintergrundGesetzt()) echt.add("extra_display");
        if (launcherInstalliert()) echt.add("extra_launcher");
        for (String id : echt) {
            String st = s.optString(id, OFFEN);
            // 0.1.38.3: wieder geöffnete Schritte bleiben offen, auch wenn sie am Auto schon eingerichtet sind
            if (!PFLICHT.contains(id) && !imWiederholen(z, id) && (OFFEN.equals(st) || SPAETER.equals(st))) s.put(id, ERLEDIGT);
        }
        JSONArray liste = new JSONArray();
        String aktuell = null;
        boolean pflichtOffen = false;
        JSONArray spaeter = new JSONArray();
        for (String id : SCHRITTE) {
            String st = s.optString(id, OFFEN);
            // 0.1.38.3: beim Wiederholen ist nichts Pflicht und jeder wieder geöffnete Schritt kommt im Ablauf dran
            boolean wdh = imWiederholen(z, id);
            boolean frei = wdh && ERLEDIGT.equals(z.getJSONObject("vorher").optString(id, ""));   // schon beantwortet
            JSONObject e = new JSONObject().put("id", id).put("pflicht", PFLICHT.contains(id) && !frei).put("status", st);
            if (FORTGESCHRITTEN.contains(id)) e.put("gruppe", "fortgeschritten");
            e.put("schnellstart", SCHNELLSTART.contains(id) || wdh && OFFEN.equals(st));
            if (g.has(id)) e.put("grund", g.getString(id));
            liste.put(e);
            if (aktuell == null && OFFEN.equals(st)) aktuell = id;
            if (OFFEN.equals(st) && PFLICHT.contains(id) && !frei) pflichtOffen = true;
            if (SPAETER.equals(st)) spaeter.put(id);
        }
        JSONObject r = new JSONObject();
        r.put("version", VERSION);
        r.put("abgeschlossen", !pflichtOffen);
        r.put("aktuell", aktuell == null ? "fertig" : aktuell);
        r.put("schritte", liste);
        r.put("offen_spaeter", spaeter);
        r.put("faehigkeiten", faehigkeiten == null ? JSONObject.NULL : faehigkeiten);
        r.put("selbsttest", selbsttest == null ? JSONObject.NULL : selbsttest);
        r.put("werte", z.optJSONObject("werte") == null ? new JSONObject() : z.getJSONObject("werte"));
        r.put("neuZaehler", z.optInt("neuZaehler", 0));
        r.put("wiederholung", z.optBoolean("wiederholung", false));
        r.put("hinweise", hinweise(root, s));
        // H3: einmalige Einträge „Noch offen“ (Projektion aktiv, Sichtbarkeit unbelegt, Ton aus)
        JSONArray offen = at.greenchili.surfmate.einrichtung.ProjektionsHinweis.offen(root, tonVon(root));
        // Strompreis außerhalb des plausiblen Bands (TRIPKOSTEN_BEFUND: 0,01 €/kWh aus Overdrive) – nie still korrigiert
        JSONArray preis = at.greenchili.surfmate.charging.PreisPlausibel.offen(root);
        for (int i = 0; i < preis.length(); i++) offen.put(preis.get(i));
        // Ordner SurfMate → Panoptes nicht umbenannt (beide da / rename fehlgeschlagen) – je Datenträger
        JSONArray ordner = at.greenchili.surfmate.storage.AppOrdner.offen();
        for (int i = 0; i < ordner.length(); i++) offen.put(ordner.get(i));
        r.put("noch_offen", offen);
        r.put("art", z.optString("art", "neu"));
        return r;
    }

    /**
     * 0.1.26 (Alfreds Auto, „Noch offen“ voller Erledigtem): Nicht-Pflicht-Schritte, die am Auto wirklich eingerichtet
     * sind – abgeleitet aus dem echten Zustand, nicht aus dem Merker. Der Bestand bekommt neue Schritte pauschal als
     * „später“ ({@link #anfang}, {@link #bestandNachpruefen}); das darf nie einen erledigten Schritt überdecken.
     * „rechte“ kennt nur die App (Android-Rechte), sie meldet sie über {@code POST /api/einrichtung/schritt}.
     */
    /**
     * 0.1.38.8 (Wächter Standard an – REGEL NR.2 ist hier von Projektinhaber bewusst aufgehoben): Soll der Bestand beim ersten
     * Start auf dieser Version EINMALIG mit Rechtshinweis gefragt werden, ob der Wächter an soll? Nur, wenn der Nutzer
     * den Wächter nie eingerichtet hat (kein gültiger triggerMode) UND ihn nicht bewusst ausgeschaltet hat. Eine
     * vorhandene Wahl – auch ausdrücklich „aus“ – bleibt unangetastet (wird nie still eingeschaltet). Rein/testbar.
     */
    public static boolean waechterStandardFragen(JSONObject root) {
        JSONObject sv = root == null ? null : root.optJSONObject("surveillance");
        if (sv == null) return true;
        if (TriggerModus.gueltig(sv.optString("triggerMode", ""))) return false;   // schon eingerichtet (inkl. „aus“)
        if (sv.optBoolean("disabledByUser", false)) return false;                  // bewusst ausgeschaltet
        return true;
    }

    public static Set<String> echtEingerichtet(JSONObject root, List<Feld> felder, boolean planerSchluessel) {
        Set<String> e = new HashSet<>();
        if (root == null) return e;
        // 0.1.38.8 (Wächter Standard an): ist der Wächter an, gilt der Schritt als erledigt (fällt aus „Noch einrichten“).
        JSONObject svAn = root.optJSONObject("surveillance");
        if (svAn != null && svAn.optBoolean("surveillanceEnabled", false)) e.add("waechter");
        JSONObject tg = root.optJSONObject("telegram");
        if (tg != null && tg.optLong("ownerChatId", 0) > 0) e.add("extra_telegram");
        JSONObject k = root.optJSONObject("kopplungen");
        JSONArray geraete = k == null ? null : k.optJSONArray("geraete");
        for (int i = 0; geraete != null && i < geraete.length(); i++) {
            JSONObject gr = geraete.optJSONObject(i);
            String art = gr == null ? "" : gr.optString("art", "");
            if (at.greenchili.surfmate.auth.Kopplungen.ART_HANDY.equals(art)) e.add("extra_handy");
            // Kopplungen vor 0.1.25 haben kein „art“ – das waren EV-Cockpits (Alfreds Auto: „EV-Cockpit home“ vom 21.10.)
            if ("cockpit".equals(art) || art.isEmpty()) e.add("extra_cockpit");
        }
        if (planerSchluessel) e.add("extra_planer");
        // 0.1.38.3: Kameras gelten erst als eingerichtet, wenn der Nutzer selbst zugeordnet hat. Die automatische
        // Rundumsicht-Probe (probedAndValidated) zählt nicht, sie hat niemand angesehen. DiLink 5 speichert die
        // Zuordnung als dilink5CameraMapping (dort gibt es keine manuelle Kamera-ID).
        JSONObject cam = root.optJSONObject("camera");
        if (cam != null && (cam.optBoolean("manualOverride", false) || !cam.optString("dilink5CameraMapping", "").isEmpty())) {
            e.add("kameras");
        }
        if (at.greenchili.surfmate.notzugang.Notzugang.an(root)) e.add("extra_notzugang");
        JSONObject sc = root.optJSONObject("speedcam");
        if (sc != null && sc.optBoolean("enabled", false)) e.add("blitzer");
        // 0.1.38.8 (Projektinhaber, „Noch einrichten": „vieles davon hab ich doch schon?"): drei der neuen 0.1.38-Schritte
        // lassen sich am echten Config-Zustand erkennen; erkannt heißt „erledigt" (fällt aus „Noch einrichten").
        // extra_tacho: der Hinweis ist bestätigt (Pflicht vor jeder Freigabe) oder es ist schon eine App freigegeben.
        JSONObject tn = root.optJSONObject("tachoNavi");
        if (tn != null && (tn.optLong("hinweisVerstanden", 0) > 0
                || (tn.optJSONArray("freigaben") != null && tn.optJSONArray("freigaben").length() > 0))) {
            e.add("extra_tacho");
        }
        // extra_abschaltbericht: der Nutzer hat den Schalter angefasst (Standard an, Schlüssel erst nach Eingriff da).
        if (tg != null && tg.has("abschaltBericht")) e.add("extra_abschaltbericht");
        // extra_ladesaeule: Erkennung angefasst (Standard an) oder schon eine Säule gemerkt.
        JSONObject ld = root.optJSONObject("laden");
        if (ld != null && ld.has("saeuleNachschlagen")) e.add("extra_ladesaeule");
        JSONObject ls = root.optJSONObject("ladesaeule");
        if (ls != null && ls.optJSONArray("gemerkt") != null && ls.optJSONArray("gemerkt").length() > 0) e.add("extra_ladesaeule");
        // extra_overdrive: eine echte Übernahme ist gelaufen (Marker, s. UebernahmeApiHandler) → nicht mehr anbieten.
        JSONObject ue = root.optJSONObject("uebernahme");
        if (ue != null && ue.optBoolean("overdriveErledigt", false)) e.add("extra_overdrive");
        // Daten teilen: mindestens eine Freigabe der Gruppe „datenschutz“ ist eingeschaltet
        for (Feld f : felder == null ? java.util.Collections.<Feld>emptyList() : felder) {
            if (f.typ != Feld.Typ.BOOL || !"datenschutz".equals(f.gruppe) || f.zugriff == null) continue;
            try {
                if (Boolean.TRUE.equals(f.zugriff.lese(root))) { e.add("daten"); break; }
            } catch (Exception ignored) {
            }
        }
        return e;
    }

    /** Planer-Schlüssel gespeichert (Gerät oder Mitglied); nur Vorhandensein, kein Entschlüsseln nötig. */
    static boolean planerSchluesselDa(JSONObject root) {
        JSONObject p = root == null ? null : root.optJSONObject("planner");
        if (p == null) return false;
        if (!p.optString("geraeteSchluessel", "").isEmpty()) return true;
        return at.greenchili.surfmate.planer.PlanerSchluessel.kopf(p, x -> null) != null;
    }

    private static List<Feld> felderOhneIo(JSONObject root) {
        try {
            return Katalog.felder(kontextOhneIo(root));
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Teil C: solange Betriebsart bzw. Karte nicht beantwortet sind, gilt sparsamer Betrieb bzw. keine Karte –
     * das sagt der Assistent dazu (Textschlüssel, Text liefert der Neubau).
     */
    static JSONArray hinweise(JSONObject root, JSONObject schritte) {
        JSONArray h = new JSONArray();
        if (!ERLEDIGT.equals(schritte.optString("betrieb")) && sparsamBisBetrieb(root)) {
            h.put("einr.hinweis.sparsam_bis_betrieb");
        }
        JSONObject k = root == null ? null : root.optJSONObject("karte");
        if (!ERLEDIGT.equals(schritte.optString("karte")) && (k == null || !k.has("quelle"))) {
            h.put("einr.hinweis.keine_karte");
        }
        return h;
    }

    /**
     * Neuinstallation ohne beantwortete Betriebsart und ohne ausdrückliches Energieprofil → sparsam
     * (kein stiller Standard „Immer an“ mit vollem Verbrauch). Bestand bleibt unberührt.
     */
    public static boolean sparsamBisBetrieb(JSONObject root) {
        if (root == null) return false;
        JSONObject e = root.optJSONObject("energie");
        if (e != null && e.has("profil")) return false;
        JSONObject z = root.optJSONObject(ABSCHNITT);
        if (z == null || !"neu".equals(z.optString("art"))) return false;
        JSONObject s = z.optJSONObject("schritte");
        return s == null || !ERLEDIGT.equals(s.optString("betrieb"));
    }

    /**
     * 0.1.32: {@code update.pruefen} ungesetzt heißt „an“, das gilt nur für den Bestand. Eine neue Einrichtung, die die
     * Update-Frage noch nicht beantwortet hat, ruft nichts ab (Regel 2). Handprüfung und gesetzte Werte bleiben unberührt.
     */
    public static boolean updatesUnbeantwortet(JSONObject root) {
        if (root == null) return false;
        JSONObject u = root.optJSONObject("update");
        if (u != null && u.has("pruefen")) return false;
        JSONObject z = root.optJSONObject(ABSCHNITT);
        // neuZaehler > 0: „Einrichtung erneut starten“ an einem laufenden Auto, das prüft weiter wie bisher
        if (z == null || !"neu".equals(z.optString("art")) || z.optInt("neuZaehler", 0) > 0) return false;
        JSONObject s = z.optJSONObject("schritte");
        return s == null || !ERLEDIGT.equals(s.optString("updates"));
    }

    /** Erste Einrichtung dieses Autos: kein Bestand, nie „erneut gestartet“, Mobilfunk-Wert nie gesetzt. */
    static boolean ersteEinrichtung(JSONObject root) throws Exception {
        JSONObject z = zustand(root);
        JSONObject u = root == null ? null : root.optJSONObject("update");
        return "neu".equals(z.optString("art")) && z.optInt("neuZaehler", 0) == 0 && (u == null || !u.has("mobilfunk"));
    }

    static String tonVon(JSONObject root) {
        return at.greenchili.surfmate.blitzer.BlitzerDienst.ton(root == null ? null : root.optJSONObject("speedcam"));
    }

    private static boolean overdriveVorhanden() {
        try {
            return overdriveErkennung.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hintergrundGesetzt() {
        try {
            return hintergrundErkennung.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean launcherInstalliert() {
        try {
            return launcherErkennung.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ Änderungen (rein, testbar)

    /** Ergebnis einer Änderung: HTTP-Code, Antwort und was zu schreiben ist (Abschnitt → Werte). */
    public static final class Antwort {
        public int code = 200;
        public final JSONObject body = new JSONObject();
        public final Map<String, Map<String, Object>> schreiben = new LinkedHashMap<>();
        public String locale;

        static Antwort fehler(int code, String error) throws Exception {
            Antwort a = new Antwort();
            a.code = code;
            a.body.put("success", false).put("error", error);
            return a;
        }
    }

    public static Antwort schritt(JSONObject root, String id, JSONObject antwort, List<Feld> felder,
                                  boolean extern, long jetztMs) throws Exception {
        if (!SCHRITTE.contains(id)) return Antwort.fehler(404, "unbekannt");
        JSONObject an = antwort == null ? new JSONObject() : antwort;
        JSONObject einst = new JSONObject();          // Werte für die Katalog-Prüfung
        JSONArray bestaetigt = new JSONArray();
        Map<String, Map<String, Object>> direkt = new LinkedHashMap<>();
        String locale = null;
        JSONArray fehlt = new JSONArray();
        switch (id) {
            case "sprache":
                if (!SPRACHEN.contains(an.optString("locale", ""))) fehlt.put("locale");
                else locale = an.getString("locale");
                break;
            case "haftung":
                // App-Vorlauf: Häkchen vor dem Dienst gesetzt → nachgereichter Zeitpunkt, wenn plausibel
                long z = an.optLong("zeit", 0L);
                if (z >= ZEIT_MIN_MS && z <= jetztMs) jetztMs = z;
                if (!an.optBoolean("gelesen", false)) fehlt.put("gelesen");
                if (!Katalog.HINWEISE_VERSION.equals(an.optString("version", ""))) fehlt.put("version");
                if (fehlt.length() == 0) {
                    einst.put("hinweise.version", Katalog.HINWEISE_VERSION);
                    bestaetigt.put("hinweise.version");
                }
                break;
            case "auto":
                if (an.optString("modell", "").trim().isEmpty()) fehlt.put("modell");
                break;
            case "waechter": {
                String m = an.optString("modus", "");
                if (!TriggerModus.gueltig(m)) fehlt.put("modus");
                if (!(an.opt("ton") instanceof Boolean)) fehlt.put("ton");
                if (fehlt.length() == 0) {
                    einst.put("surveillance.triggerMode", m);
                    bestaetigt.put("surveillance.triggerMode");
                    boolean waechterAn = !TriggerModus.AUS.equals(m);
                    direkt.computeIfAbsent("recording", x -> new HashMap<>()).put("audioEnabled",
                            waechterAn && an.getBoolean("ton"));
                    // §0 „Wächter an/aus“: „aus“ schaltet den Wächter ab (keine Aufnahme), jede andere Wahl an
                    Map<String, Object> svDirekt = direkt.computeIfAbsent("surveillance", x -> new HashMap<>());
                    svDirekt.put("surveillanceEnabled", waechterAn);
                    // 0.1.38.8: „aus“ ist eine bewusste Entscheidung (Rechtshinweis gesehen) → nicht wieder einschalten;
                    // jede andere Wahl bestätigt zugleich den Wächter-Rechtshinweis (guardLegal), festgehalten als Version.
                    svDirekt.put("disabledByUser", !waechterAn);
                    if (waechterAn) svDirekt.put("guardLegalVersion", Katalog.GUARD_LEGAL_VERSION);
                }
                // 0.1.28 P10: Ereignis-Clip beim Fahren (Vollbremsung/Stoß) – eigene Frage ohne Vorauswahl.
                // Optional für ältere Oberflächen; fehlt sie, gilt der Standard nach Land wie bisher.
                Object ec = an.opt("ereignisClip");
                if (ec != null && ec != JSONObject.NULL) {
                    if (!(ec instanceof Boolean)) fehlt.put("ereignisClip");
                    else if (fehlt.length() == 0) einst.put("recording." + at.greenchili.surfmate.recording.EreignisClip.SCHALTER, ec);
                }
                break;
            }
            case "betrieb": {
                String m = an.optString("modus", "");
                if (!"immer".equals(m) && !"fahrt".equals(m)) fehlt.put("modus");
                else direkt.computeIfAbsent("surveillance", x -> new HashMap<>())
                        .put("operatingMode", "fahrt".equals(m) ? "onOnly" : "onAndOff");
                // 0.1.28 P10 (SETUP_WIZARD §2 Nr. 9): „Wächter stoppt …“ als eine einfache Wahl; nur bei „Immer an“
                // (sonst läuft Panoptes im Stand nicht). Optional für ältere Oberflächen. 12 V bleibt nach Akku-Art.
                Object st = an.opt("stopp");
                if (st != null && st != JSONObject.NULL) {
                    int[] w = STOPP.get(String.valueOf(st));
                    if (w == null) fehlt.put("stopp");
                    else if ("immer".equals(m)) {
                        einst.put("guardStop.enabled", true);
                        einst.put("guardStop.socBelow", w[0]);
                        einst.put("guardStop.maxParkHours", w[1]);
                    }
                }
                break;
            }
            case "karte": {
                String q = an.optString("quelle", "");
                if (!Kartenquelle.COMMUNITY.equals(q) && !Kartenquelle.OSM.equals(q) && !Kartenquelle.KEINE.equals(q)) {
                    fehlt.put("quelle");
                } else {
                    einst.put("karte.quelle", q);
                }
                break;
            }
            case "updates": {
                if (!(an.opt("pruefen") instanceof Boolean)) fehlt.put("pruefen");
                Object mob = an.opt("mobilfunk");
                if (mob != null && mob != JSONObject.NULL && !(mob instanceof Boolean)) fehlt.put("mobilfunk");
                // 0.1.38.6 (04.11.): ohne Angabe bekommen neue Einrichtungen „Korrekturen automatisch“, der
                // Bestand behält seinen Wert (kein Wert = „Nur melden“)
                String korr = at.greenchili.surfmate.appupdate.UpdateRegel.MODUS_KORREKTUREN;
                String mo = an.optString("modus", ersteEinrichtung(root) ? korr : "");
                if (!mo.isEmpty() && !"melden".equals(mo) && !"automatisch".equals(mo) && !korr.equals(mo)) {
                    fehlt.put("modus");
                }
                if (fehlt.length() == 0) {
                    einst.put("update.pruefen", an.getBoolean("pruefen"));
                    if (!mo.isEmpty()) einst.put("update.modus", mo);
                    if (mob instanceof Boolean) einst.put("update.mobilfunk", mob);
                    else if (ersteEinrichtung(root)) {
                        // 0.1.32 fremdes Datenvolumen: nur neue Einrichtungen laden im WLAN, jeden Mobilfunk-Download
                        // erlaubt der Tipp am Hinweis einzeln. Der Bestand behält seinen Wert.
                        Map<String, Object> u = direkt.computeIfAbsent("update", x -> new HashMap<>());
                        u.put("mobilfunk", false);
                        u.put("mobilfunkEinmal", true);
                    }
                }
                break;
            }
            case "strompreis": {
                // Strompreis zu Hause (AC) je kWh, ohne Vorauswahl (Koordination 22.10.); Währung optional
                // „Zuhause gratis“ ist eine eigene Wahl (Koordination 22.10.) → 0 €/kWh, ausdrücklich bestätigt
                boolean gratis = an.optBoolean("gratis", false);
                Object v = an.opt("acRate");
                double rate = gratis ? 0 : v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
                if (!gratis && !(rate > 0 && rate < 100)) fehlt.put("acRate");
                String cur = an.optString("waehrung", "").trim();
                if (!cur.isEmpty() && (cur.length() > 4 || cur.contains(" "))) fehlt.put("waehrung");
                if (fehlt.length() == 0) {
                    Map<String, Object> t = direkt.computeIfAbsent("tripAnalytics", x -> new HashMap<>());
                    t.put("electricityRate", rate);
                    t.put("rateGratis", gratis);
                    if (!cur.isEmpty()) t.put("currency", cur);
                }
                break;
            }
            case "daten": {
                // Nur genannte Freigaben (BOOL der Gruppe „datenschutz“); nicht genannte bleiben, wie sie sind (aus).
                java.util.Iterator<String> it = an.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Feld f = Katalog.finde(felder, k);
                    if (f == null || f.typ != Feld.Typ.BOOL || !"datenschutz".equals(f.gruppe)
                            || !(an.opt(k) instanceof Boolean)) {
                        fehlt.put(k);
                        continue;
                    }
                    einst.put(k, an.getBoolean(k));
                    if (f.bestaetigung != null) bestaetigt.put(k);   // die Liste zeigt je Schalter Was/Wofür/Wohin
                }
                break;
            }
            default:
                break;   // Prüf-, Info- und Extra-Schritte: Rückmeldung genügt
        }
        if (fehlt.length() > 0) {
            Antwort a = Antwort.fehler(422, "fehlt");
            a.body.put("fehlt", fehlt);
            return a;
        }
        Antwort a = new Antwort();
        if (einst.length() > 0) {
            JSONObject req = new JSONObject().put("werte", einst).put("bestaetigt", bestaetigt);
            final Map<String, Map<String, Object>> gesammelt = new LinkedHashMap<>();
            Einstellungen.Ergebnis e = Einstellungen.aendern(felder, root, kontextOhneIo(root), req, extern,
                    (abschnitt, werte) -> {
                        gesammelt.computeIfAbsent(abschnitt, x -> new HashMap<>()).putAll(werte);
                        return true;
                    }, jetztMs);
            if (!e.ok()) {
                Antwort f = Antwort.fehler(422, "ungueltig");
                f.body.put("fehler", e.fehler);
                return f;
            }
            a.schreiben.putAll(gesammelt);
        }
        for (Map.Entry<String, Map<String, Object>> d : direkt.entrySet()) {
            a.schreiben.computeIfAbsent(d.getKey(), x -> new HashMap<>()).putAll(d.getValue());
        }
        JSONObject z = zustand(root);
        // Prüf-Schritt mit ok:false: Antwort merken, Schritt bleibt, wie er war (offen bzw. später)
        boolean nichtOk = PRUEF.contains(id) && Boolean.FALSE.equals(an.opt("ok"));
        String neuerStatus = nichtOk ? z.getJSONObject("schritte").optString(id, OFFEN) : ERLEDIGT;
        if ("usb_freigabe".equals(id)) neuerStatus = ERLEDIGT;   // der Dienst antwortet = Freigabe da
        z.getJSONObject("schritte").put(id, neuerStatus);
        if ("fertig".equals(id) && z.optBoolean("wiederholung", false)) wiederholungBeenden(z);
        z.getJSONObject("werte").put(id, an);
        // Zeitpunkt jeder Antwort (Haftung: ggf. nachgereicht; Autostart: Selbsttest vergleicht mit der Installation)
        z.getJSONObject("werte").getJSONObject(id).put("zeit", jetztMs);
        a.schreiben.computeIfAbsent(ABSCHNITT, x -> new HashMap<>()).putAll(zustandWerte(z));
        a.locale = locale;
        a.body.put("success", true).put("id", id).put("status", neuerStatus);
        return a;
    }

    /** Der Katalog-Kontext ohne Gerätezugriff ist in Tests nötig; im Daemon liefert ihn der Handler. */
    private static Katalog.Kontext kontextOhneIo(JSONObject root) {
        try {
            return EinstellungenApiHandler.kontext(root);
        } catch (Throwable t) {
            return new Katalog.Kontext();
        }
    }

    public static Antwort spaeter(JSONObject root, String id) throws Exception {
        if (!SCHRITTE.contains(id)) return Antwort.fehler(404, "unbekannt");
        JSONObject z = zustand(root);
        // 0.1.38.3: Beim Wiederholen ist jeder Schritt überspringbar; der Stand von vorher bleibt (nichts geht verloren)
        String vorher = imWiederholen(z, id) ? z.getJSONObject("vorher").optString(id, "") : "";
        // Pflicht bleibt Pflicht, solange sie nie beantwortet war
        if (PFLICHT.contains(id) && !ERLEDIGT.equals(vorher)) return Antwort.fehler(422, "pflicht");
        String neu = vorher.isEmpty() || OFFEN.equals(vorher) ? SPAETER : vorher;
        z.getJSONObject("schritte").put(id, neu);
        Antwort a = new Antwort();
        a.schreiben.put(ABSCHNITT, zustandWerte(z));
        a.body.put("success", true).put("id", id).put("status", neu);
        return a;
    }

    /**
     * 0.1.38.8: „Erledigt / nicht nötig“ – ein optionaler Schritt wird dauerhaft als erledigt markiert, ohne ihn im
     * Assistenten durchzugehen. Für Punkte, die sich nicht am echten Config-Zustand erkennen lassen (Launcher,
     * Hintergrundbild/Display-Sperre, Tiermodus, Assistenz, Overdrive). Pflicht und „fertig“ sind ausgenommen (422).
     * Entfallene bleiben entfallen. Grund-Merker {@code einr.grund.manuell}, damit „Alle Schritte“ es sauber zeigt.
     */
    public static Antwort erledigt(JSONObject root, String id) throws Exception {
        if (!SCHRITTE.contains(id)) return Antwort.fehler(404, "unbekannt");
        if (PFLICHT.contains(id) || "fertig".equals(id)) return Antwort.fehler(422, "pflicht");
        JSONObject z = zustand(root);
        if (ENTFAELLT.equals(z.getJSONObject("schritte").optString(id, OFFEN))) return Antwort.fehler(422, "entfaellt");
        z.getJSONObject("schritte").put(id, ERLEDIGT);
        JSONObject g = z.optJSONObject("gruende");
        if (g == null) { g = new JSONObject(); z.put("gruende", g); }
        g.put(id, "einr.grund.manuell");
        Antwort a = new Antwort();
        a.schreiben.put(ABSCHNITT, zustandWerte(z));
        a.body.put("success", true).put("id", id).put("status", ERLEDIGT);
        return a;
    }

    /** 0.1.38.3: Schritte, die beim „Noch einmal durchgehen“ nicht wieder aufgehen (USB-Freigabe: kein Popup ohne Grund). */
    static final Set<String> NICHT_WIEDERHOLEN = new HashSet<>(Arrays.asList("usb_freigabe", "fertig"));

    /** Gehört der Schritt zur laufenden Wiederholung? */
    static boolean imWiederholen(JSONObject z, String id) {
        if (!z.optBoolean("wiederholung", false) || z.optJSONObject("vorher") == null) return false;
        JSONArray ids = z.optJSONArray("wiederholIds");
        if (ids == null) return true;
        for (int i = 0; i < ids.length(); i++) if (id.equals(ids.optString(i))) return true;
        return false;
    }

    /**
     * 0.1.38.3: Einrichtung noch einmal durchgehen ({@code nurId == null}) bzw. einen einzelnen Schritt wieder öffnen.
     * Einstellungen und Antworten bleiben; der alte Status wird gemerkt („Später“ stellt ihn wieder her, „fertig“
     * räumt auf). Zähler +1, damit die App den Assistenten am Bildschirm im Auto öffnet. Entfallene Schritte bleiben weg.
     */
    public static Antwort wiederholen(JSONObject root, String nurId) throws Exception {
        if (nurId != null && (!SCHRITTE.contains(nurId) || NICHT_WIEDERHOLEN.contains(nurId))) {
            return Antwort.fehler(404, "unbekannt");
        }
        JSONObject z = zustand(root);
        JSONObject s = z.getJSONObject("schritte");
        JSONObject vorher = z.optBoolean("wiederholung", false) && z.optJSONObject("vorher") != null
                ? z.getJSONObject("vorher") : new JSONObject(s.toString());
        JSONArray ids = new JSONArray();
        for (String id : SCHRITTE) {
            if (NICHT_WIEDERHOLEN.contains(id) || ENTFAELLT.equals(s.optString(id))) continue;
            if (nurId != null && !nurId.equals(id)) continue;
            s.put(id, OFFEN);
            ids.put(id);
        }
        z.put("wiederholung", true).put("vorher", vorher);
        if (nurId == null) z.remove("wiederholIds"); else z.put("wiederholIds", ids);
        z.put("neuZaehler", z.optInt("neuZaehler", 0) + 1);
        Antwort a = new Antwort();
        a.schreiben.put(ABSCHNITT, zustandWerte(z));
        a.body.put("success", true).put("neuZaehler", z.getInt("neuZaehler")).put("schritte", ids);
        return a;
    }

    /** Am Ende einer Wiederholung: nicht beantwortete Schritte bekommen ihren alten Status zurück. */
    static void wiederholungBeenden(JSONObject z) throws Exception {
        JSONObject vorher = z.optJSONObject("vorher");
        JSONObject s = z.getJSONObject("schritte");
        if (vorher != null) {
            for (String id : SCHRITTE) {
                if ("fertig".equals(id) || !OFFEN.equals(s.optString(id))) continue;
                String v = vorher.optString(id, "");
                if (!v.isEmpty() && !OFFEN.equals(v)) s.put(id, v);
            }
        }
        z.remove("wiederholung");
        z.remove("vorher");
        z.remove("wiederholIds");
    }

    /**
     * B3: Schnellstart wieder offen, Zähler +1 (die App löscht daraufhin ihre lokalen Merker,
     * {@code OnboardingState.abgleichen}). Gewählte Einstellungen bleiben in der Config; {@code werte}
     * bleibt als Vorschlag – Einwilligungen zeigt die Oberfläche trotzdem ohne Vorauswahl.
     * 0.1.32: Erledigtes außerhalb des Schnellstarts bleibt erledigt (ein Bestand verliert so z. B. nicht „updates“).
     */
    public static Antwort neu(JSONObject root) throws Exception {
        JSONObject z = zustand(root);
        JSONObject alt = z.getJSONObject("schritte");
        JSONObject s = new JSONObject();
        for (String id : SCHRITTE) {
            s.put(id, !SCHNELLSTART.contains(id) && ERLEDIGT.equals(alt.optString(id)) ? ERLEDIGT : OFFEN);
        }
        schnellstartNachziehen(s);
        z.put("schritte", s);
        z.put("gruende", new JSONObject());
        z.put("art", "neu");
        z.remove("wiederholung");
        z.remove("vorher");
        z.remove("wiederholIds");
        z.put("neuZaehler", z.optInt("neuZaehler", 0) + 1);
        Antwort a = new Antwort();
        a.schreiben.put(ABSCHNITT, zustandWerte(z));
        a.body.put("success", true).put("neuZaehler", z.getInt("neuZaehler"));
        return a;
    }

    private static Map<String, Object> zustandWerte(JSONObject z) throws Exception {
        Map<String, Object> w = new LinkedHashMap<>();
        for (String k : new String[] {"version", "art", "neuZaehler", "schritte", "gruende", "werte"}) {
            if (z.has(k)) w.put(k, z.get(k));
        }
        // 0.1.38.3: Wiederholung; null löscht den Schlüssel beim Schreiben (nach „fertig“ bzw. „neu“)
        for (String k : new String[] {"wiederholung", "vorher", "wiederholIds"}) {
            w.put(k, z.has(k) ? z.get(k) : JSONObject.NULL);
        }
        return w;
    }
}
