package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.PairingCodes;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** Kopplung (scope "kopplung") und Regel B für gekoppelte Geräte. */
public class KopplungUndRegelBTest {

    @Test
    public void pairingCodeIsSingleUseAndExpires() {
        String c = PairingCodes.create(1000L);
        assertEquals(8, c.length());
        assertFalse(PairingCodes.redeem("FALSCH12", 1001L));
        assertTrue(PairingCodes.redeem(c.toLowerCase(), 1002L));   // Groß/klein egal
        assertFalse("nur einmal", PairingCodes.redeem(c, 1003L));
        String d = PairingCodes.create(5000L);
        assertFalse("abgelaufen", PairingCodes.redeem(d, 5000L + PairingCodes.TTL_MS + 1));
        String e1 = PairingCodes.create(0L);
        String e2 = PairingCodes.create(0L);
        assertFalse("neuer Code ersetzt alten", PairingCodes.redeem(e1, 1L));
        assertTrue(PairingCodes.redeem(e2, 1L));
    }

    @Test
    public void externGuardBlocksRemoteAccessPaths() {
        assertTrue(ExternGuard.blocks("GET", "/api/system/tailscale"));
        assertTrue(ExternGuard.blocks("POST", "/api/system/tailscale/hostname"));
        assertTrue(ExternGuard.blocks("GET", "/api/wireguard"));
        assertTrue(ExternGuard.blocks("GET", "/api/debug/autoservice/remote-control"));
        assertTrue(ExternGuard.blocks("GET", "/api/backup/export"));
        assertTrue(ExternGuard.blocks("GET", "/api/settings/unified"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/code"));
        assertTrue(ExternGuard.blocks("POST", "/api/system/daemon"));
        assertTrue(ExternGuard.blocks("POST", "/api/telegram/token"));
        assertFalse("Lesen des Daemon-Status erlaubt", ExternGuard.blocks("GET", "/api/system/daemon"));
        assertFalse(ExternGuard.blocks("PUT", "/api/einstellungen"));
        assertFalse(ExternGuard.blocks("GET", "/api/einstellungen/schema"));
        assertFalse(ExternGuard.blocks("GET", "/api/planer/identitaet"));
        assertFalse(ExternGuard.blocks("GET", "/api/vehicle/status"));
    }

    @Test
    public void autoIdIsRandomFormattedAndNeverVin() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = EinstellungenApiHandler.neueAutoId();
            assertTrue(id, EinstellungenApiHandler.gueltigeAutoId(id));
            ids.add(id);
        }
        assertEquals(200, ids.size());
        assertFalse(EinstellungenApiHandler.gueltigeAutoId("LGXTEST0000000001"));   // VIN-Form nie gültig
        assertFalse(EinstellungenApiHandler.gueltigeAutoId(""));
    }

    @Test
    public void autoIdIsReadBackFromConfigAndNotDerivedFromVin() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java")), StandardCharsets.UTF_8);
        int s = src.indexOf("static synchronized String autoId()");
        String body = src.substring(s, src.indexOf("static boolean gueltigeAutoId", s));
        assertTrue("stabil: vorhandene Kennung wird zurückgegeben", body.contains("if (gueltigeAutoId(id)) return id;"));
        assertTrue("nach dem Schreiben zurückgelesen", body.contains("forceReload()"));
        assertFalse(body.toLowerCase().contains("vin"));
    }

    @Test
    public void wiringScopeAndGuard() throws Exception {
        String mw = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/AuthMiddleware.java")), StandardCharsets.UTF_8);
        assertTrue(mw.contains("REQUEST_SCOPE.remove();"));
        assertTrue(mw.contains("REQUEST_SCOPE.set(validation.scope);"));
        assertTrue(mw.contains("\"/auth/kopplung\""));
        String hs = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/HttpServer.java")), StandardCharsets.UTF_8);
        int auth = hs.indexOf("if (!authenticated) {");
        int guard = hs.indexOf("ExternGuard.blocks(method, pathOnly)", auth);
        int route = hs.indexOf("routeToHandlers(method, path, body", auth);
        assertTrue(auth > 0 && guard > auth && guard < route);
        String am = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/auth/AuthManager.java")), StandardCharsets.UTF_8);
        assertTrue(am.contains("payload.put(\"scope\", scope)"));
        String ah = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java")), StandardCharsets.UTF_8);
        int t = ah.indexOf("private static boolean handleTokenValidation");
        String tokenPath = ah.substring(t, ah.indexOf("private static boolean", t + 10));
        assertFalse("Login-Code-Weg gibt nie einen Kopplungs-JWT", tokenPath.contains("PairingCodes"));
        assertNotEquals(-1, ah.indexOf("generateJwt(at.greenchili.surfmate.auth.PairingCodes.SCOPE, kid)"));
    }
}
