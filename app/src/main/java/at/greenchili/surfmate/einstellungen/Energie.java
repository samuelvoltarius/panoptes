package at.greenchili.surfmate.einstellungen;

import org.json.JSONObject;

/**
 * Energie-Profil (ENERGIE_INTERVALLE.md, Koordination 21.10.2026: Batterieschutz im Stand). Rein, testbar.
 *
 * <p>{@code energie.profil}: {@code normal} | {@code sparsam} | {@code minimal}. <b>Nicht gesetzt = normal</b> –
 * Alt-Installationen (auch Alfreds Auto) verhalten sich exakt wie bisher, bis jemand bewusst umstellt (erst nach der
 * 12-V-Messung). Neue Installationen bekommen {@code sparsam} über die Einrichtung (Schema-Merkmal {@code empfohlen}).
 *
 * <p>Ein ausdrücklich gesetzter Einzelwert gewinnt immer gegen das Profil (Experten, bestehende Configs).
 * Untergrenzen schützen den Wächter: Kamera nie unter 2 fps im Leerlauf (Gesundheitswächter BILD_MAX 60 s),
 * bei Bewegung immer volle Rate (Idle-Drossel fährt beim ersten Bewegungsereignis hoch).
 */
public final class Energie {

    private Energie() {}

    public static final String NORMAL = "normal", SPARSAM = "sparsam", MINIMAL = "minimal";

    public static String profil(JSONObject root) {
        JSONObject e = root == null ? null : root.optJSONObject("energie");
        String p = e == null ? "" : e.optString("profil", "");
        if (SPARSAM.equals(p) || MINIMAL.equals(p)) return p;
        // 0.1.10 Einrichtung C: Neuinstallation ohne beantwortete Betriebsart → sparsam (Bestand: normal wie bisher)
        if (p.isEmpty() && at.greenchili.surfmate.server.EinrichtungApiHandler.sparsamBisBetrieb(root)) return SPARSAM;
        return NORMAL;
    }

    private static JSONObject abschnitt(JSONObject root, String name) {
        return root == null ? null : root.optJSONObject(name);
    }

    /**
     * Künstliche Nutzeraktivität im Stand (AccSentry, alle 10 s; hält AP/USB-Strom wach).
     * Ausdrücklich {@code surveillance.keepUsbPowerOnAccOff} gewinnt. Sonst: normal = an (bisher),
     * sparsam = nur wenn der Wächter eingeschaltet ist, minimal = aus.
     */
    public static boolean kuenstlicheAktivitaet(JSONObject root, boolean waechterAn) {
        JSONObject s = abschnitt(root, "surveillance");
        if (s != null && s.has("keepUsbPowerOnAccOff")) return s.optBoolean("keepUsbPowerOnAccOff", true);
        switch (profil(root)) {
            case SPARSAM: return waechterAn;
            case MINIMAL: return false;
            default: return true;
        }
    }

    /** App-Wachhalter (DaemonKeepalive) im Stand behalten? normal = ja, sparsam = nur mit Wächter, minimal = nein. */
    public static boolean appWachhalterImStand(JSONObject root, boolean waechterAn) {
        switch (profil(root)) {
            case SPARSAM: return waechterAn;
            case MINIMAL: return false;
            default: return true;
        }
    }

    /** GPS-Weitergabe im Stand (keine frischen Fixes): Sekunden zwischen zwei Sendungen. Fahrt immer 1 s. */
    public static int gpsStandSekunden(JSONObject root) {
        JSONObject e = abschnitt(root, "energie");
        if (e != null && e.has("gpsStandSekunden")) return klemme(e.optInt("gpsStandSekunden", 1), 1, 60);
        switch (profil(root)) {
            case SPARSAM: return 10;
            case MINIMAL: return 30;
            default: return 1;
        }
    }

    /**
     * Weckalarm (ProcessRevival, RTC_WAKEUP) in Minuten. Wirkt nur, wenn alle Panoptes-Prozesse gleichzeitig tot
     * sind; die BYD-MCU kappt WLAN nach ~10–15 min – deshalb höchstens 10 (Dauerbetrieb, nie aussperren).
     * normal/sparsam 5 (Wirkung laut Code-Messung vernachlässigbar), minimal 10.
     */
    public static int weckMinuten(JSONObject root) {
        JSONObject e = abschnitt(root, "energie");
        if (e != null && e.has("weckMinuten")) return klemme(e.optInt("weckMinuten", 5), 5, 10);
        return MINIMAL.equals(profil(root)) ? 10 : 5;
    }

