package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Kopplungs-Widerruf (Koordination 21.10.2026): Liste, kid im JWT, sofortige Wirkung. */
public class KopplungenTest {

    private static final long TAG = 24L * 3600 * 1000;

    /** Speicher im Arbeitsspeicher; zählt Schreibvorgänge, kann Fehler vortäuschen. */
    static final class Mem implements Kopplungen.Speicher {
        JSONArray inhalt = new JSONArray();
        int schreibvorgaenge;
        boolean kaputt;

        public JSONArray lade() {
            return inhalt;
        }

        public boolean speichere(JSONArray g) {
            if (kaputt) return false;
            schreibvorgaenge++;
            try {
                inhalt = new JSONArray(g.toString());
            } catch (Exception e) {
                return false;
            }
            return true;
        }
    }

    private static AuthManager.JwtValidation pruefe(String scope, String kid, Kopplungen k, long now) throws Exception {
        AuthManager.JwtValidation v = AuthManager.JwtValidation.success("dev");
        v.scope = scope;
        JSONObject payload = new JSONObject();
        if (kid != null) payload.put("kid", kid);
        return AuthManager.kopplungPruefen(v, payload, k, now);
    }

    @Test
    public void revokeTakesEffectImmediatelyAndOnlyForThatDevice() throws Exception {
        Mem m = new Mem();
        Kopplungen k = new Kopplungen(m);
        String a = k.anlegen("Handy Alex", 1000);
        String b = k.anlegen("Tablet", 1000);
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(pruefe(PairingCodes.SCOPE, a, k, 2000).valid);
        assertTrue(pruefe(PairingCodes.SCOPE, b, k, 2000).valid);

        assertEquals(Kopplungen.WIDERRUFEN, k.widerrufen(a));
        assertFalse("sofort ungültig", pruefe(PairingCodes.SCOPE, a, k, 2001).valid);
        assertTrue("andere Kopplung bleibt", pruefe(PairingCodes.SCOPE, b, k, 2001).valid);
        assertTrue("Alfreds Anmeldung (ohne scope) bleibt", pruefe(null, null, k, 2001).valid);
        assertEquals("gespeichert", 1, m.inhalt.length());
        assertEquals(Kopplungen.UNBEKANNT, k.widerrufen(a));
        assertEquals(Kopplungen.UNBEKANNT, k.widerrufen(""));

        // Nach Neustart (neue Instanz, gleicher Speicher) bleibt der Widerruf bestehen.
        Kopplungen neu = new Kopplungen(m);
        assertFalse(pruefe(PairingCodes.SCOPE, a, neu, 3000).valid);
        assertTrue(pruefe(PairingCodes.SCOPE, b, neu, 3000).valid);
    }

    @Test
    public void revokeWorksInMemoryEvenIfSavingFails() throws Exception {
        Mem m = new Mem();
        Kopplungen k = new Kopplungen(m);
        String a = k.anlegen("x", 0);
        m.kaputt = true;
        assertEquals(Kopplungen.NUR_IM_SPEICHER, k.widerrufen(a));
        assertFalse(pruefe(PairingCodes.SCOPE, a, k, 1).valid);
        assertNull("ohne Speichern keine neue Kopplung", k.anlegen("y", 2));
    }

    @Test
    public void couplingTokensNeedAKnownKid() throws Exception {
        Kopplungen k = new Kopplungen(new Mem());
        assertFalse("alte Kopplungs-JWTs ohne kid gelten nicht mehr", pruefe(PairingCodes.SCOPE, null, k, 0).valid);
        assertFalse("erfundene id", pruefe(PairingCodes.SCOPE, "k-0000000000000000", k, 0).valid);
        assertFalse("unbekannter scope", pruefe("admin", null, k, 0).valid);
        String a = k.anlegen("x", 0);
        AuthManager.JwtValidation v = pruefe(PairingCodes.SCOPE, a, k, 1);
        assertTrue(v.valid);
        assertEquals(a, v.kid);
        assertNull("Alfreds Anmeldung hat keine Kopplungs-id", pruefe(null, a, k, 1).kid);
    }

