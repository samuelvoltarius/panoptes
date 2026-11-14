package at.greenchili.surfmate.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.function.LongSupplier;

/**
 * 0.1.43.4 (Kleinkram, F7): Datei per QR-Code zwischen Handy und Auto. Rein bis auf die Adresssuche; die Uhr ist
 * einsetzbar. Gemeinsame Grundlage für „Datei vom Handy ins Auto“ (hochladen) und „Datei vom Auto aufs Handy“
 * (holen, z. B. eine Aufnahme aus der Kamera-Liste des Launchers).
 *
 * <p>Regeln (Koordination 13.11.2026): nur im lokalen Netz (private oder Link-Local-Adresse, nie Loopback und nie
 * Tailscale/Tunnel), Einmal-Kennung mit 128 Bit, {@link #GUELTIG_MS} gültig, höchstens EINE aktive Sitzung (eine neue
 * ersetzt die alte), eine Datei je Kennung, falsche Kennungen werden gezählt und sperren kurz. Nichts geht nach
 * außen; Ziel der Datei ist der Panoptes-Ordner ({@link Dateiablage}).
 */
public final class DateiQr {

    /** Wie lange ein QR-Code gilt (Anzeige „läuft ab in 1:57“). */
    public static final long GUELTIG_MS = 120_000L;
    /** Falsche Kennungen je Fenster, danach 429. */
    static final int FEHLER_MAX = 10;
    static final long FEHLER_FENSTER_MS = 120_000L;

    public static final String HOCHLADEN = "hochladen";
    public static final String HOLEN = "holen";

    /** Zustände der Sitzung. */
    public static final String OFFEN = "offen";
    public static final String LAEUFT = "laeuft";
    public static final String FERTIG = "fertig";
    public static final String ABGELAUFEN = "abgelaufen";

    /** Eine Datei vom Auto zum Handy (nur für {@link #HOLEN}); der Aufrufer liefert Namen, Länge und Strom. */
    public interface Quelle {
        String name();

        long laenge();

        /** Öffnet die Datei neu; der Aufrufer schließt den Strom. */
        InputStream oeffnen() throws IOException;
    }

    /** Eine QR-Sitzung (unveränderlich nach außen, nur über {@link DateiQr} geändert). */
    public static final class Sitzung {
        public final String kennung;
        public final String zweck;
        public final long bis;
        public final String anzeigeName;   // nur HOLEN: Name der angebotenen Datei
        final Quelle quelle;
        String zustand = OFFEN;
        String uploadId;
        String ergebnisName;

        Sitzung(String kennung, String zweck, long bis, String anzeigeName, Quelle quelle) {
            this.kennung = kennung;
            this.zweck = zweck;
            this.bis = bis;
            this.anzeigeName = anzeigeName;
            this.quelle = quelle;
        }

        public String zustand() {
            return zustand;
        }

        /** Nur HOLEN: die angebotene Datei oder null. */
        public Quelle quelleHolen() {
            return quelle;
        }

        /** Nur HOLEN: Länge der angebotenen Datei, sonst -1. */
        public long groesseHolen() {
            return quelle == null ? -1 : quelle.laenge();
        }

        public String ergebnisName() {
            return ergebnisName;
        }
    }

    /** Ergebnis einer Prüfung: {@code grund == null} heißt gültig. */
    public static final class Pruefung {
        public final String grund;      // null | falsch | abgelaufen | zu_viele | belegt
        public final int http;
        public final Sitzung sitzung;

        Pruefung(String grund, int http, Sitzung sitzung) {
            this.grund = grund;
            this.http = http;
            this.sitzung = sitzung;
        }

        public boolean ok() {
            return grund == null;
        }
    }

    private static final SecureRandom ZUFALL = new SecureRandom();
    private final LongSupplier uhr;
    private Sitzung aktiv;
    private int fehler;
    private long fehlerSeit;

    public DateiQr() {
        this(System::currentTimeMillis);
    }

    public DateiQr(LongSupplier uhr) {
        this.uhr = uhr;
    }