    /**
     * 0.1.33: Sekunden zwischen zwei Lesungen im Parken bei Auto aus. Standard 90 = bisher. Höchstens 180: Der
     * Wächter-Stopp erkennt das Nachladen an 2 Werten in Folge, seltener verpasst er kurze Nachladungen.
     */
    public static int parkLeseSekunden(JSONObject root) {
        JSONObject e = abschnitt(root, "energie");
        return e != null && e.has("parkLeseSekunden") ? klemme(e.optInt("parkLeseSekunden", 90), 90, 180) : 90;
    }

    /** Erlaubte Stufen für {@code kamera.fpsRuhe} / {@code kamera.fpsBewegung} (Koordination 0.1.9, Neubau 2). */
    public static final int[] KAMERA_STUFEN = {2, 5, 10, 15};

    /**
     * Nutzerwahl {@code kamera.<name>} (2|5|10|15, als Zahl oder Text gespeichert) oder −1 (nicht gesetzt bzw.
     * ungültig → Energie-Profil bzw. bisheriger Wert).
     */
    public static int kameraWahl(JSONObject root, String name) {
        JSONObject k = abschnitt(root, "kamera");
        if (k == null || !k.has(name) || k.isNull(name)) return -1;
        int v;
        try {
            v = Integer.parseInt(String.valueOf(k.opt(name)).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        for (int s : KAMERA_STUFEN) if (s == v) return v;
        return -1;
    }

    /**
     * Kamera-Rate bei Bewegung bzw. Aufnahme im Stand: {@code kamera.fpsBewegung} gewinnt, sonst der bisherige
     * Wert ({@code camera.surveillanceTargetFps} → {@code camera.targetFps} → 15, vom Aufrufer übergeben).
     */
    public static int kameraBewegungFps(JSONObject root, int bisher) {
        int w = kameraWahl(root, "fpsBewegung");
        return w > 0 ? w : bisher;
    }

    /** Kamera-Idle-Drossel bei scharfem Wächter (camera.surveillanceIdleThrottle ausdrücklich gewinnt). */
    public static boolean kameraDrossel(JSONObject root) {
        // Wer kamera.fpsRuhe wählt, will die Ruhe-Rate auch haben
        if (kameraWahl(root, "fpsRuhe") > 0) return true;
        JSONObject c = abschnitt(root, "camera");
        if (c != null && c.has("surveillanceIdleThrottle")) return c.optBoolean("surveillanceIdleThrottle", false);
        return !NORMAL.equals(profil(root));
    }

    /** HAL-fps im Leerlauf (scharf, keine Bewegung); Untergrenze 2. */
    public static int kameraLeerlaufFps(JSONObject root) {
        int wahl = kameraWahl(root, "fpsRuhe");
        if (wahl > 0) return wahl;
        JSONObject c = abschnitt(root, "camera");
        if (c != null && c.has("surveillanceIdleFps")) return klemme(c.optInt("surveillanceIdleFps", 5), 1, 15);
        return MINIMAL.equals(profil(root)) ? 3 : 5;
    }

    /** Minuten ohne Bewegung bis zur Ruhestufe (0 = aus). */
    public static int kameraRuheMinuten(JSONObject root) {
        JSONObject c = abschnitt(root, "camera");
        if (c != null && c.has("surveillanceQuietTierMinutes")) {
            return klemme(c.optInt("surveillanceQuietTierMinutes", 5), 0, 240);
        }
        return MINIMAL.equals(profil(root)) ? 2 : 5;
    }

    /** KI-Takt (Hz) in der Ruhestufe; Untergrenze 2 im Profil (ausdrücklich ab 1). */
    public static int kameraRuheFps(JSONObject root) {
        JSONObject c = abschnitt(root, "camera");
        if (c != null && c.has("surveillanceQuietTierFps")) return klemme(c.optInt("surveillanceQuietTierFps", 3), 1, 15);
        return MINIMAL.equals(profil(root)) ? 2 : 3;
    }

    static int klemme(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
