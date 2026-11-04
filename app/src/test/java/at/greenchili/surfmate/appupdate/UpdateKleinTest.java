package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.38.8 (04.11.): kleine Updates kommen still, große mit Sammelliste; einmalige Umstellung „melden“ →
 * „korrekturen“ mit einer Nachricht; keine Doppelmeldung, wenn gerade gesucht oder installiert wird.
 */
public class UpdateKleinTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String LATEST = AppUpdateTest.LATEST;

    long jetzt = 1_800_000_000_000L;
    int installiertVc = 8;
    String installiert = "0.1.7";
    Boolean accAus = false;
    Object[] autoAus = {false, "cloud_gate_car_on"};
    boolean speichernKlappt = true;
    boolean infoKlappt = true;
    final Map<String, String> cfg = new HashMap<>();
    final Map<String, String> texte = new HashMap<>();
    final List<String> abrufe = new ArrayList<>();
    final List<String> gemeldet = new ArrayList<>();
    final List<String> umstellung = new ArrayList<>();
    final List<String> info = new ArrayList<>();
    final List<String> updateMeldungen = new ArrayList<>();
    final List<Map<String, Object>> gesetzt = new ArrayList<>();
    File basis;
    File dd;
    AppUpdateDienst d;

    @Before
    public void auf() throws Exception {
        basis = tmp.newFolder("b");
        dd = tmp.newFolder("d");
    }

    AppUpdateDienst neu() {
        d = new AppUpdateDienst(new AppUpdateDienst.Netz() {
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
            @Override public int installierterVc() { return installiertVc; }
            @Override public String installierteVersion() { return installiert; }
            @Override public Object[] autoAus() { return autoAus; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return accAus; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { }
            @Override public long jetzt() { return jetzt; }
            @Override public String fahrsperre() { return null; }
            @Override public boolean waechterEreignis() { return false; }
            @Override public boolean aufnahmeLaeuft() { return false; }
            @Override public Boolean gangP() { return true; }
            @Override public void melde(Freigabe f, long g) { gemeldet.add(f.versionName); }
            @Override public boolean setzeConfig(Map<String, Object> werte) {
                if (!speichernKlappt) return false;
                gesetzt.add(werte);
                for (Map.Entry<String, Object> e : werte.entrySet()) cfg.put(e.getKey(), String.valueOf(e.getValue()));
                return true;
            }
            @Override public void meldeUmstellung(String titel, String text) { umstellung.add(text); }
            @Override public boolean zeigeInfo(String titel, String text) {
                if (!infoKlappt) return false;
                info.add(text);
                return true;
            }
            @Override public void meldeUpdate(UpdateMeldung.Meldung m) { updateMeldungen.add(m.art + " " + m.version); }
        });
        return d;
    }

    /** Ein Gerät, dessen Updater schon lief (appupdate_stand.json besteht). */
    void bestand() throws Exception {
        Files.write(new File(basis, "appupdate_stand.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(basis, ".appupdate-quelle").toPath(), new byte[0]);   // Quelle schon übernommen (0.1.15)
    }

    void freigabe(String name, String zusatz) {
        String j = AppUpdateTest.vj(9, name, "stabil", "v" + name, AppUpdateTest.SHA0);
        texte.put(LATEST, j.substring(0, j.length() - 1) + zusatz + "}");
    }

    static Freigabe f(String name) throws Exception {
        return Freigabe.lies(AppUpdateTest.vj(70, name, "stabil", "v" + name, AppUpdateTest.SHA0));
    }

    void ruhigStehen() {
        d.hinweisTakt();
        jetzt += UpdateHinweis.RUHE_MS + 6000;
        d.hinweisTakt();
    }

    long apkAbrufe() {
        return abrufe.stream().filter(u -> u.endsWith(".apk")).count();
    }

    // ------------------------------------------------------------- klein / groß

    @Test
    public void kleinIstNurDieVierteStelle() throws Exception {
        assertTrue(UpdateHinweis.klein(f("0.1.38.8"), "0.1.38.7"));
        assertTrue(UpdateHinweis.klein(f("0.1.38.1"), "0.1.38"));
        assertTrue("übersprungene Korrekturen bleiben klein", UpdateHinweis.klein(f("0.1.38.9"), "0.1.38.6"));
        assertTrue(UpdateHinweis.klein(f("0.1.38.8"), "0.1.38.7-test.2"));
        assertFalse(UpdateHinweis.klein(f("0.1.39"), "0.1.38.8"));
        assertFalse("bringt 0.1.38 mit", UpdateHinweis.klein(f("0.1.38.4"), "0.1.37"));
        assertFalse(UpdateHinweis.klein(f("0.1.39.1"), "0.1.38.8"));
        assertFalse(UpdateHinweis.klein(f("0.2.0.1"), "0.1.0"));
        assertFalse(UpdateHinweis.klein(f("0.1.38.7"), "0.1.38.7"));
        assertFalse("unbekannt installiert: groß", UpdateHinweis.klein(f("0.1.38.8"), null));
        assertFalse(UpdateHinweis.klein(null, "0.1.38.7"));
    }

    @Test
    public void kleinKommtStillUnabhaengigVonDerArt() throws Exception {
        for (String art : new String[]{"fix", "funktion", "beides"}) {
            gemeldet.clear();
            abrufe.clear();
            basis = tmp.newFolder("b_" + art);
            cfg.put("modus", UpdateRegel.MODUS_KORREKTUREN);
            neu();
            freigabe("0.1.7.1", ",\"art\":\"" + art + "\"");
            d.pruefen();
            assertEquals(art, 0, gemeldet.size());
            autoAus = new Object[]{true, null};
            accAus = true;
            d.takt();
            jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1000;
            d.takt();
            assertEquals(art + ": von selbst geladen", 1, apkAbrufe());
            autoAus = new Object[]{false, "cloud_gate_car_on"};
            accAus = false;
        }
    }

    @Test
    public void imModusAutomatischKleinStillGrossGemeldet() throws Exception {
        cfg.put("modus", "automatisch");
        neu();
        freigabe("0.1.7.1", "");
        d.pruefen();
        assertEquals("klein: still", 0, gemeldet.size());
        basis = tmp.newFolder("b2");
        neu();
        freigabe("0.1.8", "");
        d.pruefen();
        assertEquals("groß: vorher gemeldet", Arrays.asList("0.1.8"), gemeldet);
    }

    @Test
    public void nurMeldenMeldetAuchKleine() throws Exception {
        bestand();
        cfg.put("modus", "melden");
        Files.write(new File(basis, AppUpdateDienst.KLEIN_MARKE).toPath(), "umgestellt melden 1\n".getBytes());
        neu();
        freigabe("0.1.7.1", "");
        d.pruefen();
        assertEquals(Arrays.asList("0.1.7.1"), gemeldet);
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += 3 * UpdateRegel.AUTOMATISCH_NACH_MS;
        d.takt();
        assertEquals("nie von selbst", 0, apkAbrufe());
    }

    @Test
    public void testkanalKleinNieVonSelbst() throws Exception {
        cfg.put("modus", UpdateRegel.MODUS_KORREKTUREN);
        cfg.put("kanal", "test");
        neu();
        texte.put(LATEST, AppUpdateTest.vj(8, "0.1.7", "stabil", "v0.1.7", AppUpdateTest.SHA0));
        texte.put(UpdateQuelle.aus(null, null, null).releasesListe(), "[{\"tag_name\":\"v0.1.7.1-test.1\",\"draft\":false}]");
        texte.put(UpdateQuelle.aus(null, null, null).download("v0.1.7.1-test.1", "version.json"),
                AppUpdateTest.vj(9, "0.1.7.1", "test", "v0.1.7.1-test.1", AppUpdateTest.SHA0));
        d.pruefen();
        assertEquals("Vorabversion wird gemeldet", 1, gemeldet.size());
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += 3 * UpdateRegel.AUTOMATISCH_NACH_MS;
        d.takt();
        assertEquals(0, apkAbrufe());
    }

    // ------------------------------------------------------------- einmalige Umstellung

    @Test
    public void meldenWirdEinmalUmgestelltMitEinerNachricht() throws Exception {
        bestand();
        cfg.put("modus", "melden");
        neu();
        assertEquals(UpdateRegel.MODUS_KORREKTUREN, cfg.get("modus"));
        assertTrue(new File(basis, AppUpdateDienst.KLEIN_MARKE).isFile());
        assertEquals("noch nichts vor dem Takt (Bot startet erst)", 0, umstellung.size());
        d.takt();
        d.takt();
        assertEquals("Telegram + Handy genau einmal", 1, umstellung.size());
        ruhigStehen();
        jetzt += 60_000;
        ruhigStehen();
        assertEquals("Karte am Display genau einmal", 1, info.size());
        assertEquals(umstellung.get(0), info.get(0));
        // Neustart des Daemons: nichts mehr
        neu();
        d.takt();
        ruhigStehen();
        assertEquals(1, umstellung.size());
        assertEquals(1, info.size());
        assertEquals("einmal gespeichert", 1, gesetzt.size());
    }

    @Test
    public void karteNurImRuhigenStand() throws Exception {
        bestand();
        cfg.put("modus", "melden");
        neu();
        accAus = true;                                   // Auto aus: Display dunkel
        ruhigStehen();
        assertEquals(0, info.size());
        accAus = false;
        infoKlappt = false;
        ruhigStehen();
        assertEquals(0, info.size());
        infoKlappt = true;
        jetzt += UpdateHinweis.PAUSE_NACH_FEHLVERSUCH_MS + 1000;
        ruhigStehen();
        assertEquals("später noch einmal", 1, info.size());
        neu();
        ruhigStehen();
        assertEquals("Karte überlebt den Neustart nicht doppelt", 1, info.size());
    }

    @Test
    public void meldenDanachBleibt() throws Exception {
        bestand();
        cfg.put("modus", "melden");
        neu();
        d.takt();
        assertEquals(UpdateRegel.MODUS_KORREKTUREN, cfg.get("modus"));
        cfg.put("modus", "melden");                      // bewusst zurück
        neu();
        d.takt();
        neu();
        assertEquals("melden", cfg.get("modus"));
        assertEquals(1, umstellung.size());
        assertEquals(1, gesetzt.size());
    }

    @Test
    public void automatischUndKorrekturenBleibenUnveraendert() throws Exception {
        for (String m : new String[]{"automatisch", UpdateRegel.MODUS_KORREKTUREN}) {
            basis = tmp.newFolder("b_" + m);
            bestand();
            cfg.put("modus", m);
            neu();
            d.takt();
            assertEquals(m, cfg.get("modus"));
            assertTrue(new File(basis, AppUpdateDienst.KLEIN_MARKE).isFile());
        }
        assertEquals(0, gesetzt.size());
        assertEquals(0, umstellung.size());
        ruhigStehen();
        assertEquals(0, info.size());
    }

    @Test
    public void bestandOhneWertWirdUmgestellt() throws Exception {
        bestand();
        neu();
        assertEquals(UpdateRegel.MODUS_KORREKTUREN, cfg.get("modus"));
        d.takt();
        assertEquals(1, umstellung.size());
    }

    @Test
    public void neueEinrichtungUndPruefenAusBleiben() throws Exception {
        neu();                                           // kein Stand: neue Installation, die Einrichtung fragt
        assertNull(cfg.get("modus"));
        assertTrue(new File(basis, AppUpdateDienst.KLEIN_MARKE).isFile());
        basis = tmp.newFolder("b_aus");
        bestand();
        cfg.put("modus", "melden");
        cfg.put("pruefen", "false");
        neu();
        d.takt();
        assertEquals("melden", cfg.get("modus"));
        assertEquals(0, umstellung.size());
        assertEquals(0, gesetzt.size());
    }

    @Test
    public void speichernScheitertVersuchtEsBeimNaechstenStart() throws Exception {
        bestand();
        cfg.put("modus", "melden");
        speichernKlappt = false;
        neu();
        d.takt();
        assertFalse(new File(basis, AppUpdateDienst.KLEIN_MARKE).exists());
        assertEquals(0, umstellung.size());
        speichernKlappt = true;
        neu();
        d.takt();
        assertEquals(UpdateRegel.MODUS_KORREKTUREN, cfg.get("modus"));
        assertEquals(1, umstellung.size());
    }

    @Test
    public void regelDerUmstellung() {
        assertTrue(UpdateRegel.umstellenAufKlein(true, "melden", null, false));
        assertTrue(UpdateRegel.umstellenAufKlein(true, null, "true", false));
        assertFalse(UpdateRegel.umstellenAufKlein(false, "melden", null, false));
        assertFalse(UpdateRegel.umstellenAufKlein(true, "melden", "false", false));
        assertFalse(UpdateRegel.umstellenAufKlein(true, "melden", null, true));
        assertFalse(UpdateRegel.umstellenAufKlein(true, "automatisch", null, false));
        assertFalse(UpdateRegel.umstellenAufKlein(true, "korrekturen", null, false));
    }

    @Test
    public void nachrichtWieAlfredSieWill() throws Exception {
        JSONObject de = new JSONObject(UpdateHinweisTest.lies("src/main/assets/server-i18n/de.json"));
        assertEquals("Kleine Korrektur-Updates kommen ab jetzt von selbst (nur im Stand, nie während der Fahrt). "
                + "Große Updates meldet Panoptes weiter vorher. Ändern: Einstellungen → Updates.",
                de.getJSONObject("update_klein").getString("text"));
        for (String sp : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(UpdateHinweisTest.lies("src/main/assets/server-i18n/" + sp + ".json"));
            assertFalse(sp, j.getJSONObject("update_klein").getString("text").isEmpty());
            assertFalse(sp, j.getJSONObject("update_klein").getString("titel").isEmpty());
            assertTrue(sp, j.getJSONObject("notifications").getString("update_verfuegbar").contains("{0}"));
            assertTrue(sp, j.getJSONObject("notifications").getString("update_zurueck").contains("{0}"));
            String hilfe = j.getJSONObject("settings").getJSONObject("update").getJSONObject("modus").getString("help");
            assertTrue(sp + ": Hilfe nennt ein Beispiel für klein und groß",
                    hilfe.contains("0.1.38.7 → 0.1.38.8") && hilfe.contains("0.1.38 → 0.1.39"));
            // Telegram verschickt Markdown: keine Sonderzeichen, die den Text kaputt machen
            assertFalse(sp, j.getJSONObject("update_klein").getString("text").matches(".*[*_`\\[].*"));
        }
    }

    // ------------------------------------------------------------- nach stiller Installation keine Meldung

    void stand(String json) throws Exception {
        Files.write(new File(basis, "appupdate_stand.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    void meldungStand(int panoptesVc) throws Exception {
        Files.write(new File(basis, UpdateMeldung.DATEI).toPath(),
                ("{\"panoptesVc\":" + panoptesVc + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void stillInstalliertMeldetNichts() throws Exception {
        Files.write(new File(basis, AppUpdateDienst.KLEIN_MARKE).toPath(), "x\n".getBytes());
        stand("{\"stillVc\":9}");
        meldungStand(8);
        installiertVc = 9;
        installiert = "0.1.7.1";
        neu();
        d.updateMeldungTakt(jetzt);
        assertEquals(0, updateMeldungen.size());
        // die Marke gilt nur einmal: eine spätere getippte Version meldet wieder
        installiertVc = 10;
        installiert = "0.1.7.2";
        jetzt += AppUpdateDienst.MELDUNG_TAKT_MS;
        d.updateMeldungTakt(jetzt);
        assertEquals(Arrays.asList(UpdateMeldung.PANOPTES_INSTALLIERT + " 0.1.7.2"), updateMeldungen);
    }

    @Test
    public void getipptOderGrossMeldetInstalliert() throws Exception {
        Files.write(new File(basis, AppUpdateDienst.KLEIN_MARKE).toPath(), "x\n".getBytes());
        stand("{\"stillVc\":0}");
        meldungStand(8);
        installiertVc = 9;
        installiert = "0.1.8";
        neu();
        d.updateMeldungTakt(jetzt);
        assertEquals(Arrays.asList(UpdateMeldung.PANOPTES_INSTALLIERT + " 0.1.8"), updateMeldungen);
    }

    @Test
    public void stilleMarkeFaelltWennDasSkriptNichtAnlief() throws Exception {
        cfg.put("modus", UpdateRegel.MODUS_KORREKTUREN);
        neu();
        freigabe("0.1.7.1", "");
        d.pruefen();
        autoAus = new Object[]{true, null};
        accAus = true;
        d.takt();
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1000;
        d.takt();
        assertEquals("Download versucht (scheitert im Test)", 1, apkAbrufe());
        JSONObject s = new JSONObject(new String(Files.readAllBytes(new File(basis, "appupdate_stand.json").toPath()),
                StandardCharsets.UTF_8));
        assertEquals(0, s.getInt("stillVc"));
    }

    @Test
    public void fehlschlagMeldetGenauEinmal() throws Exception {
        Files.write(new File(basis, AppUpdateDienst.KLEIN_MARKE).toPath(), "x\n".getBytes());
        meldungStand(8);
        File rueck = new File(basis, ".appupdate-rueckfall");
        Files.write(rueck.toPath(), "9 tot\n".getBytes());
        assertTrue(rueck.setLastModified(jetzt));        // frisch (die Uhr im Test liegt in der Zukunft)
        neu();
        d.updateMeldungTakt(jetzt);
        assertEquals(Arrays.asList(UpdateMeldung.PANOPTES_ZURUECK + " 0.1.7"), updateMeldungen);
        jetzt += AppUpdateDienst.MELDUNG_TAKT_MS;
        d.updateMeldungTakt(jetzt);
        neu();
        jetzt += AppUpdateDienst.MELDUNG_TAKT_MS;
        d.updateMeldungTakt(jetzt);
        assertEquals("nur einmal, auch nach dem Neustart", 1, updateMeldungen.size());
        assertEquals("update.rolled_back", UpdateMeldung.telegramSchluessel(UpdateMeldung.PANOPTES_ZURUECK));
        assertEquals("notifications.update_zurueck", UpdateMeldung.pushSchluessel(UpdateMeldung.PANOPTES_ZURUECK));
    }

    // ------------------------------------------------------------- Doppelmeldung (04.11. 19:17)

    @Test
    public void sucheAufKnopfdruckMeldetNichtExtra() throws Exception {
        neu();
        freigabe("0.1.8", "");
        d.pruefen(true);
        assertEquals("Telegram/Handy/Web zeigen das Ergebnis selbst", 0, gemeldet.size());
        jetzt += UpdateRegel.PRUEF_TAKT_MS;
        d.pruefen(false);
        assertEquals("auch später nicht noch einmal", 0, gemeldet.size());
        assertEquals(9, d.status("de").getJSONObject("verfuegbar").getInt("versionCode"));
    }

    @Test
    public void laufendeInstallationVonAussenMeldetNicht() throws Exception {
        neu();
        freigabe("0.1.8", "");
        Files.write(new File(basis, FernUpdate.MARKE).toPath(), ("9 " + jetzt + " 0.1.8\n").getBytes());
        d.pruefen(false);
        assertEquals(0, gemeldet.size());
    }

    @Test
    public void deploySperreMeldetNicht() throws Exception {
        neu();
        freigabe("0.1.8", "");
        assertTrue(new File(basis, ".deploy.lock").mkdir());
        d.pruefen(false);
        assertEquals(0, gemeldet.size());
    }

    @Test
    public void hintergrundMeldetEinmal() throws Exception {
        neu();
        freigabe("0.1.8", "");
        Files.write(new File(basis, FernUpdate.MARKE).toPath(), ("8 " + jetzt + " 0.1.7\n").getBytes());
        d.pruefen(false);
        d.pruefen(false);
        assertEquals("alte Fern-Marke zählt nicht", Arrays.asList("0.1.8"), gemeldet);
    }

    // ------------------------------------------------------------- große Version: Sammelliste ≤ 8

    static String seitGross(int n) {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= n; i++) a.put("Punkt " + i);
        return ",\"notizen\":{\"de\":[\"nur diese Version\"]},\"notizenSeitGross\":{\"de\":" + a + ",\"en\":[\"en 1\"]}";
    }

    @Test
    public void grossesUpdateMeldetSammellisteHoechstensAcht() throws Exception {
        String j = AppUpdateTest.vj(70, "0.1.39", "stabil", "v0.1.39", AppUpdateTest.SHA0);
        Freigabe g = Freigabe.lies(j.substring(0, j.length() - 1) + seitGross(10) + "}");
        assertEquals("Freigabe kappt bei 8", 8, g.notizenSeitGross("de").length());
        String t = at.greenchili.surfmate.server.Messages.withLocale("de", () -> UpdateHinweis.wasNeu(g, "0.1.38.8", "de"));
        assertEquals("Art + 8 Punkte", 9, t.split("\n").length);
        assertTrue(t.contains("• Punkt 1") && t.contains("• Punkt 8") && !t.contains("nur diese Version"));
        String karte = UpdateHinweis.wasNeuKurz(g, "0.1.38.8", "de");
        assertEquals("Karte: Art + 3 Punkte", 4, karte.split("\n").length);
        assertEquals("Sprache ohne Liste: Englisch", "en 1", UpdateHinweis.zusammenfassung(g, "0.1.38.8", "fr").getString(0));
        // bleibt beim Speichern erhalten (appupdate_stand.json)
        assertEquals(8, Freigabe.lies(g.alsJson().toString()).notizenSeitGross("de").length());
    }

    @Test
    public void ohneSammellisteDieEigenenNotizen() throws Exception {
        String j = AppUpdateTest.vj(70, "0.1.39", "stabil", "v0.1.39", AppUpdateTest.SHA0);
        Freigabe g = Freigabe.lies(j.substring(0, j.length() - 1) + ",\"notizen\":{\"de\":[\"eigen\"]}}");
        assertEquals("eigen", UpdateHinweis.zusammenfassung(g, "0.1.38.8", "de").getString(0));
        assertFalse(g.alsJson().has("notizenSeitGross"));
    }

    @Test
    public void kleinesUpdateNimmtNieDieSammelliste() throws Exception {
        String j = AppUpdateTest.vj(70, "0.1.38.9", "stabil", "v0.1.38.9", AppUpdateTest.SHA0);
        Freigabe k = Freigabe.lies(j.substring(0, j.length() - 1) + seitGross(5) + "}");
        assertEquals("nur diese Version", UpdateHinweis.zusammenfassung(k, "0.1.38.8", "de").getString(0));
    }

    @Test
    public void statusUndHandyTragenKleinUndZusammenfassung() throws Exception {
        neu();
        freigabe("0.1.8", seitGross(3));
        d.pruefen();
        JSONObject v = d.status("de").getJSONObject("verfuegbar");
        assertFalse(v.getBoolean("klein"));
        assertEquals(3, v.getJSONArray("zusammenfassung").length());
        assertTrue(v.getString("kurz").startsWith("• Punkt 1"));
        FernUpdate fern = new FernUpdate(new FernUpdate.Umgebung() {
            @Override public JSONObject status(String sprache) { return d.status(sprache); }
            @Override public String installieren(int versionCode) { return null; }
            @Override public String fahrsperre() { return null; }
            @Override public void telegram(String version, String geraet) { }
            @Override public File basis() { return basis; }
            @Override public long jetzt() { return jetzt; }
        });
        JSONObject n = fern.stand("de", true).getJSONObject("neueste");
        assertFalse(n.getBoolean("klein"));
        assertEquals("Punkt 1", n.getJSONArray("zusammenfassung").getString(0));
    }
}
