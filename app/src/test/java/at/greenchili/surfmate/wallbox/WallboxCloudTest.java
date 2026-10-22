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

/** Stufe 2d: Easee und Zaptec (Hersteller-Cloud, nur mit Einwilligung) gegen Attrappen der Hersteller-APIs. */
public class WallboxCloudTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());

    @Before
    public void auf() {
        CloudAdapter.TOKENS.clear();
        CloudAdapter.EASEE_SITES.clear();
    }

    /** Easee-API: Login, Site, Site-State, Befehle, Einstellungen. */
    static final class EaseeAttrappe implements WallboxHttp {
        final List<String> anfragen = Collections.synchronizedList(new ArrayList<>());
        int opMode = 2, logins = 0;
        double strom = 16;

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            try {
                anfragen.add(m + " " + url);
                if (!url.startsWith("https://api.easee.com/api/")) return new Antwort(400, "falscher Host");
                String p = url.substring("https://api.easee.com/api".length());
                if (p.equals("/accounts/login")) {
                    logins++;
                    JSONObject b = new JSONObject(rumpf);
                    if (!"a@b.at".equals(b.getString("userName")) || !"pw-easee".equals(b.getString("password"))) return new Antwort(400, "{}");
                    return new Antwort(200, "{\"accessToken\":\"tokE\",\"expiresIn\":86400,\"tokenType\":\"Bearer\",\"refreshToken\":\"r\"}");
                }
                if (!"Bearer tokE".equals(kopf.get("Authorization"))) return new Antwort(401, "");
                if (p.equals("/chargers/EH123456/site")) return new Antwort(200, "{\"id\":4711,\"name\":\"Zuhause\"}");
                if (p.equals("/sites/4711/state")) {
                    JSONObject st = new JSONObject().put("chargerOpMode", opMode).put("totalPower", opMode == 3 ? strom * 0.23 : 0.0)
                            .put("dynamicChargerCurrent", strom);
                    return new Antwort(200, new JSONObject().put("circuitStates", new JSONArray().put(new JSONObject().put("circuitID", 1)
                            .put("chargerStates", new JSONArray().put(new JSONObject().put("chargerID", "EH123456").put("chargerState", st))))).toString());
                }
                if (p.equals("/chargers/EH123456/commands/resume_charging")) {
                    opMode = 3;
                    return new Antwort(202, "");
                }
                if (p.equals("/chargers/EH123456/commands/pause_charging")) {
                    opMode = 2;
                    return new Antwort(202, "");
                }
                if (p.equals("/chargers/EH123456/settings")) {
                    strom = new JSONObject(rumpf).getDouble("dynamicChargerCurrent");
                    return new Antwort(202, "");
                }
                return new Antwort(404, "");
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
        }
    }

    /** Zaptec-API: OAuth-Passwort-Grant (Formular), State-Liste, sendCommand (528 bei „ändert nichts“), update. */
    static final class ZaptecAttrappe implements WallboxHttp {
        final List<String> anfragen = Collections.synchronizedList(new ArrayList<>());
        int opMode = 2;
        boolean stopp = true, mit708 = true;
        double strom = 16;

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            try {
                anfragen.add(m + " " + url + (rumpf == null ? "" : " " + rumpf));
                if (!url.startsWith("https://api.zaptec.com/")) return new Antwort(400, "falscher Host");
                String p = url.substring("https://api.zaptec.com".length());
                if (p.equals("/oauth/token")) {
                    if (!"application/x-www-form-urlencoded".equals(kopf.get("Content-Type"))) return new Antwort(415, "");
                    if (!rumpf.contains("grant_type=password") || !rumpf.contains("username=z%40b.at") || !rumpf.contains("password=pw%26z")) {
                        return new Antwort(400, "{\"error\":\"invalid_grant\"}");
                    }
                    return new Antwort(200, "{\"access_token\":\"tokZ\",\"expires_in\":86400}");
                }
                if (!"Bearer tokZ".equals(kopf.get("Authorization"))) return new Antwort(401, "");
                if (p.equals("/api/chargers/zap-1/state")) {
                    JSONArray a = new JSONArray()
                            .put(new JSONObject().put("StateId", 710).put("ValueAsString", String.valueOf(opMode)))
                            .put(new JSONObject().put("StateId", 513).put("ValueAsString", String.valueOf(opMode == 3 ? strom * 230 : 0)))
                            .put(new JSONObject().put("StateId", 718).put("ValueAsString", stopp ? "1" : "0"));
                    if (mit708) a.put(new JSONObject().put("StateId", 708).put("ValueAsString", String.valueOf(strom)));
                    return new Antwort(200, a.toString());
                }
                if (p.equals("/api/chargers/zap-1/sendCommand/507")) {
                    if (!stopp) return new Antwort(500, "{\"Code\":528,\"Details\":\"Charging is not Paused\"}");
                    stopp = false;
                    opMode = 3;
                    return new Antwort(200, "");
                }
                if (p.equals("/api/chargers/zap-1/sendCommand/506")) {
                    stopp = true;
                    opMode = 5;
                    return new Antwort(200, "");
                }
                if (p.equals("/api/chargers/zap-1/update")) {
                    strom = new JSONObject(rumpf).getDouble("maxChargeCurrent");
                    return new Antwort(200, "");
                }
                return new Antwort(404, "");
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
        }
    }

    WallboxDienst dienst(WallboxHttp h) {
        return new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, h,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { });
    }

    static JSONObject easee(boolean einw) throws Exception {
        return new JSONObject().put("schluessel", "easee").put("titel", "Easee").put("adapter", "easee").put("geraeteId", "EH123456")
                .put("benutzer", "a@b.at").put("passwort", "pw-easee").put("einwilligung", einw);
    }

    static JSONObject zaptec(boolean einw) throws Exception {
        return new JSONObject().put("schluessel", "zap").put("titel", "Zaptec").put("adapter", "zaptec").put("geraeteId", "zap-1")
                .put("benutzer", "z@b.at").put("passwort", "pw&z").put("einwilligung", einw);
    }

    @Test
    public void ohneEinwilligung_keineAnfrage_andereGeraeteLaufen() throws Exception {
        EaseeAttrappe e = new EaseeAttrappe();
        WallboxDirektTest.GoeAttrappe g = new WallboxDirektTest.GoeAttrappe(true);
        WallboxHttp weiche = (m, url, k, r) -> url.contains("easee") ? e.anfrage(m, url, k, r) : g.anfrage(m, url, k, r);
        WallboxDienst d = dienst(weiche);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(easee(false),
                WallboxDirektTest.geraet("garage", "goe", "http://192.168.1.30"))).status);
        JSONObject s = d.status().json;
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        JSONObject lpE = s.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("einwilligung_fehlt", lpE.getString("fehler"));
        assertEquals("aus", s.getJSONArray("ladepunkte").getJSONObject(1).getString("modus"));   // go-e läuft weiter
        assertEquals(409, d.freigabe(new JSONObject().put("ladepunkt", "direkt:easee").put("an", true), "t").status);
        assertTrue(e.anfragen.toString(), e.anfragen.isEmpty());
        JSONObject gespeichert = d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0);
        assertEquals(JSONObject.NULL, gespeichert.get("einwilligungZeit"));
        assertFalse(gespeichert.toString().contains("pw-easee"));
        assertTrue(gespeichert.getBoolean("passwortGesetzt"));
    }

    @Test
    public void easee_statusSteuernTokenNurEinmal() throws Exception {
        EaseeAttrappe e = new EaseeAttrappe();
        WallboxDienst d = dienst(e);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(easee(true))).status);
        assertEquals(jetzt.get(), d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).getLong("einwilligungZeit"));
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        assertEquals(16, lp.getInt("stromA"));
        assertTrue(lp.getBoolean("verbunden"));
        WallboxTuyaTest.gibFrei(d, "direkt:easee");
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:easee", "strom", 10).getBoolean("uebernommen"));
        JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:easee", "modus", "sofort");
        assertTrue(r.getBoolean("uebernommen"));
        assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
        assertEquals(2300, r.getJSONObject("ladepunkt").getLong("leistungW"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:easee", "modus", "aus").getBoolean("uebernommen"));
        assertEquals(1, e.logins);                                         // Token im Speicher wiederverwendet
        assertEquals(1, e.anfragen.stream().filter(a -> a.endsWith("/site")).count());
        for (String a : e.anfragen) assertFalse(a, a.contains("pw-easee"));
        String platte = new String(Files.readAllBytes(new File(tmp.getRoot(), "wb.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(platte.contains("pw-easee"));
        assertFalse(platte.contains("tokE"));
    }

    @Test
    public void zaptec_formularToken_528istOk_stromZurueckgelesen() throws Exception {
        ZaptecAttrappe z = new ZaptecAttrappe();
        WallboxDienst d = dienst(z);
        assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(zaptec(true))).status);
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertTrue(d.status().json.toString(), d.status().json.getBoolean("erreichbar"));
        assertEquals("aus", lp.getString("modus"));
        WallboxTuyaTest.gibFrei(d, "direkt:zap");
        JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:zap", "modus", "sofort");
        assertTrue(r.getBoolean("uebernommen"));
        assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:zap", "modus", "sofort").getBoolean("uebernommen"));   // 528 → kein Fehler
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:zap", "strom", 12).getBoolean("uebernommen"));
        assertTrue(z.anfragen.contains("POST https://api.zaptec.com/api/chargers/zap-1/update {\"maxChargeCurrent\":12}"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:zap", "modus", "aus").getBoolean("uebernommen"));
    }

    @Test
    public void zaptec_ohne708_ergebnisUnbekannt_ehrlich() throws Exception {
        ZaptecAttrappe z = new ZaptecAttrappe();
        z.mit708 = false;
        WallboxDienst d = dienst(z);
        d.speichereEinstellungen(WallboxDirektTest.konfig(zaptec(true)));
        WallboxTuyaTest.gibFrei(d, "direkt:zap");
        WallboxDienst.Ergebnis f = d.steuern(WallboxTest.steuer("direkt:zap", "strom", 12, null), "t");
        WallboxDienst.Ergebnis r = d.steuern(WallboxTest.steuer("direkt:zap", "strom", 12, f.json.getString("bestaetigung")), "t");
        assertEquals(200, r.status);
        assertTrue(r.json.getBoolean("ok"));
        assertEquals(JSONObject.NULL, r.json.get("uebernommen"));
        assertEquals("gesendet_ungeprueft", d.protokoll().json.getJSONArray("eintraege").getJSONObject(0).getString("ergebnis"));
    }

    @Test
    public void falscheAnmeldung_undKontowechselVerwirftPasswort() throws Exception {
        EaseeAttrappe e = new EaseeAttrappe();
        WallboxDienst d = dienst(e);
        d.speichereEinstellungen(WallboxDirektTest.konfig(easee(true).put("passwort", "falsch")));
        assertEquals("anmeldung", d.status().json.getString("fehler"));
        d.speichereEinstellungen(WallboxDirektTest.konfig(easee(true)));
        assertTrue(d.status().json.getBoolean("erreichbar"));
        JSONObject anderer = easee(true).put("benutzer", "c@d.at");
        anderer.remove("passwort");
        d.speichereEinstellungen(WallboxDirektTest.konfig(anderer));
        assertFalse(d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).getBoolean("passwortGesetzt"));
        assertEquals(400, d.speichereEinstellungen(WallboxDirektTest.konfig(easee(true).put("geraeteId", "x/../y"))).status);
    }
}
