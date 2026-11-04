package at.greenchili.surfmate.notifications.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 0.1.38.6 Sicherheit Teil 2: Dateien mit Geheimnissen, die nur der Kamera-Daemon (uid 2000) liest, sind 0600.
 * Die Gesamt-Config bleibt bewusst 0666 (App uid 10xxx und SentryDaemon uid 1000 lesen sie direkt), siehe
 * SICHERHEIT_TEIL2_BEFUND – deshalb liegen Geheimnisse dort nur verschlüsselt.
 */
public class GeheimDateienNurBesitzerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void vapidSchluesselWirdNur0600() throws Exception {
        assumeTrue("POSIX-Rechte (Linux/Auto), unter Windows übersprungen",
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        File f = tmp.newFile("vapid.json");
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"));
        VapidKeyStore.nurBesitzer(f);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())));
    }

    @Test
    public void vapidSchreibenUndBestandSetzenDieRechte() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/notifications/push/VapidKeyStore.java"));
        int schreiben = q.indexOf("fos.getFD().sync();");
        int recht = q.indexOf("nurBesitzer(tmp);");
        int umbenennen = q.indexOf("tmp.renameTo(keyFile)");
        assertTrue("0600 vor dem Umbenennen", schreiben > 0 && recht > schreiben && umbenennen > recht);
        assertTrue("Bestand beim Laden", q.contains("nurBesitzer(keyFile);"));
    }

    @Test
    public void brueckeUndWireGuardBleibenNurBesitzer() throws Exception {
        String b = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/bruecke/Bruecke.java"));
        assertTrue(b.contains("tmp.setReadable(false, false);") && b.contains("tmp.setReadable(true, true);"));
        String w = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/wireguard/WireGuardStore.java"));
        assertTrue(w.contains("0600") || w.contains("rw-------"));
    }

    @Test
    public void telegramKopplungsPinNurVerschluesselt() throws Exception {
        String t = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/telegram/config/UnifiedTelegramConfig.java"));
        assertTrue(t.contains("return CredentialCipher.decrypt(load().optString(K_PAIR_PIN, \"\"));"));
        assertTrue(t.contains("if (!enc.isEmpty() && !CredentialCipher.isEncrypted(enc)) return false;"));
        assertTrue(t.contains("delta.put(K_PAIR_PIN, enc);"));
        assertTrue(t.contains("delta.put(K_PAIR_PIN, pinEnc);"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/TelegramApiHandler.java"));
        assertTrue("keine PIN zeigen, die nicht gespeichert ist", h.contains("if (!UnifiedTelegramConfig.setPairPin(pin, expiry)) {"));
    }
}
