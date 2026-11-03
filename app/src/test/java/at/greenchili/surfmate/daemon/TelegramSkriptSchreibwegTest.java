package at.greenchili.surfmate.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.launcher.DaemonLauncher;
import at.greenchili.surfmate.logging.LogArchivShellTest;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 0.1.36: AccSentryDaemon schreibt start_telegram.sh zeilenweise. Der alte echo-Weg
 * ergab unter mksh ein anderes Skript ('\000' im tr der Besitzer-Prüfung).
 * Jetzt printf '%s\n' wie DaemonLauncher.buildWatchdogScriptWriteCommand.
 */
public class TelegramSkriptSchreibwegTest {

    private static final String QUELLE =
            "app/src/main/java/at/greenchili/surfmate/daemon/AccSentryDaemon.java";

    @Test
    public void befehlNutztPrintfNieEcho() {
        String b = DaemonLauncher.buildScriptLineWriteCommand("tr '\\000' ' ' < /proc/$p/cmdline", true, "/x/s.sh");
        assertTrue(b, b.startsWith("printf '%s\\n' \""));
        assertTrue(b, b.endsWith("\" > /x/s.sh"));
        assertFalse(b, b.contains("echo"));
        assertTrue(DaemonLauncher.buildScriptLineWriteCommand("a", false, "/x/s.sh").endsWith("\" >> /x/s.sh"));
    }

    @Test
    public void quelleSchreibtTelegramSkriptNichtMehrPerEcho() throws Exception {
        Path p = Paths.get(QUELLE);
        if (!Files.exists(p)) p = Paths.get("..").resolve(QUELLE);
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertFalse("echo-Schreibweg fuer start_telegram.sh ist zurueck", src.contains("\"echo \\\"\" + escaped"));
        assertTrue(src.contains(".buildScriptLineWriteCommand(line, first, scriptPath)"));
    }

    @Test
    public void geschriebenesSkriptIstByteGleichMitJavaText() throws Exception {
        org.junit.Assume.assumeTrue(LogArchivShellTest.shell() != null);
        List<String> zeilen = DaemonLauncher.Companion.buildTelegramWatchdogScript("/a/base.apk", "");
        boolean hatNul = false;
        for (String z : zeilen) hatNul |= z.contains("\\000");
        assertTrue("Skript enthaelt die kritische Folge \\000 nicht mehr – Test pruefen", hatNul);
        File dir = Files.createTempDirectory("telegram-schreiben").toFile();
        try {
            String ziel = LogArchivShellTest.pfad(new File(dir, "start_telegram.sh"));
            StringBuilder sk = new StringBuilder();
            boolean first = true;
            for (String z : zeilen) {
                sk.append(DaemonLauncher.buildScriptLineWriteCommand(z, first, ziel)).append('\n');
                first = false;
            }
            LogArchivShellTest.fuehreAus(sk.toString());
            byte[] geschrieben = Files.readAllBytes(new File(dir, "start_telegram.sh").toPath());
            assertEquals(String.join("\n", zeilen) + "\n", new String(geschrieben, StandardCharsets.UTF_8));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }
}
