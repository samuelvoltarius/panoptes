package at.greenchili.surfmate.server;

import at.greenchili.surfmate.sprache.SprachBefehle;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 0.1.44 (P2) Sprachbefehle im Kern: {@code POST /api/sprache/v1/befehl {befehl, marke?}}.
 *
 * <ul>
 *   <li>Nur die Panoptes-App direkt am Auto ({@link SprachAufrufer}); sonst 403. Nur mit Schalter
 *       {@value SprachBefehle#SCHALTER} (ab Werk aus); sonst 409 {@code schalter_aus}.</li>
 *   <li>Nur Befehle aus {@link SprachBefehle#KERN}; alles andere 400. Hupe, Blinker, Licht, Alarm gibt es hier nicht
 *       (Regel A+), auch keine Fenster, Türen oder Ladeklappe.</li>
 *   <li>Fahrzeugbefehle ({@link SprachBefehle#FAHRZEUG}) erst nach Rückfrage: ohne Marke kommt nur
 *       {@code {rueckfrage:true, marke}} zurück, ausgeführt wird erst mit dieser Marke (einmal, 2 Minuten gültig).
 *       Klima geht über dieselben Regeln wie die Launcher-Klima-Kachel ({@link LauncherKlimaBefehl}): Stufen-Modell,
 *       wärmer/kälter = eine Stufe, nie eine erfundene Gradzahl, Ratenlimit, Fahrsperre im Router.</li>
 *   <li>Musik: Medientaste (wie {@code /api/vehicle/media}). Reichweite und Ort: nur lesen, Ort nur aus dem
 *       Zwischenspeicher (kein Netz).</li>
 * </ul>
 * Jedes Ergebnis steht im Befehls-Log als {@code sprache.*}.
 */
public final class SprachApiHandler {

    static final String PFAD = "/api/sprache/v1/befehl";
    static final String ZIEL = "sprache";
    static final Rueckfrage RUECKFRAGE = new Rueckfrage();

    /** Lesen und Wirkung (im Test nachgebaut). */
    interface Kern {
        boolean schalterAn();
        Object[] klima(String aktion, long jetzt) throws Exception;
        boolean medientaste(String taste);
        /** Reichweite in km oder null. */
        Integer reichweiteKm();
        /** Ortsname aus dem Zwischenspeicher oder null. */
        String ort();
    }

    static volatile Kern kern = new Echt();

    private SprachApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.startsWith("/api/sprache/")) return false;
        if (!PFAD.equals(p)) { HttpResponse.sendJson(out, 404, "{\"ok\":false,\"grund\":\"pfad\"}"); return true; }
        if (!"POST".equals(method)) { HttpResponse.sendJson(out, 405, "{\"ok\":false,\"grund\":\"methode\"}"); return true; }
        String wer = AuthMiddleware.isDirektAmAuto() ? SprachAufrufer.pruefe() : SprachAufrufer.NICHT_LOKAL;
        if (wer != null) {
            BefehlsLog.grund(wer);
            HttpResponse.sendJson(out, 403, new JSONObject().put("ok", false).put("grund", wer).toString());
            return true;
        }
        Object[] r = ausfuehren(body, System.currentTimeMillis());
        HttpResponse.sendJson(out, (Integer) r[0], r[1].toString());
        return true;
    }

    /** Antwort {@code {status, json}} (ohne Aufrufer-Prüfung, die macht {@link #handle}). */
    static Object[] ausfuehren(String body, long jetzt) throws Exception {
        JSONObject in;
        try {
            in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            in = new JSONObject();
        }
        String befehl = in.optString("befehl", "");
        JSONObject o = new JSONObject().put("ok", false).put("befehl", befehl);
        Kern k = kern;
        if (!k.schalterAn()) return new Object[]{409, o.put("grund", "schalter_aus")};
        if (!SprachBefehle.KERN.contains(befehl)) {
            BefehlsLog.grund("befehl");
            return new Object[]{400, o.put("grund", "befehl")};
        }
        if (SprachBefehle.brauchtRueckfrage(befehl)) {
            String marke = in.optString("marke", "");
            if (marke.isEmpty()) {
                // Erst fragen: die App zeigt „Klima einschalten? Ja/Nein“ und hört auf Ja/Nein.
                return new Object[]{200, o.put("rueckfrage", true).put("marke", RUECKFRAGE.frage(befehl, ZIEL, jetzt))};
            }
            if (!RUECKFRAGE.bestaetigt(marke, befehl, ZIEL, jetzt)) {
                BefehlsLog.grund("rueckfrage");
                BefehlsLog.ereignis("sprache." + befehl, "abgelehnt", null, null);
                return new Object[]{409, o.put("grund", "rueckfrage_ungueltig")};
            }
            Object[] kl = k.klima(SprachBefehle.KLIMA_AKTION.get(befehl), jetzt);
            JSONObject j = (JSONObject) kl[1];
            j.put("befehl", befehl);
            return new Object[]{kl[0], j};
        }
        String taste = SprachBefehle.MEDIENTASTE.get(befehl);
        if (taste != null) {
            boolean ok = k.medientaste(taste);
            BefehlsLog.ereignis("sprache." + befehl, ok ? "ok" : "fehler", null, null);
            return new Object[]{200, o.put("ok", ok)};
        }
        if (SprachBefehle.REICHWEITE.equals(befehl)) {
            Integer km = k.reichweiteKm();
            if (km == null) return new Object[]{200, o.put("grund", "unbekannt")};
            return new Object[]{200, o.put("ok", true).put("km", km.intValue())};
        }
        // WO_BIN_ICH
        String ort = k.ort();
        if (ort == null || ort.isEmpty()) return new Object[]{200, o.put("grund", "unbekannt")};
        return new Object[]{200, o.put("ok", true).put("ort", ort)};
    }

    private static final class Echt implements Kern {
        @Override
        public boolean schalterAn() {
            try {
                JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject(SprachBefehle.ABSCHNITT);
                return s != null && s.optBoolean("befehle", false);
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public Object[] klima(String aktion, long jetzt) throws Exception {
            return LauncherKlimaBefehl.ausfuehren(new JSONObject().put("aktion", aktion).toString(), jetzt, "sprache.klima.");
        }

        @Override
        public boolean medientaste(String taste) {
            return VehicleControlApiHandler.dispatchMediaKey(taste);
        }

        @Override
        public Integer reichweiteKm() {
            try {
                at.greenchili.surfmate.byd.BydVehicleData d = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
                if (d == null || d.elecRangeKm == at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE || d.elecRangeKm < 0) return null;
                return d.elecRangeKm;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public String ort() {
            try {
                at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
                long alter = System.currentTimeMillis() - g.getLastUpdate();
                if (g.getLastUpdate() <= 0 || alter < 0 || alter > 10 * 60_000L) return null;
                // Nur Zwischenspeicher: keine Anfrage ins Netz für einen Sprachbefehl.
                at.greenchili.surfmate.geo.PlaceResult r =
                        at.greenchili.surfmate.geo.GeocodingResolver.getInstance().resolveCachedOnly(g.getLatitude(), g.getLongitude());
                return r == null ? null : r.displayName;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
