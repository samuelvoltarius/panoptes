package at.greenchili.surfmate.weckwort;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Hülle der Weckwort-Messung im eigenen Prozess {@code :weckwort} (Manifest, nicht exportiert). Grund: Vosk ist
 * nativer Code. Stürzt er ab (am x86-Emulator mit ARM-Übersetzung belegt: SIGILL), trifft es nur diesen Prozess,
 * nicht die App mit Status-Band und Tonaufnahme. Nach dem Ende beendet sich der Prozess selbst und gibt den
 * Speicher des Modells frei.
 *
 * <p>Aufträge kommen nur von {@link WeckwortSteuerung} im App-Prozess (gleiche UID).
 */
public final class WeckwortMessDienst extends Service {

    static final String AKTION_START = "at.greenchili.surfmate.weckwort.START";
    static final String AKTION_STOPP = "at.greenchili.surfmate.weckwort.STOPP";
    static final String EXTRA_MINUTEN = "minuten";
    static final String EXTRA_GRUND = "grund";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String aktion = intent == null ? null : intent.getAction();
        if (AKTION_START.equals(aktion)) {
            int minuten = intent.getIntExtra(EXTRA_MINUTEN, WeckwortMessRegeln.MINUTEN_STANDARD);
            Handler haupt = new Handler(Looper.getMainLooper());
            Runnable fertig = () -> haupt.postDelayed(this::beenden, 1_000L);
            new Thread(() -> {
                org.json.JSONObject r = WeckwortMessung.imDienstStarten(getApplicationContext(), minuten, fertig);
                if (!r.optBoolean("ok", false) && !WeckwortMessung.laeuft()) {
                    WeckwortSteuerung.abgelehntSchreiben(getApplicationContext(), r.optString("grund", "fehler"));
                    haupt.post(this::beenden);
                }
            }, "WeckwortStart").start();
        } else if (AKTION_STOPP.equals(aktion)) {
            String grund = intent.getStringExtra(EXTRA_GRUND);
            new Thread(() -> {
                WeckwortMessung.stoppen(grund == null ? "nutzer" : grund);
                if (!WeckwortMessung.laeuft()) new Handler(Looper.getMainLooper()).post(this::beenden);
            }, "WeckwortStopp").start();
        }
        // Nicht neu starten, wenn Android den Prozess beendet: eine Messung startet nur per Tipp am Auto.
        return START_NOT_STICKY;
    }

    private void beenden() {
        stopSelf();
        // Eigener Prozess nur für die Messung: ganz beenden, damit Modell und Vosk-Speicher sicher weg sind.
        if (!WeckwortMessung.laeuft()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
