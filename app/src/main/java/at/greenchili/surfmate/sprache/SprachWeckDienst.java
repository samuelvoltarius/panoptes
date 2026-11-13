package at.greenchili.surfmate.sprache;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import at.greenchili.surfmate.R;

/**
 * 0.1.44 (P2) Weckwort Stufe 2: hört im Prozess {@code :sprache} auf „Hallo Auto“ (Englisch „hello car“) und öffnet
 * dann den Sprachbefehl. Weiterführung der Messung aus Stufe 1 (gleicher Baustein Vosk, gleiche Regeln).
 *
 * <ul>
 *   <li>Nur mit beiden Schaltern ({@value SprachBefehle#SCHALTER}, {@value SprachBefehle#SCHALTER_WECKWORT}, ab Werk aus),
 *       nur per Tipp eingeschaltet ({@link SprachStart}), nie per Netz, Telegram, Handy oder Automation.</li>
 *   <li>Solange es hört, steht oben die Anzeige „Mikrofon hört auf „Hallo Auto““ (ohne Overlay-Recht startet nichts;
 *       Tipp auf die Anzeige = aus). Verschwindet sie, endet das Zuhören.</li>
 *   <li>Ende bei: Tipp, Schalter aus (≤ 5 s), Panoptes nimmt Ton auf, nach {@link SprachRegeln#WECK_MAX_MIN} Minuten.
 *       Nach einem Neustart ist es aus (nicht klebrig).</li>
 *   <li>Kein Ton wird gespeichert oder gesendet; nur der Vergleich mit dem Weckwort zählt.</li>
 * </ul>
 */
public final class SprachWeckDienst extends Service {

    static final String AKTION_START = "at.greenchili.surfmate.sprache.WECK_START";
    static final String AKTION_STOPP = "at.greenchili.surfmate.sprache.WECK_STOPP";
    static final String AKTION_WEITER = "at.greenchili.surfmate.sprache.WECK_WEITER";
    /** Nach einem Treffer so lange Pause, falls der Sprachbefehl nicht zurückmeldet. */
    static final long PAUSE_MAX_MS = 60_000L;

    private static final AtomicBoolean LAEUFT = new AtomicBoolean(false);
    /** Unterbricht das Zuhören des Weckworts: Ende oder ein Sprachbefehl braucht das Mikrofon (nie zwei zugleich). */
    private static final AtomicBoolean HALT = new AtomicBoolean(false);
    private static volatile long pauseBis;
    private final AtomicBoolean abbruch = new AtomicBoolean(false);
    private final Handler haupt = new Handler(Looper.getMainLooper());
    private volatile long ende;
    private String sprache;
    private TextView anzeige;

    static boolean laeuft() { return LAEUFT.get(); }

    /** Ein Sprachbefehl beginnt (gleicher Prozess): Weckwort gibt das Mikrofon frei, bis {@link #weiter} kommt. */
    static void pausieren() {
        if (!LAEUFT.get()) return;
        pauseBis = System.currentTimeMillis() + PAUSE_MAX_MS;
        HALT.set(true);
    }

    static void starten(Context ctx, String sprache) {
        if (!erlaubtAnzeige(ctx)) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.sprache_keine_anzeige), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        ctx.startService(new Intent(ctx, SprachWeckDienst.class).setAction(AKTION_START).putExtra(SprachStart.EXTRA_SPRACHE, sprache));
    }

    static void stoppen(Context ctx, String grund) {
        try { ctx.startService(new Intent(ctx, SprachWeckDienst.class).setAction(AKTION_STOPP)); } catch (Throwable ignored) { }
    }

    static void weiter(Context ctx) {
        if (!laeuft()) return;
        try { ctx.startService(new Intent(ctx, SprachWeckDienst.class).setAction(AKTION_WEITER)); } catch (Throwable ignored) { }
    }

    static boolean erlaubtAnzeige(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String aktion = intent == null ? null : intent.getAction();
        if (AKTION_START.equals(aktion) && !LAEUFT.get()) {
            sprache = SprachBefehle.sprache(intent.getStringExtra(SprachStart.EXTRA_SPRACHE));
            if (!SprachErkenner.modellDa(this, sprache) || !anzeigeZeigen()) { beenden(); return START_NOT_STICKY; }
            LAEUFT.set(true);
            abbruch.set(false);
            ende = System.currentTimeMillis() + SprachRegeln.WECK_MAX_MIN * 60_000L;
            new Thread(this::schleife, "SprachWeckwort").start();
            haupt.postDelayed(this::pruefen, 5000);
        } else if (AKTION_STOPP.equals(aktion)) {
            beenden();
        } else if (AKTION_WEITER.equals(aktion)) {
            pauseBis = 0;
        }
        // Nicht neu starten, wenn Android den Prozess beendet: das Weckwort startet nur per Tipp.
        return START_NOT_STICKY;
    }

    private void schleife() {
        while (!abbruch.get()) {
            if (System.currentTimeMillis() < pauseBis) { schlafen(500); continue; }
            HALT.set(false);
            String t = SprachErkenner.hoeren(this, sprache, SprachBefehle.weckGrammatik(sprache), 60_000L, HALT);
            if (abbruch.get()) break;
            if (HALT.get()) continue;   // pausiert: ein Sprachbefehl hat das Mikrofon
            if (SprachBefehle.WECKWORT.get(sprache).equals(t)) {
                pauseBis = System.currentTimeMillis() + PAUSE_MAX_MS;
                // Über den App-Prozess: der prüft Schalter und ob Panoptes selbst Ton aufnimmt.
                startActivity(new Intent(this, SprachStart.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(SprachStart.EXTRA_WECK, true));
            }
        }
    }

    /** Alle 5 s: Schalter, Anzeige, Zeit. */
    private void pruefen() {
        if (!LAEUFT.get()) return;
        String g = SprachRegeln.weckStopp(System.currentTimeMillis(), ende, SprachStart.schalterAn(SprachBefehle.SCHALTER),
                SprachStart.schalterAn(SprachBefehle.SCHALTER_WECKWORT), anzeige != null && anzeige.isAttachedToWindow());
        if (g != null) { beenden(); return; }
        haupt.postDelayed(this::pruefen, 5000);
    }

    private boolean anzeigeZeigen() {
        try {
            TextView t = new TextView(this);
            t.setText(getString(R.string.sprache_weckwort_anzeige, SprachBefehle.WECKWORT.get(sprache)));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            int p = Math.round(12 * getResources().getDisplayMetrics().density);
            t.setPadding(p * 2, p, p * 2, p);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.argb(230, 120, 20, 20));
            g.setCornerRadius(p * 2);
            t.setBackground(g);
            t.setOnClickListener(v -> beenden());   // Tipp auf die Anzeige = aus
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = p;
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(t, lp);
            anzeige = t;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void beenden() {
        abbruch.set(true);
        HALT.set(true);
        LAEUFT.set(false);
        haupt.removeCallbacksAndMessages(null);
        if (anzeige != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(anzeige); } catch (Throwable ignored) { }
            anzeige = null;
        }
        stopSelf();
    }

    private static void schlafen(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        beenden();
        super.onDestroy();
    }
}
