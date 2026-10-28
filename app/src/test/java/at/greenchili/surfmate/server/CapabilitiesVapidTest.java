package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** 0.1.26.1: capabilities.push.vapid = nur der öffentliche VAPID-Schlüssel (HANDY_API_VERTRAG §4). */
public class CapabilitiesVapidTest {

    private static JSONObject capabilities() throws Exception {
        Method m = SystemApiHandler.class.getDeclaredMethod("capabilities");
        m.setAccessible(true);
        return (JSONObject) m.invoke(null);
    }

    private static void keyStoreNull() throws Exception {
        Field f = NotificationApiHandler.class.getDeclaredField("keyStore");
        f.setAccessible(true);
        f.set(null, null);
    }

    private static byte[] fest32(BigInteger v) {
        byte[] roh = v.toByteArray(), d = new byte[32];
        int n = Math.min(32, roh.length);
        System.arraycopy(roh, roh.length - n, d, 32 - n, n);
        return d;
    }

    @After
    public void zurueck() throws Exception {
        NotificationApiHandler.vapidQuelleFuerTest = null;
        keyStoreNull();
    }

    @Test
    public void nurOeffentlicherSchluessel() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] p = new byte[65];
        p[0] = 0x04;
        System.arraycopy(fest32(pub.getW().getAffineX()), 0, p, 1, 32);
        System.arraycopy(fest32(pub.getW().getAffineY()), 0, p, 33, 32);
        final String erwartet = Base64.getUrlEncoder().withoutPadding().encodeToString(p);
        NotificationApiHandler.vapidQuelleFuerTest = () -> erwartet;

        JSONObject c = capabilities();
        JSONObject push = c.getJSONObject("push");
        assertEquals("nur das eine Feld", 1, push.length());
        String vapid = push.getString("vapid");
        assertEquals(erwartet, vapid);
        byte[] roh = Base64.getUrlDecoder().decode(vapid);
        assertEquals("unkomprimiert P-256", 65, roh.length);
        assertEquals(0x04, roh[0]);

        byte[] d = fest32(((ECPrivateKey) kp.getPrivate()).getS());
        String text = c.toString();
        assertFalse(text.contains(Base64.getUrlEncoder().withoutPadding().encodeToString(d)));
        assertFalse(text.contains(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())));
    }

    @Test
    public void ohnePushKeinFeld() throws Exception {
        assertNull(NotificationApiHandler.vapidOeffentlich());
        assertFalse(capabilities().has("push"));
    }

    @Test
    public void quelleIstNurDerOeffentlicheTeil() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/server/NotificationApiHandler.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        String q = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int a = q.indexOf("static String vapidOeffentlich()");
        String rumpf = q.substring(a, q.indexOf("\n    }", a));
        assertTrue(rumpf.contains("k.publicKeyB64Url()"));
        assertFalse(rumpf.contains("privateKey"));
        assertFalse(rumpf.contains("load()"));
    }
}
