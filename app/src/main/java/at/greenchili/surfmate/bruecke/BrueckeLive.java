package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.1.25 Live-Bild über die Brücke (BRUECKE_MEDIEN §2.2): {@code {"id","v":2,"m":"LIVE","q"?}} auf einem Kanal mit Recht
 * „kamera“. Das Auto greift den eigenen H.264-Stream lokal ab (127.0.0.1:8987, nichts verlässt das Auto außer den
 * verschlüsselten Rahmen) und reicht jede Stream-Nachricht als v2-Stücke weiter.
 *
 * <ul>
 *   <li>Nur auf Anforderung, höchstens eine Sitzung je Auto (sonst 0x13 409 {@code live_belegt}).</li>
 *   <li>Qualität: ULTRA_LOW (Standard), LOW oder MEDIUM – mehr nur über den Direktweg.</li>
 *   <li>Lebenszeichen {@code {"t":"da","id"}} vom Handy; nach 120 s ohne → Stopp (0x13 408 {@code keine_interaktion}),
 *       spätestens nach 15 min (0x13 410 {@code zeit_um}). Abbruch {@code {"t":"ab","id"}} (0x13 499).
 *       {@code {"t":"keyframe","id"}} fordert ein neues Schlüsselbild an.</li>
 *   <li>Stücke: 0x10 Kopf {@code {"id","s":200,"h":{"content-type":"video/h264"},"live":true}}, je Stream-Nachricht
 *       0x11 (Teil) … 0x14 (letzter Teil dieser Nachricht), damit das Handy die Nachrichtengrenzen behält.</li>
 *   <li>Gegendruck: Ist der Sendepuffer voll, werden Rahmen verworfen (Live: lieber springen als nachhinken) und
 *       danach ein Schlüsselbild angefordert. Live-Daten zählen gegen die Tagesgrenze des Autos.</li>
 * </ul>
 */
final class BrueckeLive {

    static final byte V2_LIVE_ENDE = 0x14;
    static final long OHNE_DA_MS = 120_000L, HOECHSTENS_MS = 15 * 60_000L;
    static final java.util.Set<String> STUFEN = new java.util.HashSet<>(java.util.Arrays.asList("ULTRA_LOW", "LOW", "MEDIUM"));

    /** Lokale Stream-Quelle (im Daemon: WebSocket auf 127.0.0.1:8987; im Test ersetzt). */
    interface Quelle {
        interface Empfaenger {
            void nachricht(byte[] daten);
            void zu();
        }

        interface Verbindung {
            void text(String s);
            void schliessen();
        }

        Verbindung oeffnen(String stufe, Empfaenger e) throws Exception;
    }

    private static final AtomicBoolean BELEGT = new AtomicBoolean(false);

    /** Eine laufende Sitzung; die Steuerung (da/ab/keyframe) kommt aus dem Kanal-Thread. */
    static final class Sitzung {
        final long id;
        volatile long letztesDa;
        volatile boolean stopp;
        volatile Quelle.Verbindung verbindung;

        Sitzung(long id, long jetzt) {
            this.id = id;
            this.letztesDa = jetzt;
        }

        void da(long jetzt) { letztesDa = jetzt; }

        void keyframe() {
            Quelle.Verbindung v = verbindung;
            if (v != null) v.text("keyframe");
        }
    }

    private BrueckeLive() {}

    static boolean istLive(JSONObject req) {
        return "LIVE".equalsIgnoreCase(req.optString("m", ""));
    }

