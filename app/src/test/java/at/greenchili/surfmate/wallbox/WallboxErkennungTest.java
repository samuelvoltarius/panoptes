package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Koordination 22.10.: Tuya-Standardladegeräte (Kategorie qccdz) aus der Datentabelle erkennen; HA-Weg für Tuya-Wallboxen
 * (Rolle „strom“ in A, Zuordnungsvorschlag ohne Code je Modell).
 */
public class WallboxErkennungTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());

    static JSONObject tabelle() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/web/shared/wallbox-tuya-modelle.json")),
                StandardCharsets.UTF_8));
    }

    @Test
    public void tabelle_gueltig_jederEintragBelegt() throws Exception {
        JSONArray m = tabelle().getJSONArray("modelle");
        assertTrue(m.length() >= 1);
        for (int i = 0; i < m.length(); i++) {
            JSONObject e = m.getJSONObject(i);
            assertTrue(e.getString("id"), e.getString("id").matches("[a-z0-9_-]{1,64}"));
            assertFalse(e.getString("id"), e.optString("beleg", "").trim().isEmpty());
            assertNotNull(TuyaQuelle.pruefeZuordnung(e.getJSONObject("dp")));
        }
    }

    @Test
    public void erkennung_lanMuster_undNichtBeiFremdenGeraeten() throws Exception {
        JSONObject t = tabelle();
        JSONObject std = new JSONObject().put("3", "charger_free").put("4", 16).put("9", 0).put("18", false).put("10", 0);
        JSONObject er = TuyaQuelle.erkenne(t, std, true);
        assertEquals("qccdz-standard", er.getString("modell"));
        assertEquals("18", er.getJSONObject("zuordnung").getJSONObject("schalter").getString("dp"));
        // Steckdose/Licht: passt nicht
        assertNull(TuyaQuelle.erkenne(t, new JSONObject().put("1", true).put("3", 120).put("4", 5), true));
        assertNull(TuyaQuelle.erkenne(t, new JSONObject().put("3", "connected").put("4", 16).put("18", false), true));
        assertNull(TuyaQuelle.erkenne(t, new JSONObject().put("3", "charger_free").put("4", "16").put("18", false), true));
        // Cloud über Codes
        JSONObject cloud = new JSONObject().put("work_state", "charger_charging").put("charge_cur_set", 10).put("switch", true);
        assertEquals("qccdz-standard", TuyaQuelle.erkenne(t, cloud, false).getString("modell"));
        assertNull(TuyaQuelle.erkenne(t, std, false));
    }

    @Test
    public void dienst_standardladegeraet_erkannt_steuerbar() throws Exception {
        JSONObject t = tabelle();
        for (String v : new String[]{"3.3", "3.4", "3.5"}) {
            File k = new File(tmp.getRoot(), "wb" + v + ".json");
            try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe(v, true)) {
                WallboxDienst d = new WallboxDienst(k, new File(tmp.getRoot(), "p" + v), WallboxTest.GEHEIM, null, WallboxTest.DNS,
                        () -> "steht", jetzt::get, ms -> { }, g.verbinder(), () -> t);
                assertEquals(200, d.speichereEinstellungen(WallboxTuyaTest.lanKonfig(v, null, null, TuyaGeraetAttrappe.SCHLUESSEL)).status);
                JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
                assertEquals(v, "erkannt", lp.getString("zuordnung"));
                assertEquals("qccdz-standard", lp.getString("modell"));
                assertEquals(JSONObject.NULL, lp.get("rohDps"));
                assertFalse(lp.getBoolean("laedt"));
                WallboxTuyaTest.gibFrei(d, "tuya:garage");
                assertTrue(WallboxTuyaTest.fuehreAus(d, "tuya:garage", "strom", 10).getBoolean("uebernommen"));
                JSONObject r = WallboxTuyaTest.fuehreAus(d, "tuya:garage", "modus", "sofort");
                assertTrue(r.getBoolean("uebernommen"));
                assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
                assertEquals(true, g.dps.getBoolean("18"));
                assertEquals(10, g.dps.getInt("4"));
                assertEquals(400, WallboxTuyaTest.status(d, "tuya:garage", "strom", 20));   // Vorlage vorsichtig 6–16 A
            }
        }
    }

    @Test
    public void dienst_ohneTabelleUnbekannt() throws Exception {
        try (TuyaGeraetAttrappe g = new TuyaGeraetAttrappe("3.3", true)) {
            WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p"), WallboxTest.GEHEIM, null,
                    WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { }, g.verbinder(), () -> null);
            d.speichereEinstellungen(WallboxTuyaTest.lanKonfig("3.3", null, null, TuyaGeraetAttrappe.SCHLUESSEL));
            assertEquals("unbekannt", d.status().json.getJSONArray("ladepunkte").getJSONObject(0).getString("zuordnung"));
        }
    }

    // ------------------------------------------------------------------ HA-Weg (Alfreds Wallbox lief über HA)

    static HaAttrappe tuyaInHa() throws Exception {
        HaAttrappe ha = new HaAttrappe();
        ha.setze("switch.ev_charger", "off", new JSONObject().put("friendly_name", "EV Charger"));
        ha.setze("number.ev_charger_charging_current", "16", new JSONObject().put("unit_of_measurement", "A")
                .put("device_class", "current").put("min", 6).put("max", 32).put("friendly_name", "EV Charger Charging current"));
        ha.setze("sensor.ev_charger_charger_status", "charger_insert", new JSONObject().put("friendly_name", "EV Charger Status"));
        ha.setze("sensor.ev_charger_total_power", "0.0", new JSONObject().put("unit_of_measurement", "kW").put("device_class", "power")
                .put("friendly_name", "EV Charger Total power"));
        ha.setze("switch.kaffeemaschine", "on", new JSONObject().put("friendly_name", "Kaffeemaschine"));
        ha.setze("number.thermostat_soll", "21", new JSONObject().put("unit_of_measurement", "°C"));
        ha.setze("light.kueche", "on", new JSONObject());
        return ha;
    }

    @Test
    public void ha_vorschlag_findetTuyaWallboxOhneModellcode() throws Exception {
        HaAttrappe ha = tuyaInHa();
        JSONArray alle = new JSONArray();
        for (JSONObject s : ha.states.values()) alle.put(s);
        JSONObject v = HaQuelle.vorschlagAus(alle).getJSONObject("vorschlag");
        assertEquals("switch.ev_charger", v.getString("modus"));
        assertEquals("number.ev_charger_charging_current", v.getString("strom"));
        assertEquals("sensor.ev_charger_charger_status", v.getString("laedt"));
        assertEquals("sensor.ev_charger_total_power", v.getString("leistung"));
        // Vorschlag ist eine gültige Zuordnung
        assertNotNull(HaQuelle.pruefeZuordnung(v));
    }

    @Test
    public void ha_tuyaWallbox_stromUndEinAus() throws Exception {
        HaAttrappe ha = tuyaInHa();
        WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p"), WallboxTest.GEHEIM, ha,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { });
        // Vorschlag über die API-Logik (ungespeichert)
        JSONObject roh = new JSONObject().put("quelle", "ha").put("ha", new JSONObject().put("url", "http://192.168.1.20:8123")
                .put("token", HaAttrappe.TOKEN).put("ladepunkte", new JSONArray()));
        JSONObject vor = d.haVorschlag(roh).json;
        assertTrue(vor.toString(), vor.getBoolean("ok"));
        JSONObject z = vor.getJSONObject("vorschlag");
        roh.getJSONObject("ha").put("ladepunkte", new JSONArray().put(z));
        assertEquals(200, d.speichereEinstellungen(roh).status);
        JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        assertEquals(16, lp.getInt("stromA"));
        assertEquals(6, lp.getJSONObject("faehigkeiten").getJSONObject("strom").getInt("min"));
        assertEquals(32, lp.getJSONObject("faehigkeiten").getJSONObject("strom").getInt("max"));
        assertFalse(lp.getBoolean("laedt"));
        WallboxTuyaTest.gibFrei(d, "ha:wallbox");
        assertTrue(WallboxTuyaTest.fuehreAus(d, "ha:wallbox", "strom", 10).getBoolean("uebernommen"));
        assertTrue(ha.dienste.get(0).contains("/api/services/number/set_value"));
        assertTrue(WallboxTuyaTest.fuehreAus(d, "ha:wallbox", "modus", "sofort").getBoolean("uebernommen"));
        assertEquals(400, WallboxTuyaTest.status(d, "ha:wallbox", "strom", 40));
        ha.setze("sensor.ev_charger_charger_status", "charger_charging", new JSONObject());
        assertTrue(d.status().json.getJSONArray("ladepunkte").getJSONObject(0).getBoolean("laedt"));
    }
}
