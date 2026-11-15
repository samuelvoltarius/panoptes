package at.greenchili.surfmate.appupdate;

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

/**
 * 0.1.43.8 (Paket G): update-on-car.sh, Teil stop_daemons und Statuszeile.
 * <ul>
 * <li>F1: Das alte Muster {@code ^CLASSPATH=.*&#47;data/app/$PKG-} traf unter Android 11+ nicht
 * ({@code /data/app/~~…==/at.greenchili.surfmate-…==/base.apk}). {@code apk_prozesse} trifft beide Formen, auch mit
 * {@code bmmcamera.jar:} davor, aber weder Prozesse ohne CLASSPATH (Notzugang, tailscaled) noch den Launcher. Nicht
 * lesbare environ-Dateien geben keine Meldung mehr aus.</li>
 * <li>2: {@code tailscaled=} zählt nur echte tailscaled-Prozesse mit genau dem Panoptes-statedir; Hüll- und
 * Prüf-Shells mit „--statedir …“ in der Befehlszeile zählen nicht mehr, der Notzugang steht getrennt als
 * {@code notzugang=}.</li>
 * </ul>
 * Läuft die ECHTEN Funktionen aus dem Skript gegen ein Attrappen-/proc.
 */
public class UpdateSkriptProzesseTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String NL = "\n";
    private static final String PKG = "at.greenchili.surfmate";
    private static final String NEW = "/data/user_de/0/com.android.shell/surfmate";

    @Test
    public void apkProzesseTreffenAndroid10UndAndroid11() throws Exception {
        String skript = skript();
        File proc = tmp.newFolder("proc");
        umgebung(proc, "201", "CLASSPATH=/data/app/" + PKG + "-AbC12==/base.apk");                      // Android 10
        umgebung(proc, "202", "PATH=/system/bin", "CLASSPATH=/data/app/~~Xy9_-Q==/" + PKG + "-Zz8==/base.apk"); // Android 11+
        umgebung(proc, "203", "CLASSPATH=/system/framework/bmmcamera.jar:/data/app/~~Xy9_-Q==/" + PKG + "-Zz8==/base.apk");
        umgebung(proc, "204", "CLASSPATH=/system/framework/bmmcamera.jar:/data/app/" + PKG + "-AbC12==/base.apk");
        umgebung(proc, "205", "PATH=/system/bin", "HOME=/");                                            // Notzugang ab 0.1.43.7
        umgebung(proc, "206", "PATH=/system/bin");                                                      // tailscaled ohne CLASSPATH
        umgebung(proc, "207", "CLASSPATH=/data/app/~~Q1==/" + PKG + ".launcher-W2==/base.apk");          // Launcher
        umgebung(proc, "208", "CLASSPATH=/system/framework/am.jar");                                    // fremdes app_process
        umgebung(proc, "209", "X=CLASSPATH=/data/app/" + PKG + "-AbC12==/base.apk");                    // nur im Wert
        // nicht lesbar: environ ist ein Ordner bzw. fehlt (auf dem Auto: Permission denied)
        assertTrue(new File(proc, "210/environ").mkdirs());
        assertTrue(new File(proc, "211").mkdirs());
        assertTrue(new File(proc, "self").mkdirs());

        String fn = block(skript, "APK_CP_MUSTER=", "apk_prozesse() {");
        String pr = LogArchivShellTest.pfad(proc);
        String lauf = fn.replace("/proc", pr);
        assertEquals("zwei /proc-Stellen im Skript", 2, anzahl(fn, "/proc"));
        assertEquals("beide ersetzt", 2, anzahl(lauf, pr));
        String out = LogArchivShellTest.fuehreAus("PKG=" + PKG + NL + lauf
                + "for p in $(apk_prozesse); do echo \"KILL $p\"; done" + NL + "true" + NL);
        for (String p : new String[] {"201", "202", "203", "204"}) assertTrue(p + ": " + out, out.contains("KILL " + p));
        for (String p : new String[] {"205", "206", "207", "208", "209", "210", "211"}) {
            assertFalse(p + " bleibt stehen: " + out, out.contains("KILL " + p));
        }
        assertEquals("keine Fehlermeldung aus der Schleife: " + out, 4, out.trim().split("\n").length);
        assertFalse(out, out.contains("environ"));
    }

    @Test
    public void stopDaemonsNutztDieAuswahlUndBleibtStumm() throws Exception {
        String skript = skript();
        String stop = block(skript, "stop_daemons() {", "stop_daemons() {");
        assertTrue(stop, stop.contains("for p in $(apk_prozesse); do"));
        assertTrue(stop, stop.contains("kill -9 \"$p\" 2>/dev/null"));
        assertFalse("altes Muster (nur Android 10) ist weg", skript.contains("grep -q \"^CLASSPATH=.*/data/app/$PKG-\""));
        assertTrue("Fehler beim Öffnen von environ landen in der Klammer",
                skript.contains("{ tr '\\0' '\\n' < /proc/$p/environ | grep -qE \"$APK_CP_MUSTER\"; } 2>/dev/null"));
        assertFalse("keine Pfad-Ausnahme für den Notzugang", skript.contains("panoptes-notzugang/bin"));
    }

    @Test
    public void tailscaledZaehltNurEchteProzesseJeStatedir() throws Exception {
        String skript = skript();
        File proc = tmp.newFolder("proc");
        // 301: Panoptes-tailscaled; 302: Notzugang-tailscaled; 303: sh -c-Hülle des Starts (kein tailscaled);
        // 304: Prüf-Shell mit „--statedir …“ im Skripttext; 305: tailscaled mit ähnlichem, aber anderem statedir
        befehl(proc, "301", "/data/local/tmp/tailscaled", "--tun", "userspace-networking", "--statedir", NEW + "/.tailscale",
                "--socket", "127.0.0.1:41641");
        befehl(proc, "302", "/data/local/tmp/panoptes-notzugang/bin/tailscaled", "--tun", "userspace-networking",
                "--statedir", "/data/local/tmp/panoptes-notzugang/state", "--socket", "127.0.0.1:8633", "--port", "0");
        befehl(proc, "303", "sh", "-c", "/data/local/tmp/tailscaled --tun userspace-networking --statedir " + NEW + "/.tailscale");
        befehl(proc, "304", "sh", "-c", "for p in $(pidof tailscaled); do grep -qF -- \"--statedir " + NEW + "/.tailscale\"; done");
        befehl(proc, "305", "/data/local/tmp/tailscaled", "--statedir", NEW + "/.tailscale-alt");
        String fn = block(skript, "NZ_STATE=", "ts_zahl() {");
        String pr = LogArchivShellTest.pfad(proc);
        // Attrappe für pidof: wie auf dem Auto nur Prozesse mit dem Namen tailscaled (nicht die Hüll-Shells)
        String pidof = "pidof() { [ \"$1\" = tailscaled ] && echo 301 302 305 399; }" + NL;
        String out = LogArchivShellTest.fuehreAus("NEW=" + NEW + NL + pidof + fn.replace("/proc", pr)
                + "echo \"tailscaled=$(ts_zahl \"$NEW/.tailscale\") notzugang=$(ts_zahl \"$NZ_STATE\")\"" + NL);
        assertEquals("tailscaled=1 notzugang=1", out.trim());

        // Die alte Zählung (grep -c Teilstring auf die Befehlszeilen) hätte hier 4 gemeldet: 301, 303, 304, 305.
        // Der Notzugang (302) passt nie darauf: anderes statedir.
        int alt = 0;
        for (String p : new String[] {"301", "302", "303", "304", "305"}) {
            String z = new String(Files.readAllBytes(new File(proc, p + "/cmdline").toPath()), StandardCharsets.UTF_8)
                    .replace((char) 0, ' ');
            if (z.contains("statedir " + NEW + "/.tailscale")) alt++;
            if (p.equals("302")) assertFalse("Notzugang passt nie aufs alte Muster", z.contains("statedir " + NEW));
        }
        assertEquals(4, alt);

        assertTrue(skript.contains("log \"Stand nach $((i / 2)) min: tailscaled=$TS notzugang=$NZ web=$WEB daemons=$DM/3 skripte=$SK\""));
        assertTrue(skript.contains("TS=$(ts_zahl \"$NEW/.tailscale\")"));
        assertFalse("keine Zählung über ps-Befehlszeilen mehr", skript.contains("grep -c \"[s]tatedir"));
    }

    private static String block(String skript, String start, String funktion) {
        int a = skript.indexOf(start);
        int f = skript.indexOf(funktion, a);
        assertTrue("fehlt: " + start, a >= 0 && f >= a);
        int b = skript.indexOf(NL + "}" + NL, f);
        return skript.substring(a, b + 3);
    }

    private static String skript() throws Exception {
        String a = lies("app/src/main/assets/deploy/update-on-car.sh");
        assertEquals("beide Kopien gleich", a, lies("deploy/update-on-car.sh"));
        return a;
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(wurzel().resolve(rel)), StandardCharsets.UTF_8).replace("\r" + NL, NL);
    }

    private static int anzahl(String text, String teil) {
        int n = 0;
        for (int i = text.indexOf(teil); i >= 0; i = text.indexOf(teil, i + teil.length())) n++;
        return n;
    }

    private static void umgebung(File proc, String pid, String... eintraege) throws Exception {
        schreibe(new File(proc, pid), "environ", eintraege);
    }

    private static void befehl(File proc, String pid, String... argv) throws Exception {
        schreibe(new File(proc, pid), "cmdline", argv);
    }

    private static void schreibe(File d, String name, String... teile) throws Exception {
        assertTrue(d.isDirectory() || d.mkdirs());
        StringBuilder sb = new StringBuilder();
        for (String e : teile) sb.append(e).append((char) 0);
        Files.write(new File(d, name).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path wurzel() {
        Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("app/src/main/assets/deploy"))) p = RepoWurzel.hoch(p);
        if (p == null) throw new IllegalStateException("Repo nicht gefunden");
        return p;
    }
}
