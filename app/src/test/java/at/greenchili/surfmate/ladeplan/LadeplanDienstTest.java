package at.greenchili.surfmate.ladeplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.charging.TariffProfile;
import at.greenchili.surfmate.charging.Zeittarif;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** 0.1.20 Ladeplan-Ausführung gegen eine Wallbox-Attrappe (LADEPLAN_KONZEPT §5). */
public class LadeplanDienstTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final ZoneId WIEN = ZoneId.of("Europe/Vienna");
    static final String LP = "direkt:haus";

    long jetzt = ZonedDateTime.of(2026, 10, 28, 22, 15, 0, 0, WIEN).toInstant().toEpochMilli();
    double soc = 40;
    double lat = 47.8, lon = 13.0;
    String sperre = null;
    long hand = 0;

    /** Wallbox-Attrappe: merkt Befehle, Echo einstellbar. */
    final class Box implements LadeplanDienst.Wallbox {
        String modus = "aus";
        boolean verbunden = true;
        boolean echo = true;
        String hinweis = null;
        int stromA = 6;
        final List<String> befehle = new ArrayList<>();

        @Override public JSONObject status() throws JSONException {
            JSONObject lp = new JSONObject().put("id", LP).put("titel", "Garage").put("art", "wallbox")
                    .put("verbunden", verbunden).put("modus", modus).put("stromA", stromA)
                    .put("faehigkeiten", new JSONObject().put("strom", new JSONObject().put("min", 6).put("max", 16)));
            if (hinweis != null) lp.put("hinweis", hinweis);
            return new JSONObject().put("erreichbar", true).put("eingerichtet", true).put("ladepunkte", new JSONArray().put(lp));
        }

        @Override public String fahrsperre() {
            return sperre;
        }

        @Override public JSONObject steuern(String ladepunkt, String aktion, Object wert) throws JSONException {
            befehle.add(ladepunkt + " " + aktion + "=" + wert);
            if (!echo) return new JSONObject().put("status", 200).put("json", new JSONObject().put("ok", false).put("uebernommen", false));
            if ("modus".equals(aktion)) modus = String.valueOf(wert);
            if ("strom".equals(aktion)) stromA = (Integer) wert;
            return new JSONObject().put("status", 200).put("json", new JSONObject().put("ok", true).put("uebernommen", true));
        }

        @Override public long letzteHandSteuerung(String ladepunkt) {
            return hand;
        }
    }

    Box box;
    LadeplanDienst d;

    @Before
    public void setUp() throws Exception {
        box = new Box();
        JSONObject tj = new TariffProfile("Zuhause", 47.8, 13.0, 50, 0.30, 0, "€").toJson().put("id", "t1")
                .put("zeittarif", Zeittarif.vorlage(Zeittarif.VORLAGE_TAG_NACHT, 0.30, "AT"));
        TariffProfile tarif = new TariffProfile(tj);
        JSONArray orte = new JSONArray().put(new JSONObject().put("id", "o1").put("name", "Zuhause").put("lat", 47.8).put("lon", 13.0)
                .put("r", 100).put("eigenschaften", new JSONObject().put("zuhause", true)).put("verweise", new JSONObject().put("tarif", "t1")));
        LadeplanDienst.Umgebung u = new LadeplanDienst.Umgebung() {
            @Override public long jetzt() { return jetzt; }
            @Override public ZoneId zone() { return WIEN; }
            @Override public JSONObject telemetrie() {
                try {
                    return new JSONObject().put("soc", soc).put("lat", lat).put("lon", lon);
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            @Override public JSONArray orte() { return orte; }
            @Override public TariffProfile tarif(String id) { return "t1".equals(id) ? tarif : null; }
            @Override public double festerPreis() { return 0.30; }
            @Override public double kapazitaetKwh() { return 40; }
        };
        d = new LadeplanDienst(tmp.newFile("ladeplan.json"), new File2(tmp).f("zustand.json"), u, box);
        assertNull(d.aendere(new JSONObject().put("modus", "guenstig").put("ladepunkt", LP), false));
    }

    /** Neue (noch nicht angelegte) Datei im Temp-Ordner. */
    static final class File2 {
        final TemporaryFolder t;
        File2(TemporaryFolder t) { this.t = t; }
        java.io.File f(String n) { return new java.io.File(t.getRoot(), n); }
    }

    void freigeben() throws Exception {
        assertNull(d.aendere(new JSONObject().put("freigabe", new JSONObject().put("an", true)), false));
    }

    @Test
    public void probelaufSchaltetNie() throws Exception {
        JSONObject s = d.takt();
        assertEquals("probelauf", s.getJSONObject("zustand").getString("phase"));
        assertTrue(s.getJSONObject("zustand").getBoolean("wuerdeLaden"));
        assertTrue(box.befehle.isEmpty());
        assertEquals("probelauf", s.getJSONArray("letzte").getJSONObject(0).getString("grund"));
        assertEquals("zeittarif", s.getJSONObject("tarif").getString("art"));
        assertEquals(0, s.getJSONObject("vorschau").getDouble("ersparnis"), 1e-9);   // jetzt schon im Nachtband
    }

    @Test
    public void mitFreigabeStartUndStoppMitEcho() throws Exception {
        freigeben();
        JSONObject s = d.takt();
        assertEquals("laedt", s.getJSONObject("zustand").getString("phase"));
        assertEquals(java.util.Arrays.asList(LP + " modus=sofort"), box.befehle);
        assertEquals("sofort", box.modus);
        // Ziel erreicht → Stopp
        soc = 80;
        jetzt += 60_000;
        s = d.takt();
        assertEquals(LP + " modus=aus", box.befehle.get(1));
        assertEquals("fertig", s.getJSONObject("zustand").getString("phase"));
        assertEquals(2, s.getJSONObject("zustand").getInt("schaltungen"));
    }

    @Test
    public void hoechststromWirdVorDemStartGesetzt() throws Exception {
        freigeben();
        assertNull(d.aendere(new JSONObject().put("hoechststromA", 10), false));
        d.takt();
        assertEquals(java.util.Arrays.asList(LP + " strom=10", LP + " modus=sofort"), box.befehle);
    }

    @Test
    public void ohneEchoEinmalWiederholenDannPause() throws Exception {
        freigeben();
        box.echo = false;
        JSONObject s = d.takt();
        assertEquals("laedt", s.getJSONObject("zustand").getString("phase"));
        jetzt += 60_000;
        s = d.takt();
        assertEquals("pausiert", s.getJSONObject("zustand").getString("phase"));
        assertEquals("fehlschlag", s.getJSONObject("zustand").getJSONObject("pausiert").getString("grund"));
        jetzt += 60_000;
        d.takt();
        assertEquals(2, box.befehle.size());
        // Abstecken + Einstecken hebt die Pause auf
        box.verbunden = false;
        d.takt();
        box.verbunden = true;
        box.echo = true;
        s = d.takt();
        assertEquals("laedt", s.getJSONObject("zustand").getString("phase"));
        assertEquals("sofort", box.modus);
    }

    @Test
    public void brueckenSperreFahrsperreUndUnterwegsSchaltenNicht() throws Exception {
        freigeben();
        box.hinweis = "bruecke_gesperrt";
        assertEquals("bruecke_gesperrt", d.takt().getJSONObject("zustand").getString("phase"));
        box.hinweis = null;
        sperre = "fahrt";
        assertEquals("fahrsperre", d.takt().getJSONObject("zustand").getString("phase"));
        sperre = null;
        lat = 48.2;
        JSONObject s = d.takt();
        assertEquals("nicht_zuhause", s.getJSONObject("zustand").getString("phase"));
        // Vorschau gibt es trotzdem (Tarif des Heimorts)
        assertTrue(s.getJSONObject("vorschau").getDouble("kwh") > 0);
        box.verbunden = false;
        lat = 47.8;
        assertEquals("nicht_angesteckt", d.takt().getJSONObject("zustand").getString("phase"));
        assertTrue(box.befehle.isEmpty());
    }

    @Test
    public void handGehtVorPlan() throws Exception {
        freigeben();
        d.takt();
        hand = jetzt + 30_000;   // Nutzer schaltet selbst über Panoptes
        jetzt += 60_000;
        JSONObject s = d.takt();
        assertEquals("pausiert", s.getJSONObject("zustand").getString("phase"));
        assertEquals("hand", s.getJSONObject("zustand").getJSONObject("pausiert").getString("grund"));
        // Wallbox-App stoppt: Plan kämpft nicht
        box.modus = "aus";
        jetzt += 60_000;
        d.takt();
        assertEquals(1, box.befehle.size());
        d.weiter();
        jetzt += 60_000;
        d.takt();
        assertEquals("sofort", box.modus);
    }

    @Test
    public void fremdeAbweichungNachRuhezeitPausiert() throws Exception {
        freigeben();
        d.takt();
        box.modus = "aus";   // z. B. Wallbox-App
        jetzt += LadeplanDienst.HAND_RUHE_MS + 60_000;
        JSONObject s = d.takt();
        assertEquals("pausiert", s.getJSONObject("zustand").getString("phase"));
        assertEquals(1, box.befehle.size());
    }

    @Test
    public void planAusStopptEinmalUndDannNieWieder() throws Exception {
        freigeben();
        d.takt();
        assertNull(d.aendere(new JSONObject().put("modus", "aus"), false));
        assertEquals(LP + " modus=aus", box.befehle.get(1));
        jetzt += 60_000;
        assertEquals("aus", d.takt().getJSONObject("zustand").getString("phase"));
        assertEquals(2, box.befehle.size());
    }

    @Test
    public void externDarfPlanAberNieFreigabe() throws Exception {
        assertEquals("nur_am_auto", d.aendere(new JSONObject().put("freigabe", new JSONObject().put("an", true)), true));
        assertEquals("nur_am_auto", d.aendere(new JSONObject().put("ladepunkt", "direkt:x"), true));
        assertNull(d.aendere(new JSONObject().put("mindestladung", 40), true));
        assertEquals(40, d.einstellungen().getInt("mindestladung"));
        assertFalse(d.einstellungen().getJSONObject("freigabe").getBoolean("an"));
        assertEquals("modus_ungueltig", d.aendere(new JSONObject().put("modus", "boerse"), false));
        assertEquals("phasen_ungueltig", d.aendere(new JSONObject().put("phasen", 2), false));
    }

    @Test
    public void freigabeGiltNurFuerDenLadepunkt() throws Exception {
        freigeben();
        JSONObject f = d.einstellungen().getJSONObject("freigabe");
        assertEquals(LP, f.getString("ladepunkt"));
        assertEquals(jetzt, f.getLong("zeit"));
        assertNull(d.aendere(new JSONObject().put("ladepunkt", "direkt:anders"), false));
        assertFalse(d.einstellungen().getJSONObject("freigabe").getBoolean("an"));
    }

    @Test
    public void sofortUndZuVieleSchaltungen() throws Exception {
        freigeben();
        jetzt = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, WIEN).toInstant().toEpochMilli();
        assertEquals("wartet", d.takt().getJSONObject("zustand").getString("phase"));
        d.sofort(true);
        assertEquals("laedt", d.takt().getJSONObject("zustand").getString("phase"));
        assertEquals("sofort", box.modus);
    }
}
