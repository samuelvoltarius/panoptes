package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** WALLBOX_VERTRAG v1: Adressen, evcc, Home Assistant, Freigabe, Bestätigung, Fahrt-Sperre, Geheimnisse, Protokoll. */
public class WallboxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Attrappe für CredentialCipher: erkennbar „verschlüsselt“, nie Klartext. */
    static final WallboxKonfig.Geheim GEHEIM = new WallboxKonfig.Geheim() {
        @Override
        public String ver(String klar) {
            return "ENC:" + new StringBuilder(klar).reverse();
        }

        @Override
        public String ent(String g) {
            return g != null && g.startsWith("ENC:") ? new StringBuilder(g.substring(4)).reverse().toString() : "";
        }
    };

    static final WallboxAdresse.Aufloeser DNS = host -> {
        switch (host) {
            case "evcc.local": return new InetAddress[]{InetAddress.getByName("192.168.1.8")};
            case "haus.tail1234.ts.net": return new InetAddress[]{InetAddress.getByName("100.101.102.103")};
            case "gemischt.example": return new InetAddress[]{InetAddress.getByName("192.168.1.9"), InetAddress.getByName("8.8.8.8")};
            case "demo.evcc.io": return new InetAddress[]{InetAddress.getByName("185.199.108.153")};
            case "localhost": return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
            default: return InetAddress.getAllByName(host);   // nur IP-Literale in diesen Tests
        }
    };

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());
    final AtomicReference<String> fahrt = new AtomicReference<>("steht");
    EvccAttrappe evcc;
    HaAttrappe ha;
    WallboxHttp weiche;
    WallboxDienst dienst;
    File konfig, protokoll;

    @Before
    public void auf() throws Exception {
        evcc = new EvccAttrappe();
        ha = new HaAttrappe();
        weiche = (m, url, k, r) -> url.contains(":8123") ? ha.anfrage(m, url, k, r) : evcc.anfrage(m, url, k, r);
        konfig = new File(tmp.getRoot(), "wallbox.json");
        protokoll = new File(tmp.getRoot(), "wallbox_protokoll.jsonl");
        dienst = new WallboxDienst(konfig, protokoll, GEHEIM, weiche, DNS, fahrt::get, jetzt::get, ms -> { });
    }

    // ------------------------------------------------------------------ Adressen

    @Test
    public void adressen_privatVpnOeffentlichGesperrt() throws Exception {
        assertEquals("http://192.168.1.8:7070", WallboxAdresse.pruefe("http://192.168.1.8:7070/", false, DNS));
        assertEquals("http://evcc.local:7070", WallboxAdresse.pruefe("http://evcc.local:7070", false, DNS));
        assertEquals("https://haus.tail1234.ts.net", WallboxAdresse.pruefe("https://haus.tail1234.ts.net", false, DNS));
        assertEquals("http://10.0.0.5:8123/ha", WallboxAdresse.pruefe("http://10.0.0.5:8123/ha/", false, DNS));
        assertEquals("http://[fd7a:115c:a1e0::1]:7070", WallboxAdresse.pruefe("http://[fd7a:115c:a1e0::1]:7070", false, DNS));
        erwarte("adresse_oeffentlich", () -> WallboxAdresse.pruefe("https://demo.evcc.io", false, DNS));
        erwarte("adresse_oeffentlich", () -> WallboxAdresse.pruefe("http://demo.evcc.io", true, DNS));   // öffentlich nur https
        assertEquals("https://demo.evcc.io", WallboxAdresse.pruefe("https://demo.evcc.io", true, DNS));
        erwarte("adresse_oeffentlich", () -> WallboxAdresse.pruefe("http://gemischt.example", false, DNS));
        erwarte("adresse_gesperrt", () -> WallboxAdresse.pruefe("http://127.0.0.1:8180", true, DNS));
        erwarte("adresse_gesperrt", () -> WallboxAdresse.pruefe("http://localhost:8180", true, DNS));
        erwarte("adresse_gesperrt", () -> WallboxAdresse.pruefe("http://169.254.169.254", true, DNS));
        erwarte("adresse_gesperrt", () -> WallboxAdresse.pruefe("https://byd.serveone.at", true, DNS));
        erwarte("adresse_gesperrt", () -> WallboxAdresse.pruefe("https://x.SERVEONE.at.", true, DNS));
        erwarte("adresse_ungueltig", () -> WallboxAdresse.pruefe("http://user:pw@192.168.1.8", false, DNS));
        erwarte("adresse_ungueltig", () -> WallboxAdresse.pruefe("ftp://192.168.1.8", false, DNS));
        erwarte("adresse_ungueltig", () -> WallboxAdresse.pruefe("http://192.168.1.8/?x=1", false, DNS));
        erwarte("adresse_ungueltig", () -> WallboxAdresse.pruefe("", false, DNS));
    }

    // ------------------------------------------------------------------ Einrichtung, Status

    @Test
    public void standard_nichtsEingerichtet() throws Exception {
        JSONObject s = dienst.status().json;
        assertEquals(JSONObject.NULL, s.get("quelle"));
        assertFalse(s.getBoolean("eingerichtet"));
        assertEquals("nicht_eingerichtet", s.getString("fehler"));
        assertEquals(0, s.getJSONArray("ladepunkte").length());
        assertTrue(evcc.anfragen.isEmpty());
    }

    @Test
    public void evcc_statusNormalisiert_heizungNieSteuerbar() throws Exception {
        richteEvccEin();
        JSONObject s = dienst.status().json;
        assertTrue(s.getBoolean("erreichbar"));
        assertEquals("0.317.2", s.getString("evccVersion"));
        JSONArray lps = s.getJSONArray("ladepunkte");
        assertEquals(3, lps.length());
        JSONObject carport = lps.getJSONObject(0);
        assertEquals("evcc:1", carport.getString("id"));
        assertEquals("pv", carport.getString("modus"));            // smart + alwaysCharge off
        assertEquals("smart", carport.getString("modusRoh"));
        assertEquals(95, carport.getInt("zielSoc"));
        assertFalse(carport.getBoolean("steuernFrei"));
        assertEquals(JSONObject.NULL, carport.getJSONObject("faehigkeiten").get("plan"));   // kein Fahrzeug erkannt
        JSONObject garage = lps.getJSONObject(1);
        assertEquals("sofort", garage.getString("modus"));
        assertEquals(3680, garage.getLong("leistungW"));
        assertEquals("ziel_bis", garage.getJSONObject("faehigkeiten").getString("plan"));
        JSONObject wp = lps.getJSONObject(2);
        assertEquals("heizung", wp.getString("art"));
        assertEquals(JSONObject.NULL, wp.get("zielSoc"));          // 70 wäre eine Temperatur
        assertEquals(0, wp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
        for (int i = 0; i < lps.length(); i++) {
            for (java.util.Iterator<String> it = lps.getJSONObject(i).keys(); it.hasNext(); ) { String key = it.next(); assertFalse(key, key.startsWith("_")); }
        }
    }

    @Test
    public void modusVon_altUndNeu() throws Exception {
        assertEquals("pv", EvccQuelle.modusVon("smart", "off"));
        assertEquals("minpv", EvccQuelle.modusVon("smart", "on"));
        assertEquals("minpv", EvccQuelle.modusVon("smart", "once"));
        assertEquals("pv", EvccQuelle.modusVon("smart", null));
        assertEquals("minpv", EvccQuelle.modusVon("minpv", null));
        assertEquals("aus", EvccQuelle.modusVon("off", "on"));
        assertEquals("sofort", EvccQuelle.modusVon("now", null));
        assertEquals(null, EvccQuelle.modusVon("irgendwas", null));
    }

    // ------------------------------------------------------------------ Steuern evcc

    @Test
    public void steuern_ohneFreigabe403_undNichtsGesendet() throws Exception {
        richteEvccEin();
        WallboxDienst.Ergebnis r = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test");
        assertEquals(403, r.status);
        assertEquals("nicht_freigegeben", r.json.getString("grund"));
        assertTrue(nurLesend());
    }

    @Test
    public void freigabe_brauchtBestaetigung_undHeizungNie() throws Exception {
        richteEvccEin();
        WallboxDienst.Ergebnis r = dienst.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", true), "test");
        assertEquals(409, r.status);
        assertFalse(dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0).getBoolean("steuernFrei"));
        r = dienst.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", true)
                .put("bestaetigung", r.json.getString("bestaetigung")), "test");
        assertEquals(200, r.status);
        assertTrue(dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0).getBoolean("steuernFrei"));
        assertEquals(400, dienst.freigabe(new JSONObject().put("ladepunkt", "evcc:3").put("an", true), "test").status);
        // zurücknehmen sofort, auch während der Fahrt
        fahrt.set("faehrt");
        assertEquals(200, dienst.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", false), "test").status);
        assertFalse(dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0).getBoolean("steuernFrei"));
        assertTrue(nurLesend());
    }

    @Test
    public void steuern_zweistufig_kennungEinmalig_genauDieseAktion_30s() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        WallboxDienst.Ergebnis frage = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test");
        assertEquals(409, frage.status);
        assertEquals("bestaetigung_noetig", frage.json.getString("grund"));
        assertEquals("Carport", frage.json.getJSONObject("frage").getString("titel"));
        String k = frage.json.getString("bestaetigung");
        assertEquals(32, k.length());
        // andere Aktion mit dieser Kennung → neue Frage, nichts gesendet
        assertEquals(409, dienst.steuern(steuer("evcc:1", "modus", "aus", k), "test").status);
        assertTrue(nurLesend());
        // abgelaufen
        String k2 = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test").json.getString("bestaetigung");
        jetzt.addAndGet(31_000);
        assertEquals(409, dienst.steuern(steuer("evcc:1", "modus", "sofort", k2), "test").status);
        assertTrue(nurLesend());
        // richtig
        String k3 = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test").json.getString("bestaetigung");
        WallboxDienst.Ergebnis ok = dienst.steuern(steuer("evcc:1", "modus", "sofort", k3), "test");
        assertEquals(200, ok.status);
        assertTrue(ok.json.getBoolean("uebernommen"));
        assertEquals("sofort", ok.json.getJSONObject("ladepunkt").getString("modus"));
        assertTrue(evcc.anfragen.contains("POST /api/loadpoints/1/mode/now"));
        // dieselbe Kennung ein zweites Mal → wieder Frage
        assertEquals(409, dienst.steuern(steuer("evcc:1", "modus", "sofort", k3), "test").status);
    }

    @Test
    public void steuern_pvUndMinpv_neuesEvcc_setztAlwaysCharge() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        assertTrue(fuehreAus("evcc:1", "modus", "minpv").getBoolean("uebernommen"));
        assertEquals("on", evcc.lp(1).getString("alwaysCharge"));
        assertTrue(fuehreAus("evcc:1", "modus", "pv").getBoolean("uebernommen"));
        assertEquals("off", evcc.lp(1).getString("alwaysCharge"));
        assertEquals("smart", evcc.lp(1).getString("mode"));
        assertTrue(evcc.anfragen.contains("POST /api/loadpoints/1/alwayscharge/off"));
    }

    @Test
    public void steuern_altesEvcc_ohneAlwaysCharge() throws Exception {
        evcc.alteModi = true;
        richteEvccEin();
        gibFrei("evcc:1");
        assertTrue(fuehreAus("evcc:1", "modus", "minpv").getBoolean("uebernommen"));
        assertEquals("minpv", evcc.lp(1).getString("mode"));
        for (String a : evcc.anfragen) assertFalse(a, a.contains("alwayscharge"));
    }

    @Test
    public void steuern_ziel_undGrenzen() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        JSONObject r = fuehreAus("evcc:1", "ziel", 80);
        assertTrue(r.getBoolean("uebernommen"));
        assertEquals(80, evcc.lp(1).getInt("limitSoc"));
        assertEquals(400, dienst.steuern(steuer("evcc:1", "ziel", 10, null), "test").status);
        assertEquals(400, dienst.steuern(steuer("evcc:1", "ziel", 101, null), "test").status);
        assertEquals(400, dienst.steuern(steuer("evcc:1", "ziel", 80.5, null), "test").status);
        assertEquals(400, dienst.steuern(steuer("evcc:1", "modus", "turbo", null), "test").status);
        assertEquals(400, dienst.steuern(steuer("evcc:1", "loeschen", null, null), "test").status);
    }

    @Test
    public void steuern_plan_zielBis_nurMitFahrzeug() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        gibFrei("evcc:2");
        JSONObject plan = new JSONObject().put("zeit", "2026-10-23T07:00:00+02:00").put("soc", 80);
        assertEquals(400, dienst.steuern(steuer("evcc:1", "plan", plan, null), "test").status);   // Carport ohne Fahrzeug
        JSONObject r = fuehreAus("evcc:2", "plan", plan);
        assertTrue(r.toString(), r.getBoolean("uebernommen"));
        assertTrue(evcc.anfragen.contains("POST /api/vehicles/vehicle_1/plan/soc/80/2026-10-23T05%3A00%3A00Z"));
        assertEquals(80, r.getJSONObject("ladepunkt").getJSONObject("plan").getInt("soc"));
        assertTrue(fuehreAus("evcc:2", "plan_loeschen", null).getBoolean("uebernommen"));
        // Vergangenheit, zu weit, kaputt
        assertEquals(400, dienst.steuern(steuer("evcc:2", "plan", new JSONObject().put("zeit", "2026-10-22T09:00:00Z").put("soc", 80), "x"), "t").status);
        assertEquals(400, fuehreAusStatus("evcc:2", "plan", new JSONObject().put("zeit", "2026-12-24T09:00:00Z").put("soc", 80)));
        assertEquals(400, fuehreAusStatus("evcc:2", "plan", new JSONObject().put("zeit", "morgen").put("soc", 80)));
    }

    @Test
    public void fahrtSperre_auchBeiUnbekanntemZustand() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        fahrt.set("faehrt");
        assertEquals("fahrt", dienst.status().json.getString("steuernGesperrt"));
        WallboxDienst.Ergebnis r = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test");
        assertEquals(423, r.status);
        assertEquals("fahrt", r.json.getString("grund"));
        fahrt.set("unbekannt");
        r = dienst.steuern(steuer("evcc:1", "modus", "sofort", null), "test");
        assertEquals(423, r.status);
        assertEquals("fahrzustand_unbekannt", r.json.getString("grund"));
        assertEquals(423, dienst.freigabe(new JSONObject().put("ladepunkt", "evcc:2").put("an", true), "t").status);
        assertTrue(nurLesend());
    }

    @Test
    public void fahrzustandAusTelemetrie_failClosed() throws Exception {
        long t = 1_761_100_000_000L;
        // Telemetrie fehlt
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(null, t));
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject(), t));
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", JSONObject.NULL).put("gear", "UNKNOWN(0)"), t));
        // nur eins von beiden bekannt → gesperrt (Regel: frisch, 0 km/h UND P)
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0), t));
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("gear", "P"), t));
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 2).put("gear", "P"), t));
        assertEquals("steht", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "P"), t));
        assertEquals("steht", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", t - 5_000), t));
        assertEquals("steht", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", t / 1000), t));
        // Telemetrie zu alt
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", t - 31_000), t));
        assertEquals("unbekannt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", (t - 120_000) / 1000), t));
        // fährt
        assertEquals("faehrt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "D"), t));
        assertEquals("faehrt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 30).put("gear", "P"), t));
        assertEquals("faehrt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 0).put("gear", "N"), t));
        assertEquals("faehrt", WallboxDienst.fahrzustandAus(new JSONObject().put("speed", 30), t));
    }

    @Test
    public void fahrtSperre_ueberTelemetrieLieferant() throws Exception {
        AtomicReference<JSONObject> tele = new AtomicReference<>(null);
        WallboxDienst d = new WallboxDienst(konfig, protokoll, GEHEIM, weiche, DNS, WallboxDienst.aus(tele::get, jetzt::get), jetzt::get, ms -> { });
        d.speichereEinstellungen(new JSONObject().put("quelle", "evcc").put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070")));
        assertEquals("fahrzustand_unbekannt", d.status().json.getString("steuernGesperrt"));
        assertEquals(423, d.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", true), "t").status);
        tele.set(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", jetzt.get() - 60_000));
        assertEquals(423, d.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", true), "t").status);
        tele.set(new JSONObject().put("speed", 0).put("gear", "P").put("timestamp", jetzt.get()));
        assertEquals(409, d.freigabe(new JSONObject().put("ladepunkt", "evcc:1").put("an", true), "t").status);
        assertTrue(nurLesend());
    }

    // ------------------------------------------------------------------ Geheimnisse, Einstellungen

    @Test
    public void geheimnis_verschluesselt_nieInAntwort_beiHostwechselVerworfen() throws Exception {
        JSONObject e = new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070").put("apiKey", "evcc_geheim123"));
        assertEquals(200, dienst.speichereEinstellungen(e).status);
        String platte = new String(Files.readAllBytes(konfig.toPath()), StandardCharsets.UTF_8);
        assertFalse(platte.contains("evcc_geheim123"));
        assertTrue(platte.contains("ENC:"));
        JSONObject oeff = dienst.einstellungen().json;
        assertFalse(oeff.toString().contains("geheim"));
        assertFalse(oeff.toString().contains("ENC:"));
        assertTrue(oeff.getJSONObject("evcc").getBoolean("apiKeyGesetzt"));
        // Schlüssel wird gesendet
        evcc.erwarteterSchluessel = "evcc_geheim123";
        assertTrue(dienst.status().json.getBoolean("erreichbar"));
        // gleicher Host, Feld fehlt → bleibt
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070/")));
        assertTrue(dienst.einstellungen().json.getJSONObject("evcc").getBoolean("apiKeyGesetzt"));
        // anderer Host ohne neue Eingabe → verworfen
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.66:7070")));
        assertFalse(dienst.einstellungen().json.getJSONObject("evcc").getBoolean("apiKeyGesetzt"));
        assertEquals("anmeldung", dienst.status().json.getString("fehler"));
        // "" löscht
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070").put("apiKey", "evcc_x")));
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070").put("apiKey", "")));
        assertFalse(dienst.einstellungen().json.getJSONObject("evcc").getBoolean("apiKeyGesetzt"));
    }

    @Test
    public void quellenwechsel_loeschtFreigaben_freigabeNieUeberEinstellungen() throws Exception {
        richteEvccEin();
        gibFrei("evcc:1");
        // Freigaben im Rumpf werden ignoriert
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070"))
                .put("freigaben", new JSONArray().put("evcc:2")));
        JSONArray f = dienst.einstellungen().json.getJSONArray("freigaben");
        assertEquals(1, f.length());
        assertEquals("evcc:1", f.getString(0));
        // anderer Host → alle weg
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.9:7070")));
        assertEquals(0, dienst.einstellungen().json.getJSONArray("freigaben").length());
    }

    @Test
    public void einstellungen_gesperrteAdresseWirdNichtGespeichert() throws Exception {
        WallboxDienst.Ergebnis r = dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://127.0.0.1:8180")));
        assertEquals(400, r.status);
        assertEquals("adresse_gesperrt", r.json.getString("grund"));
        assertFalse(konfig.exists());
        assertEquals(400, dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "https://demo.evcc.io"))).status);
        assertEquals(400, dienst.speichereEinstellungen(new JSONObject().put("quelle", "wallbox9000")).status);
        assertEquals(400, dienst.speichereEinstellungen(null).status);
    }

    @Test
    public void test_istNurLesend_undSpeichertNichts() throws Exception {
        WallboxDienst.Ergebnis r = dienst.test(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070")));
        assertEquals(200, r.status);
        assertEquals(3, r.json.getJSONArray("ladepunkte").length());
        assertFalse(konfig.exists());
        assertTrue(nurLesend());
    }

    // ------------------------------------------------------------------ Home Assistant

    @Test
    public void ha_statusSteuernZurueckgelesen() throws Exception {
        richteHaEin();
        JSONObject s = dienst.status().json;
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        JSONObject lp = s.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("ha:garage", lp.getString("id"));
        assertEquals("aus", lp.getString("modus"));
        assertEquals(0, lp.getLong("leistungW"));
        assertEquals(80, lp.getInt("zielSoc"));
        assertEquals(10, lp.getInt("zielMin"));
        assertEquals("22:00", lp.getJSONObject("plan").getString("zeit"));
        JSONArray modi = lp.getJSONObject("faehigkeiten").getJSONArray("modus");
        assertEquals(3, modi.length());   // minpv nicht zugeordnet
        gibFrei("ha:garage");
        ha.verzoegerung = 2;   // träge Integration: Zurücklesen wartet
        JSONObject r = fuehreAus("ha:garage", "modus", "sofort");
        assertTrue(r.toString(), r.getBoolean("uebernommen"));
        assertTrue(ha.dienste.get(0).startsWith("/api/services/select/select_option"));
        assertTrue(ha.dienste.get(0).contains("\"option\":\"Fast\""));
        ha.verzoegerung = 0;
        assertTrue(fuehreAus("ha:garage", "ziel", 70).getBoolean("uebernommen"));
        assertTrue(fuehreAus("ha:garage", "plan", new JSONObject().put("zeit", "01:30")).getBoolean("uebernommen"));
        assertTrue(ha.dienste.get(2).contains("\"time\":\"01:30:00\""));
        assertEquals(400, fuehreAusStatus("ha:garage", "modus", "minpv"));
        assertEquals(400, fuehreAusStatus("ha:garage", "plan", new JSONObject().put("zeit", "25:00")));
        assertEquals(400, fuehreAusStatus("ha:garage", "plan_loeschen", null));
    }

    @Test
    public void ha_leistungUndLaedt_einheitenUndUnbekannt() throws Exception {
        ha.setze("sensor.wb_leistung", "3.7", new JSONObject().put("unit_of_measurement", "kW"));
        ha.setze("binary_sensor.wb_laedt", "on", new JSONObject());
        richteHaEin();
        JSONObject lp = dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals(3700, lp.getLong("leistungW"));
        assertTrue(lp.getBoolean("laedt"));
        ha.setze("sensor.wb_leistung", "unavailable", new JSONObject());
        ha.states.remove("select.wb_modus");
        lp = dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals(JSONObject.NULL, lp.get("leistungW"));
        assertEquals(JSONObject.NULL, lp.get("modus"));
        assertEquals(0, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
    }

    @Test
    public void ha_switchAlsModus_undFalschesTokenHeisstAnmeldung() throws Exception {
        ha.setze("switch.wb_laden", "off", new JSONObject());
        JSONObject z = HaAttrappe.zuordnung().put("modus", "switch.wb_laden");
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "ha").put("ha", new JSONObject()
                .put("url", "http://192.168.1.20:8123").put("token", HaAttrappe.TOKEN).put("ladepunkte", new JSONArray().put(z))));
        JSONObject lp = dienst.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals(2, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
        gibFrei("ha:garage");
        assertTrue(fuehreAus("ha:garage", "modus", "sofort").getBoolean("uebernommen"));
        assertEquals("on", ha.states.get("switch.wb_laden").getString("state"));
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "ha").put("ha", new JSONObject()
                .put("url", "http://192.168.1.20:8123").put("token", "falsch").put("ladepunkte", new JSONArray().put(z))));
        assertEquals("anmeldung", dienst.status().json.getString("fehler"));
    }

    @Test
    public void ha_zuordnungGeprueft() throws Exception {
        String[][] falsch = {
                {"modus", "light.kueche"}, {"ziel", "select.x"}, {"leistung", "sensor.x; rm"}, {"startzeit", "datetime.x"},
        };
        for (String[] f : falsch) {
            JSONObject z = HaAttrappe.zuordnung().put(f[0], f[1]);
            WallboxDienst.Ergebnis r = dienst.speichereEinstellungen(new JSONObject().put("quelle", "ha").put("ha", new JSONObject()
                    .put("url", "http://192.168.1.20:8123").put("token", "t").put("ladepunkte", new JSONArray().put(z))));
            assertEquals(f[1], 400, r.status);
        }
        WallboxDienst.Ergebnis r = dienst.speichereEinstellungen(new JSONObject().put("quelle", "ha").put("ha", new JSONObject()
                .put("url", "http://192.168.1.20:8123").put("ladepunkte", new JSONArray().put(HaAttrappe.zuordnung()))));
        assertEquals("token_fehlt", r.json.getString("grund"));
    }

    // ------------------------------------------------------------------ Protokoll

    @Test
    public void protokoll_ohneUrlUndToken_neuesteZuerst() throws Exception {
        dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070").put("apiKey", "evcc_geheim123")));
        evcc.erwarteterSchluessel = "evcc_geheim123";
        gibFrei("evcc:1");
        fuehreAus("evcc:1", "ziel", 77);
        dienst.steuern(steuer("evcc:1", "modus", "turbo", null), "panoptes");
        JSONArray e = dienst.protokoll().json.getJSONArray("eintraege");
        assertEquals(3, e.length());
        assertEquals("fehler:wert_ungueltig", e.getJSONObject(0).getString("ergebnis"));
        assertEquals("uebernommen", e.getJSONObject(1).getString("ergebnis"));
        assertEquals(77, e.getJSONObject(1).getInt("wert"));
        assertEquals("freigabe", e.getJSONObject(2).getString("aktion"));
        String datei = new String(Files.readAllBytes(protokoll.toPath()), StandardCharsets.UTF_8);
        assertFalse(datei.contains("192.168"));
        assertFalse(datei.contains("geheim"));
        assertFalse(datei.contains("ENC:"));
    }

    @Test
    public void protokoll_behaeltHoechstens200() throws Exception {
        WallboxProtokoll p = new WallboxProtokoll(protokoll);
        for (int i = 0; i < 230; i++) p.schreibe(i, "evcc", "evcc:1", "ziel", i, "ok", "t");
        assertEquals(50, p.lies(50).length());
        assertEquals(200, p.lies(1000).length());
        assertEquals(229, p.lies(1).getJSONObject(0).getInt("wert"));
    }

    // ------------------------------------------------------------------ Hilfen

    void richteEvccEin() throws Exception {
        WallboxDienst.Ergebnis r = dienst.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070")));
        assertEquals(r.json.toString(), 200, r.status);
    }

    void richteHaEin() throws Exception {
        WallboxDienst.Ergebnis r = dienst.speichereEinstellungen(new JSONObject().put("quelle", "ha").put("ha", new JSONObject()
                .put("url", "http://192.168.1.20:8123").put("token", HaAttrappe.TOKEN)
                .put("ladepunkte", new JSONArray().put(HaAttrappe.zuordnung()))));
        assertEquals(r.json.toString(), 200, r.status);
    }

    void gibFrei(String id) throws Exception {
        WallboxDienst.Ergebnis r = dienst.freigabe(new JSONObject().put("ladepunkt", id).put("an", true), "test");
        assertEquals(409, r.status);
        r = dienst.freigabe(new JSONObject().put("ladepunkt", id).put("an", true).put("bestaetigung", r.json.getString("bestaetigung")), "test");
        assertEquals(r.json.toString(), 200, r.status);
    }

    static JSONObject steuer(String id, String aktion, Object wert, String kennung) throws Exception {
        JSONObject o = new JSONObject().put("ladepunkt", id).put("aktion", aktion).put("wert", wert == null ? JSONObject.NULL : wert);
        if (kennung != null) o.put("bestaetigung", kennung);
        return o;
    }

    JSONObject fuehreAus(String id, String aktion, Object wert) throws Exception {
        WallboxDienst.Ergebnis f = dienst.steuern(steuer(id, aktion, wert, null), "test");
        assertEquals(f.json.toString(), 409, f.status);
        WallboxDienst.Ergebnis r = dienst.steuern(steuer(id, aktion, wert, f.json.getString("bestaetigung")), "test");
        assertEquals(r.json.toString(), 200, r.status);
        assertNotNull(r.json.getJSONObject("ladepunkt"));
        return r.json;
    }

    int fuehreAusStatus(String id, String aktion, Object wert) throws Exception {
        WallboxDienst.Ergebnis f = dienst.steuern(steuer(id, aktion, wert, null), "test");
        if (f.status != 409) return f.status;
        return dienst.steuern(steuer(id, aktion, wert, f.json.getString("bestaetigung")), "test").status;
    }

    boolean nurLesend() throws Exception {
        for (String a : evcc.anfragen) if (!a.startsWith("GET ")) return false;
        return ha.dienste.isEmpty();
    }

    interface Wurf {
        void los() throws Exception;
    }

    static void erwarte(String code, Wurf w) throws Exception {
        try {
            w.los();
            fail("erwartet " + code);
        } catch (WallboxFehler f) {
            assertEquals(code, f.code);
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
