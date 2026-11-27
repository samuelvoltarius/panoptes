package at.greenchili.surfmate.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * 0.1.43.22 (Upstream Overdrive #516): Die Geräte-ID (Schlüssel-Keim der ENC:-Zugangsdaten)
 * steht nirgends im Klartext im Log, höchstens ein kurzer Hash-Präfix.
 */
public class GeraeteIdLogTest {

    private static final String ID = "byd-0123456789abcdef0123456789abcdef";

    @Test
    public void kurzEnthaeltKeinZeichenDerId() {
        String k = GeraeteIdLog.kurz(ID);
        assertTrue(k, k.matches("id#[0-9a-f]{8}"));
        assertFalse(k.contains("byd-"));
        // Keine vier aufeinanderfolgenden Zeichen der ID-Nutzlast im Ergebnis
        String nutz = ID.substring(4);
        for (int i = 0; i + 4 <= nutz.length(); i++) {
            assertFalse(k.substring(3).contains(nutz.substring(i, i + 4)));
        }
    }

    @Test
    public void kurzIstStabilUndUnterscheidet() {
        assertEquals(GeraeteIdLog.kurz(ID), GeraeteIdLog.kurz(ID));
        assertEquals("Zeilenende egal (Datei mit \\n)", GeraeteIdLog.kurz(ID), GeraeteIdLog.kurz(ID + "\n"));
        assertNotEquals(GeraeteIdLog.kurz(ID), GeraeteIdLog.kurz("byd-ffffffffffffffffffffffffffffffff"));
        assertEquals("id#leer", GeraeteIdLog.kurz(null));
        assertEquals("id#leer", GeraeteIdLog.kurz(""));
    }

    /** Vertrag über den Quelltext: keine Logzeile hängt eine Geräte-ID-Variable roh an. */
    @Test
    public void keineLogzeileMitRoherGeraeteId() throws Exception {
        File wurzel = new File("src/main/java/at/greenchili/surfmate");
        assertTrue("Quelltext nicht gefunden: " + wurzel.getAbsolutePath(), wurzel.isDirectory());
        // Log-Aufruf, in dessen Klammern eine ID-Variable direkt (Java „+ x“ oder Kotlin „$x“) steht
        Pattern roh = Pattern.compile(
                "(?i)(log\\(|Log\\.[diwev]\\(|logger\\.(debug|info|warn|error)\\()[^;\\n]*"
                        + "(\\+\\s*(state\\.)?(deviceId|tempId|fileId)\\b(?!\\s*\\()"
                        + "|\\$\\{?(deviceId|tempId)\\b"
                        + "|Device ID[^\\n]*\\$\\{?id\\b)");
        StringBuilder treffer = new StringBuilder();
        pruefe(wurzel, roh, treffer);
        assertEquals("Geräte-ID roh im Log:\n" + treffer, "", treffer.toString());
    }

    @Test
    public void adbBefehlMitIdLaeuftSensitive() throws Exception {
        String d = lies("src/main/java/at/greenchili/surfmate/util/DeviceIdGenerator.kt");
        assertFalse("echo '$id' darf nicht über execute()/executeSync() geloggt werden",
                d.contains("executor.execute(\n            command = \"echo '$id'"));
        assertFalse(d.contains("executor.executeSync(\"echo '$id'"));
        assertTrue(d.contains("executor.executeSensitive("));
        assertTrue(d.contains("executor.executeSyncSensitive(\"echo '$id' > $ID_FILE\""));
        String a = lies("src/main/java/at/greenchili/surfmate/launcher/AdbShellExecutor.kt");
        assertTrue(a.contains("fun executeSyncSensitive(command: String, description: String"));
        assertTrue(a.contains("executeSyncInternal(command, \"<sensitive:$description>\", deadlineMs)"));
        assertTrue(a.contains("logger.debug(TAG, \"Executing sync: $commandForLog\")"));
    }

    private static void pruefe(File f, Pattern p, StringBuilder treffer) throws Exception {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File c : k) pruefe(c, p, treffer);
            return;
        }
        String n = f.getName();
        if (!n.endsWith(".java") && !n.endsWith(".kt")) return;
        String[] zeilen = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < zeilen.length; i++) {
            Matcher m = p.matcher(zeilen[i]);
            if (m.find()) treffer.append(f.getPath()).append(':').append(i + 1).append(' ').append(zeilen[i].trim()).append('\n');
        }
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
