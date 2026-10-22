package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;

/** WALLBOX_VERTRAG §4b Tuya: Prüfvektoren aus tinytuya 1.20 (MIT, Black-Box-Orakel), LAN 3.3/3.4/3.5, Cloud, Einwilligung. */
public class WallboxTuyaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final byte[] K = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());
    File konfig, protokoll;

    @Before
    public void auf() {
        konfig = new File(tmp.getRoot(), "wallbox.json");
        protokoll = new File(tmp.getRoot(), "p.jsonl");
    }

    // ------------------------------------------------------------------ Prüfvektoren (tinytuya als Orakel)

    @Test
    public void vektoren_rahmen55aaCrcUndHmac() throws Exception {
        byte[] crc = hex("000055aa000000010000000a0000000b616263ccc1bab90000aa55");
        assertArrayEquals(crc, TuyaProtokoll.rahmen55aa(1, 10, "abc".getBytes(StandardCharsets.UTF_8), null));
        TuyaProtokoll.Rahmen r = TuyaProtokoll.lies55aa(crc, null);
        assertEquals(10, r.cmd);
        assertEquals("abc", new String(r.daten, StandardCharsets.UTF_8));
        byte[] h = hex("000055aa000000020000000d00000027616263eed8470ebb325a1f9c5e95ba65ab9272581c0f558a9b5f6f0c90a4e3f2de4af40000aa55");
        assertArrayEquals(h, TuyaProtokoll.rahmen55aa(2, 13, "abc".getBytes(StandardCharsets.UTF_8), K));
        assertEquals("abc", new String(TuyaProtokoll.lies55aa(h, K).daten, StandardCharsets.UTF_8));
        // verfälscht → abgelehnt
        crc[17] ^= 1;
        WallboxTest.erwarte("tuya_pruefsumme", () -> TuyaProtokoll.lies55aa(crc, null));
        WallboxTest.erwarte("tuya_schluessel", () -> TuyaProtokoll.lies55aa(h, "fedcba9876543210".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void vektoren_rahmen6699Gcm() throws Exception {
        byte[] f = hex("000066990000000000030000000d00000021313233343536373839303132ae342e99847cfb2c7cafba5e6b4b45bb2c23eda29200009966");
        assertArrayEquals(f, TuyaProtokoll.rahmen6699(3, 13, "hello".getBytes(StandardCharsets.UTF_8), K,
                "123456789012".getBytes(StandardCharsets.UTF_8)));
        TuyaProtokoll.Rahmen r = TuyaProtokoll.lies6699(f, K);
        assertEquals(13, r.cmd);
        assertEquals("hello", new String(r.daten, StandardCharsets.UTF_8));
        f[25] ^= 1;
        WallboxTest.erwarte("tuya_schluessel", () -> TuyaProtokoll.lies6699(f, K));
    }

    @Test
    public void vektoren_sitzungsschluesselUndEcb() throws Exception {
        byte[] lok = new byte[16], fern = new byte[16];
        for (int i = 0; i < 16; i++) { lok[i] = (byte) i; fern[i] = (byte) (100 + i); }
        assertArrayEquals(hex("66c0d72e3ec70800f70b18898e76c684"), TuyaProtokoll.sitzungsSchluessel("3.4", K, lok, fern));
        assertArrayEquals(hex("995ba0797218575a8c42637ebce7cc51"), TuyaProtokoll.sitzungsSchluessel("3.5", K, lok, fern));
        assertArrayEquals(hex("c27ee03f8be481e63320de7dc6eb429664215f365b0dad6b2ac8fab15b1d40bd"),
                TuyaProtokoll.ecb(Cipher.ENCRYPT_MODE, K, "{\"dps\":{\"1\":true}}".getBytes(StandardCharsets.UTF_8), true));
    }

    @Test
    public void vektoren_cloudSignatur() {
        assertEquals("C85BFDCBCA73B5BE03A9558E8055CAF5C0B715DC9CD05EE71E18C9A608892E3E",
                TuyaCloud.signatur("cid0123", "sec4567", "", "1761100000123", "GET", "/v1.0/token?grant_type=1", null));
        assertEquals("1884B1CF0DDC356677B4295B883092902703270DB6FED479215468506F7C6579",
                TuyaCloud.signatur("cid0123", "sec4567", "tok123", "1761100000123", "GET", "/v1.0/iot-03/devices/dev89/status", null));
        assertEquals("E618B61FA4E4332DCF7D00E825D991C7131A59A23FD1DF8BCAF059621BD40654",
                TuyaCloud.signatur("cid0123", "sec4567", "tok123", "1761100000123", "POST", "/v1.0/iot-03/devices/dev89/commands",
                        "{\"commands\":[{\"code\":\"switch\",\"value\":true}]}"));
    }

    // ------------------------------------------------------------------ LAN gegen Attrappe

    @Test
    public void lan_alleVersionen_lesenUndSetzen() throws Exception {
        for (String v : new String[]{"3.3", "3.4", "3.5"}) {
            try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe(v)) {
                TuyaLan lan = new TuyaLan("192.168.1.50", TuyaGeraetAttrappe.ID, K, v, g.verbinder());
                assertEquals(v, false, lan.dps().getBoolean("1"));
                lan.setze(new JSONObject().put("4", 10).put("1", true));
                JSONObject d = lan.dps();
                assertTrue(v, d.getBoolean("1"));
                assertEquals(v, 10, d.getInt("4"));
                assertEquals(v, 2300, d.getInt("9"));
                if (!v.equals("3.3")) assertEquals(v, TuyaProtokoll.SESS_START, (int) g.befehle.get(0));
            }
        }
    }

    @Test
    public void lan_falscherSchluessel_34() throws Exception {
        try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe("3.4")) {
            g.falscherSchluessel = "ffffffffffffffff".getBytes(StandardCharsets.UTF_8);
            TuyaLan lan = new TuyaLan("192.168.1.50", TuyaGeraetAttrappe.ID, K, "3.4", g.verbinder());
            WallboxTest.erwarte("tuya_keine_antwort", lan::dps);   // Gerät verwirft den Rahmen und legt auf
        }
    }

    // ------------------------------------------------------------------ Dienst: LAN

    static JSONObject zuordnung() throws Exception {
        return new JSONObject()
                .put("schalter", new JSONObject().put("dp", "1").put("code", "switch"))
                .put("strom", new JSONObject().put("dp", "4").put("code", "charge_cur_set").put("min", 6).put("max", 16).put("faktor", 1))
                .put("status", new JSONObject().put("dp", "3").put("code", "work_state").put("laedt", new JSONArray().put("charging")))
                .put("leistung", new JSONObject().put("dp", "9").put("code", "power").put("faktorW", 1));
    }

    WallboxDienst dienst(TuyaGeraetAttrappe g, WallboxHttp http, JSONObject tabelle) {
        return new WallboxDienst(konfig, protokoll, WallboxTest.GEHEIM, http, WallboxTest.DNS, () -> "steht",
                jetzt::get, ms -> { }, g == null ? TuyaLan.TCP : g.verbinder(), () -> tabelle);
    }

    static JSONObject lanKonfig(String version, JSONObject zu, String modell, String localKey) throws Exception {
        JSONObject geraet = new JSONObject().put("schluessel", "garage").put("titel", "Garage").put("geraeteId", TuyaGeraetAttrappe.ID)
                .put("ip", "192.168.1.50").put("version", version).put("zuordnung", zu == null ? JSONObject.NULL : zu)
                .put("modell", modell == null ? JSONObject.NULL : modell);
        if (localKey != null) geraet.put("localKey", localKey);
        return new JSONObject().put("quelle", "tuya").put("tuya", new JSONObject().put("zugang", "lan")
                .put("geraete", new JSONArray().put(geraet)));
    }

    @Test
    public void dienst_unbekanntesModell_nurLesen_rohDps() throws Exception {
        try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe("3.4")) {
            WallboxDienst d = dienst(g, null, null);
            assertEquals(200, d.speichereEinstellungen(lanKonfig("3.4", null, null, TuyaGeraetAttrappe.SCHLUESSEL)).status);
            JSONObject s = d.status().json;
            assertTrue(s.toString(), s.getBoolean("erreichbar"));
            JSONObject lp = s.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("tuya:garage", lp.getString("id"));
            assertEquals("unbekannt", lp.getString("zuordnung"));
            assertEquals(16, lp.getJSONObject("rohDps").getInt("4"));
            assertEquals(0, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
            WallboxDienst.Ergebnis r = d.freigabe(new JSONObject().put("ladepunkt", "tuya:garage").put("an", true), "t");
            assertEquals(400, r.status);
            assertEquals("tuya_zuordnung_fehlt", r.json.getString("grund"));
            assertFalse(g.befehle.contains(TuyaProtokoll.CONTROL_NEW));
        }
    }

    @Test
    public void dienst_mitZuordnung_steuernZurueckgelesen_alleVersionen() throws Exception {
        for (String v : new String[]{"3.3", "3.4", "3.5"}) {
            konfig.delete();
            try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe(v)) {
                WallboxDienst d = dienst(g, null, null);
                assertEquals(200, d.speichereEinstellungen(lanKonfig(v, zuordnung(), null, TuyaGeraetAttrappe.SCHLUESSEL)).status);
                JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
                assertEquals("aus", lp.getString("modus"));
                assertEquals(16, lp.getInt("stromA"));
                assertFalse(lp.getBoolean("laedt"));
                assertEquals(JSONObject.NULL, lp.get("rohDps"));
                gibFrei(d, "tuya:garage");
                JSONObject r = fuehreAus(d, "tuya:garage", "strom", 10);
                assertTrue(v + r, r.getBoolean("uebernommen"));
                r = fuehreAus(d, "tuya:garage", "modus", "sofort");
                assertTrue(v + r, r.getBoolean("uebernommen"));
                assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
                assertEquals(2300, r.getJSONObject("ladepunkt").getLong("leistungW"));
                assertEquals(400, status(d, "tuya:garage", "strom", 20));
                assertEquals(400, status(d, "tuya:garage", "strom", 5));
                assertEquals(400, status(d, "tuya:garage", "ziel", 80));
                assertEquals(400, status(d, "tuya:garage", "modus", "pv"));
            }
        }
    }

    @Test
    public void dienst_modellAusTabelle() throws Exception {
        JSONObject tabelle = new JSONObject().put("version", 1).put("modelle", new JSONArray().put(
                new JSONObject().put("id", "test-wb").put("name", "Test").put("dp", zuordnung())));
        try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe("3.3")) {
            WallboxDienst d = dienst(g, null, tabelle);
            d.speichereEinstellungen(lanKonfig("3.3", null, "test-wb", TuyaGeraetAttrappe.SCHLUESSEL));
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("bekannt", lp.getString("zuordnung"));
            assertEquals(2, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
        }
    }

    @Test
    public void dienst_lanAdresseNurPrivat_localKeyGeschuetzt() throws Exception {
        WallboxDienst d = dienst(null, null, null);
        String lk = "Zx9YtQ2wLm4Np8Rs";
        JSONObject k = lanKonfig("3.3", zuordnung(), null, lk);
        k.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).put("ip", "8.8.8.8");
        assertEquals("adresse_oeffentlich", d.speichereEinstellungen(k).json.getString("grund"));
        k.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).put("ip", "127.0.0.1");
        assertEquals("adresse_gesperrt", d.speichereEinstellungen(k).json.getString("grund"));
        k.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).put("ip", "haus.tail1234.ts.net");
        assertEquals(200, d.speichereEinstellungen(k).status);
        String platte = new String(Files.readAllBytes(konfig.toPath()), StandardCharsets.UTF_8);
        assertFalse(platte.contains(lk));
        assertTrue(platte.contains("ENC:"));
        JSONObject oeff = d.einstellungen().json;
        assertFalse(oeff.toString().contains(lk));
        assertFalse(oeff.toString().contains("ENC:"));
        assertTrue(oeff.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).getBoolean("localKeyGesetzt"));
        // gleiche Geräte-ID, Feld fehlt → bleibt
        JSONObject ohneSchluessel = lanKonfig("3.3", zuordnung(), null, null);
        ohneSchluessel.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).put("ip", "haus.tail1234.ts.net");
        assertEquals(200, d.speichereEinstellungen(ohneSchluessel).status);
        assertTrue(d.einstellungen().json.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).getBoolean("localKeyGesetzt"));
        // andere Geräte-ID → verworfen
        JSONObject anders = lanKonfig("3.3", zuordnung(), null, null);
        anders.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).put("geraeteId", "bfANDERS1234567");
        d.speichereEinstellungen(anders);
        assertFalse(d.einstellungen().json.getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).getBoolean("localKeyGesetzt"));
        assertEquals("tuya_schluessel_fehlt", d.status().json.getString("fehler"));
        // falsches Format
        assertEquals("tuya_schluessel_format", d.speichereEinstellungen(lanKonfig("3.3", null, null, "zu-kurz")).json.getString("grund"));
        // Zuordnung geprüft
        JSONObject boese = zuordnung();
        boese.getJSONObject("strom").put("max", 500);
        assertEquals(400, d.speichereEinstellungen(lanKonfig("3.3", boese, null, TuyaGeraetAttrappe.SCHLUESSEL)).status);
    }

    // ------------------------------------------------------------------ Cloud

    /** Tuya-OpenAPI-Attrappe: prüft Signatur, schickt nie ein Geheimnis. */
    static final class CloudAttrappe implements WallboxHttp {
        final List<String> anfragen = new ArrayList<>();
        final JSONObject werte = new JSONObject();

        CloudAttrappe() throws Exception {
            werte.put("switch", false).put("charge_cur_set", 16).put("work_state", "connected").put("power", 0);
        }

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            try {
                anfragen.add(m + " " + url);
                if (kopf.containsKey("secret") || String.valueOf(rumpf).contains("sec4567")) return new Antwort(400, "secret leak");
                String pfad = url.substring(url.indexOf("/v1.0"));
                String tok = kopf.getOrDefault("access_token", "");
                String soll = TuyaCloud.signatur("cid0123abc", "sec4567", tok, kopf.get("t"), m, pfad, rumpf);
                if (!soll.equals(kopf.get("sign"))) return new Antwort(200, "{\"success\":false,\"code\":1004}");
                if (pfad.startsWith("/v1.0/token")) {
                    return new Antwort(200, "{\"success\":true,\"result\":{\"access_token\":\"tok123\",\"expire_time\":7200}}");
                }
                if (!"tok123".equals(tok)) return new Antwort(200, "{\"success\":false,\"code\":1010}");
                if (m.equals("GET") && pfad.endsWith("/status")) {
                    JSONArray a = new JSONArray();
                    for (java.util.Iterator<String> it = werte.keys(); it.hasNext(); ) {
                        String k = it.next();
                        a.put(new JSONObject().put("code", k).put("value", werte.get(k)));
                    }
                    return new Antwort(200, new JSONObject().put("success", true).put("result", a).toString());
                }
                if (m.equals("POST") && pfad.endsWith("/commands")) {
                    JSONArray c = new JSONObject(rumpf).getJSONArray("commands");
                    for (int i = 0; i < c.length(); i++) werte.put(c.getJSONObject(i).getString("code"), c.getJSONObject(i).get("value"));
                    boolean an = werte.getBoolean("switch");
                    werte.put("work_state", an ? "charging" : "connected").put("power", an ? werte.getInt("charge_cur_set") * 230 : 0);
                    return new Antwort(200, "{\"success\":true,\"result\":true}");
                }
                return new Antwort(404, "");
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
        }
    }

    static JSONObject cloudKonfig(boolean einwilligung, String secret) throws Exception {
        JSONObject c = new JSONObject().put("region", "eu").put("clientId", "cid0123abc").put("einwilligung", einwilligung);
        if (secret != null) c.put("secret", secret);
        JSONObject geraet = new JSONObject().put("schluessel", "garage").put("titel", "Garage").put("geraeteId", "dev89abc")
                .put("zuordnung", zuordnung());
        return new JSONObject().put("quelle", "tuya").put("tuya", new JSONObject().put("zugang", "cloud").put("cloud", c)
                .put("geraete", new JSONArray().put(geraet)));
    }

    @Test
    public void cloud_ohneEinwilligung_keinEinzigerAbruf() throws Exception {
        CloudAttrappe c = new CloudAttrappe();
        WallboxDienst d = dienst(null, c, null);
        assertEquals(200, d.speichereEinstellungen(cloudKonfig(false, "sec4567")).status);
        JSONObject s = d.status().json;
        assertEquals("einwilligung_fehlt", s.getString("fehler"));
        assertEquals(409, d.freigabe(new JSONObject().put("ladepunkt", "tuya:garage").put("an", true), "t").status);
        assertEquals(200, d.test(cloudKonfig(false, null)).status);
        assertTrue(c.anfragen.toString(), c.anfragen.isEmpty());
        assertEquals(JSONObject.NULL, d.einstellungen().json.getJSONObject("tuya").getJSONObject("cloud").get("einwilligungZeit"));
    }

    @Test
    public void cloud_mitEinwilligung_signiert_steuern() throws Exception {
        CloudAttrappe c = new CloudAttrappe();
        WallboxDienst d = dienst(null, c, null);
        assertEquals(200, d.speichereEinstellungen(cloudKonfig(true, "sec4567")).status);
        JSONObject cl = d.einstellungen().json.getJSONObject("tuya").getJSONObject("cloud");
        assertEquals(jetzt.get(), cl.getLong("einwilligungZeit"));
        assertTrue(cl.getBoolean("secretGesetzt"));
        assertFalse(cl.toString().contains("sec4567"));
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        gibFrei(d, "tuya:garage");
        assertTrue(fuehreAus(d, "tuya:garage", "modus", "sofort").getBoolean("uebernommen"));
        assertTrue(fuehreAus(d, "tuya:garage", "strom", 8).getBoolean("uebernommen"));
        for (String a : c.anfragen) assertTrue(a, a.startsWith("GET https://openapi.tuyaeu.com/") || a.startsWith("POST https://openapi.tuyaeu.com/"));
        // Einwilligung zurück → sofort nichts mehr
        int vorher = c.anfragen.size();
        d.speichereEinstellungen(cloudKonfig(false, null));
        assertEquals("einwilligung_fehlt", d.status().json.getString("fehler"));
        assertEquals(vorher, c.anfragen.size());
        // Client-ID gewechselt → Geheimnis weg
        JSONObject neu = cloudKonfig(true, null);
        neu.getJSONObject("tuya").getJSONObject("cloud").put("clientId", "anderer0123");
        d.speichereEinstellungen(neu);
        assertFalse(d.einstellungen().json.getJSONObject("tuya").getJSONObject("cloud").getBoolean("secretGesetzt"));
        assertEquals(0, d.einstellungen().json.getJSONArray("freigaben").length());
    }

    @Test
    public void cloud_regionNurAusListe() throws Exception {
        WallboxDienst d = dienst(null, new CloudAttrappe(), null);
        JSONObject k = cloudKonfig(true, "sec4567");
        k.getJSONObject("tuya").getJSONObject("cloud").put("region", "https://evil.example");
        assertEquals(400, d.speichereEinstellungen(k).status);
    }

    // ------------------------------------------------------------------ Hilfen

    static void gibFrei(WallboxDienst d, String id) throws Exception {
        WallboxDienst.Ergebnis r = d.freigabe(new JSONObject().put("ladepunkt", id).put("an", true), "t");
        assertEquals(r.json.toString(), 409, r.status);
        r = d.freigabe(new JSONObject().put("ladepunkt", id).put("an", true).put("bestaetigung", r.json.getString("bestaetigung")), "t");
        assertEquals(r.json.toString(), 200, r.status);
    }

    static JSONObject fuehreAus(WallboxDienst d, String id, String aktion, Object wert) throws Exception {
        WallboxDienst.Ergebnis f = d.steuern(WallboxTest.steuer(id, aktion, wert, null), "t");
        assertEquals(f.json.toString(), 409, f.status);
        WallboxDienst.Ergebnis r = d.steuern(WallboxTest.steuer(id, aktion, wert, f.json.getString("bestaetigung")), "t");
        assertEquals(r.json.toString(), 200, r.status);
        return r.json;
    }

    static int status(WallboxDienst d, String id, String aktion, Object wert) throws Exception {
        WallboxDienst.Ergebnis f = d.steuern(WallboxTest.steuer(id, aktion, wert, null), "t");
        if (f.status != 409) return f.status;
        return d.steuern(WallboxTest.steuer(id, aktion, wert, f.json.getString("bestaetigung")), "t").status;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
