package at.greenchili.surfmate.wallbox;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 0.1.20 „Wallbox für alle“: go-e Cloud (Token per Handy), Wallbox Pulsar, Tuya D′, Rückfallkette (WALLBOX_FERN_KONZEPT). */
public class WallboxFuerAlleTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-28T10:00:00Z").toEpochMilli());
    static final String TOKEN = "goetoken" + "x".repeat(24);

    @Before
    public void auf() {
        PulsarAdapter.TOKENS.clear();
    }

    WallboxDienst dienst(WallboxHttp h) {
        return new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, h,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { });
    }

    String platte() throws Exception {
        return new String(Files.readAllBytes(new File(tmp.getRoot(), "wb.json").toPath()), StandardCharsets.UTF_8);
    }

    /** go-e API v2 – lokal (http://192.168.1.30) oder Cloud (https://012345.api.v3.go-e.io mit Bearer-Token). */
    static final class Goe implements WallboxHttp {
        final List<String> anfragen = Collections.synchronizedList(new ArrayList<>());
        final String basis;
        final String token;
        boolean erreichbar = true;
        int frc = 1, amp = 16;

        Goe(String basis, String token) {
            this.basis = basis;
            this.token = token;
        }

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) throws WallboxFehler {
            anfragen.add(m + " " + url);
            if (!erreichbar) throw new WallboxFehler("nicht_erreichbar", 502);
            if (token != null && !("Bearer " + token).equals(kopf.get("Authorization"))) return new Antwort(401, "{}");
            String p = url.substring(basis.length());
            try {
                if (p.startsWith("/api/status")) {
                    JSONArray nrg = new JSONArray();
                    for (int i = 0; i < 16; i++) nrg.put(i == 11 ? (frc == 2 ? 3680 : 0) : 0);
                    return new Antwort(200, new JSONObject().put("alw", frc == 2).put("car", frc == 2 ? 2 : 3).put("amp", amp)
                            .put("frc", frc).put("nrg", nrg).toString());
                }
                if (p.startsWith("/api/set?frc=")) {
                    frc = Integer.parseInt(p.substring("/api/set?frc=".length()));
                    return new Antwort(200, "{\"frc\":true}");
                }
                if (p.startsWith("/api/set?amp=")) {
                    amp = Integer.parseInt(p.substring("/api/set?amp=".length()));
                    return new Antwort(200, "{\"amp\":true}");
                }
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
            return new Antwort(404, "");
        }
    }

    static JSONObject goeCloud(String s, boolean einw) throws Exception {
        return new JSONObject().put("schluessel", s).put("titel", "go-e Cloud").put("adapter", "goecloud").put("geraeteId", "012345")
                .put("einwilligung", einw);
    }

    @Test
    public void goeCloud_tokenPerHandy_einmalig_nieAufDerPlatte() throws Exception {
        Goe g = new Goe("https://012345.api.v3.go-e.io", TOKEN);
        WallboxDienst d = dienst(g);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(goeCloud("cloud", true))).status);
        // ohne Token: Anmeldung fehlt, keine einzige Anfrage
        JSONObject s = d.status().json;
        assertFalse(s.getBoolean("erreichbar"));
        assertEquals("anmeldung", s.getString("fehler"));
        assertTrue(g.anfragen.isEmpty());
        // nur für ein go-e-Cloud-Gerät
        assertEquals(404, d.eingabeStart(new JSONObject().put("schluessel", "anders")).status);
        WallboxDienst.Ergebnis st = d.eingabeStart(new JSONObject().put("schluessel", "cloud"));
        String k = st.json.getString("kennung");
        assertEquals(32, k.length());
        assertEquals("offen", d.eingabeStatus(k).json.getString("zustand"));
        assertEquals(410, d.eingabe(new JSONObject().put("kennung", "f".repeat(32)).put("token", TOKEN)).status);
        assertEquals(400, d.eingabe(new JSONObject().put("kennung", k).put("token", "zu kurz")).status);
        assertEquals(200, d.eingabe(new JSONObject().put("kennung", k).put("token", TOKEN)).status);
        assertEquals("empfangen", d.eingabeStatus(k).json.getString("zustand"));
        assertEquals(410, d.eingabe(new JSONObject().put("kennung", k).put("token", TOKEN)).status);   // nur einmal
        assertFalse(platte().contains(TOKEN));
        assertTrue(d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).getBoolean("passwortGesetzt"));
        s = d.status().json;
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        JSONObject lp = s.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        WallboxTuyaTest.gibFrei(d, "direkt:cloud");
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:cloud", "modus", "sofort").getBoolean("uebernommen"));
        for (String a : g.anfragen) assertFalse(a, a.contains(TOKEN));   // Token nur im Kopf, nie in der URL
        // abgelaufen
        String k2 = d.eingabeStart(new JSONObject().put("schluessel", "cloud")).json.getString("kennung");
        jetzt.addAndGet(WallboxDienst.EINGABE_MS + 1);
        assertEquals("abgelaufen", d.eingabeStatus(k2).json.getString("zustand"));
        assertEquals(410, d.eingabe(new JSONObject().put("kennung", k2).put("token", TOKEN)).status);
    }

    @Test
    public void eingabe_ratenbegrenzt() throws Exception {
        WallboxDienst d = dienst(new Goe("https://012345.api.v3.go-e.io", TOKEN));
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(goeCloud("cloud", true))).status);
        String k = d.eingabeStart(new JSONObject().put("schluessel", "cloud")).json.getString("kennung");
        for (int i = 0; i < WallboxDienst.EINGABE_FEHLER_MAX; i++) {
            assertEquals(410, d.eingabe(new JSONObject().put("kennung", "0".repeat(32)).put("token", TOKEN)).status);
        }
        assertEquals(429, d.eingabe(new JSONObject().put("kennung", k).put("token", TOKEN)).status);
    }

    @Test
    public void goeCloud_ohneEinwilligung_keineAnfrage() throws Exception {
        Goe g = new Goe("https://012345.api.v3.go-e.io", TOKEN);
        WallboxDienst d = dienst(g);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(goeCloud("cloud", false).put("passwort", TOKEN))).status);
        assertEquals("einwilligung_fehlt", d.status().json.getJSONArray("ladepunkte").getJSONObject(0).getString("fehler"));
        assertTrue(g.anfragen.isEmpty());
    }

    // ------------------------------------------------------------------ Pulsar

    static final class Pulsar implements WallboxHttp {
        final List<String> anfragen = Collections.synchronizedList(new ArrayList<>());
        int status = 182, strom = 16, logins;

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            anfragen.add(m + " " + url + (rumpf == null ? "" : " " + rumpf));
            try {
                if (url.equals("https://user-api.wall-box.com/users/signin")) {
                    logins++;
                    String erwartet = "Basic " + java.util.Base64.getEncoder().encodeToString("w@b.at:pw-wb".getBytes(StandardCharsets.UTF_8));
                    if (!erwartet.equals(kopf.get("Authorization")) || !"wallbox".equals(kopf.get("Partner"))) return new Antwort(401, "{}");
                    return new Antwort(200, new JSONObject().put("data", new JSONObject().put("attributes",
                            new JSONObject().put("token", "jwtP").put("ttl", 0))).toString());
                }
                if (!"Bearer jwtP".equals(kopf.get("Authorization"))) return new Antwort(401, "{}");
                if (url.equals("https://api.wall-box.com/chargers/status/12345")) {
                    return new Antwort(200, new JSONObject().put("status_id", status).put("charging_power", status == 194 ? 7.4 : 0)
                            .put("config_data", new JSONObject().put("max_charging_current", strom)).put("max_available_current", 32).toString());
                }
                if (url.equals("https://api.wall-box.com/v3/chargers/12345/remote-action")) {
                    status = new JSONObject(rumpf).getInt("action") == 1 ? 194 : 182;
                    return new Antwort(200, "{}");
                }
                if (url.equals("https://api.wall-box.com/v2/charger/12345") && "PUT".equals(m)) {
                    strom = new JSONObject(rumpf).getInt("maxChargingCurrent");
                    return new Antwort(200, "{}");
                }
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
            return new Antwort(404, "");
        }
    }

    @Test
    public void pulsar_lesenStartStoppStrom() throws Exception {
        Pulsar p = new Pulsar();
        WallboxDienst d = dienst(p);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(new JSONObject().put("schluessel", "pulsar").put("titel", "Pulsar")
                .put("adapter", "pulsar").put("geraeteId", "12345").put("benutzer", "w@b.at").put("passwort", "pw-wb").put("einwilligung", true))).status);
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        assertTrue(lp.getBoolean("verbunden"));
        assertEquals(16, lp.getInt("stromA"));
        WallboxTuyaTest.gibFrei(d, "direkt:pulsar");
        JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:pulsar", "modus", "sofort");
        assertTrue(r.getBoolean("uebernommen"));
        assertEquals(7400, r.getJSONObject("ladepunkt").getLong("leistungW"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:pulsar", "strom", 10).getBoolean("uebernommen"));
        assertTrue(p.anfragen.contains("PUT https://api.wall-box.com/v2/charger/12345 {\"maxChargingCurrent\":10}"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:pulsar", "modus", "aus").getBoolean("uebernommen"));
        assertEquals(1, p.logins);
        assertFalse(platte().contains("pw-wb"));
    }

    // ------------------------------------------------------------------ Tuya D′

    static final String LOCAL_KEY = "lokalkey" + "k".repeat(8);

    @Test
    public void tuya_schluesselEinmalHolen_dannLokal() throws Exception {
        List<String> anfragen = new ArrayList<>();
        WallboxHttp tuya = (m, url, k, r) -> {
            anfragen.add(m + " " + url);
            try {
                if (url.endsWith("/v1.0/token?grant_type=1")) {
                    return new WallboxHttp.Antwort(200, new JSONObject().put("success", true)
                            .put("result", new JSONObject().put("access_token", "atok").put("expire_time", 7200)).toString());
                }
                if (url.endsWith("/v1.0/devices/bf4839172db96045cdaeg0")) {
                    return new WallboxHttp.Antwort(200, new JSONObject().put("success", true)
                            .put("result", new JSONObject().put("local_key", LOCAL_KEY).put("ip", "203.0.113.5")).toString());
                }
            } catch (Exception e) {
                return new WallboxHttp.Antwort(500, e.toString());
            }
            return new WallboxHttp.Antwort(404, "");
        };
        WallboxDienst d = dienst(tuya);
        JSONObject t = new JSONObject().put("zugang", "cloud")
                .put("cloud", new JSONObject().put("region", "eu").put("clientId", "abcdefgh12").put("secret", "s".repeat(32)).put("einwilligung", false))
                .put("geraete", new JSONArray().put(new JSONObject().put("schluessel", "wb").put("titel", "Wallbox")
                        .put("geraeteId", "bf4839172db96045cdaeg0").put("ip", "192.168.1.60").put("version", "3.5")));
        assertEquals(200, d.speichereEinstellungen(new JSONObject().put("quelle", "tuya").put("tuya", t)).status);
        // ohne Einwilligung keine Anfrage
        assertEquals(409, d.tuyaSchluesselHolen(new JSONObject().put("schluessel", "wb")).status);
        assertTrue(anfragen.isEmpty());
        t.getJSONObject("cloud").put("einwilligung", true).remove("secret");
        assertEquals(200, d.speichereEinstellungen(new JSONObject().put("quelle", "tuya").put("tuya", t)).status);
        assertEquals(404, d.tuyaSchluesselHolen(new JSONObject().put("schluessel", "gibtsnicht")).status);
        WallboxDienst.Ergebnis r = d.tuyaSchluesselHolen(new JSONObject().put("schluessel", "wb"));
        assertEquals(r.json.toString(), 200, r.status);
        assertTrue(r.json.getBoolean("lokal"));
        assertFalse(r.json.toString().contains(LOCAL_KEY));
        JSONObject e = d.einstellungen().json.getJSONObject("tuya");
        assertEquals("lan", e.getString("zugang"));
        assertTrue(e.getJSONArray("geraete").getJSONObject(0).getBoolean("localKeyGesetzt"));
        assertTrue(e.getJSONObject("cloud").getBoolean("secretGesetzt"));   // Cloud-Zugang bleibt (für später)
        assertFalse(platte().contains(LOCAL_KEY));
        assertEquals(LOCAL_KEY, WallboxTest.GEHEIM.ent(d.konfig().getJSONObject("tuya").getJSONArray("geraete").getJSONObject(0).getString("localKey")));
    }

    // ------------------------------------------------------------------ Rückfallkette

    JSONObject ketteKonfig() throws Exception {
        return new JSONObject().put("quelle", "kette")
                .put("direkt", new JSONObject().put("geraete", new JSONArray()
                        .put(WallboxDirektTest.geraet("lokal", "goe", "http://192.168.1.30"))
                        .put(goeCloud("cloud", true).put("passwort", TOKEN))))
                .put("kette", new JSONObject().put("titel", "Garage").put("wege", new JSONArray()
                        .put(new JSONObject().put("ladepunkt", "direkt:lokal")).put(new JSONObject().put("ladepunkt", "direkt:cloud"))));
    }

    @Test
    public void kette_ersterErreichbarerWeg_genauEineQuelleJeBefehl() throws Exception {
        Goe lokal = new Goe("http://192.168.1.30", null);
        Goe cloud = new Goe("https://012345.api.v3.go-e.io", TOKEN);
        WallboxHttp weiche = (m, url, k, r) -> url.startsWith(cloud.basis) ? cloud.anfrage(m, url, k, r) : lokal.anfrage(m, url, k, r);
        WallboxDienst d = dienst(weiche);
        WallboxDienst.Ergebnis gs = d.speichereEinstellungen(ketteKonfig());
        assertEquals(gs.json.toString(), 200, gs.status);

        // zu Hause: lokal gilt, Cloud wird gar nicht gefragt
        JSONObject s = d.status().json;
        JSONArray lps = s.getJSONArray("ladepunkte");
        assertEquals(1, lps.length());
        JSONObject lp = lps.getJSONObject(0);
        assertEquals(WallboxKonfig.KETTE_ID, lp.getString("id"));
        assertEquals("Garage", lp.getString("titel"));
        assertEquals("wlan", lp.getJSONObject("weg").getString("art"));
        assertEquals(JSONObject.NULL, lp.getJSONArray("wege").getJSONObject(1).get("ok"));
        assertTrue(cloud.anfragen.isEmpty());

        WallboxTuyaTest.gibFrei(d, WallboxKonfig.KETTE_ID);
        // unterwegs: lokal nicht erreichbar → Cloud; Befehl geht NUR über die Cloud
        lokal.erreichbar = false;
        int vorher = lokal.anfragen.size();
        JSONObject r = WallboxTuyaTest.fuehreAus(d, WallboxKonfig.KETTE_ID, "modus", "sofort");
        assertTrue(r.toString(), r.getBoolean("uebernommen"));
        assertEquals("cloud", r.getJSONObject("ladepunkt").getJSONObject("weg").getString("art"));
        assertEquals(2, cloud.frc);
        assertEquals(1, lokal.frc);
        for (String a : lokal.anfragen.subList(vorher, lokal.anfragen.size())) assertFalse(a, a.contains("/api/set"));

        // kein Weg erreichbar → ehrliche Anzeige
        cloud.erreichbar = false;
        lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("kein_weg", lp.getString("fehler"));
        assertEquals("nicht_erreichbar", lp.getJSONArray("wege").getJSONObject(1).getString("fehler"));
        assertTrue(WallboxTuyaTest.status(d, WallboxKonfig.KETTE_ID, "modus", "aus") >= 400);
    }

    @Test
    public void kette_pruefungUndFreigabeBeiAenderungWeg() throws Exception {
        WallboxDienst d = dienst(new Goe("http://192.168.1.30", null));
        JSONObject k = ketteKonfig();
        k.getJSONObject("kette").put("wege", new JSONArray().put(new JSONObject().put("ladepunkt", "direkt:lokal")));
        assertEquals(400, d.speichereEinstellungen(k).status);   // Kette braucht mindestens 2 Wege
        k = ketteKonfig();
        k.getJSONObject("kette").getJSONArray("wege").put(new JSONObject().put("ladepunkt", "direkt:gibtsnicht"));
        assertEquals(400, d.speichereEinstellungen(k).status);
        k = ketteKonfig();
        k.getJSONObject("kette").getJSONArray("wege").put(new JSONObject().put("ladepunkt", "evcc:1"));
        assertEquals(400, d.speichereEinstellungen(k).status);
        assertEquals(200, d.speichereEinstellungen(ketteKonfig()).status);
        WallboxTuyaTest.gibFrei(d, WallboxKonfig.KETTE_ID);
        // gleiche Kette noch einmal gespeichert → Freigabe bleibt
        assertEquals(200, d.speichereEinstellungen(ketteKonfig()).status);
        assertTrue(WallboxKonfig.freigegeben(d.konfig(), WallboxKonfig.KETTE_ID));
        // Adresse eines Wegs geändert → Freigabe weg
        JSONObject k2 = ketteKonfig();
        k2.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).put("adresse", "http://192.168.1.31");
        assertEquals(200, d.speichereEinstellungen(k2).status);
        assertFalse(WallboxKonfig.freigegeben(d.konfig(), WallboxKonfig.KETTE_ID));
        // Reihenfolge geändert → Freigabe weg
        WallboxTuyaTest.gibFrei(d, WallboxKonfig.KETTE_ID);
        JSONObject k3 = ketteKonfig();
        k3.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).put("adresse", "http://192.168.1.31");
        JSONArray w = k3.getJSONObject("kette").getJSONArray("wege");
        k3.getJSONObject("kette").put("wege", new JSONArray().put(w.get(1)).put(w.get(0)));
        assertEquals(200, d.speichereEinstellungen(k3).status);
        assertFalse(WallboxKonfig.freigegeben(d.konfig(), WallboxKonfig.KETTE_ID));
    }

    @Test
    public void planWeg_nurModusUndStrom_nurFreigegeben_protokolliert() throws Exception {
        Goe g = new Goe("http://192.168.1.30", null);
        WallboxDienst d = dienst(g);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(WallboxDirektTest.geraet("garage", "goe", "http://192.168.1.30"))).status);
        assertEquals(403, d.steuernPlan("direkt:garage", "modus", "sofort").status);
        WallboxTuyaTest.gibFrei(d, "direkt:garage");
        assertEquals(400, d.steuernPlan("direkt:garage", "modus", "pv").status);
        assertEquals(400, d.steuernPlan("direkt:garage", "ziel", 80).status);
        WallboxDienst.Ergebnis r = d.steuernPlan("direkt:garage", "modus", "sofort");
        assertEquals(r.json.toString(), 200, r.status);
        assertTrue(r.json.getBoolean("uebernommen"));
        assertEquals(2, g.frc);
        JSONObject p = d.protokoll().json.getJSONArray("eintraege").getJSONObject(0);
        assertEquals(WallboxDienst.HERKUNFT_LADEPLAN, p.getString("herkunft"));
        assertEquals(0, d.letzteHandSteuerung("direkt:garage"));
        WallboxTuyaTest.fuehreAus(d, "direkt:garage", "modus", "aus");
        assertEquals(jetzt.get(), d.letzteHandSteuerung("direkt:garage"));
    }
}
