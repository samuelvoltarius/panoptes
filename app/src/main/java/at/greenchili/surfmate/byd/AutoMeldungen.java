package at.greenchili.surfmate.byd;

import at.greenchili.surfmate.server.HttpResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 0.1.43.20 „Meldungen des Autos“ (Konkurrenz-Check 24.11., Vorbild BYDMate „Fehlercodes lesen“) – NUR LESEN.
 *
 * <p>Quelle: {@code BYDAutoInstrumentDevice.getMalfunctionInfo(int typ)}, öffentlich dokumentiert im BYD-SDK v1.0.5
 * (Rückgabe {@code DEVICE_HAS_THE_MALFUNCTION=1} / {@code DEVICE_NOT_HAS_THE_MALFUNCTION=0}). Das ist derselbe
 * Getter-Weg wie Tempo, Reifen oder Kilometerstand: kein UDS, keine OBD-Befehle, kein {@code BYDAutoTestDevice}, kein CAN,
 * nichts Schreibendes ({@code setClearFault} wird NIE aufgerufen). Die Typnummern sind in SDK v1.0.5 und Firmware
 * 13.1.32.2510230 gleich (z. B. ABS 6, Reifendruck 13, OK 19); neuere Typen bis 0x3a stammen aus der Firmware.
 *
 * <ul>
 *   <li>{@code GET /api/auto/meldungen} – Login (nicht in {@code ExternGuard}); höchstens alle 30 s neu gelesen.</li>
 *   <li>Nur Wert 1 ist eine Meldung, 0 ist in Ordnung, alles andere (Fehlercode, nicht vorhanden) zählt als unbekannt.</li>
 *   <li>Antwortet kein einziger Typ mit 0 oder 1, gibt das Auto keine Meldungen heraus ({@code verfuegbar=false});
 *       die Startseite blendet die Kachel dann aus. Keine Vermutung, kein „alles in Ordnung“ ohne Beleg.</li>
 * </ul>
 */
public final class AutoMeldungen {

    private AutoMeldungen() {}

    public static final String PFAD = "/api/auto/meldungen";
    static final String GERAET = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";
    static final long CACHE_MS = 30_000L;

    /** Typnummer → Schlüssel (i18n {@code auto_meldungen.typ.<schluessel>}). MALFUNCTION_OK (19) fehlt bewusst. */
    static final Object[][] TYPEN = {
            {1, "anzeige"}, {2, "oeldruck"}, {3, "feststellbremse"}, {4, "ladesystem"}, {5, "motor"},
            {6, "abs"}, {7, "esp"}, {8, "luftverlust"}, {9, "wassertemperatur"}, {10, "elektrische_feststellbremse"},
            {11, "airbag"}, {12, "lenkhilfe"}, {13, "reifendruck"}, {14, "svs"}, {15, "motortemperatur"},
            {16, "akku"}, {17, "akkutemperatur"}, {18, "antrieb"}, {20, "ev"}, {21, "hybrid"},
            {22, "schluessel"}, {23, "gurt"}, {24, "lenkung"}, {25, "waermemanagement"}, {26, "elektronisches_parken"},
            {27, "ev_funktion"}, {28, "gangwahl"}, {29, "zwoelf_volt"}, {30, "motorsystem"}, {31, "motor_teilfunktion"},
            {32, "getriebe_funktion"}, {33, "getriebe"}, {34, "bremsbelaege"}, {35, "licht_vorne_links"},
            {36, "licht_vorne_rechts"}, {37, "licht_vorne"}, {38, "speicher"}, {39, "schiebedach"}, {40, "ladedose"},
            {41, "abstandstempomat"}, {42, "spurassistent"}, {43, "verkehrszeichen"}, {44, "fernlichtassistent"},
            {45, "toter_winkel"}, {46, "notbremsassistent"}, {47, "gleichstrom"}, {48, "ladesaeule"},
            {49, "bordlader"}, {50, "wechselstrom_ladegeraet"}, {51, "ladestecker"}, {52, "kuehlmittel"},
            {53, "oelstand"}, {54, "netzwerk"}, {55, "hochvolt_isolation"}, {56, "motor_kuehlung"},
            {57, "zwoelf_volt_system"}, {58, "ladestecker_steckt"}};

    /** Ein Lesezugriff je Typ; null = nicht lesbar. */
    public interface Leser {
        Integer info(int typ);
    }

    /** Rein: alle Typen fragen und auswerten. */
    public static JSONObject auswerten(Leser l, long jetzt) throws Exception {
        JSONArray m = new JSONArray();
        int bekannt = 0, unbekannt = 0;
        for (Object[] t : TYPEN) {
            int typ = (Integer) t[0];
            Integer v = null;
            try {
                v = l == null ? null : l.info(typ);
            } catch (Throwable ignored) {
            }
            if (v == null || (v != 0 && v != 1)) {
                unbekannt++;
                continue;
            }
            bekannt++;
            if (v == 1) m.put(new JSONObject().put("typ", typ).put("schluessel", t[1]));
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("verfuegbar", bekannt > 0);
        o.put("meldungen", m);
        o.put("geprueft", bekannt);
        o.put("unbekannt", unbekannt);
        o.put("stand", jetzt);
        return o;
    }

    private static JSONObject letzte;
    private static long letzteZeit;

    /** Laufzeit: Instrument-Gerät über den Kontext des Daemons, Getter mit einem int. */
    static synchronized JSONObject lesen(long jetzt) throws Exception {
        if (letzte != null && jetzt - letzteZeit < CACHE_MS && jetzt >= letzteZeit) return letzte;
        Object geraet = null;
        try {
            android.content.Context ctx = at.greenchili.surfmate.daemon.CameraDaemon.getAppContext();
            if (ctx != null) geraet = BydDeviceHelper.getDevice(GERAET, ctx);
        } catch (Throwable ignored) {
        }
        final Object g = geraet;
        JSONObject o = auswerten(g == null ? null : typ -> {
            Object r = BydDeviceHelper.callGetter(g, "getMalfunctionInfo", typ);
            return r instanceof Number ? ((Number) r).intValue() : null;
        }, jetzt);
        letzte = o;
        letzteZeit = jetzt;
        return o;
    }

    /** HTTP; false = nicht zuständig. */
    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals(PFAD)) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendJson(out, 405, "{\"ok\":false,\"grund\":\"methode\"}");
            return true;
        }
        HttpResponse.sendJson(out, 200, lesen(System.currentTimeMillis()).toString());
        return true;
    }
}