    @Test
    public void slidingThirtyDays() throws Exception {
        Mem m = new Mem();
        Kopplungen k = new Kopplungen(m);
        String a = k.anlegen("x", 0);
        assertTrue(k.pruefe(a, 29 * TAG));
        assertTrue("gleitend: 29 Tage nach letzter Nutzung", k.pruefe(a, 58 * TAG));
        assertFalse("31 Tage ungenutzt", k.pruefe(a, 89 * TAG));
        assertTrue(k.alle(89 * TAG).getJSONObject(0).getBoolean("abgelaufen"));
    }

    @Test
    public void lastSeenIsWrittenThrottled() {
        Mem m = new Mem();
        Kopplungen k = new Kopplungen(m);
        String a = k.anlegen("x", 0);
        int nachAnlegen = m.schreibvorgaenge;
        for (int i = 1; i <= 100; i++) k.pruefe(a, i * 1000L);
        assertEquals("100 Anfragen in 100 s: kein Schreiben", nachAnlegen, m.schreibvorgaenge);
        k.pruefe(a, Kopplungen.SPEICHER_TAKT_MS + 1);
        assertEquals(nachAnlegen + 1, m.schreibvorgaenge);
        assertEquals(Kopplungen.SPEICHER_TAKT_MS + 1, m.inhalt.optJSONObject(0).optLong("zuletzt"));
    }

    @Test
    public void listForUiAndLimits() throws Exception {
        Kopplungen k = new Kopplungen(new Mem());
        k.anlegen("  EV-Cockpit <script>\u0007 ", 5);
        JSONObject g = k.alle(6).getJSONObject(0);
        assertEquals("EV-Cockpit script", g.getString("name"));
        assertEquals(5, g.getLong("angelegt"));
        assertEquals(5, g.getLong("zuletzt"));
        assertTrue(g.getString("id").matches("^k-[0-9a-f]{16}$"));
        assertEquals("Gerät", Kopplungen.bereinigeName(null));
        assertEquals(40, Kopplungen.bereinigeName("x".repeat(80)).length());
        for (int i = 1; i < Kopplungen.MAX_GERAETE; i++) assertNotNull(k.anlegen("g" + i, 7));
        assertNull("Obergrenze", k.anlegen("zu viel", 8));
    }

    @Test
    public void couplingTokenCannotListOrRevoke() {
        assertTrue(ExternGuard.blocks("GET", "/api/kopplungen"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/widerrufen"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/code"));
        assertFalse("eigene Sitzung erneuern darf es", ExternGuard.blocks("POST", "/api/sitzung/erneuern"));
    }

    @Test
    public void wiring() throws Exception {
        String am = lies("src/main/java/at/greenchili/surfmate/auth/AuthManager.java");
        assertTrue("jede Anfrage prüft die Liste",
                am.contains("return kopplungPruefen(ok, payload, Kopplungen.get(), System.currentTimeMillis());"));
        assertTrue(am.contains("payload.put(\"kid\", kid)"));
        String ah = lies("src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java");
        assertTrue(ah.contains("Kopplungen.get().anlegen(name, now)"));
        assertTrue(ah.contains("generateJwt(at.greenchili.surfmate.auth.PairingCodes.SCOPE, kid)"));
        String eh = lies("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        int liste = eh.indexOf("pathOnly.equals(\"/api/kopplungen\")");
        int widerruf = eh.indexOf("pathOnly.equals(\"/api/kopplung/widerrufen\")");
        assertTrue(liste > 0 && widerruf > 0);
        assertTrue("zweite Sperre im Handler",
                eh.substring(liste, liste + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        assertTrue(eh.substring(widerruf, widerruf + 200).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        String mw = lies("src/main/java/at/greenchili/surfmate/server/AuthMiddleware.java");
        assertTrue(mw.contains("REQUEST_KID.remove();"));
        assertTrue(mw.contains("REQUEST_KID.set(validation.kid);"));
        String hs = lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(hs.contains("path.startsWith(\"/api/kopplungen\")"));
        assertTrue(hs.contains("path.startsWith(\"/api/sitzung/\")"));
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
