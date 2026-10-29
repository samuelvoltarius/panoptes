package at.greenchili.surfmate.gewohnheiten;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 0.1.22 Gewohnheiten E1/E1b/E2 (GEWOHNHEITEN_KONZEPT.md). */
public class GewohnheitenTest {

    private static final ZoneId Z = ZoneId.of("Europe/Vienna");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ Herkunft

    @Test
    public void herkunftJeAnfrage() {
        assertEquals(Herkunft.DISPLAY, Herkunft.ausAnfrage(false, null, false, "/api/vehicle/climate"));
        assertEquals(Herkunft.HANDY, Herkunft.ausAnfrage(false, null, true, "/api/vehicle/climate"));
        assertEquals(Herkunft.HANDY, Herkunft.ausAnfrage(true, "handy", false, "/api/handy/v1/aktion"));
        assertEquals(Herkunft.HA, Herkunft.ausAnfrage(true, "homeassistant", false, "/api/vehicle/seat"));
        assertEquals(Herkunft.HA, Herkunft.ausAnfrage(true, "cockpit", false, "/api/ha/v1/aktion"));
        assertEquals(Herkunft.COCKPIT, Herkunft.ausAnfrage(true, "cockpit", false, "/api/wallbox/steuern"));
        assertEquals(Herkunft.COCKPIT, Herkunft.ausAnfrage(true, null, false, "/api/ladeplan"));
    }

    @Test
    public void nichtGesetztHeisstAutomationUndZaehltNicht() {
        Herkunft.setze(null);
        assertEquals(Herkunft.AUTOMATION, Herkunft.aktuell());
        assertNull(Herkunft.gesetzt());
        for (String q : new String[] {Herkunft.DISPLAY, Herkunft.HANDY, Herkunft.COCKPIT, Herkunft.HA, Herkunft.TELEGRAM,
                Herkunft.AUTO, Herkunft.BYD_APP}) assertTrue(q, Herkunft.zaehlt(q));
        assertFalse(Herkunft.zaehlt(Herkunft.AUTOMATION));
        assertFalse(Herkunft.zaehlt(Herkunft.AUTO_SYSTEM));
        assertFalse(Herkunft.zaehlt(null));
    }

    // ------------------------------------------------------------------ Kontext

    @Test
    public void kontextTagestypUndOrt() throws Exception {
        JSONArray orte = new JSONArray()
                .put(new JSONObject().put("id", "o1").put("lat", 48.30).put("lon", 14.275).put("r", 100)
                        .put("eigenschaften", new JSONObject().put("zuhause", true)))
                .put(new JSONObject().put("id", "buero").put("lat", 48.40).put("lon", 14.335).put("r", 100));
        Kontext.Werte w = new Kontext.Werte();
        w.orte = orte;
        w.lat = 48.3001;
        w.lon = 14.2751;
        w.aussen = 3;
        JSONObject k = Kontext.bilde(ms(2026, 10, 26, 7, 2), Z, w);   // Montag
        assertEquals("werktag", k.getString("tag"));
        assertEquals("mo", k.getString("wt"));
        assertEquals(7 * 60 + 2, k.getInt("min"));
        assertEquals("zuhause", k.getString("ort"));
        assertEquals(3, k.getInt("aussen"));
        assertEquals("wochenende", Kontext.bilde(ms(2026, 10, 25, 9, 0), Z, w).getString("tag"));
        assertEquals("buero", Kontext.ort(orte, 48.4001, 14.3351));
        assertEquals("weg", Kontext.ort(orte, 48.5, 14.0));
        assertEquals("unbekannt", Kontext.ort(orte, Double.NaN, 13.0));
    }

    // ------------------------------------------------------------------ Erwartungen (Echo)

    @Test
    public void erwartungTrifftZielOderRichtungUndVerfaellt() {
        Erwartungen e = new Erwartungen();
        e.erwarte("temp_fahrer", 22, 1000);
        assertFalse("andere Richtung", e.verbrauche("temp_fahrer", 22, 21, 10));
        assertTrue("Richtung zum Ziel", e.verbrauche("temp_fahrer", 20, 21, 10));
        assertFalse("verbraucht", e.verbrauche("temp_fahrer", 20, 22, 10));
        e.erwarte("klima", 1, 1000);
        assertFalse("abgelaufen", e.verbrauche("klima", 0, 1, 2000));
        assertEquals(0, e.anzahl());
    }

