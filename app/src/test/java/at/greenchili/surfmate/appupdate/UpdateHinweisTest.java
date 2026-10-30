package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.MessageOverlayController;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 0.1.31 Update-Hinweis am Display: nur im Stand, höchstens 3× je Version, nichts zusätzlich nach außen. */
public class UpdateHinweisTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final long H = 3_600_000L;
    static final String LATEST = AppUpdateTest.LATEST;

    long jetzt = 1_800_000_000_000L;
    Boolean accAus = false;
    String sperre = null;
    double kmh = 0;
    boolean ereignis = false;
    boolean aufnahme = false;
    Boolean gang = null;
    boolean overlayKlappt = true;
    Object[] autoAus = {false, "cloud_gate_car_on"};
    final Map<String, String> cfg = new HashMap<>();
    final Map<String, String> texte = new HashMap<>();
    final List<String> abrufe = new ArrayList<>();
    final List<String> gezeigt = new ArrayList<>();
    final List<String> notizen = new ArrayList<>();
    final List<String> weg = new ArrayList<>();
    final List<String> gestartet = new ArrayList<>();
    File basis;
    File dd;
    AppUpdateDienst d;

    @Before
    public void auf() throws Exception {
        basis = tmp.newFolder("b");
        dd = tmp.newFolder("d");
        d = neu();
    }

    AppUpdateDienst neu() {
        return new AppUpdateDienst(new AppUpdateDienst.Netz() {
            @Override public String text(String url) throws Exception {
                abrufe.add(url);
                String t = texte.get(url);
                if (t == null) throw new java.io.IOException("404 " + url);
                return t;
            }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p) throws Exception {
                abrufe.add(url);
                throw new java.io.IOException("kein Download im Test");
            }
            @Override public long groesse(String url) { return 1234; }
        }, new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() { return autoAus; }
            @Override public double kmh() { return kmh; }
            @Override public Boolean accAus() { return accAus; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { gestartet.add(tag); }
            @Override public long jetzt() { return jetzt; }
            @Override public String fahrsperre() { return sperre; }
            @Override public boolean waechterEreignis() { return ereignis; }
            @Override public boolean aufnahmeLaeuft() { return aufnahme; }
            @Override public Boolean gangP() { return gang; }
            @Override public boolean zeigeHinweis(String griff, Freigabe f, String n) {
                if (!overlayKlappt) return false;
                gezeigt.add(griff);
                notizen.add(n);
                return true;
            }
            @Override public void nimmHinweisWeg(String griff) { weg.add(griff); }
        });
    }

    /** Update gefunden, dann so viele Takte, wie der ruhige Stand braucht. */
    void gefunden() {
        texte.put(LATEST, AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateTest.SHA0));
        d.pruefen();
    }

    void stehen(long ms) {
        d.hinweisTakt();
        jetzt += ms;
        d.hinweisTakt();
    }

    // ------------------------------------------------------------- wann die Karte kommt

    @Test
    public void kommtNurNachRuhigemStand() {
        gefunden();
        d.hinweisTakt();
        assertEquals("sofort nach dem Einschalten noch nicht", 0, gezeigt.size());
        jetzt += UpdateHinweis.RUHE_MS - 1000;
        d.hinweisTakt();
        assertEquals(0, gezeigt.size());
        jetzt += 6000;
        d.hinweisTakt();
        assertEquals(1, gezeigt.size());
    }

    @Test
    public void nieBeiFahrtOderUnklarerLage() {
        assertFalse(UpdateHinweis.ruhig(false, "not_park", 0, false));      // R, D, N
        assertFalse(UpdateHinweis.ruhig(false, "gear_unknown", 0, false));
        assertFalse(UpdateHinweis.ruhig(false, "moving", 12, false));
        assertFalse(UpdateHinweis.ruhig(false, null, 3, false));
        assertFalse(UpdateHinweis.ruhig(false, null, Double.NaN, false));   // km/h unbekannt
        assertFalse(UpdateHinweis.ruhig(true, null, 0, false));             // Zündung aus: Display ist dunkel
        assertFalse(UpdateHinweis.ruhig(null, null, 0, false));             // ACC nicht autoritativ
        assertFalse(UpdateHinweis.ruhig(false, null, 0, true));             // Wächter-Ereignis, Abschreckung
        assertTrue(UpdateHinweis.ruhig(false, null, 0, false));
    }

    @Test
    public void gangAusPNimmtDieKarteSofortWeg() {
        gefunden();
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(1, gezeigt.size());
        jetzt += 1000;
        d.hinweisTakt();
        assertEquals("steht weiter", 0, weg.size());
        sperre = "not_park";
        jetzt += 1000;
        d.hinweisTakt();
        assertEquals(gezeigt, weg);
    }

    @Test
    public void wachEreignisNimmtDieKarteWeg() {
        gefunden();
        stehen(UpdateHinweis.RUHE_MS + 6000);
        ereignis = true;
        jetzt += 1000;
        d.hinweisTakt();
        assertEquals(gezeigt, weg);
    }

    @Test
    public void wartet24StundenUndHoechstensDreimal() throws Exception {
        gefunden();
        for (int mal = 1; mal <= 3; mal++) {
            stehen(UpdateHinweis.RUHE_MS + 6000);
            assertEquals(mal, gezeigt.size());
            sperre = "not_park";                                   // losgefahren
            jetzt += 1000;
            d.hinweisTakt();
            sperre = null;
            jetzt += 23 * H;
            stehen(UpdateHinweis.RUHE_MS + 6000);
            assertEquals("vor Ablauf von 24 h nicht wieder", mal, gezeigt.size());
            jetzt += 2 * H;
        }
        stehen(UpdateHinweis.RUHE_MS + 6000);
        jetzt += 30 * H;
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals("nach dreimal nur noch das Abzeichen", 3, gezeigt.size());
        assertEquals(9, d.status("de").getJSONObject("verfuegbar").getInt("versionCode"));
    }

    @Test
    public void zaehlerUeberlebtDenNeustart() {
        gefunden();
        stehen(UpdateHinweis.RUHE_MS + 6000);
        d = neu();
        jetzt += H;
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(1, gezeigt.size());
    }

    @Test
    public void neueVersionBeginntVonVorn() {
        UpdateHinweis.Stand s = new UpdateHinweis.Stand();
        UpdateHinweis.gezeigt(s, 9, 1000);
        UpdateHinweis.gezeigt(s, 9, 2000);
        assertEquals(2, s.anzahl);
        UpdateHinweis.gezeigt(s, 10, 3000);
        assertEquals(1, s.anzahl);
        assertEquals(10, s.vc);
    }

    @Test
    public void nichtGezeigtZaehltNichtUndDrosselt() {
        gefunden();
        overlayKlappt = false;
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(0, gezeigt.size());
        overlayKlappt = true;
        jetzt += 6000;
        d.hinweisTakt();
        assertEquals("kein Dauerfeuer", 0, gezeigt.size());
        jetzt += UpdateHinweis.PAUSE_NACH_FEHLVERSUCH_MS;
        d.hinweisTakt();
        assertEquals(1, gezeigt.size());
    }

    @Test
    public void schalterAusZeigtNichts() {
        cfg.put("hinweis", "false");
        gefunden();
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(0, gezeigt.size());
    }

    @Test
    public void pruefenAusOderAutomatischZeigtNichts() {
        gefunden();
        cfg.put("pruefen", "false");
        stehen(UpdateHinweis.RUHE_MS + 6000);
        cfg.remove("pruefen");
        cfg.put("modus", "automatisch");
        jetzt += 6000;
        d.hinweisTakt();
        assertEquals(0, gezeigt.size());
    }

    @Test
    public void vorabversionNurImGewaehltenKanal() throws Exception {
        UpdateHinweis.Lage l = lage(Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "test", "v0.1.8-test.1", AppUpdateTest.SHA0)));
        l.kanal = "stabil";
        assertEquals("kanal", UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
        l.kanal = "test";
        assertNull(UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
        l.automatisch = true;   // Vorabversion installiert nie von selbst, also bleibt der Hinweis
        assertNull(UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
    }

    @Test
    public void nichtBeiZuAltZurueckgerolltOderLaufendemUpdate() throws Exception {
        Freigabe f = Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateTest.SHA0));
        UpdateHinweis.Lage l = lage(f);
        l.installiert = 1;      // minVersion 2
        assertEquals("zu_alt", UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
        l = lage(f);
        l.rueckfallVc = 9;
        assertEquals("zurueckgerollt", UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
        l = lage(f);
        l.laeuftSchon = true;
        assertEquals("laeuft_schon", UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
        l = lage(f);
        l.installiert = 9;
        assertEquals("kein_update", UpdateHinweis.nichtWeil(l, new UpdateHinweis.Stand()));
    }

    UpdateHinweis.Lage lage(Freigabe f) {
        UpdateHinweis.Lage l = new UpdateHinweis.Lage();
        l.schalterAn = true;
        l.pruefenAn = true;
        l.kanal = "stabil";
        l.installiert = 8;
        l.verfuegbar = f;
        l.ruhigSeit = 0;
        l.jetzt = UpdateHinweis.RUHE_MS;
        return l;
    }

    // ------------------------------------------------------------- nichts zusätzlich nach außen

    @Test
    public void derHinweisFragtNieDasNetz() {
        gefunden();
        abrufe.clear();
        for (int i = 0; i < 50; i++) {
            d.hinweisTakt();
            jetzt += 5000;
        }
        assertEquals(1, gezeigt.size());
        assertEquals(new ArrayList<String>(), abrufe);
    }

    // ------------------------------------------------------------- „Jetzt installieren“

    FernUpdate fern(String sperreGrund) {
        return new FernUpdate(new FernUpdate.Umgebung() {
            public JSONObject status(String sprache) { return d.status(sprache); }
            public String installieren(int vc) { return d.installieren(vc); }
            public String fahrsperre() { return sperreGrund; }
            public void telegram(String version, String geraet) { }
            public File basis() { return basis; }
            public long jetzt() { return jetzt; }
        });
    }

    @Test
    public void zuendungAnMerktDieVersionVorStattZuInstallieren() throws Exception {
        gefunden();
        JSONObject r = d.hinweisInstallieren(fern(null), "0.1.8", "de");
        assertTrue(r.getBoolean("ok"));
        assertTrue(r.getBoolean("vorgemerkt"));
        assertTrue(r.getString("text").length() > 10);
        assertTrue(d.status("de").getBoolean("vorgemerkt"));
        assertEquals(0, gestartet.size());
        // vorgemerkt = keine Karte mehr für diese Version
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(0, gezeigt.size());
    }

    @Test
    public void vorgemerktInstalliertErstWennDasAutoAusIst() throws Exception {
        UpdateRegel.Lage l = new UpdateRegel.Lage();
        l.installiert = 8;
        l.verfuegbar = Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateTest.SHA0));
        l.gateGrund = "cloud_gate_car_on";
        UpdateHinweis.Stand s = new UpdateHinweis.Stand();
        UpdateHinweis.vormerken(s, 9, 1000);
        assertEquals("Zündung an: nie", -1, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, 1000));
        l.gateGrund = null;
        l.autoAus = true;
        l.kmh = 0;
        assertEquals(1000 + UpdateRegel.AUTOMATISCH_NACH_MS, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, 1000));
        assertEquals(-1, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, -1));
        assertEquals("Wächter, Aufnahme, Gang: jetzt nicht", -1, UpdateHinweis.vorgemerktAb(l, s, "stabil", false, 2000, 1000));
        l.kmh = 5;
        assertEquals("fährt: nie", -1, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, 1000));
        l.kmh = 0;
        l.laeuftSchon = true;
        assertEquals(".deploy.lock: nie", -1, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, 1000));
        l.laeuftSchon = false;
        l.rueckfallVc = 9;
        assertEquals("zurückgerollt: nie", -1, UpdateHinweis.vorgemerktAb(l, s, "stabil", true, 2000, 1000));
    }

    @Test
    public void bedingung1NurDieseVersionUndSiebenTage() throws Exception {
        UpdateHinweis.Stand s = new UpdateHinweis.Stand();
        UpdateHinweis.vormerken(s, 9, 1000);
        assertTrue(UpdateHinweis.vorgemerktGilt(s, 9, 1000 + UpdateHinweis.VORGEMERKT_GILT_MS - 1));
        assertFalse(UpdateHinweis.vorgemerktGilt(s, 9, 1000 + UpdateHinweis.VORGEMERKT_GILT_MS));
        assertFalse("neuere Version", UpdateHinweis.vorgemerktGilt(s, 10, 2000));
        assertFalse("Uhr rückwärts", UpdateHinweis.vorgemerktGilt(s, 9, 500));
        // im Dienst: neuere Version → keine Vormerkung mehr, die Karte kommt für sie neu
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        texte.put(LATEST, AppUpdateTest.vj(10, "0.1.9", "stabil", "v0.1.9", AppUpdateTest.SHA0));
        d.pruefen();
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(1, gezeigt.size());
    }

    @Test
    public void bedingung1NachSiebenTagenKommtDieKarteWieder() throws Exception {
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        jetzt += UpdateHinweis.VORGEMERKT_GILT_MS + 1000;
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
        stehen(UpdateHinweis.RUHE_MS + 6000);
        assertEquals(1, gezeigt.size());
    }

    @Test
    public void bedingung2JedePruefungNeu() {
        assertTrue(UpdateHinweis.installFrei(null, true, false, false));
        assertTrue("Gang unbekannt bei Auto aus", UpdateHinweis.installFrei(null, null, false, false));
        assertFalse(UpdateHinweis.installFrei(null, false, false, false));
        assertFalse(UpdateHinweis.installFrei("state_unknown", true, false, false));
        assertFalse(UpdateHinweis.installFrei(null, true, true, false));
        assertFalse(UpdateHinweis.installFrei(null, true, false, true));
    }

    long apkAbrufe() {
        return abrufe.stream().filter(u -> u.endsWith(".apk")).count();
    }

    void vorgemerktUndAus() throws Exception {
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1000;
    }

    @Test
    public void bedingung2AufnahmeOderEreignisVerschiebt() throws Exception {
        vorgemerktUndAus();
        aufnahme = true;
        d.takt();
        aufnahme = false;
        ereignis = true;
        d.takt();
        ereignis = false;
        gang = false;
        d.takt();
        assertEquals(0, apkAbrufe());
        gang = true;
        d.takt();
        assertEquals("später erneut", 1, apkAbrufe());
    }

    @Test
    public void bedingung2KeineSchleife() throws Exception {
        vorgemerktUndAus();
        d.takt();                                   // Download scheitert im Test
        assertEquals(1, apkAbrufe());
        d.takt();
        jetzt += 30 * 60_000L;
        d.takt();
        assertEquals("Pause nach dem Fehlschlag", 1, apkAbrufe());
        jetzt += UpdateHinweis.VORGEMERKT_PAUSE_MS;
        d.takt();
        assertEquals(2, apkAbrufe());
        jetzt += UpdateHinweis.VORGEMERKT_PAUSE_MS + 1000;
        d.takt();
        assertEquals(3, apkAbrufe());
        assertFalse("nach 3 Fehlschlägen verfallen", d.status("de").getBoolean("vorgemerkt"));
        jetzt += 2 * UpdateHinweis.VORGEMERKT_PAUSE_MS;
        d.takt();
        assertEquals(3, apkAbrufe());
    }

    @Test
    public void bedingung3TextInAllenSprachen() throws Exception {
        JSONObject h = new JSONObject(lies("src/main/assets/server-i18n/de.json")).getJSONObject("update_hinweis");
        assertEquals("Panoptes installiert das Update etwa 10 Minuten, nachdem du das Auto abgestellt hast. "
                + "Der Wächter ist dabei ein paar Minuten aus.", h.getString("vorgemerkt"));
        assertEquals("Zurücknehmen", h.getString("zuruecknehmen"));
        for (String l : new String[]{"en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("update_hinweis");
            assertTrue(l, j.getString("vorgemerkt").contains("10"));
            assertTrue(l, j.getString("zuruecknehmen").length() > 2);
        }
        String overlay = lies("src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java");
        assertTrue(overlay.contains("hinweisAktion(\"zuruecknehmen\", version)"));
    }

    @Test
    public void bedingung4NieZurueckgerollt() throws Exception {
        gefunden();
        Files.write(new File(basis, ".appupdate-rueckfall").toPath(), "9 0.1.8".getBytes(StandardCharsets.UTF_8));
        JSONObject r = d.hinweisInstallieren(fern(null), "0.1.8", "de");
        assertFalse(r.getBoolean("vorgemerkt"));
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
    }

    @Test
    public void bedingung4VorabversionNurImGewaehltenKanal() throws Exception {
        cfg.put("kanal", "test");
        texte.put(LATEST, AppUpdateTest.vj(8, "0.1.7", "stabil", "v0.1.7", AppUpdateTest.SHA0));
        texte.put(UpdateQuelle.aus(null, null, null).releasesListe(), "[{\"tag_name\":\"v0.1.8-test.1\",\"draft\":false}]");
        texte.put(UpdateQuelle.aus(null, null, null).download("v0.1.8-test.1", "version.json"),
                AppUpdateTest.vj(9, "0.1.8", "test", "v0.1.8-test.1", AppUpdateTest.SHA0));
        d.pruefen();
        assertTrue(d.hinweisInstallieren(fern(null), "0.1.8", "de").getBoolean("vorgemerkt"));
        d.hinweisZuruecknehmen();
        cfg.put("kanal", "stabil");
        assertFalse("Kanal zurückgestellt", d.hinweisInstallieren(fern(null), "0.1.8", "de").getBoolean("vorgemerkt"));
    }

    @Test
    public void nurMeldenInstalliertOhneTippNieVonSelbst() {
        gefunden();
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += 2 * UpdateRegel.AUTOMATISCH_NACH_MS;
        d.takt();
        assertEquals(0, abrufe.stream().filter(u -> u.endsWith(".apk")).count());
    }

    @Test
    public void vorgemerktStartetNachZehnMinutenAus() throws Exception {
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        assertEquals("erst warten", 0, abrufe.stream().filter(u -> u.endsWith(".apk")).count());
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1000;
        d.takt();
        assertEquals("der Download beginnt", 1, abrufe.stream().filter(u -> u.endsWith(".apk")).count());
    }

    @Test
    public void zuruecknehmenHebtDasVormerkenAuf() throws Exception {
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        d.hinweisZuruecknehmen();
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1000;
        d.takt();
        assertEquals(0, abrufe.stream().filter(u -> u.endsWith(".apk")).count());
    }

    @Test
    public void vormerkenUeberlebtDenNeustart() throws Exception {
        gefunden();
        d.hinweisInstallieren(fern(null), "0.1.8", "de");
        d = neu();
        assertTrue(d.status("de").getBoolean("vorgemerkt"));
    }

    @Test
    public void inFahrtWirdNichtVorgemerkt() throws Exception {
        gefunden();
        JSONObject r = d.hinweisInstallieren(fern("moving"), "0.1.8", "de");
        assertFalse(r.getBoolean("ok"));
        assertEquals("faehrt", r.getString("grund"));
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
    }

    @Test
    public void autoAusStartetSofort() throws Exception {
        gefunden();
        autoAus = new Object[]{true, null};
        JSONObject r = d.hinweisInstallieren(fern(null), "0.1.8", "de");
        assertTrue(r.getBoolean("ok"));
        assertFalse(r.getBoolean("vorgemerkt"));
        assertEquals("laeuft", r.getString("zustand"));
    }

    @Test
    public void andereVersionWirdNichtVorgemerkt() throws Exception {
        gefunden();
        JSONObject r = d.hinweisInstallieren(fern(null), "9.9.9", "de");
        assertFalse(r.getBoolean("ok"));
        assertFalse(d.status("de").getBoolean("vorgemerkt"));
    }

    // ------------------------------------------------------------- Texte, Schalter, Oberfläche

    @Test
    public void notizenWerdenGekuerzt() {
        assertEquals("• a\n• b", UpdateHinweis.notizenText(new JSONArray().put("a").put(" ").put("b")));
        JSONArray lang = new JSONArray();
        for (int i = 0; i < 100; i++) lang.put("Eine neue Funktion mit einem langen Satz Nummer " + i);
        assertTrue(UpdateHinweis.notizenText(lang).length() <= UpdateHinweis.NOTIZEN_MAX_ZEICHEN);
        assertEquals("", UpdateHinweis.notizenText(null));
    }

    @Test
    public void befehlIstSauberGequotet() {
        String c = MessageOverlayController.updateHinweisCommand("00000000000000ab", "0.1.8", "T", "M", "• it's new",
                "I", "S", "W", "Z", "R", "F", 120);
        assertTrue(c.contains("--es kind update_hinweis"));
        assertTrue(c.contains("'• it'\\''s new'"));
        assertTrue(c.contains("--ei timeoutSec 120"));
    }

    @Test
    public void schalterStehtImKatalogStandardAn() {
        Feld f = Katalog.finde(Katalog.felder(new Katalog.Kontext()), "update.hinweis");
        assertEquals(Feld.Typ.BOOL, f.typ);
        assertEquals(Boolean.TRUE, f.standard);
        assertEquals("update", f.gruppe);
    }

    @Test
    public void textePlusSchalterInAllenFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json"));
            JSONObject h = j.getJSONObject("update_hinweis");
            for (String k : new String[]{"titel", "frage", "installieren", "spaeter", "was_neu", "schliessen", "fehler",
                    "vorgemerkt", "vorgemerkt_wlan", "geraet"}) {
                assertTrue(l + " " + k, h.getString(k).length() > 1);
            }
            assertTrue(h.getString("titel").contains("{0}"));
            JSONObject s = j.getJSONObject("settings").getJSONObject("update").getJSONObject("hinweis");
            assertTrue(s.getString("title").length() > 3);
            assertTrue(s.getString("help").length() > 3);
            JSONObject w = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("system");
            assertTrue(w.getString("upd_vorgemerkt").length() > 3);
            assertTrue(w.getString("upd_cancel").length() > 3);
        }
    }

    /** REGEL A+: die Karte fasst weder Ton noch Licht noch das Auto an. */
    @Test
    public void karteMachtKeinenTonUndKeinLicht() throws Exception {
        String quelle = lies("src/main/java/at/greenchili/surfmate/appupdate/UpdateHinweis.java");
        String overlay = lies("src/main/java/at/greenchili/surfmate/overlay/MessageOverlayService.java");
        int von = overlay.indexOf("private boolean showUpdateHinweis");
        String karte = overlay.substring(von, overlay.indexOf("// ── Dialog", von));
        for (String s : new String[]{quelle, karte}) {
            for (String verboten : new String[]{"MediaPlayer", "SoundPool", "ToneGenerator", "AudioPlaybackController",
                    "RingtoneManager", "setVibrate", "flash", "honk", "BydCloud"}) {
                assertFalse(verboten, s.contains(verboten));
            }
        }
    }

    @Test
    public void nurAmAutoErreichbar() throws Exception {
        String h = lies("src/main/java/at/greenchili/surfmate/server/AppUpdateApiHandler.java");
        int von = h.indexOf("\"/api/appupdate/hinweis\"");
        assertTrue(von > 0);
        assertTrue(h.substring(von, von + 240).contains("nur_am_auto"));
        String guard = lies("src/main/java/at/greenchili/surfmate/server/ExternGuard.java");
        assertFalse(guard.contains("/api/appupdate/hinweis"));
    }

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
