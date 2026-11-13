package at.greenchili.surfmate.sprache;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.1.43.2 (Fix E2E M2) Sprachmodell laden in der App selbst. Grund: Der Kern läuft als uid shell und darf ab
 * Android 11 den App-Ordner {@code Android/data/at.greenchili.surfmate/files} weder anlegen noch lesen. Die App darf es
 * immer – und genau dort liest die Erkennung ({@link SprachErkenner#modellOrdner}).
 *
 * <ul>
 *   <li>Start nur durch den Kern, nur nach dem Tipp in der Oberfläche ({@code POST /api/sprache/v1/modell {aktion:"laden"}}),
 *       über die vorhandene Brücke {@code am start-foreground-service}; exportiert nur mit {@code android.permission.DUMP}
 *       (shell hat es, andere Apps nicht).</li>
 *   <li>Quelle {@code netz}: nur die feste Vosk-URL ({@link SprachModellLaden#QUELLE}). Quelle {@code hand}: die Datei, die
 *       der Fahrer in den Ordner Download gelegt hat – der Kern reicht sie über Loopback durch. In beiden Fällen dieselbe
 *       feste SHA-256 und Größe, sonst verworfen ({@link SprachModellLaden#laden}).</li>
 *   <li>Fortschritt meldet die App dem Kern ({@code POST /api/sprache/v1/modell/stand}, nur die App-UID), die Seite liest ihn dort.</li>
 * </ul>
 */
public final class SprachModellDienst extends Service {

    public static final String KOMPONENTE = "at.greenchili.surfmate/.sprache.SprachModellDienst";
    static final String KERN = "http://127.0.0.1:" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT;
    private static final String KANAL = "sprachmodell";
    private static final int ID = 4471;

    private static final AtomicBoolean ABBRUCH = new AtomicBoolean(false);
    private static final AtomicBoolean LAEUFT = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        vordergrund();
        String aktion = intent == null ? "" : String.valueOf(intent.getStringExtra("aktion"));
        if ("abbrechen".equals(aktion)) {
            ABBRUCH.set(true);
            if (!LAEUFT.get()) fertig();
        } else if ("laden".equals(aktion) && !LAEUFT.get()) {
            String sprache = intent.getStringExtra("sprache");
            String quelle = "hand".equals(intent.getStringExtra("quelle")) ? "hand" : "netz";
            LAEUFT.set(true);
            ABBRUCH.set(false);
            new Thread(() -> laden(sprache, quelle), "SprachModellLaden").start();
        } else if (!LAEUFT.get()) {
            // "stand": nur melden, was da ist (kein Netz)
            new Thread(() -> { melden(); fertig(); }, "SprachModellStand").start();
        }
        return START_NOT_STICKY;
    }

    private void laden(String sprache, String quelle) {
        try {
            File basis = getExternalFilesDir(SprachErkenner.ORDNER);
            SprachModellLaden.Modell m = SprachModellLaden.MODELLE.get(sprache == null ? "" : sprache);
            if (m == null) { SprachModellLaden.fehler(sprache, "sprache"); return; }
            if (basis == null) { SprachModellLaden.fehler(sprache, "ordner"); return; }
            SprachModellLaden.basis = basis;
            SprachModellLaden.netz = "hand".equals(quelle) ? new AusDemKern(m.sprache) : SprachModellLaden.NETZ_QUELLE;
            String g = SprachModellLaden.vorpruefen(m);
            if (g != null) { SprachModellLaden.fehler(m.sprache, g); return; }
            Thread melder = new Thread(() -> {
                while (LAEUFT.get()) { melden(); try { Thread.sleep(700); } catch (InterruptedException e) { return; } }
            }, "SprachModellMelder");
            melder.start();
            SprachModellLaden.laden(m, ABBRUCH);
        } catch (Throwable t) {
            SprachModellLaden.fehler(sprache, "netz");
        } finally {
            LAEUFT.set(false);
            melden();
            fertig();
        }
    }

    /** Stand an den Kern (Loopback, nur die App-UID darf das). */
    private void melden() {
        HttpURLConnection c = null;
        try {
            File basis = getExternalFilesDir(SprachErkenner.ORDNER);
            if (basis != null) SprachModellLaden.basis = basis;
            JSONObject s = SprachModellLaden.stand().put("laeuft", LAEUFT.get());
            c = (HttpURLConnection) new URL(KERN + "/api/sprache/v1/modell/stand").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            c.getResponseCode();
        } catch (Throwable ignored) {
            // Kern nicht da: die Seite zeigt dann nichts Neues, das Laden selbst läuft weiter
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Quelle „hand“: die Datei aus dem Ordner Download, vom Kern über Loopback durchgereicht (Prüfsumme prüft die App). */
    static final class AusDemKern implements SprachModellLaden.Netz {
        private final String sprache;
        AusDemKern(String sprache) { this.sprache = sprache; }

        @Override
        public InputStream oeffnen(String url) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(KERN + "/api/sprache/v1/modell/datei?sprache=" + sprache).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(30_000);
            if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("datei"); }
            return c.getInputStream();
        }
    }

    private void vordergrund() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(new NotificationChannel(KANAL, getString(at.greenchili.surfmate.R.string.sprachmodell_kanal),
                        NotificationManager.IMPORTANCE_LOW));
            }
            Notification n = new Notification.Builder(this, KANAL).setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getString(at.greenchili.surfmate.R.string.sprachmodell_laedt)).setOngoing(true).build();
            startForeground(ID, n);
        } catch (Throwable ignored) {
            // ohne Benachrichtigung beendet Android den Dienst evtl.; das Laden meldet dann „netz“
        }
    }

    private void fertig() {
        try { stopForeground(true); } catch (Throwable ignored) { }
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
