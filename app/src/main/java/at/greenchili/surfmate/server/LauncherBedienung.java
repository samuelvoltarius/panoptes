package at.greenchili.surfmate.server;

import org.json.JSONObject;

/**
 * 0.1.44 (P1 „Bedienung 50+“, LUECKEN_0142 Teil 3 Punkte 1 und 10): zwei eng begrenzte Wege für den Panoptes Launcher.
 *
 * <ul>
 *   <li>{@code POST /api/launcher/v1/taste {"taste":"zurueck"|"letzte"}}: drückt als Daemon genau eine Navigationstaste
 *       (Zurück = 4, Übersicht/Letzte Apps = 187). Keine andere Taste, kein freier Tastencode, kein Fahrzeugbefehl.</li>
 *   <li>{@code POST /api/launcher/v1/home/an}: Schalter „Launcher als Startbildschirm“ an (noch nicht behalten). Der
 *       Totmann des {@code HomeWaechter} bleibt damit wirksam, bis der Launcher „Behalten“ meldet.</li>
 * </ul>
 * Beide nur von der Launcher-APK mit Panoptes-Signatur ({@code launcherDarf} im {@link LauncherApiHandler}).
 */
public final class LauncherBedienung {

    public static final String TASTE_PFAD = "/api/launcher/v1/taste";
    public static final String HOME_AN_PFAD = "/api/launcher/v1/home/an";

    private static final int ZURUECK = 4;     // KEYCODE_BACK
    private static final int UEBERSICHT = 187; // KEYCODE_APP_SWITCH

    private LauncherBedienung() {}

    /** Rein: Tastencode zum Namen; null für alles andere. */
    static Integer code(String taste) {
        if ("zurueck".equals(taste)) return ZURUECK;
        if ("letzte".equals(taste)) return UEBERSICHT;
        return null;
    }

    /** {status, antwort}: 400 bei unbekannter Taste, sonst 200 mit {ok}. */
    static Object[] taste(String body) {
        JSONObject r = new JSONObject();
        try {
            JSONObject in = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
            String t = in.optString("taste", "");
            Integer c = code(t);
            if (c == null) return new Object[] {400, r.put("ok", false).put("grund", "taste_unbekannt")};
            // Fester Befehl aus einer der zwei Konstanten oben, nichts aus der Anfrage geht in die Zeile.
            Runtime.getRuntime().exec(new String[] {"sh", "-c", "input keyevent " + c});
            return new Object[] {200, r.put("ok", true).put("taste", t).put("weg", "daemon")};
        } catch (Exception e) {
            try {
                return new Object[] {500, r.put("ok", false).put("grund", "fehler")};
            } catch (Exception ignored) {
                return new Object[] {500, new JSONObject()};
            }
        }
    }

    /** Schalter an, „Behalten“ bleibt aus (der Launcher bestätigt erst nach dem Selbsttest bzw. der Frage). */
    static JSONObject homeAn() throws Exception {
        boolean ok = at.greenchili.surfmate.launcher.HomeWaechter.setze(true, false);
        return new JSONObject().put("ok", ok);
    }
}
