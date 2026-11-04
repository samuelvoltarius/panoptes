package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.config.ConfigBackupService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Base64;

/**
 * 0.1.38.8 Integration (Koordination 04.11.): Das Bake-Geheimnis für „Aufsperren beim Annähern“ liegt nur verschlüsselt
 * in der Config (neues Feld, kein Rückfall-Klartext nötig), nie im Klartext, und geht nie in eine Sicherung.
 */
public class NaeheBakeVerschluesseltTest {

    private final JSONArray[] gespeichert = {new JSONArray()};
    private Kopplungen k;
    private String kid;
    private final byte[] bake = new byte[32];
    private String bakeB64;

    @Before
    public void auf() {
        // Ersatz für CredentialCipher (in der JVM fehlt android.util.Base64): umkehrbar, mit ENC:-Präfix
        Kopplungen.bakeCipherFuerTest(s -> "ENC:" + new StringBuilder(s).reverse(),
                s -> s.startsWith("ENC:") ? new StringBuilder(s.substring(4)).reverse().toString() : "");
        k = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        kid = k.anlegen("Pixel", Kopplungen.ART_HANDY, 1_000L);
        k.schluesselMelden(kid, "MFkwEwYHKoZIzj0CAQ", "android", "Pixel 8");
        for (int i = 0; i < bake.length; i++) bake[i] = (byte) (i * 11 + 5);
        bakeB64 = Base64.getEncoder().encodeToString(bake);
    }

    @After
    public void ab() {
        Kopplungen.bakeCipherFuerTest(null, null);
    }

    private JSONObject eintrag() {
        return gespeichert[0].optJSONObject(0);
    }

    @Test
    public void setzenSpeichertKeinKlartextUndLesenGeht() throws Exception {
        assertTrue(k.naeheSetzen(kid, "naehe-pub", 9_999_999L, bakeB64));
        String roh = gespeichert[0].toString();
        assertFalse("Klartext-Bake in der Ablage", roh.contains(bakeB64));
        assertTrue(eintrag().getString("naeheBake").startsWith("ENC:"));
        assertArrayEquals(bake, k.naeheBake(kid));
    }

    @Test
    public void ohneVerschluesselungWirdNichtsGespeichert() {
        // CredentialCipher gibt bei einem Fehler den Klartext zurück: dann gar nicht speichern
        Kopplungen.bakeCipherFuerTest(s -> s, s -> s);
        assertFalse(k.naeheSetzen(kid, "naehe-pub", 9_999_999L, bakeB64));
        assertFalse(gespeichert[0].toString().contains(bakeB64));
        assertNull(k.naeheSchluessel(kid, 0L));
        assertNull(k.naeheBake(kid));
    }

    @Test
    public void klartextImBestandGiltNicht() throws Exception {
        assertTrue(k.naeheSetzen(kid, "naehe-pub", 9_999_999L, bakeB64));
        JSONArray roh = new JSONArray(gespeichert[0].toString());
        roh.getJSONObject(0).put("naeheBake", bakeB64);   // von Hand eingetragener Klartext in der Ablage
        gespeichert[0] = roh;
        Kopplungen frisch = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        assertNull(frisch.naeheBake(kid));
    }

    @Test
    public void sicherungOhneNaehe() throws Exception {
        assertTrue(k.naeheSetzen(kid, "naehe-pub", 9_999_999L, bakeB64));
        JSONObject root = new JSONObject().put("kopplungen",
                new JSONObject().put("geraete", new JSONArray(gespeichert[0].toString())));
        ConfigBackupService.ohneNaehe(root);
        JSONObject g = root.getJSONObject("kopplungen").getJSONArray("geraete").getJSONObject(0);
        assertFalse(g.has("naeheBake"));
        assertFalse(g.has("naeheSchluessel"));
        assertFalse(g.has("naeheBis"));
        assertEquals(kid, g.getString("id"));
        // ohne Kopplungen-Abschnitt passiert nichts
        ConfigBackupService.ohneNaehe(new JSONObject());
    }
}
