package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.38.8 Paket A: auth.deviceSecret mit Schattenkopie, Klartext bleibt maßgeblich, nie ein neuer Schlüssel ohne Not. */
public class AuthSchattenTest {

    private static String quelle() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/auth/AuthManager.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void befundNieMitWert() {
        assertEquals("gleich", AuthManager.ipcBefund("geheim88", "geheim88"));
        assertEquals("abweichend", AuthManager.ipcBefund("geheim88", "anders"));
        assertEquals("nicht_erreichbar", AuthManager.ipcBefund("geheim88", null));
        assertFalse(AuthManager.ipcBefund("geheim88", "anders").contains("geheim"));
    }

    @Test
    public void schattenOhneGeraeteIdIstNichtLesbarImShellDaemon() {
        // JVM = kein App-, kein System-Prozess → keine IPC, nur selbst entschlüsseln (ohne Geräte-Id: "")
        assertEquals("", AuthManager.ausSchatten(""));
        assertEquals("", AuthManager.ausSchatten(null));
        assertEquals("", AuthManager.ausSchatten("ENC:AAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void klartextZuerstSchattenNurAlsErsatz() throws Exception {
        String k = quelle();
        int klar = k.indexOf("String secret = section.optString(KEY_DEVICE_SECRET, \"\");");
        int ersatz = k.indexOf("if (secret.isEmpty()) secret = ausSchatten(section.optString(KEY_SECRET_SCHATTEN, \"\"));");
        assertTrue(klar > 0 && ersatz > klar);
        // Schreiben: Klartext wie 0.1.38.6 (toJson) plus Schatten
        assertTrue(k.contains("JSONObject delta = state.toJson();"));
        assertTrue(k.contains("delta.put(KEY_SECRET_SCHATTEN,"));
        assertEquals("enc_deviceSecret", AuthManager.KEY_SECRET_SCHATTEN);
    }

    @Test
    public void unlesbarerSchattenErzeugtKeinenNeuenSchluessel() throws Exception {
        String k = quelle();
        int sperre = k.indexOf("if (nurSchattenOhneKlartext()) {");
        int erzeugen = k.indexOf("String candidateSecret = generateSecret(8);");
        assertTrue("Sperre steht vor dem Erzeugen", sperre > 0 && erzeugen > sperre);
    }
}
