package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.PairingCodes;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** CLOUD_UEBERGABE_VERTRAG §5: Pfade nur für art=cockpit, Entscheidung nur Besitzer, ganzer Weg über die API mit Attrappe. */
public class CloudUebergabeApiTest {

    final List<JSONObject> eingerichtet = new ArrayList<>();

    @Before
    public void auf() {
        CloudUebergabeApiHandler.zuruecksetzen(z -> {
            eingerichtet.add(new JSONObject(z.toString()));
            return new JSONObject().put("gespeichert", true).put("verified", true);
        });
        CloudUebergabeApiHandler.artVon = kid -> kid.startsWith("cockpit") ? "cockpit" : kid.startsWith("handy") ? "handy" : "homeassistant";
    }

    @After
    public void ab() {
        AuthMiddleware.setRequestScopeForTest(null);
        AuthMiddleware.setRequestKopplungForTest(null);
        CloudUebergabeApiHandler.zuruecksetzen(null);
    }

    void alsKopplung(String kid) {
        AuthMiddleware.setRequestScopeForTest(PairingCodes.SCOPE);
        AuthMiddleware.setRequestKopplungForTest(kid);
    }

    void alsBesitzer() {
        AuthMiddleware.setRequestScopeForTest(null);
        AuthMiddleware.setRequestKopplungForTest(null);
    }

    Object[] rufe(String m, String pfad, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(pfad, CloudUebergabeApiHandler.handle(m, pfad, body, out));
        String roh = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int code = Integer.parseInt(roh.substring(9, 12));
        return new Object[]{code, new JSONObject(roh.substring(roh.indexOf("\r\n\r\n") + 4))};
    }

