package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.2 Launcher von außen (UPDATE_API_CLIENTS.md): nur Update (nicht installiert → nichts), nur die freigegebene
 * Version, Bestätigung, nicht während der Fahrt, ohne WLAN erst nach Rückfrage mit Größe, Telegram mit Gerätenamen.
 */
public class FernLauncherTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    Integer installiertVc = 3;
    boolean verfuegbar = true;
    boolean neuer = true;
    String nichtWeil;
    boolean mobilFrage = true;
    String phase;
    String fehler;
    String installierenGrund;
    final List<String> installiert = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    FernUpdate f;

    @Before
    public void auf() throws Exception {
        File basis = tmp.newFolder("basis");
        f = new FernUpdate(new FernUpdate.Umgebung() {
            public JSONObject status(String sprache) { return null; }
            public String installieren(int vc) { return "nein"; }
            public String fahrsperre() { return null; }
            public void telegram(String version, String geraet) { }
            public File basis() { return basis; }
            public long jetzt() { return 1_800_000_000_000L; }

            @Override
            public JSONObject launcherStatus() {
                try {
                    return new JSONObject()
                            .put("installiert", installiertVc == null ? JSONObject.NULL : (Object) installiertVc)
                            .put("verfuegbar", verfuegbar ? new JSONObject().put("version", "0.1.38.2").put("versionCode", 5)
                                    .put("groesseBytes", 9_000_000L) : JSONObject.NULL)
                            .put("neuer", neuer)
                            .put("nichtWeil", nichtWeil == null ? JSONObject.NULL : nichtWeil)
                            .put("mobilFrage", LauncherRegel.WARTET_WLAN.equals(nichtWeil) && mobilFrage)
                            .put("phase", phase == null ? JSONObject.NULL : phase)
                            .put("fehler", fehler == null ? JSONObject.NULL : fehler);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public String launcherInstallieren(int vc, boolean mobil) {
                installiert.add(vc + (mobil ? " mobil" : ""));
                return installierenGrund;
            }

            @Override
            public void telegramLauncher(String version, String geraet) {
                telegram.add(version + " " + geraet);
            }
        });
    }

    @Test
    public void standZeigtNeuenLauncher() throws Exception {
        JSONObject s = f.launcherStand("de", true);
        assertEquals("0.1.38.2", s.getJSONObject("neueste").getString("version"));
        assertTrue(s.getBoolean("moeglich"));
        assertFalse(s.getBoolean("mobilFrage"));
        assertTrue(s.getString("text").contains("0.1.38.2"));
    }

    @Test
    public void nichtInstalliertHeisstNichtsZuTun() throws Exception {
        installiertVc = null;
        JSONObject s = f.launcherStand("de", true);
        assertTrue(s.isNull("neueste"));
        assertFalse(s.getBoolean("moeglich"));
        assertEquals("nicht_installiert", s.getString("grund"));
        JSONObject r = f.launcherStarten("0.1.38.2", true, false, "Pixel", "de");
        assertFalse(r.getBoolean("ok"));
        assertEquals("nicht_installiert", r.getString("grund"));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void aktuellerLauncherKeinUpdate() throws Exception {
        neuer = false;
        JSONObject s = f.launcherStand("de", true);
        assertTrue(s.isNull("neueste"));
        assertEquals("kein_update", s.getString("grund"));
        assertFalse(f.launcherStarten("0.1.38.2", true, false, "Pixel", "de").getBoolean("ok"));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void ohneRechtNichtMoeglich() throws Exception {
        JSONObject s = f.launcherStand("de", false);
        assertFalse(s.getBoolean("moeglich"));
        assertEquals("nicht_erlaubt", s.getString("grund"));
    }

    @Test
    public void ohneBestaetigungNichts() throws Exception {
        JSONObject r = f.launcherStarten("0.1.38.2", false, false, "Pixel", "de");
        assertEquals("bestaetigung_fehlt", r.getString("grund"));
        assertTrue(installiert.isEmpty());
        assertTrue(telegram.isEmpty());
    }

    @Test
    public void nurDieFreigegebeneVersion() throws Exception {
        JSONObject r = f.launcherStarten("9.9.9", true, false, "Pixel", "de");
        assertEquals("andere_version", r.getString("grund"));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void startetUndMeldetTelegram() throws Exception {
        JSONObject r = f.launcherStarten("0.1.38.2", true, false, "Pixel", "de");
        assertTrue(r.getBoolean("ok"));
        assertEquals("laeuft", r.getString("zustand"));
        assertEquals(java.util.Arrays.asList("5"), installiert);
        assertEquals(java.util.Arrays.asList("0.1.38.2 Pixel"), telegram);
    }

    @Test
    public void waehrendDerFahrtNicht() throws Exception {
        nichtWeil = LauncherRegel.FAEHRT;
        JSONObject r = f.launcherStarten("0.1.38.2", true, false, "Pixel", "de");
        assertFalse(r.getBoolean("ok"));
        assertEquals("faehrt", r.getString("grund"));
        assertTrue(installiert.isEmpty());
        assertFalse(f.launcherStand("de", true).getBoolean("moeglich"));
    }

    /** 0.1.38.10: „nur WLAN“ ohne Merker wie bei Panoptes: keine Frage, kein Start, der Launcher wartet aufs WLAN. */
    @Test
    public void nurWlanOhneFrageWartetAufsWlan() throws Exception {
        nichtWeil = LauncherRegel.WARTET_WLAN;
        mobilFrage = false;
        JSONObject s = f.launcherStand("de", true);
        assertFalse(s.getBoolean("mobilFrage"));
        assertFalse(s.getBoolean("moeglich"));
        JSONObject r = f.launcherStarten("0.1.38.2", true, true, "Pixel", "de");
        assertFalse(r.getBoolean("ok"));
        assertEquals("wartet_wlan", r.getString("grund"));
        assertFalse(r.optBoolean("mobilFrage", false));
        assertTrue("nie über Mobilnetz gestartet", installiert.isEmpty());
    }

    @Test
    public void ohneWlanErstFragenDannMitEinwilligung() throws Exception {
        nichtWeil = LauncherRegel.WARTET_WLAN;
        JSONObject s = f.launcherStand("de", true);
        assertTrue(s.getBoolean("mobilFrage"));
        assertTrue(s.getBoolean("moeglich"));
        JSONObject r = f.launcherStarten("0.1.38.2", true, false, "Pixel", "de");
        assertFalse(r.getBoolean("ok"));
        assertTrue(r.getBoolean("mobilFrage"));
        assertEquals(9_000_000L, r.getLong("groesseBytes"));
        assertTrue(installiert.isEmpty());
        r = f.launcherStarten("0.1.38.2", true, true, "Pixel", "de");
        assertTrue(r.getBoolean("ok"));
        assertEquals(java.util.Arrays.asList("5 mobil"), installiert);
    }

    @Test
    public void laeuftSchonKeinZweiterStart() throws Exception {
        phase = "laedt";
        JSONObject s = f.launcherStand("de", true);
        assertEquals("laeuft", s.getString("zustand"));
        assertFalse(s.getBoolean("moeglich"));
        JSONObject r = f.launcherStarten("0.1.38.2", true, false, "Pixel", "de");
        assertEquals("launcher_laeuft_schon", r.getString("grund"));
        assertTrue(installiert.isEmpty());
    }
}
