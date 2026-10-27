package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 0.1.19 (Befund Deploy 27.10., UPDATER_TEST_0118.md): Der In-App-Updater lief am Auto in „kein Context“ – im
 * app_process-Daemon gibt es keinen Android-Context. Jetzt: Skript und installiertes APK direkt aus der APK-Datei
 * (ZipFile, CLASSPATH), und die Deploy-Sperre wird bei jedem Abbruch vor dem Skriptstart freigegeben.
 * In der JVM gibt es ebenfalls keinen Context – diese Tests laufen also unter denselben Bedingungen wie der Daemon.
 */
public class UpdaterOhneContextTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    File zip(String name, String eintrag, String inhalt) throws Exception {
        File f = tmp.newFile(name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (eintrag != null) {
                z.putNextEntry(new ZipEntry(eintrag));
                z.write(inhalt.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("classes.dex"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
        }
        return f;
    }

    static File echteApk() {
        File[] l = new File("build/abgabe").listFiles((d, n) -> n.startsWith("panoptes-0.1.") && n.endsWith("-rot.apk"));
        return l == null || l.length == 0 ? null : l[l.length - 1];
    }

    @Test
    public void skriptAusDemApkLesen() throws Exception {
        File apk = zip("neu.apk", ApkQuelle.SKRIPT_EINTRAG, "#!/system/bin/sh\necho AUTO_OK\n");
        assertEquals("#!/system/bin/sh\necho AUTO_OK\n", ApkQuelle.text(apk, ApkQuelle.SKRIPT_EINTRAG));
        try {
            ApkQuelle.text(zip("leer.apk", null, null), ApkQuelle.SKRIPT_EINTRAG);
            fail("fehlender Eintrag angenommen");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("fehlt"));
        }
        try {
            ApkQuelle.text(new File(tmp.getRoot(), "gibtsnicht.apk"), ApkQuelle.SKRIPT_EINTRAG);
            fail();
        } catch (java.io.IOException erwartet) {
            // ok
        }
    }

    @Test
    public void installiertesApkAusDemClasspath() throws Exception {
        File ordner = tmp.newFolder("at.greenchili.surfmate-AbC==");
        File base = new File(ordner, "base.apk");
        assertTrue(base.createNewFile());
        File jar = tmp.newFile("bmmcamera.jar");
        String cp = jar.getAbsolutePath() + File.pathSeparator + base.getAbsolutePath();   // Android: ":"
        assertEquals(base, ApkQuelle.installiertesApk(cp));
        assertNull(ApkQuelle.installiertesApk(null));
        assertNull(ApkQuelle.installiertesApk(jar.getAbsolutePath()));
        assertNull("nicht vorhandene Datei", ApkQuelle.installiertesApk("/data/app/at.greenchili.surfmate-x/base.apk"));
    }

    /** Die echte Umgebung liest das Skript ohne Context (JVM = wie im app_process-Daemon). */
    @Test
    public void echteUmgebungBrauchtKeinenContext() throws Exception {
        AppUpdateDienst.AndroidUmgebung u = new AppUpdateDienst.AndroidUmgebung();
        File apk = zip("neu.apk", ApkQuelle.SKRIPT_EINTRAG, "#!/system/bin/sh\necho test\n");
        assertEquals("#!/system/bin/sh\necho test\n", u.skript(apk));
        // ohne Skript im neuen APK und ohne CLASSPATH: ehrlicher Fehler, nicht „kein Context“
        try {
            u.skript(zip("leer.apk", null, null));
            fail();
        } catch (java.io.IOException e) {
            assertFalse(e.getMessage(), e.getMessage().contains("Context"));
        }
        File echt = echteApk();
        assumeTrue(echt != null);
        String s = u.skript(echt);
        assertTrue("echtes update-on-car.sh", s.contains("AUTO_OK") && s.contains("DEADLINE_MIN"));
    }

    @Test
    public void sperreNurDieEigeneFreigeben() throws Exception {
        File sperre = tmp.newFolder(".deploy.lock");
        Files.write(new File(sperre, "info").toPath(), "sitzung=deploy\nschluessel=fremd-1\n".getBytes(StandardCharsets.UTF_8));
        AppUpdateDienst.sperreFreigeben(sperre, "appupdate-2");
        assertTrue("fremde Sperre bleibt", sperre.isDirectory());
        AppUpdateDienst.sperreFreigeben(sperre, "fremd-1");
        assertFalse(sperre.exists());
    }

    @Test
    public void quelltextOhneContextImSkriptweg() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        String k = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int a = k.indexOf("public String skript(File apk) throws Exception {");
        String body = k.substring(a, k.indexOf("\n        }\n", a));
        assertFalse(body.contains("getContext()"));
        assertFalse(body.contains("getAssets()"));
        assertTrue(body.contains("ApkQuelle.text(apk, ApkQuelle.SKRIPT_EINTRAG)"));
        assertNotNull(k);
    }
}