    // ------------------------------------------------------------------ Am Auto (E1b)

    @Test
    public void bedienungAmAutoEchoUndAutoSystem() throws Exception {
        Erwartungen erw = new Erwartungen();
        AutoBeobachter b = new AutoBeobachter(erw);
        long t = 1_000_000;
        assertTrue(b.beobachte(stand(true, AutoBeobachter.UNBEKANNT, 0), t).isEmpty());
        assertTrue("unbekannt → Wert ist keine Handlung", b.beobachte(stand(true, 0, 0), t += 5000).isEmpty());

        // Zündung an (Flanke) → die ersten 30 s stellt das Auto selbst wieder her
        b.beobachte(stand(false, 0, 0), t += 5000);
        b.beobachte(stand(true, 0, 0), t += 5000);
        List<AutoBeobachter.Ereignis> l = b.beobachte(stand(true, 1, 0), t += 5000);
        assertEquals(1, l.size());
        assertEquals(Herkunft.AUTO_SYSTEM, l.get(0).quelle);
        assertEquals("nach_zuendung_an", l.get(0).grenzfall);

        // später: Mensch am Auto
        b.beobachte(stand(true, 0, 0), t += 60_000);
        l = b.beobachte(stand(true, 1, 0), t += 5000);
        assertEquals(Aktionen.KLIMA_AN, l.get(0).aktion);
        assertEquals(Herkunft.AUTO, l.get(0).quelle);
        assertNull(l.get(0).grenzfall);

        // Panoptes hat Sitzheizung 2 befohlen → Echo zählt nicht
        erw.erwarte(AutoBeobachter.HEIZ_FAHRER, 2, t + 15_000);
        assertTrue(b.beobachte(stand(true, 1, 2), t += 5000).isEmpty());

        // Zündung aus → Klima aus binnen 60 s ist das Auto selbst
        b.beobachte(stand(false, 1, 2), t += 5000);
        l = b.beobachte(stand(false, 0, 2), t += 5000);
        assertEquals(Aktionen.KLIMA_AUS, l.get(0).aktion);
        assertEquals("nach_zuendung_aus", l.get(0).grenzfall);

        // Zündung aus, viel später: vermutlich BYD-App
        l = b.beobachte(stand(false, 1, 2), t += 600_000);
        assertEquals(Herkunft.BYD_APP, l.get(0).quelle);

        // Zündung unbekannt → nie gezählt
        AutoBeobachter.Stand s = stand(true, 0, 2);
        s.zuendung = null;
        l = b.beobachte(s, t += 5000);
        assertEquals(Herkunft.AUTO_SYSTEM, l.get(0).quelle);
    }

    @Test
    public void ladeendeSchaltetSelbstAb() throws Exception {
        AutoBeobachter b = new AutoBeobachter(new Erwartungen());
        long t = 5_000_000;
        AutoBeobachter.Stand a = stand(false, 1, 0);
        a.laedt = true;
        b.beobachte(a, t);
        AutoBeobachter.Stand c = stand(false, 1, 0);
        c.laedt = false;
        b.beobachte(c, t += 5000);
        AutoBeobachter.Stand d = stand(false, 0, 0);
        d.laedt = false;
        List<AutoBeobachter.Ereignis> l = b.beobachte(d, t += 5000);
        assertEquals("nach_ladeende", l.get(0).grenzfall);
    }

