package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 0.1.26.1 Update von außen: nur freigegebene Version, Bestätigung, geparkt, Telegram, Fortschritt. */
public class FernUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    long jetzt = 1_800_000_000_000L;
    String fahrsperre;
    String phase;
    String ergebnis = "verfuegbar";
    boolean installierbar = true;
    String nichtWeil;
    int installiertVc = 41;
    boolean verfuegbar = true;
    String installierenGrund;
    final List<Integer> installiert = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    FernUpdate f;

    @Before
    public void auf() throws Exception {
        File basis = tmp.newFolder("basis");
        f = new FernUpdate(new FernUpdate.Umgebung() {
            public JSONObject status(String sprache) {
                try {
                    JSONObject s = new JSONObject();
                    s.put("aktuell", new JSONObject().put("version", installiertVc >= 42 ? "0.1.26.1" : "0.1.26")
                            .put("versionCode", installiertVc));
                    s.put("verfuegbar", verfuegbar ? new JSONObject().put("version", "0.1.26.1").put("versionCode", 42)
                            .put("groesseBytes", 71_000_000L) : JSONObject.NULL);
                    s.put("installierbar", installierbar);
                    s.put("nichtWeil", nichtWeil == null ? JSONObject.NULL : nichtWeil);
                    s.put("phase", phase == null ? JSONObject.NULL : phase);
                    s.put("ergebnis", ergebnis);
                    return s;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public String installieren(int vc) { installiert.add(vc); return installierenGrund; }
            public String fahrsperre() { return fahrsperre; }
            public void telegram(String version, String geraet) { telegram.add(version + " " + geraet); }
            public File basis() { return basis; }
            public long jetzt() { return jetzt; }
        });
    }

    @Test
    public void standZeigtNeuesteUndIstMoeglich() throws Exception {
        JSONObject s = f.stand("de", true);
        assertEquals("0.1.26", s.getString("installiert"));
        assertEquals("0.1.26.1", s.getJSONObject("neueste").getString("version"));
        assertTrue(s.getBoolean("moeglich"));
        assertTrue(s.isNull("zustand"));
        assertTrue(s.getString("text").contains("0.1.26.1"));
    }

    @Test
    public void ohneRechtNichtMoeglich() throws Exception {
        JSONObject s = f.stand("de", false);
        assertFalse(s.getBoolean("moeglich"));
        assertEquals("nicht_erlaubt", s.getString("grund"));
        assertFalse(s.getBoolean("darf"));
    }

    @Test
    public void ohneBestaetigungNichts() throws Exception {
        JSONObject r = f.starten("0.1.26.1", false, "Pixel", "de");
        assertEquals("bestaetigung_fehlt", r.getString("grund"));
        assertTrue(installiert.isEmpty());
        assertTrue(telegram.isEmpty());
    }

    @Test
    public void nurDieFreigegebeneVersion() throws Exception {
        assertEquals("andere_version", f.starten("0.1.99", true, "Pixel", "de").getString("grund"));
        assertEquals("andere_version", f.starten(null, true, "Pixel", "de").getString("grund"));
        assertTrue(installiert.isEmpty());
        verfuegbar = false;
        assertEquals("kein_update", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void gesperrtInFahrtUndAusserhalbP() throws Exception {
        fahrsperre = "moving";
        assertEquals("faehrt", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        fahrsperre = "not_park";
        assertEquals("nicht_in_p", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        fahrsperre = "gear_unknown";
        assertEquals("nicht_in_p", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        fahrsperre = "state_unknown";
        assertEquals("zustand_unbekannt", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        assertTrue(installiert.isEmpty());
        // Fahrsperre frei, aber der Updater sagt Zündung an
        fahrsperre = null;
        installierbar = false;
        nichtWeil = "zuendung_an";
        assertEquals("zuendung_an", f.starten("0.1.26.1", true, "Pixel", "de").getString("grund"));
        assertTrue(installiert.isEmpty());
        assertTrue(telegram.isEmpty());
    }

    @Test
    public void startMitTelegramUndFortschritt() throws Exception {
        JSONObject r = f.starten("0.1.26.1", true, "Pixel 8", "de");
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("laeuft", r.getString("zustand"));
        assertEquals(1, installiert.size());
        assertEquals(42, (int) installiert.get(0));
        assertEquals("0.1.26.1 Pixel 8", telegram.get(0));
        // Anlauf ohne Phase: läuft
        assertEquals("laeuft", f.stand("de", true).getString("zustand"));
        phase = "laedt";
        jetzt += 10 * 60_000L;
        JSONObject s = f.stand("de", true);
        assertEquals("laeuft", s.getString("zustand"));
        assertFalse("kein zweiter Start, solange es läuft", s.getBoolean("moeglich"));
        assertEquals("laeuft_schon", f.starten("0.1.26.1", true, "Pixel 8", "de").getString("grund"));
        // nach dem Neustart: neue Version, Selbstprüfung
        phase = "beobachtet";
        installiertVc = 42;
        verfuegbar = false;
        assertEquals("laeuft", f.stand("de", true).getString("zustand"));
        phase = null;
        s = f.stand("de", true);
        assertEquals("fertig", s.getString("zustand"));
        assertTrue(s.getString("text").contains("0.1.26.1"));
        // nach 24 h vergessen
        jetzt += 25 * 3_600_000L;
        assertTrue(f.stand("de", true).isNull("zustand"));
    }

    @Test
    public void rueckfallIstFehler() throws Exception {
        assertTrue(f.starten("0.1.26.1", true, "Pixel", "de").getBoolean("ok"));
        jetzt += 20 * 60_000L;
        phase = "rueckfall";
        JSONObject s = f.stand("de", true);
        assertEquals("fehler", s.getString("zustand"));
        // nach einem Fehler darf man es noch einmal versuchen
        phase = null;
        assertTrue(f.stand("de", true).getBoolean("moeglich"));
    }

    @Test
    public void startOhneWirkungWirdFehler() throws Exception {
        assertTrue(f.starten("0.1.26.1", true, "Pixel", "de").getBoolean("ok"));
        jetzt += FernUpdate.ANLAUF_MS + 1;
        assertEquals("fehler", f.stand("de", true).getString("zustand"));
    }

    @Test
    public void updaterLehntAbKeinTelegram() throws Exception {
        installierenGrund = "laeuft_schon";
        JSONObject r = f.starten("0.1.26.1", true, "Pixel", "de");
        assertEquals("laeuft_schon", r.getString("grund"));
        assertTrue(telegram.isEmpty());
        assertNull(f.zustand(new JSONObject().put("phase", JSONObject.NULL), 41));
    }

    @Test
    public void textIstEinfachUndOhneSchluessel() {
        for (String fall : FernUpdate.DEUTSCH.keySet()) {
            String t = FernUpdate.text("de", fall, "0.1.26.1", "0.1.26");
            assertFalse(fall, t.startsWith("update_fern."));
            assertFalse(fall, t.contains("{"));
        }
        assertEquals(FernUpdate.DEUTSCH.get("nicht_moeglich"), FernUpdate.text("de", "irgendwas", null, null));
    }
}
