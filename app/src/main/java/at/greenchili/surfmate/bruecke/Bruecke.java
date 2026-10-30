package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;

/**
 * Panoptes-Brücke, Auto-Teil (BRUECKE_VERTRAG.md, 0.1.20): Zustand und Abläufe ohne Netz-Einzelheiten.
 *
 * <ul>
 *   <li>Opt-in, Standard aus. Beim Einschalten: Auto-Schlüssel A (X25519), Postfach P und Geheimnis G_auto
 *       (je 32 Byte Zufall), Anmeldung {@code POST /v1/postfach}. Beim Ausschalten: {@code DELETE} und alles weg.</li>
 *   <li>Je Handy-Kopplung: öffentlicher Handy-Schlüssel und sha256(G_handy); Server-Zugang wird mitgepflegt.</li>
 *   <li>Abweichung vom Vertrag: Der private Auto-Schlüssel liegt nicht im Android-Keystore (im Daemon, uid 2000,
 *       gibt es keinen) – er liegt mit den anderen Geheimnissen im Daemon-Verzeichnis, Datei nur für den Besitzer.</li>
 * </ul>
 * Kodierung wie der Server: 32 Byte als base64url ohne „=“, Hashes = sha256 über die rohen Bytes, hex.
 */
public final class Bruecke {

    public static final String STANDARD_SERVER = "https://byd.serveone.at/bruecke";

    /** Ablage (Datei, im Test Speicher). */
    public interface Platte {
        JSONObject lade();
        boolean speichere(JSONObject zustand);
    }

    /** REST-Aufrufe am Brücken-Server. Rückgabe: HTTP-Status, -1 = nicht erreichbar. */
    public interface ServerApi {
        int postfachAnlegen(String server, String p, String gHash);
        int postfachLoeschen(String server, String p, String g);
        int zugangHinzu(String server, String p, String g, String gHandyHash);
        int zugangWeg(String server, String p, String g, String gHandyHash);
        /** 0.1.25 Anklopfen: sha256(k) beim Brücken-Server anmelden (204). */
        default int klopfAnmelden(String server, String p, String g, String kHash) { return -1; }
    }

    /** Kopplungen-Sicht: gültige Handy-Kopplung? */
    public interface Kopplungen {
        boolean gueltigesHandy(String kid, long jetzt);
    }

    private final Platte platte;
    private final ServerApi api;
    private final Kopplungen kopplungen;
    private final SecureRandom zufall = new SecureRandom();
    private JSONObject z;

