package at.greenchili.surfmate.byd.cloud.crypto;

import java.util.function.UnaryOperator;

/**
 * 0.1.38.6 Sicherheitsfix: Zugangsdaten auf der Platte über {@link CredentialCipher}. MQTT (schon seit Langem
 * {@code ENC:} im selben Feld, auch 0.1.38.5 liest das) wird beim Laden neu geschrieben ({@link #noetig}); alle
 * anderen Felder bekommen eine Schattenkopie ({@link #schatten}), weil 0.1.38.5 dort nur Klartext liest.
 *
 * <p>Die Entscheidung ist rein (Entschlüsseln eingesetzt), damit sie ohne Android testbar ist. Zwei Wächter:
 * <ul>
 *   <li>Neu schreiben nur, wenn Verschlüsseln gerade wirklich geht (Geräte-Id lesbar). Sonst würde
 *       {@code encrypt()} wieder Klartext liefern und nichts wäre gewonnen.</li>
 *   <li>Nie neu schreiben, solange ein gespeicherter {@code ENC:}-Wert nicht entschlüsselbar ist: der Speicherweg
 *       würde ihn sonst mit „“ überschreiben, und er wäre auch nach der Rückkehr der Geräte-Id verloren.</li>
 * </ul>
 */
public final class ZugangsMigration {

    private ZugangsMigration() {}

    /** true für einen nicht leeren Wert ohne {@code ENC:}-Kennung. */
    public static boolean klartext(String gespeichert) {
        return gespeichert != null && !gespeichert.isEmpty() && !CredentialCipher.isEncrypted(gespeichert);
    }

    /**
     * Ob die Datei mit diesen gespeicherten Rohwerten neu geschrieben werden soll.
     *
     * @param entschluessle {@link CredentialCipher#decrypt} (im Test eine Attrappe); "" heißt nicht lesbar
     * @param altSchluessel ob ein Wert nur unter dem alten Firmware-Schlüssel liest ({@link CredentialCipher#needsStableReEncrypt})
     * @param verschluesselnGeht ob {@code encrypt()} gerade {@code ENC:} liefert
     */
    public static boolean noetig(Iterable<String> rohwerte, UnaryOperator<String> entschluessle,
                                 java.util.function.Predicate<String> altSchluessel, boolean verschluesselnGeht) {
        if (!verschluesselnGeht) return false;
        boolean etwas = false;
        for (String roh : rohwerte) {
            if (roh == null || roh.isEmpty()) continue;
            if (CredentialCipher.isEncrypted(roh)) {
                String klar = entschluessle.apply(roh);
                if (klar == null || klar.isEmpty()) return false;
                if (altSchluessel.test(roh)) etwas = true;
            } else {
                etwas = true;
            }
        }
        return etwas;
    }

    /** {@link #noetig} mit dem echten {@link CredentialCipher}. */
    public static boolean noetig(Iterable<String> rohwerte) {
        return noetig(rohwerte, CredentialCipher::decrypt, CredentialCipher::needsStableReEncrypt, verschluesselnGeht());
    }

    // ------------------------------------------------------------------ Schatten (0.1.38.6, Rückfall-sicher)

    /**
     * 0.1.38.6 (Koordination 04.11.): Die Selbstheilung kann nach einem fehlgeschlagenen Update auf 0.1.38.5
     * zurückrollen; die alte Version liest nur den Klartext. Deshalb bleibt in 0.1.38.6 der Klartext im
     * bisherigen Schlüssel stehen und ist die maßgebliche Quelle. Daneben liegt eine geprüfte verschlüsselte
     * Schattenkopie {@code enc_<schluessel>}. Erst eine spätere Version (frühestens 0.1.38.7, laufende Version
     * &gt; 24 h bestätigt) löscht den Klartext – nach Vergleich mit dem Schatten.
     */
    public static final String SCHATTEN = "enc_";

    public static String schattenSchluessel(String schluessel) {
        return SCHATTEN + schluessel;
    }

    /**
     * Rein: neuer Wert des Schattens oder null (nichts zu schreiben).
     * <ul>
     *   <li>Klartext leer → Schatten leeren (sonst lebte ein in der alten Version gelöschter Zugang wieder auf).</li>
     *   <li>Klartext schon {@code ENC:} → nichts (Entwicklungsstand, Lesestellen entschlüsseln).</li>
     *   <li>Schatten passt zum Klartext → nichts. Sonst (neu, oder in der alten Version geändert) neu verschlüsseln,
     *       nur mit bestandener Rückprüfung; geht das gerade nicht → null, nächster Start versucht es wieder.</li>
     * </ul>
     */
    public static String schatten(String klar, String schattenAlt, UnaryOperator<String> ver, UnaryOperator<String> ent) {
        boolean altDa = schattenAlt != null && !schattenAlt.isEmpty();
        if (klar == null || klar.isEmpty()) return altDa ? "" : null;
        if (CredentialCipher.isEncrypted(klar)) return null;
        if (altDa) {
            String a = ent.apply(schattenAlt);
            if (klar.equals(a)) return null;
        }
        return CredentialUpgrade.geprueftVerschluesselt(klar, ver, ent);
    }

    /** {@link #schatten} mit dem echten {@link CredentialCipher}. */
    public static String schatten(String klar, String schattenAlt) {
        return schatten(klar, schattenAlt, CredentialCipher::encrypt, CredentialCipher::decrypt);
    }

    /** Felder der Gesamt-Config mit Schattenkopie (Klartext bleibt in 0.1.38.6 stehen). */
    static final String[][] CONFIG_FELDER = {{"planner", "memberToken"}, {"update", "token"},
            {"bydCloud", "loginKey"}, {"bydCloud", "signPassword"}, {"bydCloud", "commandPwd"}};

    /** Beim Daemon-Start: Schatten aller {@link #CONFIG_FELDER} abgleichen. Liefert die Zahl der Schreibvorgänge. */
    public static int bestandVerschluesseln() {
        int n = 0;
        for (String[] f : CONFIG_FELDER) {
            if (CredentialUpgrade.schattenAbgleich(f[0], f[1])) n++;
        }
        return n;
    }

    /** Probe: liefert {@code encrypt()} gerade eine verschlüsselte Form, die sich auch wieder lesen lässt? */
    public static boolean verschluesselnGeht() {
        String enc = CredentialCipher.encrypt("probe");
        return CredentialCipher.isEncrypted(enc) && "probe".equals(CredentialCipher.decrypt(enc));
    }
}
