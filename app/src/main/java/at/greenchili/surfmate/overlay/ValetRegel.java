package at.greenchili.surfmate.overlay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Display-Sperre / Valet-Modus (0.1.37, DashArc-Abgleich Punkt 26), reine Regeln ohne Android.
 *
 * <p>Grundsätze:
 * <ul>
 *   <li>Die Sperre ist nur ein Panoptes-Overlay über anderen Apps. Sie gilt nur im Stand (P bzw. Auto aus) und wird
 *       nie während der Fahrt eingeschaltet.</li>
 *   <li>Sie hebt sich selbst auf, sobald das Auto nicht mehr sicher steht (Gang R/D/N, Tempo, Zustand unbekannt).
 *       Rückfahrkamera und Notruf sind damit nie blockiert.</li>
 *   <li>Steht eine Kamera-, Telefon- oder Notruf-App im Vordergrund, zieht sich das Overlay zurück (bleibt aber
 *       an), bis sie weg ist.</li>
 *   <li>Notausgang: lang drücken plus PIN. Nach je {@link #MELDEN_NACH} Fehlversuchen eine Meldung ans Handy, danach
 *       kurze Wartezeit je Versuch, nie eine dauerhafte Sperre.</li>
 *   <li>Alles nur im Speicher des Daemons: ein Neustart hebt die Sperre auf. Das Overlay zeigt sich nur, solange der
 *       Daemon es regelmäßig bestätigt ({@link #overlayZeigen}).</li>
 * </ul>
 */
public final class ValetRegel {

    public static final int PIN_MIN = 4, PIN_MAX = 8;
    public static final int MELDEN_NACH = 5;
    public static final long WARTEN_MS = 10_000L;
    /** So lange zeigt das Overlay sich ohne neue Bestätigung vom Daemon. */
    public static final long HERZSCHLAG_MS = 6_000L;
    /** Lange drücken, bis die PIN-Eingabe kommt. */
    public static final long LANG_DRUECKEN_MS = 2_000L;

    /** Paket-Teile im Vordergrund, bei denen das Overlay weicht (Kamera, Telefon, Notruf). */
    static final List<String> WEICHEN_KAMERA = Collections.unmodifiableList(Arrays.asList(
            "avm", "panorama", "rvc", "reverse", "rearview", "camera", "parking"));
    static final List<String> WEICHEN_ANRUF = Collections.unmodifiableList(Arrays.asList(
            "dialer", "incall", "telecom", "ecall", "sos", "emergency", "phone", "bluetooth.call", "btphone"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private boolean aktiv;
    private byte[] salz;
    private byte[] pinHash;
    private int fehlversuche;
    private long letzterFehler;
    private String aufgehobenGrund;

    /** Lage des Displays: zeigen, aktiv und warum. */
    public static final class Lage {
        public final boolean aktiv, zeigen;
        public final String grund;

        Lage(boolean aktiv, boolean zeigen, String grund) {
            this.aktiv = aktiv;
            this.zeigen = zeigen;
            this.grund = grund;
        }
    }

    /** Ergebnis eines Entsperr-Versuchs. */
    public static final class Versuch {
        public final boolean entsperrt, melden;
        public final String grund;
        public final int fehlversuche;
        public final long wartenMs;

        Versuch(boolean entsperrt, boolean melden, String grund, int fehlversuche, long wartenMs) {
            this.entsperrt = entsperrt;
            this.melden = melden;
            this.grund = grund;
            this.fehlversuche = fehlversuche;
            this.wartenMs = wartenMs;
        }
    }

    public static boolean pinGueltig(String pin) {
        if (pin == null || pin.length() < PIN_MIN || pin.length() > PIN_MAX) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    /**
     * Sperre einschalten. Gründe: pin_ungueltig, nicht_in_p (jeder Bewegungs- oder Unklar-Grund), schon_an.
     *
     * @param bewegungsGrund {@code DrivingSafetyGuard.getMovementBlockReason()}: null = sicher geparkt
     */
    public synchronized String anschalten(String pin, String bewegungsGrund) {
        if (!pinGueltig(pin)) return "pin_ungueltig";
        if (bewegungsGrund != null) return "nicht_in_p";
        if (aktiv) return "schon_an";
        salz = new byte[16];
        RANDOM.nextBytes(salz);
        pinHash = hash(salz, pin);
        aktiv = true;
        fehlversuche = 0;
        letzterFehler = 0;
        aufgehobenGrund = null;
        return null;
    }

    /** Sperre aufheben (Grund als Kennwort, z. B. pin, fahrt, rueckwaerts, fern). */
    public synchronized void aufheben(String grund) {
        aktiv = false;
        if (pinHash != null) Arrays.fill(pinHash, (byte) 0);
        pinHash = null;
        salz = null;
        fehlversuche = 0;
        aufgehobenGrund = grund;
    }

    public synchronized boolean aktiv() {
        return aktiv;
    }

    public synchronized int fehlversuche() {
        return fehlversuche;
    }

    public synchronized String aufgehobenGrund() {
        return aufgehobenGrund;
    }

    /**
     * Einmal je Takt: hebt bei Fahrt/R/unklarem Zustand auf und entscheidet, ob das Overlay gerade gezeigt wird.
     *
     * @param bewegungsGrund null = sicher geparkt, sonst Grund von {@code DrivingSafetyGuard}
     * @param rueckwaerts    Gang R gemeldet
     * @param vordergrund    Paket der App im Vordergrund (unter dem Overlay) oder null
     * @param anruf          Telefon meldet ein laufendes Gespräch/Klingeln
     */
    public synchronized Lage takt(String bewegungsGrund, boolean rueckwaerts, String vordergrund, boolean anruf) {
        if (!aktiv) return new Lage(false, false, aufgehobenGrund);
        if (rueckwaerts) {
            aufheben("rueckwaerts");
            return new Lage(false, false, "rueckwaerts");
        }
        if (bewegungsGrund != null) {
            aufheben("fahrt");
            return new Lage(false, false, "fahrt");
        }
        if (anruf || passt(vordergrund, WEICHEN_ANRUF)) return new Lage(true, false, "anruf");
        if (passt(vordergrund, WEICHEN_KAMERA)) return new Lage(true, false, "kamera");
        return new Lage(true, true, null);
    }

    static boolean passt(String paket, List<String> teile) {
        if (paket == null || paket.isEmpty()) return false;
        String p = paket.toLowerCase(Locale.ROOT);
        if (p.startsWith("at.greenchili.")) return false;
        for (String t : teile) if (p.contains(t)) return true;
        return false;
    }

    /** PIN am Display. Gründe: nicht_aktiv, warten, falsch. */
    public synchronized Versuch entsperren(String pin, long now) {
        if (!aktiv) return new Versuch(false, false, "nicht_aktiv", 0, 0);
        if (fehlversuche >= MELDEN_NACH && now - letzterFehler < WARTEN_MS && now >= letzterFehler) {
            return new Versuch(false, false, "warten", fehlversuche, WARTEN_MS - (now - letzterFehler));
        }
        if (pinGueltig(pin) && MessageDigest.isEqual(hash(salz, pin), pinHash)) {
            aufheben("pin");
            return new Versuch(true, false, null, 0, 0);
        }
        fehlversuche++;
        letzterFehler = now;
        boolean melden = fehlversuche % MELDEN_NACH == 0;
        return new Versuch(false, melden, "falsch", fehlversuche, fehlversuche >= MELDEN_NACH ? WARTEN_MS : 0);
    }

    /** Overlay-Seite: nur zeigen, wenn die letzte Bestätigung frisch ist (Daemon tot oder neu gestartet = weg). */
    public static boolean overlayZeigen(boolean zeigenLaut, long letzteBestaetigung, long now) {
        return zeigenLaut && letzteBestaetigung > 0 && now >= letzteBestaetigung
                && now - letzteBestaetigung <= HERZSCHLAG_MS;
    }

    private static byte[] hash(byte[] salz, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(concat(salz, pin.getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < 20_000; i++) h = md.digest(concat(salz, h));
            return h;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[(a == null ? 0 : a.length) + b.length];
        if (a != null) System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, r.length - b.length, b.length);
        return r;
    }
}
