package at.greenchili.surfmate.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Pattern;

/**
 * Golden-Test zum Befund vom 29.10.2026 (MiniMax, Chip „Auto jetzt“): Die Fassung für den Assistenten enthält
 * keinen Sentinel, keinen Rohfeldnamen, kein Verbrenner-Feld, keine Position/VIN und den Klima-Sollwert als Stufe.
 */
public class AssistentZustandTest {

    private static final ZoneId WIEN = ZoneId.of("Europe/Vienna");

    private static JSONObject befund() throws Exception {
        try (InputStream in = AssistentZustandTest.class.getResourceAsStream(
                "/genai/auto_jetzt_befund_20261029.json")) {
            String text = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
            return new JSONObject(text);
        }
    }

    private static JSONObject fassung(JSONObject roh) {
        long jetzt = roh.optLong("utc") * 1000L;
        return AssistentZustand.aus(roh, "scharf", true, jetzt, WIEN);
    }

    @Test
    public void befundOhneSentinelsRohnamenUndVerbrenner() throws Exception {
        JSONObject roh = befund();
        JSONObject out = fassung(roh);
        String text = out.toString();

        // Sentinels und unplausible Werte vom Auto
        for (String s : new String[]{"1048575", "65535", "10011", "255", "-22", "150", "-1"}) {
            assertFalse("Sentinel " + s + " in " + text, text.contains(s));
        }
        // kein Rohfeldname (jeder Schlüssel der Telemetrie als ganzes Wort)
        for (Iterator<String> it = roh.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("_")) continue;
            Pattern p = Pattern.compile("\\b" + Pattern.quote(k) + "\\b");
            assertFalse("Rohfeld " + k + " in " + text, p.matcher(text).find());
        }
        String klein = text.toLowerCase(Locale.ROOT);
        for (String s : new String[]{"oil", "coolant", "fuel", "rpm", "engine", "alarm", "emergency",
                "radar", "utc", "ms\"", "null"}) {
            assertFalse(s + " in " + text, klein.contains(s));
        }
        // keine Position, keine VIN
        assertFalse(text.contains("48.2"));
        assertFalse(text.contains("16.3"));
        assertFalse(text.contains("LGX"));
        // Klima als Stufe (SDK 29 = Stufe 13 = Heizen 4), nie °C
        assertEquals("on, Heating level 4", out.getString("climate"));
    }

    @Test
    public void befundInKlartext() throws Exception {
        JSONObject out = fassung(befund());
        assertEquals("today 09:14", out.getString("values measured"));
        assertTrue(out.getString("note").contains("asleep"));
        assertEquals("62 %", out.getString("battery charge"));
        assertEquals("231 km", out.getString("range"));
        assertEquals("80 %", out.getString("charge limit"));
        assertEquals("not connected", out.getString("charging cable"));
        assertEquals("no", out.getString("charging"));
        assertEquals("yes", out.getString("locked"));
        assertEquals("none", out.getString("open doors"));
        assertEquals("none", out.getString("open windows"));
        assertEquals("no", out.getString("car switched on"));
        assertEquals("8 °C", out.getString("outside temperature"));
        assertEquals("14 °C", out.getString("battery temperature"));
        assertEquals("12.6 V", out.getString("small 12 V battery"));
        assertEquals("12873 km", out.getString("total distance driven"));
        assertEquals("watching (armed)", out.getString("Panoptes guard"));
        // unbekannt = weggelassen: keine Innentemperatur (null), Reifen nur mit allen vier Werten, keine Ladezeit
        assertFalse(out.has("inside temperature"));
        assertFalse(out.has("tyre pressure"));
        assertFalse(out.has("time until full"));
        assertTrue("höchstens etwa 20 kurze Werte", out.length() <= 20);
    }

    @Test
    public void ladenMitLeistungUndRestzeit() throws Exception {
        JSONObject roh = befund()
                .put("is_charging", 1).put("plugged", 1).put("charge_power", 7.24)
                .put("charging_eta_hours", 1).put("charging_eta_minutes", 20)
                .put("unlocked", 1).put("door_open_rr", 1).put("trunk_open", 1)
                .put("tyre_p_rr", 248)
                .put("vd_timestamp", 1793263400L);
        JSONObject out = fassung(roh);
        assertEquals("connected", out.getString("charging cable"));
        assertEquals("yes, 7.2 kW", out.getString("charging"));
        assertEquals("1 h 20 min", out.getString("time until full"));
        assertEquals("no", out.getString("locked"));
        assertEquals("rear right door", out.getString("open doors"));
        assertEquals("open", out.getString("boot"));
        assertEquals("front left 2.5 bar, front right 2.5 bar, rear left 2.5 bar, rear right 2.5 bar",
                out.getString("tyre pressure"));
        assertFalse("frisch: kein Schlaf-Hinweis", out.has("note"));
    }

    @Test
    public void klimaAndereModelleInGradUndAus() throws Exception {
        JSONObject roh = befund();
        assertEquals("on, set to 29 °C", AssistentZustand.klima(roh, false));
        assertEquals("on, Neutral", AssistentZustand.klima(new JSONObject()
                .put("climate_on", 1).put("climate_setpoint", 25), true));
        assertEquals("off", AssistentZustand.klima(new JSONObject().put("climate_on", 0), true));
        assertNull(AssistentZustand.klima(new JSONObject(), true));
    }

    @Test
    public void leererZustandBleibtLeer() {
        assertEquals(0, AssistentZustand.aus(null, null, true, 0L, WIEN).length());
    }

    @Test
    public void zeitenWerdenOrtszeit() throws Exception {
        long jetzt = 1793263440_000L; // 29.10.2026 09:44 Wien
        JSONObject ctx = new JSONObject()
                .put("latestTrip", new JSONObject()
                        .put("startTime", 1793175240_000L)   // 28.10. 09:14
                        .put("endTime", 1792915200_000L)     // 25.10. 09:00
                        .put("distanceKm", 12.5))
                .put("voltage12vAtMs", 5L);
        AssistentZustand.zeitenInOrtszeit(ctx, jetzt, WIEN);
        assertEquals("yesterday 09:14", ctx.getJSONObject("latestTrip").getString("startTime"));
        assertEquals("25 Oct 09:00", ctx.getJSONObject("latestTrip").getString("endTime"));
        assertEquals(12.5, ctx.getJSONObject("latestTrip").getDouble("distanceKm"), 0.0);
        assertFalse("unplausibler Zeitwert fällt weg", ctx.has("voltage12vAtMs"));
    }

    @Test
    public void anweisungInAllenFuenfSprachen() {
        String[][] erwartet = {
                {"de", "Alles in Ordnung.", "Heizen Stufe 4"},
                {"en", "All is fine.", "Heating level 4"},
                {"fr", "Tout va bien.", "Chauffage niveau 4"},
                {"it", "Tutto a posto.", "Riscaldamento livello 4"},
                {"nl", "Alles in orde.", "Verwarmen stand 4"},
        };
        for (String[] e : erwartet) {
            String s = GenAiContext.withResponseLanguage("x", e[0]);
            assertTrue(e[0], s.contains("(" + e[0] + ")"));
            assertTrue(e[0], s.contains(e[1]));
            assertTrue(e[0], s.contains(e[2]));
        }
        String stil = GenAiContext.PLAIN_STYLE;
        for (String s : new String[]{"8 lines", "Never show field names", "Never guess", "local time",
                "fully electric", "not a temperature"}) {
            assertTrue(s, stil.contains(s));
        }
    }
}
