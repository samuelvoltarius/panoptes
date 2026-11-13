package at.greenchili.surfmate.server;

import at.greenchili.surfmate.sprache.SprachModellLaden;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 0.1.43.2 (Fix E2E M2) Sprachmodell laden – Kern-Seite. Der Kern (uid shell) lädt nicht mehr selbst: Ab Android 11 darf
 * er den App-Ordner nicht anlegen. Er löst nur aus und reicht weiter:
 *
 * <ul>
 *   <li>{@link #starten}: nur auf Tipp (POST laden), startet den App-Dienst {@code SprachModellDienst} über die vorhandene
 *       Brücke {@code am start-foreground-service} (Dienst nur mit DUMP startbar). Quelle {@code netz} (feste Vosk-URL) oder
 *       {@code hand} (Datei im Ordner Download).</li>
 *   <li>{@link #melden}: Stand von der App (nur App-UID, {@link SprachAufrufer}) – Fortschritt, Fehler, welche Modelle da sind.</li>
 *   <li>{@link #handDatei}: die von Hand in Download gelegte Datei (shell darf {@code /sdcard/Download} lesen, auch unter
 *       Android 11). Prüfsumme und Größe prüft die App, wie beim Laden aus dem Netz.</li>
 * </ul>
 */
public final class SprachModellKern {

    static final String DOWNLOAD = "/storage/emulated/0/Download";
    /** Gilt ein „lädt“ ohne neue Meldung so lange, danach nicht mehr (App beendet o. ä.). */
    static final long HERZSCHLAG_MS = 30_000L;
    /** Stand von der App höchstens so oft anfragen (nur lesen, kein Netz). */
    static final long STAND_ABFRAGE_MS = 60_000L;

    /** Shell (im Test nachgebaut). */
    interface Shell { String aus(String befehl); }
    static volatile Shell shell = b -> at.greenchili.surfmate.appupdate.LauncherPaket.SHELL.shell(b, 10);
    static volatile File download = new File(DOWNLOAD);

    private static JSONObject letzter;
    private static long letzterMs, abgefragtMs;

    private SprachModellKern() {}

    static File handDatei(SprachModellLaden.Modell m) { return new File(download, m.name + ".zip"); }

    /** Laden auf Tipp auslösen. Prüft Sprache, Quelle, Datei und ob schon geladen wird – ohne Netz. */
    static synchronized JSONObject starten(String spracheRoh, String quelleRoh, long jetzt) throws Exception {
        JSONObject o = new JSONObject().put("ok", false);
        SprachModellLaden.Modell m = SprachModellLaden.MODELLE.get(spracheRoh == null ? "" : spracheRoh.trim().toLowerCase(Locale.ROOT));
        if (m == null) return o.put("grund", "sprache");
        String quelle = "hand".equals(quelleRoh) ? "hand" : "netz";
        if ("hand".equals(quelle) && !handDatei(m).isFile()) return o.put("grund", "datei_fehlt");
        if (laeuft(jetzt)) return o.put("grund", "laeuft_schon");
        String aus = shell.aus("am start-foreground-service -n " + at.greenchili.surfmate.sprache.SprachModellDienst.KOMPONENTE
                + " --es aktion laden --es sprache " + m.sprache + " --es quelle " + quelle);
        if (aus == null || aus.contains("Error") || aus.contains("Exception")) return o.put("grund", "kein_dienst");
        letzter = new JSONObject().put("laden", new JSONObject().put("sprache", m.sprache).put("phase", "laedt").put("grund", "")
                .put("bytes", 0).put("gesamt", m.zipBytes)).put("laeuft", true).put("modelle", modelle());
        letzterMs = jetzt;
        return o.put("ok", true).put("sprache", m.sprache).put("quelle", quelle);
    }

    static synchronized JSONObject abbrechen() throws Exception {
        shell.aus("am start-foreground-service -n " + at.greenchili.surfmate.sprache.SprachModellDienst.KOMPONENTE + " --es aktion abbrechen");
        return new JSONObject().put("ok", true);
    }

    /** Meldung der App (Fortschritt, Ende, vorhandene Modelle). Nur bekannte Felder werden übernommen. */
    static synchronized void melden(JSONObject roh, long jetzt) throws Exception {
        if (roh == null) return;
        JSONObject l = roh.optJSONObject("laden");
        JSONObject laden = new JSONObject();
        if (l != null) {
            laden.put("sprache", kurz(l.optString("sprache"))).put("phase", kurz(l.optString("phase"))).put("grund", kurz(l.optString("grund")))
                    .put("bytes", Math.max(0, l.optLong("bytes"))).put("gesamt", Math.max(0, l.optLong("gesamt")));
        }
        JSONObject mo = new JSONObject(), rm = roh.optJSONObject("modelle");
        for (SprachModellLaden.Modell m : SprachModellLaden.MODELLE.values()) {
            JSONObject e = rm == null ? null : rm.optJSONObject(m.sprache);
            mo.put(m.sprache, e != null && e.optBoolean("da", false));
        }
        letzter = new JSONObject().put("laden", laden).put("laeuft", roh.optBoolean("laeuft", false)).put("da", mo);
        letzterMs = jetzt;
    }

    /** Stand für die Seite: Modelle (da laut App, Datei in Download), Ladevorgang. Fragt die App höchstens jede Minute. */
    static synchronized JSONObject stand(long jetzt) throws Exception {
        if (!laeuft(jetzt) && jetzt - abgefragtMs > STAND_ABFRAGE_MS) {
            abgefragtMs = jetzt;
            shell.aus("am start-foreground-service -n " + at.greenchili.surfmate.sprache.SprachModellDienst.KOMPONENTE + " --es aktion stand");
        }
        JSONObject l = letzter == null ? new JSONObject() : letzter.optJSONObject("laden");
        return new JSONObject().put("ok", true).put("modelle", modelle()).put("laden", l == null ? new JSONObject() : l)
                .put("ort", "app");
    }

    /** Laut letzter Meldung der App vorhanden? (Für den Launcher-Katalog.) */
    public static synchronized boolean modellDa(String sprache) {
        JSONObject da = letzter == null ? null : letzter.optJSONObject("da");
        return da != null && da.optBoolean(sprache, false);
    }

    private static JSONObject modelle() throws Exception {
        JSONObject mo = new JSONObject();
        for (SprachModellLaden.Modell m : SprachModellLaden.MODELLE.values()) {
            mo.put(m.sprache, new JSONObject().put("name", m.name).put("da", modellDa(m.sprache)).put("mb", m.mb())
                    .put("noetigMb", Math.round((m.zipBytes + m.entpacktBytes + SprachModellLaden.RESERVE) / 1_000_000.0))
                    .put("lizenz", m.lizenz).put("handDatei", handDatei(m).isFile()));
        }
        return mo;
    }

    private static boolean laeuft(long jetzt) {
        if (letzter == null || jetzt - letzterMs > HERZSCHLAG_MS) return false;
        JSONObject l = letzter.optJSONObject("laden");
        String p = l == null ? "" : l.optString("phase");
        return "laedt".equals(p) || "prueft".equals(p) || "entpackt".equals(p);
    }

    private static String kurz(String s) {
        return s == null ? "" : s.replaceAll("[^a-z_]", "").substring(0, Math.min(s.replaceAll("[^a-z_]", "").length(), 24));
    }

    /** Für Tests. */
    static synchronized void vergessen() { letzter = null; letzterMs = 0; abgefragtMs = 0; }
}
