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

    /** Pflicht = ohne Antwort geht es nicht weiter, kein „Später“ (B4). */
    public static final Set<String> PFLICHT = new HashSet<>(Arrays.asList(
            "sprache", "haftung", "usb_freigabe", "auto", "waechter", "betrieb", "karte", "updates"));

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
            if (root.optJSONObject(ABSCHNITT) == null) speichere(z);   // Bestand/Neu einmal festhalten
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
            case "/api/einrichtung/neu":
                a = neu(root);
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
        if ("bestand".equals(z.optString("art"))) bestandNachpruefen(root, s);
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
            if (PFLICHT.contains(id)) {
                if (antwortVorhanden(root, id)) s.put(id, ERLEDIGT);
            } else if (!BESTAND_019.contains(id)) {
                s.put(id, SPAETER);
            }
        }
        if (OFFEN.equals(s.optString("fertig")) && alleAnderenErledigt(s)) s.put("fertig", ERLEDIGT);
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
                if (PFLICHT.contains(id)) {
                    st = antwortVorhanden(root, id) ? ERLEDIGT : OFFEN;
                } else if (BESTAND_019.contains(id)) {
                    st = ENTFAELLT;
                    g.put(id, "einr.grund.bestand");
                } else if (!"fertig".equals(id)) {
                    st = SPAETER;   // neu hinzugekommen: „Noch offen“, nicht der Assistent
                }
            }
            s.put(id, st);
        }
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
                && OFFEN.equals(s.optString("kameras"))) {
            s.put("kameras", ENTFAELLT);
            g.put("kameras", "einr.grund.keine_kameras");
        }
        if (OFFEN.equals(s.optString("usb_freigabe"))) s.put("usb_freigabe", ERLEDIGT);   // Dienst antwortet
        // 0.1.26: auch ein gemerktes „später“ entfällt, wenn keine Overdrive-Daten da sind
        String od = s.optString("extra_overdrive");
        if ((OFFEN.equals(od) || SPAETER.equals(od)) && !overdriveVorhanden()) {
            s.put("extra_overdrive", ENTFAELLT);
            g.put("extra_overdrive", "einr.grund.kein_overdrive");
        }
        // 0.1.26 (Alfreds Auto): „später“ bzw. „offen“ überdeckt nie, was am Auto wirklich eingerichtet ist
        for (String id : echtEingerichtet(root, felderOhneIo(root), planerSchluesselDa(root))) {
            String st = s.optString(id, OFFEN);
            if (!PFLICHT.contains(id) && (OFFEN.equals(st) || SPAETER.equals(st))) s.put(id, ERLEDIGT);
        }
        JSONArray liste = new JSONArray();
        String aktuell = null;
        boolean pflichtOffen = false;
        JSONArray spaeter = new JSONArray();
        for (String id : SCHRITTE) {
            String st = s.optString(id, OFFEN);
            JSONObject e = new JSONObject().put("id", id).put("pflicht", PFLICHT.contains(id)).put("status", st);
            if (FORTGESCHRITTEN.contains(id)) e.put("gruppe", "fortgeschritten");
            if (g.has(id)) e.put("grund", g.getString(id));
            liste.put(e);
            if (aktuell == null && OFFEN.equals(st)) aktuell = id;
            if (OFFEN.equals(st) && PFLICHT.contains(id)) pflichtOffen = true;
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
    public static Set<String> echtEingerichtet(JSONObject root, List<Feld> felder, boolean planerSchluessel) {
        Set<String> e = new HashSet<>();
        if (root == null) return e;
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
        if (at.greenchili.surfmate.notzugang.Notzugang.an(root)) e.add("extra_notzugang");
        JSONObject sc = root.optJSONObject("speedcam");
        if (sc != null && sc.optBoolean("enabled", false)) e.add("blitzer");
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
                    direkt.computeIfAbsent("recording", x -> new HashMap<>()).put("audioEnabled",
                            !TriggerModus.AUS.equals(m) && an.getBoolean("ton"));
                    // §0 „Wächter an/aus“: „aus“ schaltet den Wächter ab (keine Aufnahme), jede andere Wahl an
                    direkt.computeIfAbsent("surveillance", x -> new HashMap<>()).put("surveillanceEnabled",
                            !TriggerModus.AUS.equals(m));
                }
                break;
            }
            case "betrieb": {
                String m = an.optString("modus", "");
                if (!"immer".equals(m) && !"fahrt".equals(m)) fehlt.put("modus");
                else direkt.computeIfAbsent("surveillance", x -> new HashMap<>())
                        .put("operatingMode", "fahrt".equals(m) ? "onOnly" : "onAndOff");
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
                String mo = an.optString("modus", "melden");
                if (!"melden".equals(mo) && !"automatisch".equals(mo)) fehlt.put("modus");
                if (fehlt.length() == 0) {
                    einst.put("update.pruefen", an.getBoolean("pruefen"));
                    einst.put("update.modus", mo);
                    if (mob instanceof Boolean) einst.put("update.mobilfunk", mob);
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
        if (PFLICHT.contains(id)) return Antwort.fehler(422, "pflicht");
        JSONObject z = zustand(root);
        z.getJSONObject("schritte").put(id, SPAETER);
        Antwort a = new Antwort();
        a.schreiben.put(ABSCHNITT, zustandWerte(z));
        a.body.put("success", true).put("id", id).put("status", SPAETER);
        return a;
    }

    /**
     * B3: alle Schritte wieder offen, Zähler +1 (die App löscht daraufhin ihre lokalen Merker,
     * {@code OnboardingState.abgleichen}). Gewählte Einstellungen bleiben in der Config; {@code werte}
     * bleibt als Vorschlag – Einwilligungen zeigt die Oberfläche trotzdem ohne Vorauswahl.
     */
    public static Antwort neu(JSONObject root) throws Exception {
        JSONObject z = zustand(root);
        JSONObject s = new JSONObject();
        for (String id : SCHRITTE) s.put(id, OFFEN);
        z.put("schritte", s);
        z.put("gruende", new JSONObject());
        z.put("art", "neu");
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
        return w;
    }
}
