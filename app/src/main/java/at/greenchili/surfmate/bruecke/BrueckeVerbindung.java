package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Auto → Brücken-Server (BRUECKE_VERTRAG §3.1): eine ausgehende WebSocket-Verbindung, solange die Brücke an ist.
 * Ping alle 25 s, bei Abbruch Neuaufbau mit Rückzug 2 s → 60 s. Je Handy-Kanal k ein Noise-IK-Responder; erst nach
 * dem Handshake mit einem bekannten Handy-Schlüssel (gültige Handy-Kopplung) gehen Anfragen an die eigene API.
 *
 * <p>Rahmen (Server, Commit e3b9ef0): Text {@code {"t":"neu"|"zu"|"limit"|...}}, Binär {@code [k][Nutzlast]}.
 */
public final class BrueckeVerbindung {

    private static final String TAG = "Bruecke";
    static final int MAX_KANAELE = 4;
    static final int MAX_ANFRAGE = 1024 * 1024;

    /** Senden zum Server (im Test ersetzt). */
    interface Sender {
        boolean text(String s);
        boolean binaer(byte[] b);
        /** 0.1.25 Gegendruck: Bytes, die noch zum Server unterwegs sind (OkHttp queueSize). */
        default long warteschlange() { return 0; }
    }

    /** Ein Handy-Kanal. */
    static final class Kanal {
        final int k;
        final NoiseIK noise;
        final BrueckeRahmen.Sammler sammler = new BrueckeRahmen.Sammler(MAX_ANFRAGE);
        volatile String kid;
        /** 0.1.24 Erstkopplung: Kanal kam mit unbekanntem Handy-Schlüssel über den Einmal-Zugang. */
        volatile boolean einmal;
        volatile byte[] hPub;
        /** 0.1.25 Medien-Kanal (Server meldet {@code "art":"medien"}): nur Kamera-Daten über v2. */
        volatile boolean medien;
        /** 0.1.25 v2-Abbruch {@code {"t":"ab","id":n}}: diese ids nicht weiter senden. */
        final java.util.Set<Long> abgebrochen = ConcurrentHashMap.newKeySet();
        /** 0.1.25 laufende Live-Sitzung dieses Kanals (Steuerung da/ab/keyframe kommt sofort an). */
        volatile BrueckeLive.Sitzung live;
        final ExecutorService arbeit = Executors.newSingleThreadExecutor();

        Kanal(int k, NoiseIK noise) {
            this.k = k;
            this.noise = noise;
        }
    }

    // ------------------------------------------------------------------ Kanal-Logik (ohne Netz, testbar)

    static final class Kanaele {
        final Map<Integer, Kanal> offen = new ConcurrentHashMap<>();
        private final Bruecke bruecke;
        private final BrueckeWeiterleitung weiter;
        private final Sender sender;
        private final java.util.function.LongSupplier uhr;
        /** Nach der Erstkopplung: Einmal-Zugang löschen (der Server schließt dann diesen Kanal, das Handy kommt mit
         *  eigenem Zugang wieder). Im Daemon verzögert, damit die Antwort vorher ankommt; im Test ersetzt. */
        Runnable einmalFertig;
        /** Lokale Stream-Quelle für LIVE (im Test ersetzt). */
        BrueckeLive.Quelle liveQuelle;
        /** 0.1.25 Anklopfen vom Planer: Route sofort lesen (Übernahme bleibt ein Tipp am Auto). Im Test ersetzt. */
        Runnable klopfen = () -> {
            Thread t = new Thread(() -> {
                try {
                    at.greenchili.surfmate.planer.PlanerRoute.get().angeklopft(System.currentTimeMillis());
                } catch (Throwable ignored) { }
            }, "Bruecke-Klopf");
            t.setDaemon(true);
            t.start();
        };

