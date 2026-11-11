package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * 0.1.41 (LC, Punkt 8 der Launcher-Liste, Alfreds Wort 11.11.: den Blinker NUR LESEN, nie bedienen): Schalter
 * „Seitenblick beim Blinken“ im Launcher. KEIN eigener Kamera- oder Blinkerweg: der Schalter setzt nur die
 * vorhandene Kern-Funktion {@code blindspot.enabled} (Standard aus) über genau den Weg der Panoptes-Einstellung
 * ({@code /api/bs/tweak?enabled=…} → resolveBlindSpotLifecycle / disableBlindSpot, Kameraprofil-Abgleich).
 * Der Kern liest den Blinker selbst ({@code BydDataCollector.readTurnNow}, nur lesend) und zeigt links Heck+links,
 * rechts rechts+Heck, bei Warnblinker nichts; 0,8 s nach dem Blinken ist das Bild weg.
 *
 * <p><b>Regel beim Fahren</b> (Fahrhilfe, nicht Unterhaltung): Das Bild darf während der Fahrt erscheinen – dafür ist
 * es da (Spurwechsel, Abbiegen; ersetzt einen Blick über die Schulter nicht, ergänzt ihn). Es erscheint nur, solange
 * der Fahrer selbst blinkt, braucht keine Berührung, macht keinen Ton und verschwindet von selbst. Beim Einschalten
 * aus dem Launcher setzt der Kern zusätzlich {@code suppressInReverse=true}: im Rückwärtsgang hat die Werks-
 * Rückfahrkamera Vorrang und wird nie verdeckt. Die Tempo-Grenzen aus Panoptes (Standard: keine) bleiben unberührt.
 *
 * <ul>
 *   <li>{@code GET  /api/launcher/v1/seitenblick} – nur lesend: an/aus, Rückwärts-Vorrang, Blinker lesbar.</li>
 *   <li>{@code POST /api/launcher/v1/seitenblick} {@code {an}} – ein/aus.</li>
 * </ul>
 * Beide nur von der Launcher-APK mit Panoptes-Signatur; im Befehls-Log und im Demo-Profil gesperrt.
 */
public final class LauncherSeitenblick {

    public static final String PFAD = "/api/launcher/v1/seitenblick";

    private LauncherSeitenblick() {}

    /** Was beim Einschalten zusätzlich gesetzt wird: Rückfahrkamera hat Vorrang. Beim Ausschalten nichts. */
    static Map<String, Object> regelnBeimEinschalten(boolean an) {
        return an ? Collections.<String, Object>singletonMap("suppressInReverse", true)
                : Collections.<String, Object>emptyMap();
    }

    /** Gepackter Blinkerwert des Kerns (bit0 links, bit1 rechts, −1 = Lichtgerät fehlt) → Anzeige im Launcher. */
    static String blinkerZustand(boolean kernBereit, int gepackt) {
        if (!kernBereit) return "unbekannt";
        return gepackt < 0 ? "nicht_lesbar" : "lesbar";
    }

    static JSONObject status(boolean an, boolean rueckwaertsAus, String blinker) throws Exception {
        return new JSONObject().put("an", an).put("rueckwaertsVorrang", rueckwaertsAus).put("blinker", blinker);
    }

    /** {@code GET}: Einstellung + einmal den Blinker LESEN (ein HAL-Lesezugriff, kein Schreiben). */
    static JSONObject lesen() throws Exception {
        JSONObject bs = at.greenchili.surfmate.config.UnifiedConfigManager.INSTANCE.getBlindSpot();
        String blinker;
        try {
            at.greenchili.surfmate.byd.BydDataCollector c = at.greenchili.surfmate.byd.BydDataCollector.getInstance();
            boolean bereit = c != null && c.isInitialized();
            blinker = blinkerZustand(bereit, bereit ? c.readTurnNow() : -1);
        } catch (Throwable t) {
            blinker = "unbekannt";
        }
        return status(bs.optBoolean("enabled", false), bs.optBoolean("suppressInReverse", false), blinker);
    }

    /** {@code POST}: Regeln setzen, dann über den Weg der Panoptes-Einstellung ein-/ausschalten. */
    static JSONObject schalten(boolean an) throws Exception {
        Map<String, Object> regeln = regelnBeimEinschalten(an);
        boolean regelnOk = regeln.isEmpty()
                || at.greenchili.surfmate.config.UnifiedConfigManager.INSTANCE.setBlindSpotValues(regeln);
        if (!regelnOk) return ergebnis(false, an, "regeln_nicht_gespeichert");
        ByteArrayOutputStream puffer = new ByteArrayOutputStream();
        StreamingApiHandler.handleBlindSpot("POST", "/api/bs/tweak?enabled=" + an, "", puffer);
        String antwort = new String(puffer.toByteArray(), StandardCharsets.UTF_8);
        return ergebnis(tweakOk(antwort), an, tweakOk(antwort) ? "" : "kern_fehler");
    }

    /** Antwort von /api/bs/tweak: 200 und {@code success:true} (sonst gespeichert, aber nicht live angewendet). */
    static boolean tweakOk(String antwort) {
        if (antwort == null || !antwort.startsWith("HTTP/1.1 200")) return false;
        int k = antwort.indexOf("\r\n\r\n");
        if (k < 0) return false;
        try { return new JSONObject(antwort.substring(k + 4).trim()).optBoolean("success", false); }
        catch (Exception e) { return false; }
    }

    private static JSONObject ergebnis(boolean ok, boolean an, String grund) throws Exception {
        try {
            BefehlsLog.ereignis("launcher.seitenblick." + (an ? "an" : "aus"), ok ? "ok" : grund, null, null);
        } catch (Throwable ignored) { }
        return new JSONObject().put("ok", ok).put("an", an).put("grund", grund);
    }
}
