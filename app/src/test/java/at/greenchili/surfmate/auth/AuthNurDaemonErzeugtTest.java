package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.25: Nur der Daemon erzeugt den JWT-Signaturschlüssel (Befund Kern 12, Erstinstallation). */
public class AuthNurDaemonErzeugtTest {

    @Test
    public void appProzessErzeugtKeinenSchluessel() throws Exception {
        assertFalse("JVM-Test zählt nicht als App-Prozess", AuthManager.istAppProzess());
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/auth/AuthManager.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int sperre = k.indexOf("if (istAppProzess()) {");
        int erzeugen = k.indexOf("String candidateSecret = generateSecret(8);");
        assertTrue("Sperre steht vor dem Erzeugen", sperre > 0 && erzeugen > sperre);
        assertTrue(k.contains("return android.os.Process.myUid() >= 10000;"));
    }
}
