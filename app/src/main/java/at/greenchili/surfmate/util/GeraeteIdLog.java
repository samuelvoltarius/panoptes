package at.greenchili.surfmate.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 0.1.43.22 (Upstream Overdrive #516, MIT): Die Geräte-ID ist der einzige Keim für den Schlüssel
 * aller {@code ENC:}-Zugangsdaten (CredentialCipher). Sie darf deshalb nie im Klartext ins Log
 * (Logcat und Log-Datei gehen per „Fehler melden“/Bugreport hinaus).
 *
 * <p>Statt der ID loggen wir nur einen kurzen Hash-Präfix: {@code id#} + die ersten 8 Hex-Zeichen
 * von SHA-256(ID). Das reicht, um in Logs zu sehen, ob App und Daemon dieselbe ID benutzen, verrät
 * aber nichts über den Keim (32 Bit eines Hashes über 128 zufällige Bit).
 *
 * <p>Upstream loggt die ersten und letzten 4 Zeichen der ID; wir nehmen bewusst den Hash, damit
 * kein einziges Zeichen der ID im Log steht.
 */
public final class GeraeteIdLog {

    private GeraeteIdLog() {}

    /** Kurzer, nicht umkehrbarer Hinweis auf die ID, für Logzeilen. Nie die ID selbst. */
    public static String kurz(String id) {
        if (id == null || id.isEmpty()) return "id#leer";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(id.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("id#");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(java.util.Locale.US, "%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "id#?";
        }
    }
}
