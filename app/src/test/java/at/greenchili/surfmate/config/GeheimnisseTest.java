package at.greenchili.surfmate.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.20 (Koordination 27.10.): Geheimnisse gehen nie an einen Client – auch nicht mit PIN-Login. Prüft jede im Code
 * vorkommende Geheimnis-Stelle der Gesamt-Konfiguration und dass die Schwärzung für JEDE Anmeldeart greift.
 */
public class GeheimnisseTest {

    /** Konfiguration mit allen bekannten Geheimnissen (Werte „GEHEIM-n“) und harmlosen Nachbarn. */
    static JSONObject beispiel() throws Exception {
        int[] n = {0};
        java.util.function.Supplier<String> g = () -> "GEHEIM-" + (n[0]++) + "-x";
        return new JSONObject()
                .put("auth", new JSONObject().put("deviceId", "dev-1").put("deviceSecret", g.get()).put("lastAccess", 5))
                .put("update", new JSONObject().put("quelle", "eigen").put("repo", "a/b").put("token", g.get()).put("mobilfunk", true))
                .put("planner", new JSONObject().put("memberToken", g.get()).put("autoId", "a-1"))
                .put("telegram", new JSONObject().put("botToken", g.get()).put("token", g.get()).put("telegramSendStartPing", true))
                .put("bydCloud", new JSONObject().put("enabled", true).put("username", "alf").put("password", g.get())
                        .put("loginKey", g.get()).put("signPassword", g.get()).put("commandPwd", g.get())
                        .put("rawPassword", g.get()).put("controlPin", g.get()))
                .put("genAi", new JSONObject().put("apiKey", g.get()).put("api_key", g.get()).put("maxOutputTokens", 512)
                        .put("routingApiKey", g.get()))
                .put("abrp", new JSONObject().put("abrpToken", g.get()).put("user_token", g.get()))
                .put("mqtt", new JSONObject().put("password", g.get()).put("hasPassword", true))
                .put("hotspot", new JSONObject().put("ssid", "Surf").put("password", g.get()))
                .put("wallbox", new JSONObject().put("geraete", new JSONArray()
                        .put(new JSONObject().put("apiKey", g.get()).put("token", g.get()).put("refresh", g.get())
                                .put("secret", g.get()).put("localKey", g.get()).put("url", "http://10.0.0.5"))))
                .put("notzugang", new JSONObject().put("authKey", g.get()).put("access_token", g.get()))
                .put("wireguard", new JSONObject().put("privateKey", g.get()).put("private_key", g.get()))
                .put("kopplung", new JSONObject().put("pairPin", g.get()).put("passwort", g.get()))
                .put("keymap", new JSONArray().put("a")).put("camping", new JSONObject().put("aktiv", false))
                .put("recording", new JSONObject().put("quality", "HIGH"));
    }

    @Test
    public void keinGeheimnisInDerAntwort() throws Exception {
        JSONObject roh = beispiel();
        String aus = Geheimnisse.schwaerzen(roh).toString();
        assertFalse("Geheimnis durchgerutscht: " + aus, aus.contains("GEHEIM-"));
        JSONObject s = Geheimnisse.schwaerzen(roh);
        assertEquals(true, s.getJSONObject("auth").getJSONObject("deviceSecret").getBoolean("gesetzt"));
        assertEquals("dev-1", s.getJSONObject("auth").getString("deviceId"));
        assertEquals("eigen", s.getJSONObject("update").getString("quelle"));
        assertTrue("Schalter bleiben", s.getJSONObject("mqtt").getBoolean("hasPassword"));
        assertEquals(512, s.getJSONObject("genAi").getInt("maxOutputTokens"));
        assertTrue(s.getJSONObject("telegram").getBoolean("telegramSendStartPing"));
        assertEquals("http://10.0.0.5", s.getJSONObject("wallbox").getJSONArray("geraete").getJSONObject(0).getString("url"));
        assertEquals("HIGH", s.getJSONObject("recording").getString("quality"));
        assertTrue("Original unverändert", roh.getJSONObject("auth").getString("deviceSecret").startsWith("GEHEIM-"));
        JSONObject leer = Geheimnisse.schwaerzen(new JSONObject().put("token", ""));
        assertFalse(leer.getJSONObject("token").getBoolean("gesetzt"));
    }

    @Test
    public void zurueckgeschickteMaskeUeberschreibtNichts() throws Exception {
        JSONObject zurueck = Geheimnisse.schwaerzen(beispiel()).getJSONObject("bydCloud");
        zurueck.put("username", "neu");
        java.util.List<String> weg = Geheimnisse.ohneMasken(zurueck);
        assertTrue(weg.contains("password") && weg.contains("loginKey"));
        assertFalse(zurueck.has("password"));
        assertEquals("neu", zurueck.getString("username"));
        JSONObject neu = new JSONObject().put("password", "neues-passwort");
        assertTrue("ersetzen bleibt möglich", Geheimnisse.ohneMasken(neu).isEmpty());
        assertEquals("neues-passwort", neu.getString("password"));
    }

    @Test
    public void namenserkennung() {
        for (String k : new String[] {"deviceSecret", "token", "botToken", "password", "passwort", "commandPwd", "apiKey",
                "api_key", "privateKey", "authKey", "loginKey", "localKey", "controlPin", "pairPin", "refresh"}) {
            assertTrue(k, Geheimnisse.istGeheim(k));
        }
        for (String k : new String[] {"maxOutputTokens", "keymap", "telegramSendStartPing", "camping",
                "key", "deviceId", "ssid", "username", "keyBatteryLevel", "pinLock"}) {
            assertFalse(k, Geheimnisse.istGeheim(k));
        }
    }

    /** Gilt für jede Anmeldeart: die Schwärzung steht vor und außerhalb jeder Scope-Abfrage. */
    @Test
    public void unifiedSchwaerztFuerJedeAnmeldung() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/server/QualitySettingsApiHandler.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = k.indexOf("private static void sendUnifiedConfig(OutputStream out)");
        int schwaerzen = k.indexOf("JSONObject config = at.greenchili.surfmate.config.Geheimnisse.schwaerzen(new JSONObject(sb.toString()));", start);
        int scope = k.indexOf("AuthMiddleware.isHandyWebRequest()", start);
        int put = k.indexOf("response.put(\"config\", config);", start);
        assertTrue("schwaerzen vor der Ausgabe", start > 0 && schwaerzen > start && put > schwaerzen);
        assertTrue("nicht von der Anmeldeart abhängig", schwaerzen < scope);
        assertFalse("kein ungeschwärzter Weg", k.substring(start, put).contains("new JSONObject(sb.toString());\n                response.put"));
        assertTrue(k.contains("if (data != null) at.greenchili.surfmate.config.Geheimnisse.ohneMasken(data);"));
    }
}
