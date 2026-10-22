package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.14 (Kern 7): Panoptes zählt und beendet nur seinen eigenen tailscaled; Notzugang bzw. fremder bleibt. */
public class TailscaledProzesseTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String UNSER = "/data/user_de/0/com.android.shell/surfmate/.tailscale";
    private static final String NOT = "/data/local/tmp/panoptes-notzugang/state";

    private static boolean shDa() {
        try {
            return new ProcessBuilder("sh", "-c", "echo ja").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fake-/proc mit drei tailscaled: 101 (unser), 202 (Notzugang), 303 (fremd ohne statedir). */
    private String lauf(String skript) throws Exception {
        File proc = tmp.newFolder();
        schreib(proc, 101, "/x/.tailscale/tailscaled\0--tun\0userspace-networking\0--statedir\0" + UNSER + "\0--socket\0127.0.0.1:8632\0");
        schreib(proc, 202, "/data/local/tmp/panoptes-notzugang/bin/tailscaled\0--statedir\0" + NOT + "\0--socket\0127.0.0.1:8633\0");
        schreib(proc, 303, "/system/bin/tailscaled\0--socket\0/tmp/x\0");
        File log = new File(proc, "kill.log");
        String attrappe = "pidof() { echo 101 202 303; }; kill() { echo \"$@\" >> '"
                + log.getAbsolutePath().replace('\\', '/') + "'; }; PROC_ROOT='" + proc.getAbsolutePath().replace('\\', '/') + "'; ";
        // Skript aus Datei: auf Windows verstümmelt ProcessBuilder Anführungszeichen in Argumenten
        File sk = new File(proc, "lauf.sh");
        Files.write(sk.toPath(), (attrappe + skript + "\n").getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("sh", sk.getAbsolutePath().replace('\\', '/')).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        String kills = log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
        return out.trim() + "|" + kills.trim();
    }

    private static void schreib(File proc, int pid, String cmdline) throws Exception {
        File d = new File(proc, String.valueOf(pid));
        d.mkdirs();
        Files.write(new File(d, "cmdline").toPath(), cmdline.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void zaehltNurDenEigenen() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        assertEquals("101|", lauf(TailscaledProzesse.pidsSkript(UNSER)));
        assertEquals("202|", lauf(TailscaledProzesse.pidsSkript(NOT)));
    }

    @Test
    public void beendetNurDenEigenen() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        assertEquals("|-9 101", lauf(TailscaledProzesse.beendenSkript(UNSER, "-9")));
        assertEquals("|101", lauf(TailscaledProzesse.beendenSkript(UNSER, "")));
    }

    @Test
    public void unsichereEingabenAbgelehnt() {
        for (String s : new String[] {"", "/a$(x)", "/a'b", "/a\"b", "/a`b`"}) {
            try {
                TailscaledProzesse.pidsSkript(s);
                fail(s);
            } catch (IllegalArgumentException ok) {
            }
        }
        try {
            TailscaledProzesse.beendenSkript(UNSER, "-9; rm");
            fail();
        } catch (IllegalArgumentException ok) {
        }
    }

    @Test
    public void keinNamensKillMehrImQuelltext() throws Exception {
        for (String rel : new String[] {
                "at/greenchili/surfmate/updater/UpdateLifecycle.java",
                "at/greenchili/surfmate/launcher/TailscaleLauncher.kt",
                "at/greenchili/surfmate/ui/daemon/TailscaleController.kt",
                "at/greenchili/surfmate/daemon/telegram/SystemCommandHandler.java"}) {
            File f = new File("src/main/java/" + rel);
            if (!f.isFile()) f = new File("app/src/main/java/" + rel);
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(rel, s.contains("killall -9 tailscaled"));
            assertFalse(rel, s.contains("pkill 'tailscaled'"));
            assertFalse(rel, s.contains("pidof tailscaled 2>/dev/null)"));
            assertFalse(rel, s.contains("pgrep -f tailscaled"));
            assertFalse(rel, s.contains("psAwkKillLine(\"tailscaled\")"));
            assertFalse(rel, s.contains("grep -F tailscaled"));
        }
    }
}
