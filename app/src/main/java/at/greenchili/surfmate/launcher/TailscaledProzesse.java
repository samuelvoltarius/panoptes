package at.greenchili.surfmate.launcher;

/**
 * 0.1.14 (Kern 7, NOTZUGANG_KONZEPT §3): Panoptes trifft nur noch SEINEN tailscaled – erkannt am eigenen
 * {@code --statedir} in {@code /proc/<pid>/cmdline}. Kein {@code killall}/{@code pkill} nach Name mehr; ein zweiter
 * tailscaled (Notzugang oder ein eigener des Nutzers, Regel 1) bleibt unberührt und zählt nicht mit.
 *
 * <p>Alles als Shell-Text (läuft als Shell über adb bzw. im Update-Skript). {@code PROC_ROOT} ist nur für Tests.
 */
public final class TailscaledProzesse {

    private TailscaledProzesse() {}

    /** statedir des Panoptes-tailscaled (Weg 1), wie TailscaleLauncher ihn startet. */
    public static final String PANOPTES_STATEDIR = at.greenchili.surfmate.util.DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/.tailscale");

    static void pruefen(String statedir) {
        if (statedir == null || statedir.isEmpty() || statedir.contains("\"") || statedir.contains("'")
                || statedir.contains("`") || statedir.contains("$")) {
            throw new IllegalArgumentException("statedir");
        }
    }

    /** Shell-Ausdruck, der die PIDs der tailscaled mit diesem statedir ausgibt (Leerzeichen-getrennt). */
    public static String pidsSkript(String statedir) {
        pruefen(statedir);
        return "for p in $(pidof tailscaled 2>/dev/null); do case \"$p\" in ''|*[!0-9]*) continue;; esac; "
                + "tr '\\000' ' ' < \"${PROC_ROOT:-/proc}/$p/cmdline\" 2>/dev/null | grep -qF -- \"--statedir "
                + statedir + "\" && printf '%s ' \"$p\"; done";
    }

    /** Shell: nur die tailscaled mit diesem statedir beenden ({@code signal} z. B. "-9" oder ""). */
    public static String beendenSkript(String statedir, String signal) {
        String sig = signal == null ? "" : signal.trim();
        if (!sig.isEmpty() && !sig.matches("-[0-9A-Z]{1,6}")) throw new IllegalArgumentException("signal");
        return "for p in $(" + pidsSkript(statedir) + "); do kill " + (sig.isEmpty() ? "" : sig + " ")
                + "\"$p\" 2>/dev/null; done";
    }
}
