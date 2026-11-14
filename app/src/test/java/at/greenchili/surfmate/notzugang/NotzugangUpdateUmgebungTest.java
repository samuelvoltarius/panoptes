package at.greenchili.surfmate.notzugang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchivShellTest;
import at.greenchili.surfmate.testutil.RepoWurzel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.43.7 (Paket F 4): Der Notzugang-tailscaled startete bei jedem Update neu. Ursache: Aufpasser und tailscaled
 * erbten vom Kamera-Daemon ({@code CLASSPATH=<apk> app_process}) unser APK im CLASSPATH, und {@code update-on-car.sh}
 * (stop_daemons) beendet vor {@code pm install} jeden Prozess mit diesem CLASSPATH. Jetzt startet
 * {@link Notzugang#shell} ohne CLASSPATH; update-on-car.sh bleibt unverändert (keine Pfad-Ausnahme).
 */
public class NotzugangUpdateUmgebungTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String APK_CP = "/data/app/at.greenchili.surfmate-AbC12==/base.apk";

    @Test
    public void notzugangStartetOhneClasspath() throws Exception {
        assertFalse(Notzugang.prozess("true").environment().containsKey("CLASSPATH"));
        // wie im Kamera-Daemon: die geerbte Umgebung trägt das APK
        Map<String, String> umgebung = new HashMap<>();
        umgebung.put("CLASSPATH", APK_CP);
        umgebung.put("PATH", "/system/bin");
        Notzugang.ohneApkUmgebung(umgebung);
        assertFalse(umgebung.containsKey("CLASSPATH"));
        assertEquals("sonst nichts entfernt", "/system/bin", umgebung.get("PATH"));
        // echte Shell: mit geerbtem CLASSPATH angelegt, nach ohneApkUmgebung kommt keiner mehr an
        String sh = LogArchivShellTest.shell();
        org.junit.Assume.assumeTrue("keine POSIX-Shell", sh != null);
        ProcessBuilder pb = new ProcessBuilder(sh, "-c", "echo cp=${CLASSPATH:-ohne}").redirectErrorStream(true);
        pb.environment().put("CLASSPATH", APK_CP);
        Notzugang.ohneApkUmgebung(pb.environment());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, p.waitFor());
        assertEquals("cp=ohne", out);
        // jeder Shell-Aufruf des Notzugangs (start, neu, serve, status, Einrichtung) geht über prozess()
        String q = new String(Files.readAllBytes(wurzel().resolve(
                "app/src/main/java/at/greenchili/surfmate/notzugang/Notzugang.java")), StandardCharsets.UTF_8);
        assertEquals("ein ProcessBuilder, der in prozess()", 1, anzahl(q, "new ProcessBuilder("));
        assertTrue(q.contains("Process p = prozess(cmd).start();"));
        assertFalse(q.contains("Runtime.getRuntime()"));
    }

    /** Die ECHTE stop_daemons-Schleife aus update-on-car.sh, gegen ein Attrappen-/proc und mit echo statt kill. */
    @Test
    public void stopDaemonsLaesstProzessOhneClasspathStehen() throws Exception {
        String nl = "\n";
        String skript = new String(Files.readAllBytes(wurzel().resolve("app/src/main/assets/deploy/update-on-car.sh")),
                StandardCharsets.UTF_8).replace("\r" + nl, nl);
        int a = skript.indexOf("    for p in $(ls /proc | grep -E '^[0-9]+$'); do" + nl);
        assertTrue("Schleife in stop_daemons gefunden", a > 0 && skript.lastIndexOf("stop_daemons() {", a) > 0);
        String ende = "    done" + nl;
        int b = skript.indexOf(ende, a);
        String schleife = skript.substring(a, b + ende.length());
        assertTrue(schleife, schleife.contains("grep -q \"^CLASSPATH=.*/data/app/$PKG-\" && kill -9 \"$p\""));

        File proc = tmp.newFolder("proc");
        umgebung(proc, "101", "CLASSPATH=" + APK_CP, "ANDROID_DATA=/data");   // Kamera-Daemon
        umgebung(proc, "102", "PATH=/system/bin", "HOME=/");                   // Notzugang ab 0.1.43.7 (ohne CLASSPATH)
        umgebung(proc, "103", "PATH=/system/bin", "CLASSPATH=" + APK_CP);      // Notzugang bis 0.1.43.6 (geerbt)
        umgebung(proc, "104", "CLASSPATH=/system/framework/am.jar");           // fremdes app_process
        assertTrue(new File(proc, "self").mkdirs());

        String pr = LogArchivShellTest.pfad(proc);
        String lauf = schleife.replace("/proc", pr).replace("kill -9 \"$p\"", "echo \"KILL $p\"");
        assertEquals("genau zwei /proc-Stellen ersetzt", 2, anzahl(lauf, pr));
        assertEquals("kill ersetzt", 0, anzahl(lauf, "kill -9"));
        String out = LogArchivShellTest.fuehreAus("PKG=at.greenchili.surfmate" + nl + lauf + "true" + nl);   // letzter grep ohne Treffer: Status 1
        assertTrue(out, out.contains("KILL 101"));
        assertTrue("alter Notzugang mit geerbtem CLASSPATH wurde beendet: " + out, out.contains("KILL 103"));
        assertFalse("Notzugang ohne CLASSPATH bleibt stehen: " + out, out.contains("KILL 102"));
        assertFalse(out, out.contains("KILL 104"));
    }

    private static int anzahl(String text, String teil) {
        int n = 0;
        for (int i = text.indexOf(teil); i >= 0; i = text.indexOf(teil, i + teil.length())) n++;
        return n;
    }

    private static void umgebung(File proc, String pid, String... eintraege) throws Exception {
        File d = new File(proc, pid);
        assertTrue(d.mkdirs());
        StringBuilder sb = new StringBuilder();
        for (String e : eintraege) sb.append(e).append((char) 0);
        Files.write(new File(d, "environ").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path wurzel() {
        Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("app/src/main/assets/deploy"))) p = RepoWurzel.hoch(p);
        if (p == null) throw new IllegalStateException("Repo nicht gefunden");
        return p;
    }
}
