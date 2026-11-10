package at.greenchili.surfmate.server;

import at.greenchili.surfmate.BuildConfig;
import at.greenchili.surfmate.analytics.Mitzaehlen;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.rueckmeldung.AutoAngaben;
import at.greenchili.surfmate.rueckmeldung.FehlerMelden;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.UUID;

/**
 * 0.1.31 (Regel 2). Nur Login oder lokal, nie für gekoppelte Geräte (ExternGuard-Erlaubnisliste, hier doppelt):
 * <ul>
 *   <li>{@code GET /api/rueckmeldung/fehler[?diagnose=1&sprache=de]} – was automatisch mitgeht, Vorschau, Stand</li>
 *   <li>{@code POST /api/rueckmeldung/fehler {text, sprache, vorschauId?, art?}} – eine Meldung absenden</li>
 *   <li>{@code GET /api/rueckmeldung/zaehlen} – ist die einmalige Frage offen, und darf sie jetzt kommen?</li>
 *   <li>{@code POST /api/rueckmeldung/zaehlen {antwort:"ja"|"nein"}} – Antwort, nur im Stand</li>
 * </ul>
 */
public final class RueckmeldungApiHandler {

    private RueckmeldungApiHandler() {}

    static final long VORSCHAU_MS = 15 * 60_000L;
    /** 0.1.38.14 (P3): Fehlerbericht ohne Oberfläche (Telegram /fehler, eingefrorene Anzeige). */
    public static final String TELEGRAM_PFAD = "/api/rueckmeldung/fehler-telegram";
    /** Darüber gilt das Auto als fahrend: Am Display nicht tippen. */
    static final double FAHRT_KMH = 3.0;