    @Test
    public void drehradZaehltErstNachRuheMitEndwert() throws Exception {
        AutoBeobachter b = new AutoBeobachter(new Erwartungen());
        long t = 9_000_000;
        b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 20).zuendungAn(), t);
        b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 20).zuendungAn(), t += 60_000);
        assertTrue(b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 21).zuendungAn(), t += 1000).isEmpty());
        assertTrue(b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 22).zuendungAn(), t += 1000).isEmpty());
        assertTrue(b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 22).zuendungAn(), t += 5000).isEmpty());
        List<AutoBeobachter.Ereignis> l = b.beobachte(new AutoBeobachter.Stand().setze(AutoBeobachter.TEMP_FAHRER, 22).zuendungAn(), t += 6000);
        assertEquals(1, l.size());
        assertEquals(Aktionen.KLIMA_TEMP, l.get(0).aktion);
        assertEquals(22, l.get(0).wert.getInt("temp"));
        assertEquals("fahrer", l.get(0).wert.getString("zone"));
        assertEquals(Herkunft.AUTO, l.get(0).quelle);
    }

    // ------------------------------------------------------------------ Erkennen (E2)

    @Test
    public void dreiWerktageUmSiebenZuhauseIstEineGewohnheit() throws Exception {
        List<JSONObject> log = new ArrayList<>();
        log.add(e(ms(2026, 10, 19, 6, 58), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 418, "zuhause", 2));
        log.add(e(ms(2026, 10, 20, 7, 4), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 424, "zuhause", 4));
        log.add(e(ms(2026, 10, 22, 7, 1), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.AUTO, "werktag", 421, "zuhause", 1));
        log.add(e(ms(2026, 10, 23, 6, 55), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 415, "zuhause", 3));
        List<JSONObject> v = GewohnheitsErkenner.erkenne(log, ms(2026, 10, 27, 12, 0), Z, Collections.emptySet(), null);
        assertEquals(1, v.size());
        JSONObject g = v.get(0);
        assertEquals(Aktionen.KLIMA_AN, g.getString("aktion"));
        assertEquals("werktag", g.getString("tag"));
        assertEquals("zuhause", g.getString("ort"));
        assertEquals("07:00", g.getString("uhrzeit"));
        assertEquals(4, g.getInt("anzahl"));
        assertEquals(4, g.getJSONArray("treffer").length());
        assertEquals("herkunftsübergreifend, meist Handy", Herkunft.HANDY, g.getString("quelle_meist"));
        assertEquals("alle unter 5 °C", 5, g.getJSONObject("bedingung").getInt("aussen_unter"));
        assertEquals("klima_an|temp=22|werktag|zuhause|7", g.getString("schluessel"));

        // abgelehnt („Nein“) → nie wieder
        assertTrue(GewohnheitsErkenner.erkenne(log, ms(2026, 10, 27, 12, 0), Z,
                Collections.singleton(g.getString("schluessel")), null).isEmpty());
        // schon eine Automation um 7:10 → keine Doppelung
        assertTrue(GewohnheitsErkenner.erkenne(log, ms(2026, 10, 27, 12, 0), Z, null,
                Collections.singletonList(new GewohnheitsErkenner.Abdeckung(Aktionen.KLIMA_AN, 430))).isEmpty());
    }

    @Test
    public void zuWenigOderNichtGezaehltIstKeineGewohnheit() throws Exception {
        long jetzt = ms(2026, 10, 27, 12, 0);
        // nur zwei Tage
        List<JSONObject> zwei = new ArrayList<>();
        zwei.add(e(ms(2026, 10, 19, 7, 0), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 420, "zuhause", 2));
        zwei.add(e(ms(2026, 10, 20, 7, 0), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 420, "zuhause", 2));
        zwei.add(e(ms(2026, 10, 20, 7, 30), Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, "werktag", 450, "zuhause", 2));
        assertTrue("zweimal am selben Tag zählt einmal", GewohnheitsErkenner.erkenne(zwei, jetzt, Z, null, null).isEmpty());

        assertTrue("Automation zählt nie", GewohnheitsErkenner.erkenne(drei(Aktionen.KLIMA_AN, Herkunft.AUTOMATION, "zuhause", 420, 420, 420),
                jetzt, Z, null, null).isEmpty());
        assertTrue("auto_system zählt nie", GewohnheitsErkenner.erkenne(drei(Aktionen.KLIMA_AN, Herkunft.AUTO_SYSTEM, "zuhause", 420, 420, 420),
                jetzt, Z, null, null).isEmpty());
        assertTrue("weg wird nie vorgeschlagen", GewohnheitsErkenner.erkenne(drei(Aktionen.KLIMA_AN, Herkunft.AUTO, "weg", 420, 420, 420),
                jetzt, Z, null, null).isEmpty());
        assertTrue("Wächter aus nie (F1)", GewohnheitsErkenner.erkenne(drei(Aktionen.WAECHTER_AUS, Herkunft.HANDY, "zuhause", 420, 420, 420),
                jetzt, Z, null, null).isEmpty());
        assertTrue("Ladeplan nur mitgeschrieben", GewohnheitsErkenner.erkenne(drei(Aktionen.LADEPLAN, Herkunft.HANDY, "zuhause", 420, 420, 420),
                jetzt, Z, null, null).isEmpty());
        assertTrue("über ±30 min verstreut", GewohnheitsErkenner.erkenne(drei(Aktionen.KLIMA_AN, Herkunft.AUTO, "zuhause", 400, 440, 480),
                jetzt, Z, null, null).isEmpty());
        assertEquals("innerhalb ±30 min", 1, GewohnheitsErkenner.erkenne(drei(Aktionen.KLIMA_AN, Herkunft.AUTO, "zuhause", 400, 420, 440),
                jetzt, Z, null, null).size());
    }

    @Test
    public void fehlgriffBinnenZweiMinutenZaehltNicht() throws Exception {
        List<JSONObject> log = drei(Aktionen.SITZHEIZUNG, Herkunft.AUTO, "zuhause", 420, 420, 420);
        // am dritten Tag sofort wieder aus → der dritte Treffer zählt nicht
        long t3 = log.get(2).getLong("t");
        log.add(e(t3 + 60_000, Aktionen.SITZHEIZUNG, w("sitz", "fahrer").put("stufe", 0), Herkunft.AUTO, "werktag", 421, "zuhause", 2));
        assertTrue(GewohnheitsErkenner.erkenne(log, ms(2026, 10, 27, 12, 0), Z, null, null).isEmpty());
    }

    @Test
    public void wallboxWennAngesteckt() throws Exception {
        List<JSONObject> log = drei(Aktionen.WALLBOX_STROM, Herkunft.COCKPIT, "zuhause", 1320, 1325, 1318);
        for (JSONObject x : log) x.put("wert", w("a", 16)).getJSONObject("kontext").put("angesteckt", true);
        List<JSONObject> v = GewohnheitsErkenner.erkenne(log, ms(2026, 10, 27, 12, 0), Z, null, null);
        assertEquals(1, v.size());
        assertTrue(v.get(0).getJSONObject("bedingung").getBoolean("angesteckt"));
        assertEquals("22:00", v.get(0).getString("uhrzeit"));
        assertEquals(Herkunft.COCKPIT, v.get(0).getString("quelle_meist"));
    }

    // ------------------------------------------------------------------ Log und Laufzeit (E1)

    @Test
    public void logSchreibenKuerzenLoeschen() throws Exception {
        GewohnheitsLog log = new GewohnheitsLog(new File(tmp.getRoot(), "g/log.jsonl"));
        long jetzt = ms(2026, 10, 27, 12, 0);
        log.schreibe(new JSONObject().put("t", jetzt - 100L * 86_400_000L).put("aktion", "klima_an"));
        log.schreibe(new JSONObject().put("t", jetzt - 10L * 86_400_000L).put("aktion", "klima_an"));
        log.schreibe(new JSONObject().put("t", jetzt).put("aktion", "klima_aus"));
        assertEquals(3, log.lies().size());
        assertEquals(1, log.kuerze(jetzt, 60, 5000));
        assertEquals(2, log.lies().size());
        assertEquals(1, log.kuerze(jetzt, 60, 1));
        assertEquals("klima_aus", log.lies().get(0).getString("aktion"));
        log.loesche();
        assertTrue(log.lies().isEmpty());
    }

    @Test
    public void ohneEinwilligungWirdNichtsMitgeschrieben() throws Exception {
        TestUmgebung u = new TestUmgebung();
        Gewohnheiten g = new Gewohnheiten(tmp.getRoot(), u, Runnable::run);
        g.notiere(Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, null);
        assertTrue(g.log.lies().isEmpty());

        u.einstellung = new JSONObject().put("an", true);
        g.notiere(Aktionen.KLIMA_AN, w("temp", 22), Herkunft.HANDY, null);
        g.notiere("aufsperren", null, Herkunft.HANDY, null);   // nie mitgeschrieben (Erlaubnisliste)
        List<JSONObject> l = g.log.lies();
        assertEquals(1, l.size());
        assertEquals(Herkunft.HANDY, l.get(0).getString("quelle"));
        assertEquals("zuhause", l.get(0).getJSONObject("kontext").getString("ort"));
        assertTrue(g.status().getBoolean("an"));
        assertEquals(1, g.status().getJSONObject("quellen").getInt("handy"));

        // „Alles vergessen“ aus den Einstellungen – gilt schon beim nächsten Status
        u.einstellung.put("vergessenAm", u.t + 1);
        u.t += 1000;
        assertEquals(0, g.status().getInt("eintraege"));
        g.notiere(Aktionen.KLIMA_AUS, null, Herkunft.DISPLAY, null);
        l = g.log.lies();
        assertEquals("nur der Eintrag nach dem Vergessen", 1, l.size());
        assertEquals(Aktionen.KLIMA_AUS, l.get(0).getString("aktion"));
    }

    @Test
    public void temperaturGleicherWertGleicherText() {
        assertEquals(22, Gewohnheiten.temp(22.0));
        assertEquals(21.5, Gewohnheiten.temp(21.4));
        assertEquals(22, Gewohnheiten.temp(21.8));
    }

    // ------------------------------------------------------------------ Regeln und Verdrahtung

    @Test
    public void nieAufsperrenHupeLichtBlinkerAutoFinden() throws Exception {
        for (String a : Aktionen.MITSCHREIBEN) {
            for (String verboten : new String[] {"auf", "unlock", "hup", "horn", "licht", "light", "blink", "flash", "find"}) {
                assertFalse(a, a.contains(verboten) && !a.equals(Aktionen.WALLBOX_STOPP));
            }
        }
        assertTrue(Aktionen.VORSCHLAGBAR.containsAll(Collections.singleton(Aktionen.WAECHTER_AN)));
        assertFalse(Aktionen.VORSCHLAGBAR.contains(Aktionen.WAECHTER_AUS));
        String haken = lies("app/src/main/java/at/greenchili/surfmate/gewohnheiten/BefehlsHaken.java");
        for (String c : new String[] {"Unlock", "FindCar", "FlashLights", "LightsCommand", "Hazard", "LockCommand", "Window", "Trunk"}) {
            assertFalse(c, haken.contains(c));
        }
    }

    @Test
    public void verdrahtung() throws Exception {
        String r = lies("app/src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        assertTrue(r.contains("BefehlsHaken.vor(cmd);") && r.contains("BefehlsHaken.nach(cmd, r);"));
        String e = lies("app/src/main/java/at/greenchili/surfmate/automation/condition/BydEvent.java");
        int b = e.indexOf("public static void bydEvent(");
        assertTrue("vor der Automations-Sperre", e.indexOf("Gewohnheiten.beobachte(data)", b) < e.indexOf("Automations.isDisabled()", b));
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(h.contains("Herkunft.setze(at.greenchili.surfmate.gewohnheiten.Herkunft.ausAnfrage("));
        assertTrue("je Anfrage zurückgesetzt", h.contains("Herkunft.setze(null);"));
        assertTrue("Automationen zählen nie", h.contains("Herkunft.setze(at.greenchili.surfmate.gewohnheiten.Herkunft.AUTOMATION)"));
        assertTrue(h.contains("GewohnheitenApiHandler.handle("));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/wallbox/WallboxDienst.java").contains("BefehlsHaken.wallbox(aktion, wert)"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/camping/Camping.java").contains("BefehlsHaken.camping(true, quelle)"));
        String s = lies("app/src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java");
        assertTrue(s.contains("Aktionen.WAECHTER_AN") && s.contains("Aktionen.WAECHTER_AUS"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java").contains("Herkunft.TELEGRAM"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/mqtt/MqttCommandRouter.java").contains("Herkunft.HA"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/LadeplanApiHandler.java").contains("Aktionen.LADEPLAN"));
    }

    @Test
    public void einwilligungImEinrichtenStandardAus() throws Exception {
        assertTrue(at.greenchili.surfmate.server.EinrichtungApiHandler.SCHRITTE.contains("extra_gewohnheiten"));
        assertFalse(at.greenchili.surfmate.server.EinrichtungApiHandler.PFLICHT.contains("extra_gewohnheiten"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/EinrichtungModell.kt").contains("\"extra_gewohnheiten\""));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/SchrittKatalog.kt").contains("GewohnheitenSchritt(a)"));
        at.greenchili.surfmate.einstellungen.Feld f = at.greenchili.surfmate.einstellungen.Katalog.finde(
                at.greenchili.surfmate.einstellungen.Katalog.felder(new at.greenchili.surfmate.einstellungen.Katalog.Kontext()), "gewohnheiten.an");
        assertTrue(f != null);
        assertEquals("Regel 2: aus bis zur Antwort", false, f.standard);
        assertTrue("Kopplung darf nicht einschalten", f.nurLesendExtern);
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json")).getJSONObject("settings").getJSONObject("gewohnheiten");
            assertFalse(l, s.getJSONObject("an").optString("title").isEmpty());
            assertFalse(l, s.getJSONObject("vergessen").optString("loeschen").isEmpty());
            assertFalse(l, new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("einr")
                    .getJSONObject("schritt").optString("extra_gewohnheiten").isEmpty());
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static final class TestUmgebung implements Gewohnheiten.Umgebung {
        long t = ms(2026, 10, 26, 7, 0);
        JSONObject einstellung;

        public long jetzt() { return t; }
        public ZoneId zone() { return Z; }
        public JSONObject einstellung() { return einstellung; }
        public Kontext.Werte kontext() {
            Kontext.Werte w = new Kontext.Werte();
            try {
                w.orte = new JSONArray().put(new JSONObject().put("id", "h").put("lat", 48.3).put("lon", 14.235).put("r", 50)
                        .put("eigenschaften", new JSONObject().put("zuhause", true)));
            } catch (Exception ignored) {
            }
            w.lat = 48.3;
            w.lon = 14.235;
            return w;
        }
        public Boolean zuendung() { return true; }
    }

    private static AutoBeobachter.Stand stand(Boolean zuendung, int klima, int heizFahrer) {
        AutoBeobachter.Stand s = new AutoBeobachter.Stand();
        s.setze(AutoBeobachter.KLIMA, klima).setze(AutoBeobachter.HEIZ_FAHRER, heizFahrer);
        s.zuendung = zuendung;
        return s;
    }

    private static List<JSONObject> drei(String aktion, String quelle, String ort, int m1, int m2, int m3) throws Exception {
        List<JSONObject> l = new ArrayList<>();
        int[] m = {m1, m2, m3};
        int[] tag = {19, 20, 21};
        for (int i = 0; i < 3; i++) {
            l.add(e(ms(2026, 10, tag[i], m[i] / 60, m[i] % 60), aktion,
                    Aktionen.SITZHEIZUNG.equals(aktion) ? w("sitz", "fahrer").put("stufe", 2) : w("temp", 22),
                    quelle, "werktag", m[i], ort, 12));
        }
        return l;
    }

    private static JSONObject e(long t, String aktion, JSONObject wert, String quelle, String tag, int min, String ort, int aussen) throws Exception {
        return new JSONObject().put("t", t).put("aktion", aktion).put("wert", wert).put("quelle", quelle)
                .put("kontext", new JSONObject().put("tag", tag).put("min", min).put("ort", ort).put("aussen", aussen));
    }

    private static JSONObject w(String k, Object v) throws Exception {
        return new JSONObject().put(k, v);
    }

    private static long ms(int j, int mo, int d, int h, int mi) {
        return LocalDateTime.of(j, mo, d, h, mi).atZone(Z).toInstant().toEpochMilli();
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