    /**
     * Läuft bis Stopp (blockiert den Arbeits-Thread des Kanals). {@code s} wird vorher an den Kanal gegeben, damit
     * da/ab/keyframe ankommen.
     */
    static void laufen(Sitzung s, String stufe, Quelle quelle, BrueckeWeiterleitung.StueckAusgang aus,
                       BrueckeWeiterleitung.MedienKonto konto, java.util.function.LongSupplier uhr) {
        long id = s.id;
        try {
            if (!STUFEN.contains(stufe)) {
                aus.senden(fehler(id, 400, "stufe"));
                return;
            }
            if (!konto.verbrauchen(0)) {
                aus.senden(fehler(id, 429, "medien_tageslimit"));
                return;
            }
            if (!BELEGT.compareAndSet(false, true)) {
                aus.senden(fehler(id, 409, "live_belegt"));
                return;
            }
        } catch (Exception e) {
            return;
        }
        final Object warten = new Object();
        final boolean[] quelleZu = {false};
        final boolean[] verworfen = {false};
        final String[] grund = {null};
        try {
            long start = uhr.getAsLong();
            byte[] k = new JSONObject().put("id", id).put("s", 200)
                    .put("h", new JSONObject().put("content-type", "video/h264")).put("live", true).put("stufe", stufe)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (!aus.senden(BrueckeWeiterleitung.stueck(BrueckeWeiterleitung.V2_KOPF, id, k, k.length))) return;
            s.verbindung = quelle.oeffnen(stufe, new Quelle.Empfaenger() {
                public void nachricht(byte[] d) {
                    if (s.stopp || d == null || d.length == 0) return;
                    try {
                        if (aus.warteschlange() > BrueckeWeiterleitung.GEGENDRUCK) {
                            verworfen[0] = true;   // Live: lieber springen als nachhinken
                            return;
                        }
                        if (verworfen[0]) {
                            verworfen[0] = false;
                            s.keyframe();
                        }
                        if (!konto.verbrauchen(d.length)) {
                            grund[0] = "medien_tageslimit";
                            s.stopp = true;
                            synchronized (warten) { warten.notifyAll(); }
                            return;
                        }
                        for (int o = 0; o < d.length; o += BrueckeWeiterleitung.V2_MAX_DATEN) {
                            int n = Math.min(BrueckeWeiterleitung.V2_MAX_DATEN, d.length - o);
                            byte[] teil = java.util.Arrays.copyOfRange(d, o, o + n);
                            byte typ = o + n >= d.length ? V2_LIVE_ENDE : BrueckeWeiterleitung.V2_DATEN;
                            if (!aus.senden(BrueckeWeiterleitung.stueck(typ, id, teil, n))) {
                                s.stopp = true;
                                synchronized (warten) { warten.notifyAll(); }
                                return;
                            }
                        }
                    } catch (Exception e) {
                        s.stopp = true;
                        synchronized (warten) { warten.notifyAll(); }
                    }
                }

                public void zu() {
                    quelleZu[0] = true;
                    synchronized (warten) { warten.notifyAll(); }
                }
            });
            s.keyframe();
            synchronized (warten) {
                while (!s.stopp && !quelleZu[0]) {
                    long jetzt = uhr.getAsLong();
                    if (jetzt - s.letztesDa >= OHNE_DA_MS) {
                        grund[0] = "keine_interaktion";
                        break;
                    }
                    if (jetzt - start >= HOECHSTENS_MS) {
                        grund[0] = "zeit_um";
                        break;
                    }
                    warten.wait(1000);
                }
            }
            s.stopp = true;
            if (grund[0] == null && quelleZu[0]) grund[0] = "quelle_zu";
            if (grund[0] == null) grund[0] = "abgebrochen";
            int status = "keine_interaktion".equals(grund[0]) ? 408 : "zeit_um".equals(grund[0]) ? 410
                    : "medien_tageslimit".equals(grund[0]) ? 429 : "quelle_zu".equals(grund[0]) ? 502 : 499;
            aus.senden(fehler(id, status, grund[0]));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            try {
                aus.senden(fehler(id, 502, "intern"));
            } catch (Exception ignored) { }
        } finally {
            s.stopp = true;
            Quelle.Verbindung v = s.verbindung;
            if (v != null) {
                try { v.schliessen(); } catch (Exception ignored) { }
            }
            BELEGT.set(false);
        }
    }

    static byte[] fehler(long id, int status, String grund) {
        byte[] j;
        try {
            j = new JSONObject().put("id", id).put("s", status).put("grund", grund).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            j = new byte[0];
        }
        return BrueckeWeiterleitung.stueck(BrueckeWeiterleitung.V2_FEHLER, id, j, j.length);
    }

    /** Nur für Tests: Belegung zurücksetzen. */
    static void freigeben() { BELEGT.set(false); }

    // ------------------------------------------------------------------ Daemon: lokaler Stream auf 127.0.0.1:8987

    static Quelle imDaemon() {
        return (stufe, e) -> {
            // Qualität setzen und Stream anfordern – lokal, ohne Tunnel-Kennzeichen (das Auto selbst, nicht das Handy)
            lokalPost("/api/stream/quality/" + stufe);
            lokalPost("/api/stream/enable");
            okhttp3.WebSocket w = BrueckeServerApi.HTTP.newWebSocket(
                    new okhttp3.Request.Builder().url("ws://127.0.0.1:"
                            + at.greenchili.surfmate.streaming.WebSocketStreamServer.PORT_OEFFENTLICH + "/").build(),
                    new okhttp3.WebSocketListener() {
                        @Override public void onMessage(okhttp3.WebSocket ws, okio.ByteString b) { e.nachricht(b.toByteArray()); }
                        @Override public void onClosed(okhttp3.WebSocket ws, int code, String r) { e.zu(); }
                        @Override public void onFailure(okhttp3.WebSocket ws, Throwable t, okhttp3.Response r) { e.zu(); }
                    });
            return new Quelle.Verbindung() {
                public void text(String s) { w.send(s); }
                public void schliessen() { w.close(1000, null); }
            };
        };
    }

    private static void lokalPost(String pfad) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:"
                + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT + pfad).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }
}