    @Test
    public void externGuardNurCockpitUndNurDieDreiPfade() {
        String ref = "/api/cloud-uebergabe/r-0123456789abcdef";
        assertFalse(ExternGuard.blocks("POST", "/api/cloud-uebergabe/anfrage", "cockpit"));
        assertFalse(ExternGuard.blocks("GET", ref, "cockpit"));
        assertFalse(ExternGuard.blocks("POST", ref, "cockpit"));
        assertTrue(ExternGuard.blocks("GET", "/api/cloud-uebergabe/anfrage", "cockpit"));
        assertTrue("Entscheiden nie mit Kopplung", ExternGuard.blocks("POST", ref + "/erlauben", "cockpit"));
        assertTrue(ExternGuard.blocks("GET", "/api/cloud-uebergabe", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", "/api/cloud-uebergabe/r-XYZ", "cockpit"));
        for (String art : new String[]{"handy", "homeassistant", null}) {
            assertTrue(art, ExternGuard.blocks("POST", "/api/cloud-uebergabe/anfrage", art));
            assertTrue(art, ExternGuard.blocks("GET", ref, art));
        }
        assertTrue("Web-Sitzung der Handy-App darf nicht entscheiden", WebHandyGuard.blocks("POST", ref + "/erlauben"));
        assertTrue(WebHandyGuard.blocks("GET", "/api/cloud-uebergabe"));
    }

    @Test
    public void ganzerWegUeberDieApi() throws Exception {
        alsBesitzer();
        assertEquals(401, rufe("POST", "/api/cloud-uebergabe/anfrage", "{}")[0]);
        alsKopplung("handy-1");
        assertEquals("nur_cockpit", ((JSONObject) rufe("POST", "/api/cloud-uebergabe/anfrage", "{}")[1]).getString("grund"));
        alsKopplung("cockpit-1");
        Object[] a = rufe("POST", "/api/cloud-uebergabe/anfrage", "{\"geraet\":\"EV-Cockpit (PC)\"}");
        assertEquals(201, a[0]);
        JSONObject j = (JSONObject) a[1];
        String ref = j.getString("ref");
        assertTrue(ref.matches("r-[0-9a-f]{16}"));
        assertTrue(j.getString("pruefzahl").matches("\\d{4}"));
        // Kopplung darf nicht selbst erlauben, auch nicht die Liste lesen
        assertEquals(403, rufe("POST", "/api/cloud-uebergabe/" + ref + "/erlauben", "")[0]);
        assertEquals(403, rufe("GET", "/api/cloud-uebergabe", null)[0]);
        assertEquals("wartet", ((JSONObject) rufe("GET", "/api/cloud-uebergabe/" + ref, null)[1]).getString("status"));
        alsKopplung("cockpit-2");
        assertEquals("fremde Kopplung sieht die ref nicht", 404, rufe("GET", "/api/cloud-uebergabe/" + ref, null)[0]);
        alsBesitzer();
        JSONObject offen = (JSONObject) rufe("GET", "/api/cloud-uebergabe", null)[1];
        assertEquals("EV-Cockpit (PC)", offen.getJSONArray("offen").getJSONObject(0).getString("geraet"));
        assertFalse("nie Schlüssel in der Liste", offen.toString().contains("a_pub"));
        assertEquals(200, rufe("POST", "/api/cloud-uebergabe/" + ref + "/erlauben", "")[0]);
        alsKopplung("cockpit-1");
        assertEquals("erlaubt", ((JSONObject) rufe("GET", "/api/cloud-uebergabe/" + ref, null)[1]).getString("status"));
        // Cockpit-Seite: verschlüsseln mit a_pub
        byte[] ePriv = new byte[32];
        java.util.Arrays.fill(ePriv, (byte) 9);
        byte[] ePub = com.google.crypto.tink.subtle.X25519.publicFromPrivate(ePriv);
        byte[] aPub = java.util.Base64.getUrlDecoder().decode(j.getString("a_pub"));
        byte[] shared = com.google.crypto.tink.subtle.X25519.computeSharedSecret(ePriv, aPub);
        String klar = "{\"username\":\"t@e.at\",\"password\":\"pw-xyz\",\"countryCode\":\"AT\",\"language\":\"de\",\"region\":\"eu\"}";
        java.lang.reflect.Method v = at.greenchili.surfmate.auth.CloudUebergabe.class.getDeclaredMethod("verschluessele",
                byte[].class, byte[].class, byte[].class, String.class, byte[].class);
        v.setAccessible(true);
        byte[] c = (byte[]) v.invoke(null, shared, aPub, ePub, ref, klar.getBytes(StandardCharsets.UTF_8));
        java.util.Base64.Encoder b = java.util.Base64.getUrlEncoder().withoutPadding();
        Object[] s = rufe("POST", "/api/cloud-uebergabe/" + ref,
                new JSONObject().put("e_pub", b.encodeToString(ePub)).put("chiffre", b.encodeToString(c)).toString());
        assertEquals(s[1].toString(), 200, s[0]);
        assertTrue(((JSONObject) s[1]).getBoolean("verified"));
        assertFalse("nie Zugangsdaten in der Antwort", s[1].toString().contains("pw-xyz"));
        assertEquals("pw-xyz", eingerichtet.get(0).getString("password"));
        assertEquals("schon_uebergeben", ((JSONObject) rufe("POST", "/api/cloud-uebergabe/" + ref, "{}")[1]).getString("grund"));
    }

    @Test
    public void haZustandNurJaNein() throws Exception {
        JSONObject z = HaApiHandler.zustand(false);
        if (z.has("cloud")) {
            JSONObject c = z.getJSONObject("cloud");
            assertEquals(1, c.length());
            assertTrue(c.get("eingerichtet") instanceof Boolean);
        }
        String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(new java.io.File(
                "src/main/java/at/greenchili/surfmate/server/HaApiHandler.java").isFile()
                ? "src/main/java/at/greenchili/surfmate/server/HaApiHandler.java"
                : "app/src/main/java/at/greenchili/surfmate/server/HaApiHandler.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(src.contains("z.put(\"cloud\", new JSONObject().put(\"eingerichtet\", cloud));"));
    }
}
