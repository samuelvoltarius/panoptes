package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.16 Planer §17: Auto-Seite der Neuplanung unterwegs (POST plan-abweichung). */
public class PlanAbweichungTest {

    static JSONObject plan() throws Exception {
        return new JSONObject()
                .put("geometrie", new JSONArray().put(new JSONArray().put(48.3).put(14.275))
                        .put(new JSONArray().put(48.2).put(16.37)))
                .put("soc_start", 90).put("soc_ankunft", 30)
                .put("ziel", new JSONObject().put("name", "Wien").put("lat", 48.2).put("lon", 16.37))
                .put("stopps", new JSONArray().put(new JSONObject().put("km", 180).put("soc_an", 25).put("soc_ab", 80)))
                .put("titel", "geheim nicht mitschicken").put("erstellt", "2026-10-23T10:00:00Z");
    }

    @Test
    public void taktZehnMinutenOderFuenfProzentpunkte() {
        PlanAbweichung p = new PlanAbweichung();
        assertTrue(p.faellig(0, 61));
        p.gefragt(0, 61);
        assertFalse(p.faellig(5 * 60_000L, 59));
        assertTrue("5 pp weniger", p.faellig(5 * 60_000L, 56));
        assertTrue("10 min", p.faellig(10 * 60_000L, 60));
        p.zuruecksetzen();
        assertTrue(p.faellig(1, 60));
    }

    @Test
    public void anfrageNurPlanfelderPositionGerundet() throws Exception {
        JSONObject b = PlanAbweichung.anfrage(plan(), 48.412345, 14.802891, 55.4, 10);
        assertNotNull(b);
        JSONObject p = b.getJSONObject("plan");
        assertTrue(p.has("geometrie") && p.has("stopps") && p.has("ziel") && p.has("soc_start"));
        assertFalse("nur Felder aus §3", p.has("titel") || p.has("erstellt"));
        assertEquals(48.412, b.getJSONArray("position").getDouble(0), 1e-9);
        assertEquals(14.803, b.getJSONArray("position").getDouble(1), 1e-9);
        assertEquals(55, b.getInt("soc"));
        assertEquals(10, b.getInt("reserve"));
        assertNull(PlanAbweichung.anfrage(new JSONObject(), 47, 13, 50, 10));
        assertNull(PlanAbweichung.anfrage(plan(), Double.NaN, 13, 50, 10));
    }

    @Test
    public void antwortUndNeuplanAnfrage() throws Exception {
        // so wie live am 23.10. (Sbg→Wien, km 35 mit 55 % statt 61 %)
        JSONObject res = new JSONObject().put("ok", true).put("neu_planen", true).put("abweichung_pp", -6)
                .put("gruende", new JSONArray().put("soc_unter_plan"))
                .put("neu_plan_anfrage", new JSONObject().put("start", new JSONArray().put(48.4).put(14.835))
                        .put("ziel", new JSONArray().put(48.2).put(16.37)).put("soc_start", 55)
                        .put("via", new JSONArray()).put("soc_reserve", 10).put("fremd", 1));
        PlanAbweichung.Befund b = PlanAbweichung.auswerten(res);
        assertTrue(b.ok && b.neuPlanen);
        assertEquals("soc_unter_plan", b.gruende.getString(0));
        JSONObject plan = PlanAbweichung.planAnfrage(b.neuPlanAnfrage, "byd-dolphin-surf", "a-0123456789abcdef");
        assertEquals(55, plan.getInt("soc_start"));
        assertEquals(10, plan.getInt("soc_ziel_min"));
        assertEquals("a-0123456789abcdef", plan.getString("auto"));
        assertFalse(plan.has("fremd"));
        assertFalse(PlanAbweichung.auswerten(null).ok);
        assertFalse(PlanAbweichung.auswerten(new JSONObject().put("ok", false).put("fehler", "x")).ok);
        assertNull("ohne neu_planen keine Anfrage", PlanAbweichung.auswerten(new JSONObject().put("ok", true)
                .put("neu_planen", false).put("neu_plan_anfrage", new JSONObject().put("start", 1).put("ziel", 2)))
                .neuPlanAnfrage);
    }

    @Test
    public void vorschlagAusAbweichungBleibtBeiPasstBisEntwarnung() {
        Neuplanung n = new Neuplanung();
        Neuplanung.Vorschlag v = new Neuplanung.Vorschlag();
        v.art = "stopp";
        v.quelle = Neuplanung.QUELLE_ABWEICHUNG;
        n.ergebnis(v);
        assertNotNull("lokal „Passt“ (z. B. 3 km neben der Route) verwirft ihn nicht", n.aktuell(RangeWatch.Stufe.PASST));
        n.abweichungEntwarnt();
        assertNull(n.aktuell(RangeWatch.Stufe.PASST));
        Neuplanung.Vorschlag lokal = new Neuplanung.Vorschlag();
        lokal.art = "stopp";
        n.ergebnis(lokal);
        assertNull("lokaler Vorschlag wie bisher", n.aktuell(RangeWatch.Stufe.PASST));
        // Ablehnen sperrt auch die Abweichung
        n.ergebnis(v);
        n.ablehnen(1000);
        assertFalse(n.abweichungErlaubt(1000 + 60_000));
        assertTrue(n.abweichungErlaubt(1000 + Neuplanung.SPERRE_NACH_ABLEHNUNG_MS));
    }

    @Test
    public void nurMitEinwilligungUndOhneSchluessel() throws Exception {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "planner.abweichung");
        assertNotNull(f);
        assertEquals(Boolean.FALSE, f.standard);
        assertEquals("datenschutz", f.gruppe);
        assertTrue(f.nurLesendExtern);
        File d = new File("src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java");
        if (!d.isFile()) d = new File("app/" + d.getPath());
        String k = new String(Files.readAllBytes(d.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int a = k.indexOf("private void abweichungPruefen(");
        String body = k.substring(a, k.indexOf("\n    }\n", a));
        assertTrue(body.contains("if (!planner.optBoolean(\"abweichung\", false) || planRoh == null) return;"));
        assertTrue("öffentlich, ohne Schlüssel", body.contains("http(\"POST\", \"plan-abweichung\", null, body.toString())"));
        assertTrue(body.contains("v.quelle = Neuplanung.QUELLE_ABWEICHUNG;"));
        assertFalse("nichts automatisch ins Navi", body.contains("navigate") || body.contains("setPendingNav"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            File s = new File("src/main/assets/server-i18n/" + l + ".json");
            if (!s.isFile()) s = new File("app/" + s.getPath());
            JSONObject t = new JSONObject(new String(Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8))
                    .getJSONObject("settings").getJSONObject("planner").getJSONObject("abweichung");
            assertFalse(l, t.getString("title").isEmpty() || t.getString("help").isEmpty());
        }
    }
}
