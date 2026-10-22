package at.greenchili.surfmate.mqtt;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Reine Entscheidungen für das Wiederverbinden der MQTT-Verbindungen (Koordination 21.10.: beim
 * Parken – power_level → 0 bzw. Netzwechsel – fiel die Verbindung und kam nicht von selbst zurück).
 *
 * <ul>
 *   <li>Backoff 5 s → 60 s mit Zufallsanteil ±20 % (früher bis 300 s ohne Streuung).</li>
 *   <li>Netz-Fingerabdruck aus den aktiven Schnittstellen: ändert er sich, ist der alte Socket
 *       verdächtig (halb offen) und wird sofort ersetzt, Backoff zurück auf null.</li>
 *   <li>power_level-Wechsel (ACC an/aus): sofort neu prüfen.</li>
 *   <li>Stehende Schleife: kein Zyklus seit {@link #stehtNachMs} → Verbindung neu aufsetzen.</li>
 * </ul>
 * Kein Wakelock: alles läuft nur, solange die CPU ohnehin wach ist.
 */
public final class Wiederverbindung {

    private Wiederverbindung() {}

    static final long BASIS_MS = 5_000L;
    static final long DECKEL_MS = 60_000L;
    /** Wächter-Takt. */
    public static final long WACHE_MS = 15_000L;

    /** Wartezeit vor dem nächsten Versuch nach {@code fehlversuche} Fehlschlägen; zufall in [0,1). */
    public static long backoffMs(int fehlversuche, double zufall) {
        if (fehlversuche <= 0) return 0L;
        long roh = BASIS_MS << Math.min(fehlversuche - 1, 10);
        long gedeckelt = Math.min(roh, DECKEL_MS);
        double faktor = 0.8 + 0.4 * Math.max(0.0, Math.min(1.0, zufall));
        return Math.max(1_000L, Math.round(gedeckelt * faktor));
    }

    /** Ab wann ein ausbleibender Zyklus als „steht“ gilt. */
    public static long stehtNachMs(long zyklusMs, long backoffMs) {
        return Math.max(3 * Math.max(1_000L, zyklusMs), 90_000L) + Math.max(0L, backoffMs);
    }

    /** Was der Wächter für eine Verbindung tun soll. */
    public enum Aktion { NICHTS, SOFORT_PRUEFEN, NETZ_NEU, NEU_AUFSETZEN }

    /**
     * @param netzGeaendert   Netz-Fingerabdruck hat sich seit dem letzten Wächterlauf geändert
     * @param powerGeaendert  power_level hat sich geändert (ACC an/aus)
     * @param verbunden       die Verbindung meldet sich verbunden
     * @param letzterZyklusMs Zeitpunkt des letzten Publish-Zyklus (0 = noch keiner)
     * @param jetztMs         jetzt
     * @param stehtNachMs     Grenze aus {@link #stehtNachMs}
     */
    public static Aktion entscheide(boolean netzGeaendert, boolean powerGeaendert, boolean verbunden,
                                    long letzterZyklusMs, long jetztMs, long stehtNachMs) {
        if (letzterZyklusMs > 0 && jetztMs - letzterZyklusMs > stehtNachMs) return Aktion.NEU_AUFSETZEN;
        if (netzGeaendert) return Aktion.NETZ_NEU;
        if (powerGeaendert) return Aktion.SOFORT_PRUEFEN;
        return Aktion.NICHTS;
    }

    /** Fingerabdruck aus Schnittstellen „name=adresse,adresse“ (sortiert, ohne Loopback). */
    public static String fingerabdruck(List<String> eintraege) {
        List<String> l = new ArrayList<>(eintraege);
        Collections.sort(l);
        return String.join(";", l);
    }

    /** Liest die aktiven Schnittstellen; bei Fehlern leer (dann gilt „unverändert“). */
    public static String aktuellerFingerabdruck() {
        List<String> eintraege = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                List<String> adr = new ArrayList<>();
                Enumeration<InetAddress> a = ni.getInetAddresses();
                while (a.hasMoreElements()) {
                    InetAddress ia = a.nextElement();
                    if (ia.isLinkLocalAddress()) continue;
                    adr.add(ia.getHostAddress());
                }
                if (adr.isEmpty()) continue;
                Collections.sort(adr);
                eintraege.add(ni.getName() + "=" + String.join(",", adr));
            }
        } catch (Throwable t) {
            return null;
        }
        return fingerabdruck(eintraege);
    }
}
