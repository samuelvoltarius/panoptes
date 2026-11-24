package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Test;

/**
 * 0.1.43.21 Teil A (PRUEF_ADB_12V_20261124): Tailscale-CLI mit Zeitlimit, Befehlsname beim ADB_ABBRUCH,
 * nach einem Neuaufbau höchstens ein wiederholter Startdurchlauf.
 */
public class Daemon014321Test {

    private static final String TS = "/data/user_de/0/com.android.shell/surfmate/.tailscale/tailscale --socket 127.0.0.1:8632 ";

    @After
    public void zurueck() {
        NeuaufbauDurchlauf.zuruecksetzen();
    }

    @Test
    public void tailscaleAufrufBekommtZeitlimit() {
        String c = TailscaleLauncher.mitZeitlimit(TS + "status --json");
        assertTrue(c, c.startsWith("if command -v timeout >/dev/null 2>&1; then T='timeout 15'; else T=''; fi; $T "));
        assertTrue(c.endsWith(TS + "status --json"));
        assertEquals(15, TailscaleLauncher.CLI_ZEITLIMIT_S);
        // Rückfall-Frist im Executor liegt über dem Gerätelimit und weit unter den bisherigen 60 s
        assertTrue(TailscaleLauncher.CLI_FRIST_MS > TailscaleLauncher.CLI_ZEITLIMIT_S * 1000L);
        assertTrue(TailscaleLauncher.CLI_FRIST_MS < AdbShellExecutor.DEFAULT_DEADLINE_MS);
        // Nachgestellte Shell-Teile bleiben unberührt
        assertTrue(TailscaleLauncher.mitZeitlimit(TS + "status || echo done").endsWith("status || echo done"));
    }

    @Test
    public void befehlsNameOhneArgumente() {
        assertEquals("tailscale status", AdbShellExecutor.befehlsName(TailscaleLauncher.mitZeitlimit(TS + "status --json")));
        assertEquals("tailscale serve status",
                AdbShellExecutor.befehlsName(TailscaleLauncher.mitZeitlimit(TS + "serve status --json")));
        assertEquals("tailscale serve", AdbShellExecutor.befehlsName(
                TailscaleLauncher.mitZeitlimit(TS + "serve --bg --tcp 5555 tcp://127.0.0.1:5555")));
        assertEquals("tailscale login", AdbShellExecutor.befehlsName(
                TailscaleLauncher.mitZeitlimit(TS + "login --hostname panoptes --timeout 1ms || echo done")));
        assertEquals("tailscale status", AdbShellExecutor.befehlsName(TS + "status --json"));
        assertEquals("pgrep", AdbShellExecutor.befehlsName("pgrep -f 'byd_cam_daemon'"));
        assertEquals("geheim", AdbShellExecutor.befehlsName("<sensitive:tailscale up mit Auth-Key>"));
        assertEquals("?", AdbShellExecutor.befehlsName(null));
        assertEquals("?", AdbShellExecutor.befehlsName("   "));
        // Keine Adressen, Pfade oder Zahlen im Namen
        String n = AdbShellExecutor.befehlsName(TS + "serve --tcp 5555 off");
        assertFalse(n.contains("127.0.0.1"));
        assertFalse(n.contains("/"));
        assertFalse(n.contains("5555"));
    }

    @Test
    public void nachNeuaufbauHoechstensEinWiederholterDurchlauf() {
        long t = 1_000_000L;
        assertTrue(NeuaufbauDurchlauf.zulassen(1, t));                 // erste Verbindung
        assertTrue(NeuaufbauDurchlauf.zulassen(1, t + 1));             // auch wiederholt gen 1 (Auth erneut)
        assertTrue(NeuaufbauDurchlauf.zulassen(2, t + 70_000L));       // Neuaufbau: einmal
        assertFalse(NeuaufbauDurchlauf.zulassen(3, t + 140_000L));     // zweiter Neuaufbau kurz danach: nicht
        assertFalse(NeuaufbauDurchlauf.zulassen(4, t + 200_000L));
        assertTrue(NeuaufbauDurchlauf.zulassen(5, t + 70_000L + NeuaufbauDurchlauf.FENSTER_MS + 1));
    }

    @Test
    public void quelltextVerdrahtet() throws IOException {
        String ts = read("app/src/main/java/at/greenchili/surfmate/launcher/TailscaleLauncher.kt");
        assertTrue(ts.contains("command = mitZeitlimit(\"$TAILSCALE_PATH --socket 127.0.0.1:$TAILSCALE_COMMUNICATION_PORT $cmd\"),"));
        assertTrue(ts.contains("deadlineMs = CLI_FRIST_MS,"));
        String ex = read("app/src/main/java/at/greenchili/surfmate/launcher/AdbShellExecutor.kt");
        assertTrue(ex.contains("\"cmd=${befehlsName(nameQuelle)}\")"));
        assertTrue(ex.contains("shellGuarded(conn, command, deadlineMs, seq, commandForLog)"));
        String ma = read("app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        assertTrue(ma.contains(".zulassen(gen, System.currentTimeMillis())"));
        assertTrue(ma.contains("LauncherEreignisse.merken(\"DURCHLAUF_UEBERSPRUNGEN\", \"gen=$gen\")"));
    }

    public static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) return at.greenchili.surfmate.testutil.Quelltext.lies(direct);
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
