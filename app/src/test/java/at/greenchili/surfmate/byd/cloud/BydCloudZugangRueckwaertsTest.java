package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.function.UnaryOperator;

/**
 * 0.1.38.6 Sicherheitsfix, Pflicht der Koordination: die BYD-Cloud-Anmeldung geht beim Umstieg nicht verloren.
 * Alter Klartext (bis 0.1.38.5) gilt unverändert, die neue verschlüsselte Form ebenso.
 */
public class BydCloudZugangRueckwaertsTest {

    /** Attrappe für CredentialCipher.decrypt: reicht Klartext durch wie das Original, "ENC:" + umgedreht. */
    static final UnaryOperator<String> ENT = v -> v.startsWith("ENC:") ? new StringBuilder(v.substring(4)).reverse().toString() : v;

    private static JSONObject abschnitt(String login, String sign, String cmd) throws Exception {
        return new JSONObject().put("enabled", true).put("username", "nutzer@example.invalid")
                .put("loginKey", login).put("signPassword", sign).put("commandPwd", cmd).put("vin", "TESTVIN0000000001");
    }

    @Test
    public void alterKlartextBleibtAngemeldet() throws Exception {
        BydCloudConfig c = BydCloudConfig.ausAbschnitt(abschnitt("lk-123", "sp-456", "cp-789"), "pw", ENT);
        assertTrue(c.isConfigured());
        assertTrue(c.isVerified());
        assertEquals("lk-123", c.loginKey);
        assertEquals("sp-456", c.signPassword);
        assertEquals("cp-789", c.commandPwd);
    }

    @Test
    public void neueVerschluesselteFormGibtDieselbenWerte() throws Exception {
        BydCloudConfig c = BydCloudConfig.ausAbschnitt(abschnitt("ENC:321-kl", "ENC:654-ps", "ENC:987-pc"), "pw", ENT);
        assertTrue(c.isConfigured());
        assertEquals("lk-123", c.loginKey);
        assertEquals("sp-456", c.signPassword);
        assertEquals("cp-789", c.commandPwd);
    }

    @Test
    public void gemischterBestandWaehrendDerMigration() throws Exception {
        BydCloudConfig c = BydCloudConfig.ausAbschnitt(abschnitt("ENC:321-kl", "sp-456", "cp-789"), "pw", ENT);
        assertTrue(c.isConfigured());
        assertEquals("lk-123", c.loginKey);
    }

    @Test
    public void unlesbarHeisstNeuAnmelden_nieFalscherWert() throws Exception {
        BydCloudConfig c = BydCloudConfig.ausAbschnitt(abschnitt("ENC:kaputt", "sp", "cp"), "pw", v -> v.startsWith("ENC:") ? "" : v);
        assertFalse(c.isConfigured());
        assertEquals("", c.loginKey);
    }
}