        Kanaele(Bruecke bruecke, BrueckeWeiterleitung weiter, Sender sender, java.util.function.LongSupplier uhr) {
            this.bruecke = bruecke;
            this.weiter = weiter;
            this.sender = sender;
            this.uhr = uhr;
            this.einmalFertig = () -> {
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) { }
                    bruecke.einmalWeg();
                }, "Bruecke-Einmal");
                t.setDaemon(true);
                t.start();
            };
        }

        void steuer(String text) {
            JSONObject j;
            try {
                j = new JSONObject(text);
            } catch (Exception e) {
                return;
            }
            String t = j.optString("t");
            int k = j.optInt("k", -1);
            if ("neu".equals(t) && k >= 1 && k <= 255) {
                NoiseIK.Schluessel a = bruecke.autoSchluessel();
                if (a == null || offen.size() >= MAX_KANAELE) {
                    schliessen(k, true);
                    return;
                }
                Kanal neu = new Kanal(k, NoiseIK.responder(a));
                neu.medien = "medien".equals(j.optString("art", ""));
                Kanal alt = offen.put(k, neu);
                if (alt != null) alt.arbeit.shutdownNow();
            } else if ("zu".equals(t) && k >= 0) {
                schliessen(k, false);   // idempotent (Server meldet ggf. mehrfach)
            } else if ("limit".equals(t)) {
                log("Server meldet Limit");
            } else if ("klopf".equals(t)) {
                log("Anklopfen vom Planer – Route wird gelesen");
                klopfen.run();
            }
        }

        void schliessen(int k, boolean melden) {
            Kanal c = offen.remove(k);
            if (c != null) c.arbeit.shutdownNow();
            if (melden) {
                try {
                    sender.text(new JSONObject().put("t", "zu").put("k", k).toString());
                } catch (Exception ignored) { }
            }
        }

        void alleSchliessen() {
            for (Integer k : offen.keySet()) schliessen(k, false);
        }

        void kopplungSchliessen(String kid) {
            for (Kanal c : offen.values()) if (kid != null && kid.equals(c.kid)) schliessen(c.k, true);
        }

        /** Binärrahmen vom Server: {@code [k][Noise-Nachricht]}. */
        void binaer(byte[] rahmen) {
            if (rahmen == null || rahmen.length < 2) return;
            int k = rahmen[0] & 0xff;
            Kanal c = offen.get(k);
            if (c == null) {
                schliessen(k, true);
                return;
            }
            byte[] m = java.util.Arrays.copyOfRange(rahmen, 1, rahmen.length);
            try {
                synchronized (c) {
                    if (!c.noise.fertig()) {
                        c.noise.leseNachricht1(m);
                        String kid = bruecke.kidFuerSchluessel(c.noise.gegenseite(), uhr.getAsLong());
                        if (kid == null) {
                            // 0.1.24 Erstkopplung: nur solange der Einmal-Zugang des Kopplungs-QR läuft, nur ein Kanal
                            if (!bruecke.einmalOffen(uhr.getAsLong()) || einmalBelegt(k)) {
                                log("Handshake von unbekanntem Handy-Schlüssel abgelehnt");
                                schliessen(k, true);
                                return;
                            }
                            c.einmal = true;
                            c.hPub = c.noise.gegenseite();
                            log("Erstkopplung über die Brücke: Kanal " + k);
                        }
                        c.kid = kid;
                        senden(k, c.noise.schreibeNachricht2(new byte[0]));
                        return;
                    }
                    byte[] anfrage = c.sammler.hinzu(c.noise.entschluesseln(m));
                    if (anfrage == null) return;
                    // v2-Steuerung (ab/da/keyframe) sofort, nicht hinter der laufenden Antwort anstellen
                    if (steuerung(c, anfrage)) return;
                    c.arbeit.execute(() -> antworten(c, anfrage));
                }
            } catch (Exception e) {
                log("Kanal " + k + " geschlossen: " + e.getClass().getSimpleName());
                schliessen(k, true);
            } catch (Throwable t) {
                schliessen(k, true);
            }
        }

        private boolean einmalBelegt(int ausser) {
            for (Kanal c : offen.values()) if (c.k != ausser && c.einmal) return true;
            return false;
        }

        /** {@code {"t":"ab","id":n}} → n, sonst -1. */
        static long abbruchId(byte[] anfrage) {
            if (anfrage.length > 200) return -1;
            try {
                JSONObject j = new JSONObject(new String(anfrage, java.nio.charset.StandardCharsets.UTF_8));
                return "ab".equals(j.optString("t", "")) ? j.optLong("id", -1) : -1;
            } catch (Exception e) {
                return -1;
            }
        }

        /** {@code {"t":"ab"|"da"|"keyframe","id":n}} sofort wirken lassen. true = war Steuerung. */
        boolean steuerung(Kanal c, byte[] anfrage) {
            if (anfrage.length > 200) return false;
            JSONObject j;
            try {
                j = new JSONObject(new String(anfrage, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                return false;
            }
            String t = j.optString("t", "");
            long id = j.optLong("id", -1);
            if (id < 0 || !(t.equals("ab") || t.equals("da") || t.equals("keyframe"))) return false;
            BrueckeLive.Sitzung s = c.live;
            boolean seine = s != null && s.id == id;
            if (t.equals("ab")) {
                c.abgebrochen.add(id);
                if (seine) s.stopp = true;
            } else if (seine && t.equals("da")) {
                s.da(uhr.getAsLong());
            } else if (seine) {
                s.keyframe();
            }
            return true;
        }

        private BrueckeWeiterleitung.StueckAusgang ausgang(Kanal c) {
            return new BrueckeWeiterleitung.StueckAusgang() {
                public boolean senden(byte[] stueck) throws Exception {
                    synchronized (c) {
                        if (offen.get(c.k) != c) return false;
                        BrueckeVerbindung.Kanaele.this.senden(c.k, c.noise.verschluesseln(stueck));
                        return true;
                    }
                }
                public long warteschlange() { return sender.warteschlange(); }
            };
        }

        /** 0.1.25 LIVE: Prüfungen wie ein Kamera-Pfad (JWT der Kopplung, Recht „kamera“), dann bis Stopp streamen. */
        private void live(Kanal c, byte[] anfrage, JSONObject j) {
            BrueckeWeiterleitung.StueckAusgang aus = ausgang(c);
            long id = j.optLong("id", -1);
            try {
                if (j.optInt("v", 1) != 2) {
                    antwortSenden(c, BrueckeWeiterleitung.fehler(id, 400, "live_nur_v2"));
                    return;
                }
                JSONObject probe = new JSONObject(j.toString()).put("m", "GET").put("p", "/ws");
                BrueckeWeiterleitung.Vorbereitet v = weiter.vorbereiten(
                        probe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), c.kid, 0);
                if (v.fehler != null) {
                    aus.senden(BrueckeWeiterleitung.v2Fehler(v));
                    return;
                }
                BrueckeLive.Sitzung s = new BrueckeLive.Sitzung(id, uhr.getAsLong());
                c.live = s;
                BrueckeLive.Quelle q = liveQuelle != null ? liveQuelle : BrueckeLive.imDaemon();
                BrueckeLive.laufen(s, j.optString("q", "ULTRA_LOW").toUpperCase(java.util.Locale.ROOT), q, aus,
                        weiter.konto(), uhr);
            } catch (Exception e) {
                schliessen(c.k, true);
            } finally {
                c.live = null;
            }
        }

        private void antworten(Kanal c, byte[] anfrage) {
            if (!c.einmal) {
                JSONObject j = null;
                try {
                    j = new JSONObject(new String(anfrage, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
                if (j != null && BrueckeLive.istLive(j)) {
                    live(c, anfrage, j);
                    return;
                }
            }
            if (!c.einmal && BrueckeWeiterleitung.istV2(anfrage)) {
                weiter.strom(anfrage, c.kid, c.medien, ausgang(c), c.abgebrochen::contains);
                return;
            }
            if (c.medien) {
                // Medien-Kanal: nur v2 (Kamera-Daten)
                antwortSenden(c, BrueckeWeiterleitung.fehler(-1, 403, "medien_kanal"));
                return;
            }
            byte[] antwort;
            if (c.einmal) {
                BrueckeWeiterleitung.EinmalSchritt s = weiter.einmal(anfrage, c.kid, c.hPub);
                antwort = s.antwort;
                if (s.neueKid != null) c.kid = s.neueKid;
                if (s.fertig) {
                    log("Erstkopplung über die Brücke fertig");
                    einmalFertig.run();
                }
            } else {
                antwort = weiter.bearbeiten(anfrage, c.kid);
            }
            antwortSenden(c, antwort);
        }

        private void antwortSenden(Kanal c, byte[] antwort) {
            try {
                synchronized (c) {
                    if (offen.get(c.k) != c) return;
                    for (byte[] stueck : BrueckeRahmen.stueckeln(antwort)) senden(c.k, c.noise.verschluesseln(stueck));
                }
            } catch (Exception e) {
                schliessen(c.k, true);
            }
        }

        private void senden(int k, byte[] nachricht) {
            byte[] r = new byte[nachricht.length + 1];
            r[0] = (byte) k;
            System.arraycopy(nachricht, 0, r, 1, nachricht.length);
            sender.binaer(r);
        }
    }

    // ------------------------------------------------------------------ Netz (OkHttp)

    private static final OkHttpClient WS = BrueckeServerApi.HTTP.newBuilder()
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private static final Object SPERRE = new Object();
    private static volatile WebSocket socket;
    private static volatile Kanaele kanaele;
    private static volatile boolean laufen;
    private static volatile boolean verbunden;
    private static volatile Thread waechter;
    private static volatile long rueckzugMs = 2000;

    private BrueckeVerbindung() {}

    public static boolean verbunden() { return verbunden; }

    /** Startet (oder hält) die Verbindung, wenn die Brücke an ist. Mehrfach aufrufbar. */
    public static void starten() {
        synchronized (SPERRE) {
            if (laufen) return;
            if (!Bruecke.get().ein()) return;
            laufen = true;
            Thread t = new Thread(BrueckeVerbindung::schleife, "Bruecke");
            t.setDaemon(true);
            waechter = t;
            t.start();
        }
    }

    public static void stoppen() {
        synchronized (SPERRE) {
            laufen = false;
            Thread t = waechter;
            if (t != null) t.interrupt();
            WebSocket s = socket;
            if (s != null) s.close(1000, null);
            Kanaele kk = kanaele;
            if (kk != null) kk.alleSchliessen();
        }
    }

    /** Aus dem Widerruf einer Kopplung. */
    static void kanaeleSchliessen(String kid) {
        Kanaele kk = kanaele;
        if (kk != null) kk.kopplungSchliessen(kid);
    }

    private static void schleife() {
        BrueckeWeiterleitung weiter = BrueckeWeiterleitung.imDaemon();
        while (laufen) {
            Bruecke b = Bruecke.get();
            if (!b.ein()) break;
            final Object fertig = new Object();
            final boolean[] zu = {false};
            Sender sender = new Sender() {
                public boolean text(String s) { WebSocket w = socket; return w != null && w.send(s); }
                public boolean binaer(byte[] x) { WebSocket w = socket; return w != null && w.send(ByteString.of(x)); }
                public long warteschlange() { WebSocket w = socket; return w == null ? 0 : w.queueSize(); }
            };
            Kanaele kk = new Kanaele(b, weiter, sender, System::currentTimeMillis);
            kanaele = kk;
            String url = b.server().replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://") + "/v1/auto";
            Request req = new Request.Builder().url(url).build();
            try {
                socket = WS.newWebSocket(req, new WebSocketListener() {
                    @Override public void onOpen(WebSocket w, Response r) {
                        try {
                            w.send(new JSONObject().put("t", "hallo").put("p", b.postfach()).put("g", b.geheimnis()).toString());
                            verbunden = true;
                            rueckzugMs = 2000;
                            log("verbunden");
                            // 0.1.25 Klopf-Merkmal einmalig beim Brücken-Server anmelden (Netz: eigener Thread)
                            Thread kt = new Thread(() -> {
                                try { b.klopfAnmelden(); } catch (Throwable ignored) { }
                            }, "Bruecke-Klopf-Anmeldung");
                            kt.setDaemon(true);
                            kt.start();
                        } catch (Exception e) {
                            w.close(1000, null);
                        }
                    }
                    @Override public void onMessage(WebSocket w, String text) { kk.steuer(text); }
                    @Override public void onMessage(WebSocket w, ByteString bytes) { kk.binaer(bytes.toByteArray()); }
                    @Override public void onClosing(WebSocket w, int code, String grund) {
                        log("Server schließt: " + code);
                        w.close(1000, null);
                        ende();
                    }
                    @Override public void onClosed(WebSocket w, int code, String grund) { ende(); }
                    @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                        log("Verbindung weg: " + t.getClass().getSimpleName());
                        ende();
                    }
                    private void ende() {
                        synchronized (fertig) {
                            zu[0] = true;
                            fertig.notifyAll();
                        }
                    }
                });
                synchronized (fertig) {
                    while (!zu[0] && laufen) {
                        fertig.wait(30_000);
                        b.einmalAufraeumen(System.currentTimeMillis());   // abgelaufener Einmal-Zugang (Kopplungs-QR)
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                log("Start fehlgeschlagen: " + t.getClass().getSimpleName());
            } finally {
                verbunden = false;
                kk.alleSchliessen();
                WebSocket s = socket;
                if (s != null) s.cancel();
                socket = null;
            }
            if (!laufen) break;
            try {
                Thread.sleep(rueckzugMs);
            } catch (InterruptedException e) {
                break;
            }
            rueckzugMs = Math.min(60_000, rueckzugMs * 2);
        }
        synchronized (SPERRE) {
            laufen = false;
            waechter = null;
        }
    }

    private static void log(String s) {
        try {
            at.greenchili.surfmate.logging.DaemonLogger.getInstance(TAG).info(s);
        } catch (Throwable ignored) {
            // JVM-Tests ohne Logger
        }
    }
}