    private static String neueKennung() {
        byte[] b = new byte[16];
        ZUFALL.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** Neue Sitzung „Handy → Auto“. Die vorige ist damit ungültig. */
    public synchronized Sitzung neuHochladen() {
        aktiv = new Sitzung(neueKennung(), HOCHLADEN, uhr.getAsLong() + GUELTIG_MS, null, null);
        return aktiv;
    }

    /** Neue Sitzung „Auto → Handy“ für genau diese eine Datei. */
    public synchronized Sitzung neuHolen(String anzeigeName, Quelle quelle) {
        if (quelle == null) throw new IllegalArgumentException("quelle");
        String n = anzeigeName == null || anzeigeName.isEmpty() ? quelle.name() : anzeigeName;
        aktiv = new Sitzung(neueKennung(), HOLEN, uhr.getAsLong() + GUELTIG_MS, n, quelle);
        return aktiv;
    }

    /** Sitzung beenden (Fenster zu). */
    public synchronized void beenden() {
        aktiv = null;
    }

    /**
     * Prüft eine Kennung aus dem Handy. Falsche oder fehlende Kennungen zählen; zu viele sperren kurz.
     * {@code zweck} muss zur Sitzung passen ({@code null} = egal, z. B. für „info“).
     */
    public synchronized Pruefung pruefe(String kennung, String zweck) {
        long jetzt = uhr.getAsLong();
        if (jetzt - fehlerSeit > FEHLER_FENSTER_MS) {
            fehler = 0;
            fehlerSeit = jetzt;
        }
        if (fehler >= FEHLER_MAX) return new Pruefung("zu_viele", 429, null);
        Sitzung s = aktiv;
        if (s == null || kennung == null || !gleich(s.kennung, kennung) || (zweck != null && !s.zweck.equals(zweck))) {
            fehler++;
            return new Pruefung("falsch", 410, null);
        }
        if (jetzt > s.bis || ABGELAUFEN.equals(s.zustand)) {
            s.zustand = ABGELAUFEN;
            return new Pruefung("abgelaufen", 410, null);
        }
        if (FERTIG.equals(s.zustand)) return new Pruefung("abgelaufen", 410, null);   // einmal gilt einmal
        return new Pruefung(null, 200, s);
    }

    private static boolean gleich(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Hochladen beginnt: genau einmal je Kennung ({@code belegt}, wenn schon eine Datei läuft). */
    public synchronized boolean beginne(Sitzung s, String uploadId) {
        if (s != aktiv || !OFFEN.equals(s.zustand)) return false;
        s.zustand = LAEUFT;
        s.uploadId = uploadId;
        return true;
    }

    /** Gehört diese Upload-Id zur laufenden Sitzung? */
    public synchronized boolean istUpload(Sitzung s, String uploadId) {
        return s == aktiv && LAEUFT.equals(s.zustand) && uploadId != null && uploadId.equals(s.uploadId);
    }

    /** Datei ist komplett (hochgeladen bzw. abgeholt): die Kennung ist verbraucht. */
    public synchronized void verbrauche(Sitzung s, String name) {
        if (s != aktiv) return;
        s.zustand = FERTIG;
        s.ergebnisName = name;
    }

    /** Zustand für die Anzeige am Auto: aktuelle Sitzung oder null. Läuft die Zeit ab, wird sie {@link #ABGELAUFEN}. */
    public synchronized Sitzung aktuell() {
        Sitzung s = aktiv;
        if (s != null && uhr.getAsLong() > s.bis && !FERTIG.equals(s.zustand)) s.zustand = ABGELAUFEN;
        return s;
    }

    /** Sekunden bis zum Ablauf (nie negativ). */
    public synchronized long restSekunden(Sitzung s) {
        return Math.max(0, (s.bis - uhr.getAsLong() + 999) / 1000);
    }

    // ---- Netz (rein bis auf lanAdresse)

    /**
     * Ist die Gegenstelle im lokalen Netz? Nur private (10/8, 172.16/12, 192.168/16) und Link-Local-Adressen.
     * Loopback zählt nicht: Tailscale reicht Verbindungen über 127.0.0.1 an den Server weiter. Tailscale selbst
     * (100.64.0.0/10) ist weder privat noch Link-Local und fällt damit heraus.
     */
    public static boolean lokalesNetz(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
        return a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    /** Wie {@link #lokalesNetz(InetAddress)} für eine Adresszeichenkette (nur Ziffern und Punkte). */
    public static boolean lokalesNetz(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        try {
            return lokalesNetz(InetAddress.getByName(ip));
        } catch (Exception e) {
            return false;
        }
    }

    /** Erste lokale IPv4-Adresse des Autos (WLAN oder Hotspot), ohne Loopback und Tunnel; null, wenn keine da ist. */
    public static String lanAdresse() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface n = ifs.nextElement();
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                String name = n.getName() == null ? "" : n.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("tailscale") || name.startsWith("wg")) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (lokalesNetz(a)) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // keine Adresse
        }
        return null;
    }

    /** Adresse für den QR-Code; die Kennung steht im Fragment (#), geht also nie in einen Zugriffslog. */
    public static String url(String lan, int port, String kennung, String zweck) {
        return "http://" + lan + ":" + port + "/shared/datei-handy.html#k=" + kennung + "&z=" + (HOLEN.equals(zweck) ? "h" : "u");
    }
}