    private static String vorschauId;
    private static String vorschauText;
    private static long vorschauZeit;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        boolean fehler = pathOnly.equals("/api/rueckmeldung/fehler");
        boolean zaehlen = pathOnly.equals("/api/rueckmeldung/zaehlen");
        // 0.1.38.14 (P3): Telegram /fehler – nur lokal (Bot-Daemon), der Bot prüft vorher den Besitzer-Chat
        boolean telegram = pathOnly.equals(TELEGRAM_PFAD);
        if (!fehler && !zaehlen && !telegram) return false;
        if (AuthMiddleware.isExternRequest()) {
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"verboten\"}");
            return true;
        }
        if (fehler && method.equals("GET")) {
            HttpResponse.sendJson(out, stand(query, System.currentTimeMillis()).toString());
            return true;
        }
        if (fehler && method.equals("POST")) {
            absenden(body, out);
            return true;
        }
        if (telegram && method.equals("POST")) {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                req = new JSONObject();
            }
            JSONObject res = berichtOhneSeite(req.optString("text", ""));
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (zaehlen && method.equals("GET")) {
            JSONObject z = UnifiedConfigManager.loadConfig().optJSONObject(Mitzaehlen.ABSCHNITT);
            boolean offen = Mitzaehlen.frageOffen(z);
            HttpResponse.sendJson(out, new JSONObject().put("offen", offen)
                    .put("fragen", offen && imStand()).toString());
            return true;
        }
        if (zaehlen && method.equals("POST")) {
            antworten(body, out);
            return true;
        }
        HttpResponse.sendError(out, 405, "Method Not Allowed");
        return true;
    }

    private static JSONObject stand(String query, long jetzt) throws Exception {
        String sprache = EinstellungenApiHandler.sprache(query);
        JSONObject res = auto(UnifiedConfigManager.loadConfig(), sprache);
        JSONObject st = FehlerMelden.DATEI_SPEICHER.lese();
        JSONArray offen = st == null ? null : st.optJSONArray("offen");
        JSONArray codes = st == null ? null : st.optJSONArray("codes");
        res.put("offen", offen == null ? 0 : offen.length())
                .put("codes", codes == null ? new JSONArray() : codes)
                .put("rest", FehlerMelden.rest(st, jetzt))
                .put("textMax", FehlerMelden.TEXT_MAX)
                .put("fahrt", faehrt());
        if (("&" + query + "&").contains("&diagnose=1&")) {
            // gleiche Teile wie diagnoseGesamt() (Telegram /fehler); 0.1.38.14: START_STUFE dazu
            String text = waechterZeile() + startZeilen()
                    + FehlerMelden.diagnoseText(at.greenchili.surfmate.genai.GenAiContext.diagnosticLogs());
            String id = UUID.randomUUID().toString();
            synchronized (RueckmeldungApiHandler.class) {
                vorschauId = id;
                vorschauText = text;
                vorschauZeit = jetzt;
            }
            res.put("diagnose", text).put("vorschauId", id);
        }
        return res;
    }

    /** Wächter-Zeile, START_STUFE (0.1.38.14) und Log-Auszug: genau das zeigt die Vorschau, genau das geht mit. */
    static String diagnoseGesamt() {
        return waechterZeile() + startZeilen()
                + FehlerMelden.diagnoseText(at.greenchili.surfmate.genai.GenAiContext.diagnosticLogs());
    }

    /** 0.1.38.14 (P3): letzte START_STUFE-Zeilen aus dem Launcher-Ereignisprotokoll (Ende der Datei). */
    static String startZeilen() {
        try {
            java.io.File f = new java.io.File(at.greenchili.surfmate.logging.LauncherEreignisse.DATEI);
            if (!f.isFile()) return "";
            long n = f.length();
            int lies = (int) Math.min(n, 32 * 1024L);
            byte[] b = new byte[lies];
            try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                r.seek(n - lies);
                r.readFully(b);
            }
            return FehlerMelden.startStufen(new String(b, java.nio.charset.StandardCharsets.UTF_8), FehlerMelden.START_MAX);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 0.1.38.14 (P3): Bildschirmgröße und -dichte des Geräts (Seal U ≠ Surf); im JVM-Test leer. */
    static Object bildschirmAngaben() {
        try {
            android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
            JSONObject b = FehlerMelden.bildschirm(m.widthPixels, m.heightPixels, m.densityDpi);
            return b == null ? new JSONObject() : b;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 0.1.38.14 (P3): gewählte Kartenquelle (community/osm/keine) – Hänger beim Umschalten. */
    static String karteQuelle(JSONObject root) {
        try {
            return at.greenchili.surfmate.einstellungen.Kartenquelle.aus(root);
        } catch (Throwable t) {
            return "unbekannt";
        }
    }

    /**
     * 0.1.38.14 (P3): derselbe Bericht wie „Fehler melden“, aber ohne Seite (Anzeige eingefroren): Telegram
     * {@code /fehler [Text]}. Diagnose-Auszug wie in der Vorschau, Anzeige-Felder fehlen (keine Seite).
     */
    static JSONObject berichtOhneSeite(String text) {
        try {
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) t = "Telegram /fehler";
            if (!FehlerMelden.textOk(t)) t = t.substring(0, Math.min(t.length(), FehlerMelden.TEXT_MAX));
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject k = FehlerMelden.koerper(t, BuildConfig.VERSION_NAME, AutoAngaben.dilink(),
                    AutoAngaben.modell(root), LocaleManager.get(), diagnoseGesamt(), "", false, null);
            k = FehlerMelden.mitErkannt(k, at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.kurz(
                    at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.jetzt()));
            if (k != null) {
                k.put("tacho", tachoProfil()).put("gang", gangAngaben())
                        .put("bildschirm", bildschirmAngaben()).put("karte", karteQuelle(root));
            }
            FehlerMelden.Ergebnis e = FehlerMelden.absenden(System.currentTimeMillis(), k,
                    FehlerMelden.DATEI_SPEICHER, FehlerMelden.NETZ);
            JSONObject res = new JSONObject().put("ok", e.stand == FehlerMelden.Stand.GESENDET
                    || e.stand == FehlerMelden.Stand.GEMERKT).put("stand", e.stand.name().toLowerCase(java.util.Locale.ROOT));
            if (e.code != null) res.put("code", e.code);
            return res;
        } catch (Throwable t) {
            try {
                return new JSONObject().put("ok", false).put("stand", "fehler");
            } catch (org.json.JSONException j) {
                return new JSONObject();
            }
        }
    }

    /** 0.1.38.11: eine Zeile Wächter-Zustand + Grund (WaechterGrund, ohne Ort/Kennung) vor den Log-Zeilen. */
    static String waechterZeile() {
        try {
            return "[waechter]\n" + FehlerMelden.nachschwaerzen(at.greenchili.surfmate.surveillance.WaechterGrund.zeile(
                    at.greenchili.surfmate.daemon.CameraDaemon.getSurveillanceStatus(),
                    SurveillanceApiHandler.zuendungAn(), SurveillanceApiHandler.verriegelt())) + "\n";
        } catch (Throwable t) {
            return "[waechter]\nWAECHTER_ZUSTAND nicht_lesbar\n";
        }
    }

    /** Was automatisch mitgeht: genau das zeigt die Seite an. */
    static JSONObject auto(JSONObject root, String sprache) throws Exception {
        JSONObject a = new JSONObject().put("version", BuildConfig.VERSION_NAME)
                .put("modell", AutoAngaben.modell(root)).put("sprache", sprache);
        a.put("gang", gangAngaben());   // 0.1.38.13: Gang-Rohwert, frisch, Zündung, Gerätemodell (wird angezeigt)
        Integer d = AutoAngaben.dilink();
        if (d != null) a.put("dilink", (int) d);
        // K2: was Panoptes am Gerät erkennt (Kamera-Eingang + Sicherheit), unabhängig vom gespeicherten Modus
        a.put("erkannt", at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.kurz(
                at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.jetzt()));
        // 0.1.38.10: was das Häkchen „Modell und Firmware mitschicken“ sendet (nur zur Anzeige; gesendet wird nur mit Häkchen)
        String fw = AutoAngaben.firmware();
        a.put("abdeckung", new JSONObject().put("modell", AutoAngaben.modell(root))
                .put("firmware", fw == null ? JSONObject.NULL : fw));
        // 0.1.38.11: Größenprofil des Fahrerdisplays (Tacho zu groß/abgeschnitten): {profil, bestaetigt, vorschlag, geraet}
        a.put("tacho", tachoProfil());
        // 0.1.38.14 (P3): Bildschirm (Pixel, dpi) und Kartenquelle – werden angezeigt und gehen mit
        a.put("bildschirm", bildschirmAngaben()).put("karte", karteQuelle(root));
        return a;
    }

    /** Größenprofil fürs Fahrerdisplay; im JVM-Test ohne Daemon leer. */
    static JSONObject tachoProfil() {
        try {
            return at.greenchili.surfmate.surveillance.ClusterProjectionController.profilStand();
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Gang für „Fehler melden“; ohne Daemon (JVM-Test) leer. */
    static JSONObject gangAngaben() {
        try {
            return at.greenchili.surfmate.monitor.GearMonitor.fehlerAngaben();
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void absenden(String body, OutputStream out) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"json\"}");
            return;
        }
        long jetzt = System.currentTimeMillis();
        String diagnose = null;
        String vid = req.optString("vorschauId", "");
        if (!vid.isEmpty()) {
            // Mit geht genau die gezeigte Vorschau, nie ein neu gelesener oder vom Aufrufer gelieferter Text
            synchronized (RueckmeldungApiHandler.class) {
                if (vid.equals(vorschauId) && jetzt - vorschauZeit <= VORSCHAU_MS) diagnose = vorschauText;
            }
            if (diagnose == null) {
                HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"vorschauAlt\"}");
                return;
            }
        }
        String l = req.optString("sprache", "");
        String sprache = LocaleManager.isSupported(l) ? l : LocaleManager.get();
        // 0.1.38.10: Modell und Firmware für die Abdeckungs-Liste nur mit dem Häkchen (Standard aus), sonst ohne
        boolean abdeckung = req.optBoolean("abdeckung", false);
        JSONObject k = FehlerMelden.koerper(req.optString("text", ""), BuildConfig.VERSION_NAME, AutoAngaben.dilink(),
                AutoAngaben.modell(UnifiedConfigManager.loadConfig()), sprache, diagnose, req.optString("art", ""),
                abdeckung, abdeckung ? AutoAngaben.firmware() : null);
        // K2: erkannter Kamera-Eingang (nur „modus/sicherheit“) zusätzlich, wie auf der Seite angezeigt
        k = FehlerMelden.mitErkannt(k, at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.kurz(
                at.greenchili.surfmate.camera.dilink5.AutoArtErkennung.jetzt()));
        if (k != null) k.put("tacho", tachoProfil());   // 0.1.38.11: Größenprofil + Modellname des Geräts
        // 0.1.38.11 (M1): WebView-Hauptversion, CSS-Breite und Pixeldichte der meldenden Seite (geprüft, feste Felder)
        FehlerMelden.mitAnzeige(k, FehlerMelden.anzeige(req.optJSONObject("anzeige")));
        if (k != null) k.put("gang", gangAngaben());   // 0.1.38.13: wie in der Vorschau
        if (k != null) {   // 0.1.38.14 (P3): wie in der Vorschau
            k.put("bildschirm", bildschirmAngaben()).put("karte", karteQuelle(UnifiedConfigManager.loadConfig()));
        }
        FehlerMelden.Ergebnis e = FehlerMelden.absenden(jetzt, k, FehlerMelden.DATEI_SPEICHER, FehlerMelden.NETZ);
        JSONObject res = new JSONObject().put("ok", e.stand == FehlerMelden.Stand.GESENDET
                || e.stand == FehlerMelden.Stand.GEMERKT).put("stand", e.stand.name().toLowerCase(java.util.Locale.ROOT));
        if (e.code != null) res.put("code", e.code);
        HttpResponse.sendJson(out, e.stand == FehlerMelden.Stand.UNGUELTIG ? 400
                : e.stand == FehlerMelden.Stand.ZUVIEL ? 429 : e.stand == FehlerMelden.Stand.ABGELEHNT ? 502 : 200, res.toString());
    }

    /**
     * Tacho-Test-Ergebnis (0.1.37) ueber denselben Weg wie „Fehler melden“. Aufruf nur aus
     * {@code TachoApiHandler} nach ausdruecklicher Einwilligung fuer genau diesen Text.
     */
    static JSONObject sendeTachoTest(String text) throws Exception {
        JSONObject k = FehlerMelden.koerper(text, BuildConfig.VERSION_NAME, AutoAngaben.dilink(),
                AutoAngaben.modell(UnifiedConfigManager.loadConfig()), LocaleManager.get(), null,
                FehlerMelden.ART_TACHO);
        if (k != null) k.put("tacho", tachoProfil());
        FehlerMelden.Ergebnis e = FehlerMelden.absenden(System.currentTimeMillis(), k,
                FehlerMelden.DATEI_SPEICHER, FehlerMelden.NETZ);
        JSONObject res = new JSONObject().put("ok", e.stand == FehlerMelden.Stand.GESENDET
                || e.stand == FehlerMelden.Stand.GEMERKT).put("stand", e.stand.name().toLowerCase(java.util.Locale.ROOT));
        if (e.code != null) res.put("code", e.code);
        return res;
    }

    private static void antworten(String body, OutputStream out) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            req = new JSONObject();
        }
        String a = req.optString("antwort", "");
        JSONObject z = UnifiedConfigManager.forceReload().optJSONObject(Mitzaehlen.ABSCHNITT);
        if ((!a.equals("ja") && !a.equals("nein")) || !Mitzaehlen.frageOffen(z)) {
            HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"nichtOffen\"}");
            return;
        }
        if (!imStand()) {
            HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"nichtImStand\"}");
            return;
        }
        boolean ja = a.equals("ja");
        UnifiedConfigManager.updateSection(Mitzaehlen.ABSCHNITT, Mitzaehlen.antwort(ja, java.time.Instant.now().toString()));
        if (ja) new Thread(() -> Mitzaehlen.taktImDaemon(System.currentTimeMillis()), "ZaehlenSofort").start();
        HttpResponse.sendJson(out, "{\"ok\":true}");
    }

    private static boolean imStand() {
        try {
            return at.greenchili.surfmate.communication.VehicleCommunicationSafety.isParked() && !faehrt();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean faehrt() {
        try {
            double kmh = at.greenchili.surfmate.byd.Stillstand.tempoJetzt(
                    at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData());
            return kmh > FAHRT_KMH;
        } catch (Throwable t) {
            return false;
        }
    }
}