    public Bruecke(Platte platte, ServerApi api, Kopplungen kopplungen) {
        this.platte = platte;
        this.api = api;
        this.kopplungen = kopplungen;
        JSONObject geladen = null;
        try { geladen = platte.lade(); } catch (Throwable ignored) { }
        this.z = geladen == null ? new JSONObject() : geladen;
        // 0.1.32.1: Bestand ohne gewählte Grenze behält die bisherigen 500 MB; neu = kein Limit, geht mit dem ersten
        // Speichern auf die Platte (sonst sähe der nächste Start einen Stand ohne Grenze als Bestand)
        if (!z.has("medienTagMb")) {
            try {
                boolean bestand = z.length() > 0;
                z.put("medienTagMb", bestand ? MEDIEN_BESTAND_MB : MEDIEN_STANDARD_MB);
                if (bestand) platte.speichere(z);
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ Kodierung

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 32 Byte aus base64url (mit oder ohne „=“), sonst null. */
    static byte[] b64_32(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        if (t.length() != 43 || !t.matches("[A-Za-z0-9_-]+")) return null;
        try {
            byte[] b = Base64.getUrlDecoder().decode(t);
            return b.length == 32 ? b : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String sha256Hex(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] zufall32() {
        byte[] b = new byte[32];
        zufall.nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ Zustand

    public synchronized boolean ein() { return z.optBoolean("ein", false) && z.has("a") && z.has("p") && z.has("g"); }

    public synchronized String server() {
        String s = z.optString("server", "").trim();
        return s.isEmpty() ? STANDARD_SERVER : s;
    }

    public synchronized String postfach() { return ein() ? z.optString("p") : null; }

    synchronized String geheimnis() { return ein() ? z.optString("g") : null; }

    /** Öffentlicher Auto-Schlüssel (base64url) oder null. */
    public synchronized String autoOeffentlich() {
        NoiseIK.Schluessel s = autoSchluessel();
        return s == null ? null : b64(s.oeffentlich);
    }

    synchronized NoiseIK.Schluessel autoSchluessel() {
        if (!ein()) return null;
        try {
            return new NoiseIK.Schluessel(b64_32(z.optString("a")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Server-Adresse setzen (nur aus Panoptes-Web mit Login). Nur https, sonst false. */
    public synchronized boolean serverSetzen(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // nur https; http ausschließlich an 127.0.0.1 (Test mit „adb reverse“ gegen einen lokalen Brücken-Server)
        if (!u.isEmpty() && !u.matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?")
                && !u.matches("http://127\\.0\\.0\\.1(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?")) return false;
        if (ein() && !u.equals(server())) return false;   // nicht im Betrieb wechseln: erst aus, dann ein
        try {
            if (u.isEmpty()) z.remove("server"); else z.put("server", u);
        } catch (Exception e) {
            return false;
        }
        return platte.speichere(z);
    }

    /** Ergebnis von ein/aus. */
    public enum Ergebnis { OK, SERVER_NICHT_ERREICHBAR, SERVER_ABGELEHNT, NICHT_GESPEICHERT }

    /** Einschalten: Schlüssel, Postfach, Anmeldung am Server. Idempotent. */
    public synchronized Ergebnis einschalten() {
        if (ein()) return Ergebnis.OK;
        try {
            byte[] a = com.google.crypto.tink.subtle.X25519.generatePrivateKey();
            String p = b64(zufall32()), g = b64(zufall32());
            int code = api.postfachAnlegen(server(), p, sha256Hex(b64_32(g)));
            if (code < 0) return Ergebnis.SERVER_NICHT_ERREICHBAR;
            if (code != 201 && code != 200) return Ergebnis.SERVER_ABGELEHNT;
            JSONObject n = new JSONObject(z.toString());
            n.put("ein", true);
            n.put("a", b64(a));
            n.put("p", p);
            n.put("g", g);
            n.put("handys", new JSONObject());
            if (!platte.speichere(n)) {
                api.postfachLoeschen(server(), p, g);
                return Ergebnis.NICHT_GESPEICHERT;
            }
            z = n;
            return Ergebnis.OK;
        } catch (Exception e) {
            return Ergebnis.SERVER_ABGELEHNT;
        }
    }

    /** Ausschalten: Postfach am Server löschen, Schlüssel und Handys vergessen. Lokal wirkt es immer. */
    public synchronized Ergebnis ausschalten() {
        if (!z.optBoolean("ein", false) && !z.has("p")) return Ergebnis.OK;
        String p = z.optString("p", null), g = z.optString("g", null);
        int code = p == null || g == null ? 204 : api.postfachLoeschen(server(), p, g);
        JSONObject n = new JSONObject();
        try {
            if (z.has("server")) n.put("server", z.optString("server"));
        } catch (Exception ignored) { }
        z = n;
        boolean ok = platte.speichere(n);
        if (!ok) return Ergebnis.NICHT_GESPEICHERT;
        return code == 204 || code == 404 ? Ergebnis.OK : Ergebnis.SERVER_NICHT_ERREICHBAR;
    }

    private JSONObject handys() {
        JSONObject h = z.optJSONObject("handys");
        if (h == null) {
            h = new JSONObject();
            try { z.put("handys", h); } catch (Exception ignored) { }
        }
        return h;
    }

    public synchronized int anzahlHandys() { return ein() ? handys().length() : 0; }

    /** Ergebnis der Handy-Anmeldung. */
    public enum HandyErgebnis { OK, AUS, UNGUELTIG, SERVER_NICHT_ERREICHBAR, SERVER_ABGELEHNT, NICHT_GESPEICHERT }

    /** {@code POST /api/handy/v1/bruecke}: Handy-Schlüssel und Zugang dieser Kopplung (ersetzt einen alten). */
    public synchronized HandyErgebnis handyAnmelden(String kid, String hPub, String gHandy) {
        if (!ein()) return HandyErgebnis.AUS;
        byte[] h = b64_32(hPub), gh = b64_32(gHandy);
        if (kid == null || kid.isEmpty() || h == null || gh == null) return HandyErgebnis.UNGUELTIG;
        String hash = sha256Hex(gh);
        int code = api.zugangHinzu(server(), postfach(), geheimnis(), hash);
        if (code < 0) return HandyErgebnis.SERVER_NICHT_ERREICHBAR;
        if (code != 201 && code != 200) return HandyErgebnis.SERVER_ABGELEHNT;
        JSONObject alt = handys().optJSONObject(kid);
        try {
            handys().put(kid, new JSONObject().put("h", b64(h)).put("z", hash));
        } catch (Exception e) {
            return HandyErgebnis.UNGUELTIG;
        }
        if (alt != null && !hash.equals(alt.optString("z"))) api.zugangWeg(server(), postfach(), geheimnis(), alt.optString("z"));
        return platte.speichere(z) ? HandyErgebnis.OK : HandyErgebnis.NICHT_GESPEICHERT;
    }

    /** Handy dieser Kopplung abmelden (Entkoppeln, Widerruf). Idempotent. */
    public synchronized boolean handyEntfernen(String kid) {
        if (kid == null) return false;
        JSONObject alt = handys().optJSONObject(kid);
        if (alt == null) return false;
        handys().remove(kid);
        if (ein()) api.zugangWeg(server(), postfach(), geheimnis(), alt.optString("z"));
        platte.speichere(z);
        return true;
    }

    /**
     * Kopplungs-id zum statischen Noise-Schlüssel des Handys – nur bei gültiger Handy-Kopplung. Die Kopplungen werden
     * außerhalb der eigenen Sperre gefragt (Widerruf ruft mit gehaltener Kopplungs-Sperre {@link #kopplungWeg}).
     */
    public String kidFuerSchluessel(byte[] hPub, long jetzt) {
        String gefunden = null;
        synchronized (this) {
            if (!ein() || hPub == null) return null;
            String gesucht = b64(hPub);
            JSONObject hs = handys();
            for (Iterator<String> it = hs.keys(); it.hasNext(); ) {
                String kid = it.next();
                JSONObject e = hs.optJSONObject(kid);
                if (e != null && MessageDigest.isEqual(gesucht.getBytes(StandardCharsets.US_ASCII),
                        e.optString("h").getBytes(StandardCharsets.US_ASCII))) {
                    gefunden = kid;
                    break;
                }
            }
        }
        return gefunden != null && kopplungen.gueltigesHandy(gefunden, jetzt) ? gefunden : null;
    }

    // ------------------------------------------------------------------ Einmal-Zugang (0.1.24, Erstkopplung über die Brücke)

    /**
     * Zum Kopplungs-Code (10 min, einmal, immer nur einer): ein zweiter Server-Zugang, damit ein noch unbekanntes Handy
     * ohne Direktweg durch die Brücke koppeln kann (BRUECKE_VERTRAG §2.3). G_einmal steht nur im QR hinter „#“. Ein neuer
     * Code ersetzt den alten Zugang. Liefert G_einmal (base64url) oder null (aus, Server nicht erreichbar).
     */
    public synchronized String einmalAnlegen(long bis) {
        if (!ein()) return null;
        einmalWeg();
        byte[] g = zufall32();
        String hash = sha256Hex(g);
        int code = api.zugangHinzu(server(), postfach(), geheimnis(), hash);
        if (code != 201 && code != 200) return null;
        try {
            z.put("einmal", new JSONObject().put("z", hash).put("bis", bis));
        } catch (Exception e) {
            api.zugangWeg(server(), postfach(), geheimnis(), hash);
            return null;
        }
        if (!platte.speichere(z)) {
            z.remove("einmal");
            api.zugangWeg(server(), postfach(), geheimnis(), hash);
            return null;
        }
        return b64(g);
    }

    /** Läuft gerade ein Einmal-Zugang? Nur dann nimmt das Auto einen unbekannten Handy-Schlüssel an. */
    public synchronized boolean einmalOffen(long jetzt) {
        JSONObject e = ein() ? z.optJSONObject("einmal") : null;
        return e != null && e.optLong("bis", 0) > jetzt;
    }

    /** Einmal-Zugang am Server löschen und vergessen (nach der Kopplung, beim neuen Code, bei Ablauf). Idempotent. */
    public synchronized void einmalWeg() {
        JSONObject e = z.optJSONObject("einmal");
        if (e == null) return;
        if (ein()) api.zugangWeg(server(), postfach(), geheimnis(), e.optString("z"));
        z.remove("einmal");
        platte.speichere(z);
    }

    /** Abgelaufenen Einmal-Zugang wegräumen (aus der Verbindungs-Schleife, auch nach einem Neustart). */
    public synchronized void einmalAufraeumen(long jetzt) {
        JSONObject e = z.optJSONObject("einmal");
        if (e != null && e.optLong("bis", 0) <= jetzt) einmalWeg();
    }

    // ------------------------------------------------------------------ 0.1.25 Tagesgrenze Kamera-Daten (BRUECKE_MEDIEN §5)

    /** 0.1.32.1 Stufe „kein Limit“: Abruf ist eine bewusste Handlung, die Größe steht vorher in der Liste. */
    public static final int MEDIEN_KEIN_LIMIT = -1;
    /** Erlaubte Einstellungen in MB pro Tag (0 = keine Kamera-Daten übers Internet). */
    public static final int[] MEDIEN_STUFEN_MB = {0, 100, 250, 500, 1000, 2000, MEDIEN_KEIN_LIMIT};
    /** Standard neuer Installationen; wer schon einen Brücken-Stand hat, behält {@link #MEDIEN_BESTAND_MB}. */
    static final int MEDIEN_STANDARD_MB = MEDIEN_KEIN_LIMIT;
    static final int MEDIEN_BESTAND_MB = 500;
    private static final long MB = 1024L * 1024L;
    private long medienTag = -1, medienHeute, medienGespeichert;

    public synchronized int medienTagMb() { return z.optInt("medienTagMb", MEDIEN_STANDARD_MB); }

    public synchronized boolean medienTagMbSetzen(int mb) {
        boolean erlaubt = false;
        for (int x : MEDIEN_STUFEN_MB) erlaubt |= x == mb;
        if (!erlaubt) return false;
        try { z.put("medienTagMb", mb); } catch (Exception e) { return false; }
        return platte.speichere(z);
    }

    private void medienTagPruefen(long jetzt) {
        long tag = jetzt / 86_400_000L;
        if (medienTag == tag) return;
        JSONObject m = z.optJSONObject("medienHeute");
        medienTag = tag;
        medienHeute = m != null && m.optLong("tag", -1) == tag ? m.optLong("bytes", 0) : 0;
        medienGespeichert = medienHeute;
    }

    /** Kamera-Daten über die Brücke zählen; false = Tagesgrenze erreicht (n = 0: nur prüfen). */
    public synchronized boolean medienVerbrauchen(long n, long jetzt) {
        medienTagPruefen(jetzt);
        int tagMb = medienTagMb();
        long grenze = tagMb * MB;
        if (tagMb != MEDIEN_KEIN_LIMIT && (medienHeute + n > grenze || (n == 0 && medienHeute >= grenze))) return false;
        medienHeute += n;
        if (medienHeute - medienGespeichert >= 10 * MB) {   // nicht bei jedem Stück auf die Platte
            try {
                z.put("medienHeute", new JSONObject().put("tag", medienTag).put("bytes", medienHeute));
                if (platte.speichere(z)) medienGespeichert = medienHeute;
            } catch (Exception ignored) { }
        }
        return true;
    }

    public synchronized long medienHeuteBytes(long jetzt) {
        medienTagPruefen(jetzt);
        return medienHeute;
    }

    // ------------------------------------------------------------------ 0.1.25 Anklopfen (Routenplaner)

    /**
     * Klopf-Merkmal k (32 Byte, base64url) für den Planer: das Auto schickt k als {@code X-Klopf} an {@code meine-route}
     * und meldet nur sha256(k) beim Brücken-Server an. Der Planer kennt kein Postfach, die Brücke kein Mitglied.
     * null, wenn die Brücke aus ist.
     */
    public synchronized String klopfMerkmal() {
        if (!ein()) return null;
        JSONObject k = z.optJSONObject("klopf");
        if (k == null || b64_32(k.optString("k")) == null) {
            k = new JSONObject();
            try {
                k.put("k", b64(zufall32())).put("angemeldet", false);
                z.put("klopf", k);
            } catch (Exception e) {
                return null;
            }
            if (!platte.speichere(z)) return null;
        }
        return k.optString("k");
    }

    /** Merkmal beim Brücken-Server anmelden, falls noch nicht geschehen (Netz – nicht im UI-Thread). */
    public synchronized boolean klopfAnmelden() {
        String k = klopfMerkmal();
        if (k == null) return false;
        JSONObject e = z.optJSONObject("klopf");
        if (e.optBoolean("angemeldet", false)) return true;
        int code = api.klopfAnmelden(server(), postfach(), geheimnis(), sha256Hex(b64_32(k)));
        if (code != 204 && code != 200) return false;
        try { e.put("angemeldet", true); } catch (Exception x) { return false; }
        return platte.speichere(z);
    }

    /** Status für Panoptes-Web (ohne Geheimnisse). */
    public synchronized JSONObject status() {
        JSONObject r = new JSONObject();
        try {
            r.put("ein", ein());
            r.put("server", server());
            r.put("handys", anzahlHandys());
            r.put("medienTagMb", medienTagMb());
            r.put("medienHeuteMb", Math.round(medienHeuteBytes(System.currentTimeMillis()) * 10.0 / MB) / 10.0);
            if (ein()) {
                r.put("p", postfach());
                r.put("a", autoOeffentlich());
            }
        } catch (Exception ignored) { }
        return r;
    }

    /** Block für Kopplungs-Antwort und QR v2: {@code {"p","a_pub","server"}} bzw. null, wenn aus. */
    public synchronized JSONObject kopplungsBlock() {
        if (!ein()) return null;
        try {
            return new JSONObject().put("p", postfach()).put("a_pub", autoOeffentlich()).put("server", server());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Laufzeit (Daemon)

    private static volatile Bruecke instanz;

    public static Bruecke get() {
        Bruecke b = instanz;
        if (b == null) {
            synchronized (Bruecke.class) {
                if (instanz == null) instanz = new Bruecke(new DateiPlatte(), new BrueckeServerApi(),
                        (kid, jetzt) -> at.greenchili.surfmate.auth.Kopplungen.get().istHandy(kid)
                                && at.greenchili.surfmate.auth.Kopplungen.get().pruefe(kid, jetzt));
                b = instanz;
            }
        }
        return b;
    }

    /** Aus {@code Kopplungen.widerrufen}: Handy der Kopplung abmelden und offene Kanäle schließen. */
    public static void kopplungWeg(String kid) {
        try {
            BrueckeVerbindung.kanaeleSchliessen(kid);
        } catch (Throwable ignored) { }
        // Server-Aufruf nicht unter der Kopplungs-Sperre des Aufrufers
        Thread t = new Thread(() -> {
            try {
                get().handyEntfernen(kid);
            } catch (Throwable ignored) { }
        }, "Bruecke-Widerruf");
        t.setDaemon(true);
        t.start();
    }

    static final class DateiPlatte implements Platte {
        private final File datei = new File(at.greenchili.surfmate.util.DaemonStorage.path("bruecke.json"));

        public JSONObject lade() {
            try {
                if (!datei.isFile()) return new JSONObject();
                return new JSONObject(new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public boolean speichere(JSONObject zustand) {
            try {
                File tmp = new File(datei.getPath() + ".tmp");
                Files.write(tmp.toPath(), zustand.toString().getBytes(StandardCharsets.UTF_8));
                //noinspection ResultOfMethodCallIgnored
                tmp.setReadable(false, false);
                //noinspection ResultOfMethodCallIgnored
                tmp.setReadable(true, true);
                //noinspection ResultOfMethodCallIgnored
                tmp.setWritable(false, false);
                //noinspection ResultOfMethodCallIgnored
                tmp.setWritable(true, true);
                return tmp.renameTo(datei);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
