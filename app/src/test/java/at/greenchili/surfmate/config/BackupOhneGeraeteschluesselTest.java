package at.greenchili.surfmate.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.38.8 Paket A: Die Sicherung trägt den Geräteschlüssel (.byd_device_id, Schlüssel von CredentialCipher) nur noch
 * auf ausdrücklichen Wunsch (Web {@code ?geraeteschluessel=true}). Telegram /backup und der App-Export nie.
 */
public class BackupOhneGeraeteschluesselTest {

    private static String lies(String pfad) throws Exception {
        java.io.File f = new java.io.File(pfad);
        if (!f.isFile()) f = new java.io.File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static final String S = "src/main/java/at/greenchili/surfmate/";

    @Test
    public void standardOhneDid() throws Exception {
        String k = lies(S + "config/ConfigBackupService.kt");
        assertTrue(k.contains("includeDid: Boolean = false"));
        assertTrue(k.contains("if (includeDid) didValue(snapshot.did)?.let { bundle.put(\"did\", it) }"));
        assertFalse(k.replace("if (includeDid) didValue", "").contains("didValue(snapshot.did)?.let"));
    }

    @Test
    public void nurDasWebMitHakenNimmtIhnMit() throws Exception {
        String web = lies(S + "server/ConfigBackupApiHandler.java");
        assertTrue(web.contains("hasParamTrue(path, \"geraeteschluessel\")"));
        // IPC-Export (App-Oberfläche, Telegram /backup) ruft ohne includeDid
        String ipc = lies(S + "server/SurveillanceIpcServer.java");
        int a = ipc.indexOf("case \"EXPORT_CONFIG\"");
        String block = ipc.substring(a, ipc.indexOf("break;", a));
        assertFalse(block.contains("includeDid"));
        assertFalse(block.contains("geraeteschluessel"));
    }

    @Test
    public void importNimmtAlteSicherungenMitDidWeiter() throws Exception {
        String k = lies(S + "config/ConfigBackupService.kt");
        assertTrue(k.contains("val didWrite = if (bundle.has(\"did\")"));
        assertTrue(k.contains("DidWriteResult(usable = true, changed = false)"));
    }
}
