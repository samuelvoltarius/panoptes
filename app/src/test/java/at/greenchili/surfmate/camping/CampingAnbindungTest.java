package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.mqtt.TelemetryFieldCatalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 0.1.16 Camping: HA/MQTT-Werte, Telegram-Befehl, Web-Seite – ohne Innentemperatur, Probelauf sichtbar. */
public class CampingAnbindungTest {

    @Test
    public void telemetrieNurMitGestartetemCamping() throws Exception {
        JSONObject p = new JSONObject();
        CampingTelemetrie.eintragen(p, new JSONObject().put("aktiv", false).put("gestartet", false));
        assertEquals(0, p.length());
        CampingTelemetrie.eintragen(p, new JSONObject().put("aktiv", false).put("gestartet", true));
        assertEquals(0, p.getInt("camping_aktiv"));
        assertEquals("aus", p.getString("camping_phase"));
    }

    @Test
    public void telemetrieImCamping() throws Exception {
        long t = 1_800_000_000_000L;
        JSONObject s = new JSONObject().put("aktiv", true).put("gestartet", true).put("phase", "pause")
                .put("naechsterZyklusMs", t).put("innenC", JSONObject.NULL)
                .put("budget", new JSONObject().put("intervallMin", 60).put("dauerMin", 12).put("reichtBisMs", t + 3_600_000L))
                .put("verbrauch", new JSONObject().put("gemessen", true).put("prozentProStunde", 2.5));
        JSONObject p = new JSONObject();
        CampingTelemetrie.eintragen(p, s);
        assertEquals(1, p.getInt("camping_aktiv"));
        assertEquals("pause", p.getString("camping_phase"));
        assertEquals(60, p.getInt("camping_intervall"));
        assertEquals(12, p.getInt("camping_dauer"));
        assertEquals(2.5, p.getDouble("camping_verbrauch"), 0);
        assertTrue(p.getString("camping_naechster_zyklus").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d[+-]\\d\\d:\\d\\d|.*Z"));
        assertFalse("nie eine Innentemperatur", p.toString().contains("innen") || p.has("inside_temp"));
        for (String k : new String[]{"camping_aktiv", "camping_phase", "camping_naechster_zyklus",
                "camping_reicht_bis", "camping_verbrauch", "camping_intervall", "camping_dauer"}) {
            assertTrue(k, TelemetryFieldCatalog.isDiscoverable(k));
            assertTrue(k, TelemetryFieldCatalog.get(k) != null);
        }
    }

    @Test
    public void telegramRueckmeldungUndUhr() {
        assertEquals("zu_kalt", at.greenchili.surfmate.daemon.telegram.CampingCommandHandler.rueckmeldung("kalt"));
        assertEquals("passt", at.greenchili.surfmate.daemon.telegram.CampingCommandHandler.rueckmeldung("passt"));
        assertEquals("zu_warm", at.greenchili.surfmate.daemon.telegram.CampingCommandHandler.rueckmeldung("warm"));
        assertNull(at.greenchili.surfmate.daemon.telegram.CampingCommandHandler.rueckmeldung("an"));
    }

    @Test
    public void webSeiteOhneInnentemperaturUndMitProbelauf() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/assets/web/local/camping.html"));
        assertFalse(h.contains("insideTemp"));
        assertFalse(h.contains("innenC"));
        assertTrue(h.contains("camping.trial"));
        assertTrue("Chromium 74: kein optional chaining", !h.contains("?.") && !h.contains("??"));
        String shell = Quelltext.lies(Paths.get("src/main/assets/web/shared/app-shell.js"));
        assertTrue(shell.contains("{ href: 'camping.html'"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(new String(Files.readAllBytes(
                    Paths.get("src/main/assets/web/i18n/" + l + ".json")), StandardCharsets.UTF_8));
            assertTrue(l, d.getJSONObject("shell").has("page_camping"));
            assertTrue(l, d.getJSONObject("camping").has("interval"));
            JSONObject s = new JSONObject(new String(Files.readAllBytes(
                    Paths.get("src/main/assets/server-i18n/" + l + ".json")), StandardCharsets.UTF_8));
            JSONObject tg = s.getJSONObject("telegram");
            assertTrue(l, tg.getJSONObject("camping").has("status_on"));
            assertTrue(l, tg.getJSONObject("help").has("camping"));
            assertTrue(l, s.getJSONObject("notifications").has("camping_tuer_offen"));
        }
    }
}
