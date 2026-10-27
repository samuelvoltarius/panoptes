package at.greenchili.surfmate.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.20 (Befund Updater 0.1.18): Im CameraDaemon (app_process) ruft niemand DaemonBootstrap.init() auf –
 * getContext() ist dort null. Stellen, die Systemdienste oder APK-Assets brauchen, fragen jetzt
 * {@link DaemonBootstrap#context()} bzw. {@link DaemonBootstrap#apkAssets()}, die auf die Quellen des
 * CameraDaemon zurückfallen. getContext() selbst bleibt unverändert (GPU-Pipeline).
 */
public class DaemonContextRueckfallTest {

    @After
    public void aufraeumen() {
        DaemonBootstrap.setDaemonQuellen(null, null);
    }

    @Test
    public void ohneQuellenNullUndKeinAbsturz() {
        DaemonBootstrap.setDaemonQuellen(null, null);
        assertNull(DaemonBootstrap.context());
        assertNull(DaemonBootstrap.apkAssets());
    }

    @Test
    public void quellenWerdenGefragtUndFehlerGeschluckt() {
        int[] gefragt = {0, 0};
        DaemonBootstrap.setDaemonQuellen(() -> { gefragt[0]++; return null; }, () -> { gefragt[1]++; return null; });
        assertNull(DaemonBootstrap.context());
        assertNull(DaemonBootstrap.apkAssets());
        assertTrue(gefragt[0] == 1 && gefragt[1] == 1);
        DaemonBootstrap.setDaemonQuellen(() -> { throw new IllegalStateException("x"); },
                () -> { throw new IllegalStateException("y"); });
        assertNull(DaemonBootstrap.context());
        assertNull(DaemonBootstrap.apkAssets());
    }

    static String quelle(String rel) throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/" + rel);
        if (!f.isFile()) f = new File("app/" + f.getPath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void daemonMeldetSeineQuellenAn() throws Exception {
        String k = quelle("daemon/CameraDaemon.java");
        int main = k.indexOf("public static void main(String[] args) {");
        int reg = k.indexOf("DaemonBootstrap.setDaemonQuellen(CameraDaemon::getAppContext, CameraDaemon::getApkAssets);");
        assertTrue("Anmeldung am Anfang von main()", main > 0 && reg > main && reg - main < 400);
    }

    /** Diese Stellen liefen im Daemon bisher immer ins Leere. Assets nie über den (Shell-)Context des Daemon. */
    @Test
    public void betroffeneStellenNutzenDenRueckfall() throws Exception {
        String[][] faelle = {
                {"server/Messages.java", "DaemonBootstrap.apkAssets()"},
                {"server/HttpServer.java", "DaemonBootstrap.apkAssets()"},
                {"camping/Camping.java", "DaemonBootstrap.apkAssets()"},
                {"ha/HaMdns.java", "DaemonBootstrap.context()"},
                {"byd/BydCarSettings.java", "DaemonBootstrap.context()"},
                {"byd/cloud/BydCloudDataProvider.java", "DaemonBootstrap.apkAssets()"},
                {"server/BydCloudApiHandler.java", "DaemonBootstrap.apkAssets()"},
        };
        for (String[] f : faelle) {
            String k = quelle(f[0]);
            assertTrue(f[0], k.contains(f[1]));
            assertFalse(f[0] + " nutzt noch getContext()", k.contains("DaemonBootstrap.getContext()"));
        }
    }
}
