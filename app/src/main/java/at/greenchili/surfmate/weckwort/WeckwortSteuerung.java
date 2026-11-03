package at.greenchili.surfmate.weckwort;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Steuerung der Weckwort-Messung im App-Prozess, aufgerufen nur von der JS-Brücke der App-WebView am Autobildschirm
 * ({@code WebViewFragment}). Die Messung selbst läuft im Prozess {@code :weckwort} ({@link WeckwortMessDienst}).
 *
 * <p>Hier wird geprüft, was nur der App-Prozess weiß: lokale Seite und ob Panoptes selbst Ton aufnimmt. Will Panoptes
 * während der Messung Ton aufnehmen, schickt {@link #tonBedarf} sofort einen Stopp-Auftrag.
 */
public final class WeckwortSteuerung {

    private static final String TAG = "WeckwortSteuerung";
    private static volatile Context app;

    private WeckwortSteuerung() {}

    public static JSONObject starten(Context ctx, boolean lokaleSeite, int minutenWunsch) {
        JSONObject r = new JSONObject();
        try {
            Context c = ctx.getApplicationContext();
            app = c;
            boolean schalter = schalterAn();
            boolean anzeige = WeckwortMessAnzeige.erlaubt(c);
            boolean recht = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean ton = at.greenchili.surfmate.audio.AppAudioCaptureController.isAnyCaptureRunning();
            String nein = WeckwortMessRegeln.startVerweigert(lokaleSeite, schalter, anzeige, recht, ton,
                    dienstLaeuft(c));
            if (nein != null) return r.put("ok", false).put("grund", nein);
            at.greenchili.surfmate.audio.AppAudioCaptureController.setTonBedarfHorcher(WeckwortSteuerung::tonBedarf);
            Intent i = new Intent(c, WeckwortMessDienst.class).setAction(WeckwortMessDienst.AKTION_START)
                    .putExtra(WeckwortMessDienst.EXTRA_MINUTEN, WeckwortMessRegeln.minuten(minutenWunsch));
            c.startService(i);
            return r.put("ok", true);
        } catch (Throwable t) {
            Log.w(TAG, "Start fehlgeschlagen: " + t.getClass().getSimpleName());
            try {
                return r.put("ok", false).put("grund", "fehler");
            } catch (Exception e) {
                return r;
            }
        }
    }

    public static JSONObject stoppen(Context ctx, String grund) {
        JSONObject r = new JSONObject();
        try {
            Context c = ctx.getApplicationContext();
            if (dienstLaeuft(c)) {
                c.startService(new Intent(c, WeckwortMessDienst.class).setAction(WeckwortMessDienst.AKTION_STOPP)
                        .putExtra(WeckwortMessDienst.EXTRA_GRUND, grund));
                r.put("lief", true);
            } else {
                r.put("lief", false);
            }
            r.put("ok", true);
        } catch (Throwable t) {
            try {
                r.put("ok", false);
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    /** Stand für die Karte: läuft (Prozess da und Datei sagt „laeuft“), Schalter, letzte Zahlen aus der Datei. */
    public static JSONObject status(Context ctx) {
        JSONObject s = new JSONObject();
        try {
            Context c = ctx.getApplicationContext();
            JSONObject erg = lesen(c);
            boolean prozess = dienstLaeuft(c);
            String st = erg == null ? "" : erg.optString("status");
            boolean offen = "laeuft".equals(st) || "laedt_modell".equals(st);
            if (offen && !prozess) {
                // Messprozess ist weg, ohne abzuschließen (z. B. Absturz im nativen Erkenner): so festhalten.
                erg.put("status", "abgebrochen").put("grund", "prozess_beendet_bei_" + st);
                schreibenRoh(c, erg);
                offen = false;
            }
            s.put("laeuft", prozess && offen);
            s.put("schalter", schalterAn());
            if (erg != null) s.put("ergebnis", erg);
        } catch (Throwable ignored) {
        }
        return s;
    }

    /** Panoptes will Ton aufnehmen (Dashcam-Ton, Mithören): laufende Messung sofort beenden. */
    static void tonBedarf() {
        Context c = app;
        if (c == null) return;
        try {
            if (dienstLaeuft(c)) stoppen(c, "panoptes_ton_aktiv");
        } catch (Throwable ignored) {
        }
    }

    /** Abgelehnter Start im Dienst (z. B. Anzeige ging nicht auf): nur Status und Grund, keine Messwerte. */
    static void abgelehntSchreiben(Context c, String grund) {
        try {
            schreibenRoh(c, new JSONObject().put("version", 1).put("art", "weckwort-messung")
                    .put("status", "abgelehnt").put("grund", grund).put("startMs", System.currentTimeMillis()));
        } catch (Throwable ignored) {
        }
    }

    /** Schreibt die Zahlen-JSON (nur Status und Zahlen) atomar. */
    private static void schreibenRoh(Context c, JSONObject j) {
        try {
            File basis = c.getExternalFilesDir(WeckwortMessRegeln.ORDNER);
            if (basis == null || (!basis.exists() && !basis.mkdirs())) return;
            File tmp = new File(basis, WeckwortMessRegeln.ERGEBNIS_DATEI + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(basis, WeckwortMessRegeln.ERGEBNIS_DATEI))) tmp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject lesen(Context c) {
        try {
            File basis = c.getExternalFilesDir(WeckwortMessRegeln.ORDNER);
            File f = basis == null ? null : new File(basis, WeckwortMessRegeln.ERGEBNIS_DATEI);
            if (f == null || !f.isFile() || f.length() > 64 * 1024) return null;
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean dienstLaeuft(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
        if (l == null) return false;
        String name = c.getPackageName() + ":weckwort";
        for (ActivityManager.RunningAppProcessInfo p : l) {
            if (name.equals(p.processName)) return true;
        }
        return false;
    }

    private static boolean schalterAn() {
        try {
            return WeckwortMessRegeln.schalterAn(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable t) {
            return false;
        }
    }
}
