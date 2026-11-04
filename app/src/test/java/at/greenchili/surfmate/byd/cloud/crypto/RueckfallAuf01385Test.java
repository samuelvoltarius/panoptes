package at.greenchili.surfmate.byd.cloud.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;
import java.util.function.UnaryOperator;

/**
 * Pflicht der Koordination (04.11.): Die Selbstheilung kann nach einem fehlgeschlagenen 0.1.38.6 auf 0.1.38.5
 * zurückrollen. Die Config nach der 0.1.38.6-Migration muss mit den Lesern von 0.1.38.5 noch alle Zugänge liefern
 * (Update-Token für den Rückfall-Updater, Planer, BYD-Cloud), und ein Wechsel hin und zurück verliert nichts.
 *
 * <p>Die {@code Leser01385}-Methoden sind die Lesestellen aus rel-0.1.38.5 (72b74271) wörtlich:
 * {@code PlanerSchluessel.kopf} (Mitglieds-Teil), {@code CommunityZugang.schluessel},
 * {@code AppUpdateDienst.config("token")}, {@code BydCloudConfig.load} (optString der drei Felder).
 */
public class RueckfallAuf01385Test {

    static final UnaryOperator<String> VER = v -> "ENC:" + new StringBuilder(v).reverse();
    static final UnaryOperator<String> DEC = v -> v.startsWith("ENC:") ? new StringBuilder(v.substring(4)).reverse().toString() : "";

    static final String MITGLIED = "0123456789abcdef0123456789abcdef";

    static final class Leser01385 {
        static String mitglied(JSONObject root) {
            String m = root.optJSONObject("planner").optString("memberToken", "").trim();
            return m.matches("^[0-9a-fA-F]{32}$") ? m : null;
        }

        static String updateToken(JSONObject root) {
            JSONObject u = root.optJSONObject("update");
            return u == null ? null : u.optString("token", null);
        }

        static String byd(JSONObject root, String feld) {
            return root.optJSONObject("bydCloud").optString(feld, "");
        }
    }

    private static JSONObject alteConfig() throws Exception {
        return new JSONObject()
                .put("planner", new JSONObject().put("memberToken", MITGLIED).put("band", true))
                .put("update", new JSONObject().put("token", "ghp-test-update").put("kanal", "stabil"))
                .put("bydCloud", new JSONObject().put("enabled", true).put("username", "nutzer@example.invalid")
                        .put("loginKey", "lk-1").put("signPassword", "sp-1").put("commandPwd", "cp-1")
                        .put("rawPassword", "ENC:altVerschluesselt"));
    }

    /** Was {@link ZugangsMigration#bestandVerschluesseln} je Feld schreibt (rein nachgestellt mit Attrappen-Cipher). */
    private static int migriere(JSONObject root) throws Exception {
        int n = 0;
        for (String[] f : ZugangsMigration.CONFIG_FELDER) {
            JSONObject sec = root.optJSONObject(f[0]);
            if (sec == null) continue;
            String sk = ZugangsMigration.schattenSchluessel(f[1]);
            String neu = ZugangsMigration.schatten(sec.optString(f[1], ""), sec.optString(sk, ""), VER, DEC);
            if (neu != null) { sec.put(sk, neu); n++; }
        }
        return n;
    }

    @Test
    public void nachDerMigrationLiest01385AllesUnveraendert() throws Exception {
        JSONObject vorher = alteConfig();
        JSONObject root = alteConfig();
        assertEquals(5, migriere(root));

        assertEquals(MITGLIED, Leser01385.mitglied(root));
        assertEquals("ghp-test-update", Leser01385.updateToken(root));
        assertEquals("lk-1", Leser01385.byd(root, "loginKey"));
        assertEquals("sp-1", Leser01385.byd(root, "signPassword"));
        assertEquals("cp-1", Leser01385.byd(root, "commandPwd"));
        // jeder alte Schlüssel trägt exakt den alten Wert; dazu kommen nur enc_*-Schlüssel
        for (Iterator<String> it = vorher.keys(); it.hasNext(); ) {
            String s = it.next();
            JSONObject a = vorher.getJSONObject(s), b = root.getJSONObject(s);
            for (Iterator<String> k = a.keys(); k.hasNext(); ) {
                String key = k.next();
                assertEquals(s + "." + key, String.valueOf(a.get(key)), String.valueOf(b.get(key)));
            }
            for (Iterator<String> k = b.keys(); k.hasNext(); ) {
                String key = k.next();
                assertTrue(s + "." + key, a.has(key) || key.startsWith(ZugangsMigration.SCHATTEN));
            }
        }
        // und der Schatten trägt dieselben Werte verschlüsselt
        assertEquals("ghp-test-update", DEC.apply(root.getJSONObject("update").getString("enc_token")));
        assertEquals("cp-1", DEC.apply(root.getJSONObject("bydCloud").getString("enc_commandPwd")));
    }

    @Test
    public void neustartIstIdempotent() throws Exception {
        JSONObject root = alteConfig();
        migriere(root);
        String nachEins = root.toString();
        assertEquals(0, migriere(root));
        assertEquals(nachEins, root.toString());
    }

    @Test
    public void hinZurueckUndWiederHinVerliertNichts() throws Exception {
        JSONObject root = alteConfig();
        migriere(root);
        // Rückfall auf 0.1.38.5: dort neuer Update-Token, BYD-Cloud abgemeldet (0.1.38.5 kennt enc_* nicht)
        root.getJSONObject("update").put("token", "ghp-neu");
        root.getJSONObject("bydCloud").put("loginKey", "").put("signPassword", "").put("commandPwd", "");
        // wieder 0.1.38.6: Schatten folgen dem Klartext, nichts lebt wieder auf
        migriere(root);
        assertEquals("ghp-neu", DEC.apply(root.getJSONObject("update").getString("enc_token")));
        assertEquals("", root.getJSONObject("bydCloud").getString("enc_loginKey"));
        assertEquals("", root.getJSONObject("bydCloud").getString("enc_commandPwd"));
        assertEquals("ghp-neu", Leser01385.updateToken(root));
    }

    @Test
    public void ohneGeraeteIdBleibtAllesWie01385() throws Exception {
        JSONObject root = alteConfig();
        String vorher = root.toString();
        for (String[] f : ZugangsMigration.CONFIG_FELDER) {
            JSONObject sec = root.optJSONObject(f[0]);
            if (sec == null) continue;   // 0.1.38.8: auth/hotspot gibt es in dieser alten Config nicht
            String neu = ZugangsMigration.schatten(sec.optString(f[1], ""), "", v -> v, DEC);   // encrypt() fail-open
            if (neu != null) sec.put(ZugangsMigration.schattenSchluessel(f[1]), neu);
        }
        assertEquals(vorher, root.toString());
        assertNotNull(Leser01385.mitglied(root));
    }
}
