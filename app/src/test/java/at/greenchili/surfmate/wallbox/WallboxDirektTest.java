package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Stufe 2a: go-e (API v1/v2) und Shelly (Gen1/Gen2) als direkte Adapter, gegen Attrappen; nie gegen echte Geräte. */
public class WallboxDirektTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());

    // ------------------------------------------------------------------ Attrappen

    /** go-e: v2 (JSON-Typen) oder v1 (alles Text, /mqtt?payload=). */
    static final class GoeAttrappe implements WallboxHttp {
        final boolean v2;
        boolean alw = false;
        int frc = 0, amp = 16, car = 3;
        final List<String> anfragen = new ArrayList<>();

        GoeAttrappe(boolean v2) {
            this.v2 = v2;
        }

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            try {
                String p = url.substring(url.indexOf('/', 8));
                anfragen.add(m + " " + p);
                boolean lädt = alw && car != 1;
                if (lädt) car = 2;
                else if (car == 2) car = 3;
                JSONArray nrg = new JSONArray();
                for (int i = 0; i < 16; i++) nrg.put(i == 11 ? (lädt ? (v2 ? amp * 230.0 : amp * 23.0) : 0) : 0);
                if (v2 && p.startsWith("/api/status")) {
                    return new Antwort(200, new JSONObject().put("alw", alw).put("car", car).put("amp", amp).put("frc", frc).put("nrg", nrg).toString());
                }
                if (v2 && p.startsWith("/api/set?frc=")) {
                    frc = Integer.parseInt(p.substring(13));
                    alw = frc == 2;
                    return new Antwort(200, "{\"frc\":true}");
                }
                if (v2 && p.startsWith("/api/set?amp=")) {
                    amp = Integer.parseInt(p.substring(13));
                    return new Antwort(200, "{\"amp\":true}");
                }
                if (!v2 && p.equals("/status")) {
                    JSONArray n1 = new JSONArray();
                    for (int i = 0; i < 16; i++) n1.put(nrg.get(i));
                    return new Antwort(200, new JSONObject().put("alw", alw ? "1" : "0").put("car", String.valueOf(car))
                            .put("amp", String.valueOf(amp)).put("nrg", n1).toString());
                }
                if (!v2 && p.startsWith("/mqtt?payload=alw=")) {
                    alw = p.endsWith("=1");
                    return new Antwort(200, "{}");
                }
                if (!v2 && p.startsWith("/mqtt?payload=amx=")) {
                    amp = Integer.parseInt(p.substring(18));
                    return new Antwort(200, "{}");
                }
                return new Antwort(404, "");
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
        }
    }

    /** Shelly Gen1 (/status, /relay) oder Gen2+ (/rpc), optional Anmeldung. */
    static final class ShellyAttrappe implements WallboxHttp {
        final int gen;
        boolean an = false;
        String basic;           // Gen1: erwartete Basic-Anmeldung
        boolean gen2Auth;       // Gen2: Digest verlangt
        double last = 1840;
        final List<String> anfragen = new ArrayList<>();

        ShellyAttrappe(int gen) {
            this.gen = gen;
        }

        @Override
        public Antwort anfrage(String m, String url, Map<String, String> kopf, String rumpf) {
            try {
                String p = url.substring(url.indexOf('/', 8));
                anfragen.add(m + " " + p + (rumpf == null ? "" : " " + rumpf));
                if (p.equals("/shelly")) {
                    JSONObject o = new JSONObject().put("type", "SHSW-PM").put("mac", "AABBCC");
                    if (gen >= 2) o.put("gen", gen).put("auth_en", gen2Auth);
                    return new Antwort(200, o.toString());
                }
                if (basic != null && !("Basic " + basic).equals(kopf.get("Authorization"))) return new Antwort(401, "");
                double p0 = an ? last : 0.5;
                if (gen == 1 && p.equals("/status")) {
                    return new Antwort(200, new JSONObject().put("relays", new JSONArray().put(new JSONObject().put("ison", an)))
                            .put("meters", new JSONArray().put(new JSONObject().put("power", p0))).toString());
                }
                if (gen == 1 && p.startsWith("/relay/0?turn=")) {
                    an = p.endsWith("on");
                    return new Antwort(200, new JSONObject().put("ison", an).toString());
                }
                if (gen >= 2 && m.equals("POST") && p.equals("/rpc/Switch.GetStatus")) {
                    if (new JSONObject(rumpf).getInt("id") != 0) return new Antwort(400, "");
                    return new Antwort(200, new JSONObject().put("id", 0).put("output", an).put("apower", p0).toString());
                }
                if (gen >= 2 && m.equals("POST") && p.equals("/rpc/Switch.Set")) {
                    an = new JSONObject(rumpf).getBoolean("on");
                    return new Antwort(200, "{\"was_on\":" + !an + "}");
                }
                return new Antwort(404, "");
            } catch (Exception e) {
                return new Antwort(500, e.toString());
            }
        }
    }

    WallboxDienst dienst(WallboxHttp http) {
        return new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, http,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { });
    }

    static JSONObject konfig(JSONObject... geraete) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject g : geraete) a.put(g);
        return new JSONObject().put("quelle", "direkt").put("direkt", new JSONObject().put("geraete", a));
    }

    static JSONObject geraet(String s, String adapter, String adresse) throws Exception {
        return new JSONObject().put("schluessel", s).put("titel", s).put("adapter", adapter).put("adresse", adresse);
    }

    // ------------------------------------------------------------------ go-e

    @Test
    public void goe_v2_lesenStartStopStrom() throws Exception {
        GoeAttrappe g = new GoeAttrappe(true);
        WallboxDienst d = dienst(g);
        assertEquals(200, d.speichereEinstellungen(konfig(geraet("garage", "goe", "http://192.168.1.30"))).status);
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("direkt:garage", lp.getString("id"));
        assertEquals("goe", lp.getString("adapter"));
        assertEquals("aus", lp.getString("modus"));
        assertEquals(16, lp.getInt("stromA"));
        assertTrue(lp.getBoolean("verbunden"));
        assertFalse(lp.getBoolean("laedt"));
        WallboxTuyaTest.gibFrei(d, "direkt:garage");
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:garage", "strom", 10).getBoolean("uebernommen"));
        JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:garage", "modus", "sofort");
        assertTrue(r.getBoolean("uebernommen"));
        assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
        assertEquals(2300, r.getJSONObject("ladepunkt").getLong("leistungW"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:garage", "modus", "aus").getBoolean("uebernommen"));
        assertTrue(g.anfragen.contains("GET /api/set?frc=2"));
        assertTrue(g.anfragen.contains("GET /api/set?frc=1"));
        assertTrue(g.anfragen.contains("GET /api/set?amp=10"));
        assertEquals(400, WallboxTuyaTest.status(d, "direkt:garage", "strom", 33));
        assertEquals(400, WallboxTuyaTest.status(d, "direkt:garage", "ziel", 80));
        assertEquals(400, WallboxTuyaTest.status(d, "direkt:garage", "modus", "pv"));
    }

    @Test
    public void goe_v1_rueckfall() throws Exception {
        GoeAttrappe g = new GoeAttrappe(false);
        WallboxDienst d = dienst(g);
        d.speichereEinstellungen(konfig(geraet("garage", "goe", "http://192.168.1.30")));
        WallboxTuyaTest.gibFrei(d, "direkt:garage");
        JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:garage", "modus", "sofort");
        assertTrue(r.getBoolean("uebernommen"));
        assertEquals(3680, r.getJSONObject("ladepunkt").getLong("leistungW"));   // nrg[11] in 0,01 kW × 10
        assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:garage", "strom", 8).getBoolean("uebernommen"));
        assertTrue(g.anfragen.contains("GET /mqtt?payload=alw=1"));
        assertTrue(g.anfragen.contains("GET /mqtt?payload=amx=8"));
    }

    // ------------------------------------------------------------------ Shelly

    @Test
    public void shelly_gen1_undGen2_nurEinAus() throws Exception {
        for (int gen : new int[]{1, 2, 3}) {
            new File(tmp.getRoot(), "wb.json").delete();
            ShellyAttrappe s = new ShellyAttrappe(gen);
            WallboxDienst d = dienst(s);
            assertEquals(200, d.speichereEinstellungen(konfig(geraet("dose", "shelly", "http://192.168.1.40"))).status);
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("gen" + gen, "aus", lp.getString("modus"));
            assertFalse(lp.getBoolean("laedt"));
            assertEquals(JSONObject.NULL, lp.getJSONObject("faehigkeiten").get("strom"));
            WallboxTuyaTest.gibFrei(d, "direkt:dose");
            JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:dose", "modus", "sofort");
            assertTrue(r.getBoolean("uebernommen"));
            assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
            assertEquals(1840, r.getJSONObject("ladepunkt").getLong("leistungW"));
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:dose", "strom", 10));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:dose", "modus", "aus").getBoolean("uebernommen"));
            String erw = gen == 1 ? "GET /relay/0?turn=on" : "POST /rpc/Switch.Set {\"id\":0,\"on\":true}";
            boolean da = false;
            for (String a : s.anfragen) da |= a.equals(erw) || (gen > 1 && a.startsWith("POST /rpc/Switch.Set") && a.contains("\"on\":true"));
            assertTrue(gen + " " + s.anfragen, da);
        }
    }

    @Test
    public void shelly_anmeldung_gen1Basic_gen2Ehrlich() throws Exception {
        ShellyAttrappe s = new ShellyAttrappe(1);
        s.basic = Base64.getEncoder().encodeToString("admin:geheim42".getBytes(StandardCharsets.UTF_8));
        WallboxDienst d = dienst(s);
        JSONObject g = geraet("dose", "shelly", "http://192.168.1.40").put("benutzer", "admin").put("passwort", "geheim42");
        assertEquals(200, d.speichereEinstellungen(konfig(g)).status);
        assertTrue(d.status().json.toString(), d.status().json.getBoolean("erreichbar"));
        String platte = new String(Files.readAllBytes(new File(tmp.getRoot(), "wb.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(platte.contains("geheim42"));
        JSONObject oeff = d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0);
        assertTrue(oeff.getBoolean("passwortGesetzt"));
        assertFalse(oeff.has("passwort"));
        // andere Adresse ohne neues Passwort → verworfen
        d.speichereEinstellungen(konfig(geraet("dose", "shelly", "http://192.168.1.41").put("benutzer", "admin")));
        assertFalse(d.einstellungen().json.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).getBoolean("passwortGesetzt"));
        // Gen2 mit Digest-Anmeldung: ehrlich nicht unterstützt
        ShellyAttrappe s2 = new ShellyAttrappe(2);
        s2.gen2Auth = true;
        new File(tmp.getRoot(), "wb.json").delete();
        WallboxDienst d2 = dienst(s2);
        d2.speichereEinstellungen(konfig(geraet("dose", "shelly", "http://192.168.1.40")));
        assertEquals("shelly_anmeldung", d2.status().json.getString("fehler"));
    }

    // ------------------------------------------------------------------ gemeinsam

    @Test
    public void direkt_mehrereGeraete_adressenPrivat_freigabenJeGeraet() throws Exception {
        GoeAttrappe g = new GoeAttrappe(true);
        ShellyAttrappe s = new ShellyAttrappe(2);
        WallboxHttp weiche = (m, url, k, r) -> url.contains("192.168.1.30") ? g.anfrage(m, url, k, r) : s.anfrage(m, url, k, r);
        WallboxDienst d = dienst(weiche);
        assertEquals("adresse_oeffentlich", d.speichereEinstellungen(konfig(geraet("x", "goe", "http://8.8.8.8"))).json.getString("grund"));
        assertEquals("adresse_gesperrt", d.speichereEinstellungen(konfig(geraet("x", "goe", "http://127.0.0.1"))).json.getString("grund"));
        assertEquals(400, d.speichereEinstellungen(konfig(geraet("x", "wallbox9000", "http://192.168.1.30"))).status);
        assertEquals(200, d.speichereEinstellungen(konfig(geraet("garage", "goe", "http://192.168.1.30"),
                geraet("dose", "shelly", "http://192.168.1.40"))).status);
        assertEquals(2, d.status().json.getJSONArray("ladepunkte").length());
        WallboxTuyaTest.gibFrei(d, "direkt:garage");
        WallboxTuyaTest.gibFrei(d, "direkt:dose");
        // Shelly-Adresse geändert → nur dessen Freigabe weg
        d.speichereEinstellungen(konfig(geraet("garage", "goe", "http://192.168.1.30"), geraet("dose", "shelly", "http://192.168.1.41")));
        JSONArray f = d.einstellungen().json.getJSONArray("freigaben");
        assertEquals(1, f.length());
        assertEquals("direkt:garage", f.getString(0));
    }

    @Test
    public void adapterTabelle_gueltig() throws Exception {
        JSONObject t = new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/web/shared/wallbox-adapter.json")),
                StandardCharsets.UTF_8));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < t.getJSONArray("adapter").length(); i++) {
            JSONObject a = t.getJSONArray("adapter").getJSONObject(i);
            assertTrue(ids.add(a.getString("id")));
            assertFalse(a.getString("beleg").isEmpty());
            if ("cloud".equals(a.getString("zugang"))) assertEquals(a.getString("id"), true, a.get("einwilligung"));
        }
        for (String k : DirektAdapter.KENNUNGEN) assertTrue(k, ids.contains(k));
    }
}
