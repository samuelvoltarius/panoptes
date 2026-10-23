package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-App-Updater: version.json streng, Regeln (nur Auto aus, Sperre), Ablauf mit nachgebautem Netz. */
public class AppUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String LATEST = "https://github.com/" + UpdateQuelle.STANDARD_REPO + "/releases/latest/download/version.json";

    static String vj(int vc, String name, String kanal, String tag, String sha) {
        return "{\"versionCode\":" + vc + ",\"versionName\":\"" + name + "\",\"kanal\":\"" + kanal + "\",\"tag\":\""
                + tag + "\",\"commit\":\"abc\",\"apk\":\"panoptes-" + name + "-abc.apk\",\"sha256\":\"" + sha
                + "\",\"zertifikatSha256\":\"" + Freigabe.ZERTIFIKAT + "\",\"minVersion\":2}";
    }

    static final String SHA0 = "0000000000000000000000000000000000000000000000000000000000000000";

    /** Freigabe mit der öffentlichen Download-Adresse (wie sie der Dienst ohne Token setzt). */
    static Freigabe frei(String json) throws Freigabe.Fehler {
        Freigabe f = Freigabe.lies(json);
        return f.mitApkUrl("https://github.com/" + UpdateQuelle.STANDARD_REPO + "/releases/download/" + f.tag + "/"
                + f.apk);
    }

    // ------------------------------------------------------------- Freigabe

    @Test
    public void versionJsonWieCi() throws Exception {
        Freigabe f = Freigabe.lies(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        assertEquals(9, f.versionCode);
        assertNull(f.apkUrl());                            // Adresse kommt nie aus version.json, nur aus der Quelle
        assertEquals("https://github.com/samuelvoltarius/panoptes/releases/download/v0.1.8/panoptes-0.1.8-abc.apk",
                frei(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0)).apkUrl());
        assertEquals(0, f.notizen("de").length());
        Freigabe t = Freigabe.lies(vj(10, "0.1.9", "test", "v0.1.9-test.2", SHA0));
        assertEquals("test", t.kanal);
    }

    @Test
    public void versionJsonStreng() {
        String[] schlecht = {
                "kein json",
                vj(0, "0.1.8", "stabil", "v0.1.8", SHA0),
                vj(9, "0.1.8", "stabil", "v0.1.8-test.1", SHA0),     // Kanal passt nicht zum Tag
                vj(9, "0.1.8", "test", "v0.1.8", SHA0),
                vj(9, "0.1.8", "stabil", "v0.1.9", SHA0),             // Tag passt nicht zur Version
                vj(9, "0.1.8", "stabil", "v0.1.8", "xyz"),
                vj(9, "0.1.8", "beta", "v0.1.8", SHA0),
                vj(9, "0.1.8", "stabil", "v0.1.8", SHA0).replace("panoptes-0.1.8-abc.apk", "../x.apk"),
                vj(9, "0.1.8", "stabil", "v0.1.8", SHA0).replace(Freigabe.ZERTIFIKAT, SHA0),
        };
        for (String s : schlecht) {
            try {
                Freigabe.lies(s);
                fail("angenommen: " + s);
            } catch (Freigabe.Fehler e) {
                assertNotNull(e.grund);
            }
        }
        try {
            Freigabe.lies(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0).replace(Freigabe.ZERTIFIKAT, SHA0));
        } catch (Freigabe.Fehler e) {
            assertEquals("signatur", e.grund);
        }
    }

    @Test
    public void notizenJeSprache() throws Exception {
        String j = vj(9, "0.1.8", "stabil", "v0.1.8", SHA0).replace("\"minVersion\":2",
                "\"minVersion\":2,\"notizen\":{\"de\":[\"Neu A\",\"\"],\"en\":[\"New A\"]}");
        Freigabe f = Freigabe.lies(j);
        assertEquals("Neu A", f.notizen("de").getString(0));
        assertEquals(1, f.notizen("de").length());
        assertEquals("New A", f.notizen("fr").getString(0));   // Rückfall en
    }

    // ------------------------------------------------------------- Regel

    static UpdateRegel.Lage lage(int inst, Freigabe f) {
        UpdateRegel.Lage l = new UpdateRegel.Lage();
        l.installiert = inst;
        l.verfuegbar = f;
        l.autoAus = true;
        return l;
    }

    @Test
    public void nurWennAutoAus() throws Exception {
        Freigabe f = Freigabe.lies(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        assertNull(UpdateRegel.nichtWeil(lage(8, f)));
        assertEquals("kein_update", UpdateRegel.nichtWeil(lage(9, f)));
        assertEquals("kein_update", UpdateRegel.nichtWeil(lage(8, null)));
        assertEquals("zu_alt", UpdateRegel.nichtWeil(lage(1, f)));
        UpdateRegel.Lage l = lage(8, f);
        l.kmh = 3;
        assertEquals("faehrt", UpdateRegel.nichtWeil(l));
        l = lage(8, f);
        l.autoAus = false;
        l.gateGrund = "cloud_gate_car_on";
        assertEquals("zuendung_an", UpdateRegel.nichtWeil(l));
        l.gateGrund = "cloud_gate_state_stale";
        assertEquals("zustand_unbekannt", UpdateRegel.nichtWeil(l));
        l = lage(8, f);
        l.laeuftSchon = true;
        assertEquals("laeuft_schon", UpdateRegel.nichtWeil(l));
    }

    @Test
    public void automatischErstNach10MinUndNieNachRueckfall() throws Exception {
        Freigabe f = Freigabe.lies(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        UpdateRegel.Lage l = lage(8, f);
        assertEquals(-1, UpdateRegel.automatischAb(l, false, 1000));       // Modus melden
        assertEquals(1000 + UpdateRegel.AUTOMATISCH_NACH_MS, UpdateRegel.automatischAb(l, true, 1000));
        assertEquals(-1, UpdateRegel.automatischAb(l, true, -1));
        l.rueckfallVc = 9;
        assertEquals(-1, UpdateRegel.automatischAb(l, true, 1000));
        assertEquals("stabil", UpdateRegel.kanal(null));
        assertEquals("stabil", UpdateRegel.kanal("beta"));
        assertFalse(UpdateRegel.automatisch(null));
    }

    // ------------------------------------------------------------- Dienst

    final Map<String, String> texte = new HashMap<>();
    final Map<String, File> dateien = new HashMap<>();
    final Map<String, String> cfg = new HashMap<>();
    final List<String> gestartet = new ArrayList<>();
    long jetzt = 100_000_000L;
    boolean autoAus = true;
    double kmh = 0;
    Boolean accAus = null;
    File basis, dd;

    AppUpdateDienst dienst() throws Exception {
        basis = tmp.newFolder("surfmate");
        dd = tmp.newFolder("deploy");
        AppUpdateDienst.Netz netz = new AppUpdateDienst.Netz() {
            @Override public String text(String url) throws Exception {
                String t = texte.get(url);
                if (t == null) throw new java.io.IOException("404 " + url);
                return t;
            }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p) throws Exception {
                File q = dateien.get(url);
                if (q == null) throw new java.io.IOException("404");
                Files.copy(q.toPath(), ziel.toPath());
                p.accept(100);
            }
            @Override public long groesse(String url) { return 1234; }
        };
        AppUpdateDienst.Umgebung env = new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() {
                if (ohnePowerLevel) return new Object[]{false, "cloud_gate_state_unknown"};
                return new Object[]{autoAus, autoAus ? null : "cloud_gate_car_on"};
            }
            @Override public double kmh() { return kmh; }
            @Override public Boolean accAus() { return accAus; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript() { return "#!/system/bin/sh\necho test\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) {
                gestartet.add(apk.getName() + "|" + vc + "|" + tag + "|" + key);
            }
            @Override public long jetzt() { return jetzt; }
            @Override public String netz() { return netzArt; }
            @Override public void melde(Freigabe f, long g) { gemeldet.add(f.versionName + "|" + g); }
        };
        return new AppUpdateDienst(netzErsatz != null ? netzErsatz : netz, env);
    }

    String netzArt = UpdateRegel.NETZ_UNBEKANNT;
    final List<String> gemeldet = new ArrayList<>();
    AppUpdateDienst.Netz netzErsatz;

    // ------------------------------------------------------------- 0.1.9: Mobilfunk, Prüfen-Schalter, Stücke

    @Test
    public void mobilfunkStandardAnNurWlanWartet() throws Exception {
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        netzArt = UpdateRegel.NETZ_MOBIL;
        JSONObject s = d.status("de");
        assertTrue(s.getBoolean("mobilfunk"));            // nicht gesetzt = an (nur SIM ist normal)
        assertTrue(s.getBoolean("installierbar"));
        assertEquals("mobil", s.getString("netz"));
        cfg.put("mobilfunk", "false");                    // „nur im WLAN“
        s = d.status("de");
        assertFalse(s.getBoolean("mobilfunk"));
        assertEquals("wartet_wlan", s.getString("nichtWeil"));
        assertEquals("wartet_wlan", d.installieren(9));
        netzArt = UpdateRegel.NETZ_UNBEKANNT;             // Ethernet/unbekannt kann die SIM der T-Box sein
        assertEquals("wartet_wlan", d.status("de").getString("nichtWeil"));
        netzArt = UpdateRegel.NETZ_WLAN;
        assertTrue(d.status("de").getBoolean("installierbar"));
        assertEquals("wlan", UpdateRegel.netz("wifi"));
        assertEquals("mobil", UpdateRegel.netz("cellular"));
        assertEquals("unbekannt", UpdateRegel.netz("ethernet"));
    }

    @Test
    public void pruefenAusFragtNichtUndInstalliertNieVonSelbst() throws Exception {
        AppUpdateDienst d = dienst();
        cfg.put("pruefen", "false");
        cfg.put("modus", "automatisch");
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.takt();
        JSONObject s = d.status("de");
        assertFalse(s.getBoolean("pruefen"));
        assertEquals("nie", s.getString("ergebnis"));     // kein Abruf bei GitHub
        // ausdrücklich auf Knopfdruck suchen geht weiterhin
        d.suchen("de");
        assertEquals("verfuegbar", d.status("de").getString("ergebnis"));
        d.takt();
        assertTrue(d.status("de").isNull("automatischGeplant"));
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 60_000;
        d.takt();
        assertTrue(gestartet.isEmpty());
        // Bestand (nicht gesetzt) = an
        cfg.remove("pruefen");
        assertTrue(d.status("de").getBoolean("pruefen"));
        assertTrue(UpdateRegel.an(null));
        assertFalse(UpdateRegel.an("false"));
    }

    @Test
    public void neueVersionEinmalMitGroesseMelden() throws Exception {
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        d.pruefen();
        assertEquals(java.util.Collections.singletonList("0.1.8|1234"), gemeldet);
        // auch nach Neustart nicht nochmal
        new AppUpdateDienst(d.netz, d.env).pruefen();
        assertEquals(1, gemeldet.size());
        assertEquals("78 MB", UpdateRegel.groesseText(78_400_000L));
        assertEquals("2 KB", UpdateRegel.groesseText(1500));
        assertEquals("", UpdateRegel.groesseText(-1));
    }

    /** Netz mit HTTP Range über ein Byte-Feld; bricht optional nach n Stücken ab. */
    static final class RangeNetz implements AppUpdateDienst.Netz {
        final byte[] daten;
        final Map<String, String> texte;
        int stuecke, abbruchNach = -1;
        final List<Long> anfragen = new ArrayList<>();
        Runnable nachStueck;
        RangeNetz(byte[] daten, Map<String, String> texte) { this.daten = daten; this.texte = texte; }
        @Override public String text(String url) throws Exception {
            String t = texte.get(url);
            if (t == null) throw new java.io.IOException("404 " + url);
            return t;
        }
        @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p) {
            throw new AssertionError("ohne Range geladen");
        }
        @Override public long groesse(String url) { return daten.length; }
        @Override public long ladenStueck(String url, File teil, long ab, long laenge, long max) throws Exception {
            anfragen.add(ab);
            if (abbruchNach >= 0 && stuecke >= abbruchNach) throw new java.io.IOException("Verbindung weg");
            int n = (int) Math.min(laenge, daten.length - ab);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(teil, true)) {
                o.write(daten, (int) ab, n);
            }
            stuecke++;
            if (nachStueck != null) nachStueck.run();
            return daten.length;
        }
    }

    @Test
    public void downloadInStueckenSetztNachAbbruchFort() throws Exception {
        byte[] daten = new byte[(int) (2.5 * AppUpdateDienst.STUECK_BYTES)];
        new java.util.Random(7).nextBytes(daten);
        File q = tmp.newFile("q.apk");
        Files.write(q.toPath(), daten);
        RangeNetz rn = new RangeNetz(daten, texte);
        rn.abbruchNach = 1;
        netzErsatz = rn;
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateDienst.sha256(q)));
        Freigabe f = frei(texte.get(LATEST));
        d.pruefen();
        d.ausfuehren(f);                                   // 1 Stück, dann Abbruch
        File teil = d.teilDatei(f);
        assertEquals(AppUpdateDienst.STUECK_BYTES, teil.length());
        JSONObject s = d.status("de");
        assertEquals(AppUpdateDienst.STUECK_BYTES, s.getLong("geladenBytes"));
        assertEquals("pausiert", s.getString("phase"));
        rn.abbruchNach = -1;
        d.ausfuehren(f);                                   // setzt fort (Range ab 4 MB), sha ok, Signatur nicht
        assertEquals(Arrays.asList(0L, AppUpdateDienst.STUECK_BYTES, AppUpdateDienst.STUECK_BYTES,
                2 * AppUpdateDienst.STUECK_BYTES), rn.anfragen);
        // Zertifikat fehlt in den Zufallsbytes → „signatur“, aber die sha256 war richtig (nicht „pruefsumme“)
        assertEquals("signatur", d.status("de").getString("fehler"));
        assertFalse(teil.exists());
    }

    @Test
    public void zuendungAnPausiertDownloadOhneFehler() throws Exception {
        byte[] daten = new byte[(int) (3 * AppUpdateDienst.STUECK_BYTES)];
        RangeNetz rn = new RangeNetz(daten, texte);
        rn.nachStueck = () -> autoAus = false;              // nach dem ersten Stück Zündung an
        netzErsatz = rn;
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        Freigabe f = frei(texte.get(LATEST));
        d.ausfuehren(f);
        assertEquals(1, rn.stuecke);
        JSONObject s = d.status("de");
        assertTrue(s.isNull("fehler"));                     // keine Eskalation
        assertTrue(gestartet.isEmpty());
        assertEquals(AppUpdateDienst.STUECK_BYTES, d.teilDatei(f).length());
    }

    @Test
    public void teildateiAndererVersionWirdWeggeraeumt() throws Exception {
        RangeNetz rn = new RangeNetz(new byte[10], texte);
        netzErsatz = rn;
        AppUpdateDienst d = dienst();
        File alt = new File(dd, "neu-appupdate-7-aaaaaaaaaaaa.apk.part");
        Files.write(alt.toPath(), new byte[5]);
        autoAus = false;                                     // nichts laden, nur aufräumen
        d.ausfuehren(frei(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0)));
        assertFalse(alt.exists());
        assertTrue(rn.anfragen.isEmpty());
    }

    /** Schema: update.pruefen und update.mobilfunk, beide Standard an (Bestand), Gruppe update. */
    @Test
    public void schemaFelder() {
        java.util.Map<String, at.greenchili.surfmate.einstellungen.Feld> m = new HashMap<>();
        for (at.greenchili.surfmate.einstellungen.Feld x
                : at.greenchili.surfmate.einstellungen.Katalog.felder(new at.greenchili.surfmate.einstellungen.Katalog.Kontext())) {
            m.put(x.key, x);
        }
        assertEquals(Boolean.TRUE, m.get("update.pruefen").standard);
        assertEquals(Boolean.TRUE, m.get("update.mobilfunk").standard);
        assertEquals("update", m.get("update.mobilfunk").gruppe);
    }

    /** Ersteinrichtung fragt update.pruefen ohne Vorauswahl und nur bei neuer Einrichtung (nicht im Replay). */
    @Test
    public void ersteinrichtungFragtOhneVorauswahl() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/onboarding/OnboardingHost.kt"));
        assertTrue(h.contains("!replayMode && !state.updateFrageDone"));
        assertTrue(h.contains("onPrimary = { beantworteUpdateFrage(ov, true) }"));
        assertTrue(h.contains("onSecondary = { beantworteUpdateFrage(ov, false) }"));
        // nicht als Einrichtungs-Standard still gesetzt
        int a = h.indexOf("private fun einrichtungsStandards()");
        assertFalse(h.substring(a, h.indexOf("private fun", a + 10)).contains("update.pruefen"));
    }

    @Test
    public void statusOhneNetzIstFehlerUndKeinAbsturz() throws Exception {
        AppUpdateDienst d = dienst();
        JSONObject s = d.status("de");
        assertEquals("nie", s.getString("ergebnis"));
        assertEquals("melden", s.getString("modus"));
        assertEquals("stabil", s.getString("kanal"));
        assertTrue(s.isNull("verfuegbar"));
        d.pruefen();                                       // Hintergrund: still (0.1.15), Fehler nur im Status-Feld
        s = d.status("de");
        assertEquals("nie", s.getString("ergebnis"));
        assertEquals("nicht_erreichbar", s.getString("fehler"));
        d.suchen("de");                                    // auf Knopfdruck sichtbar
        s = d.status("de");
        assertEquals("fehler", s.getString("ergebnis"));
        assertEquals("nicht_erreichbar", s.getString("fehler"));
        assertEquals(8, s.getJSONObject("aktuell").getInt("versionCode"));
    }

    @Test
    public void findetUpdateUndPrueftSha() throws Exception {
        AppUpdateDienst d = dienst();
        File apk = tmp.newFile("x.apk");
        Files.write(apk.toPath(), "nicht die echte apk".getBytes(StandardCharsets.UTF_8));
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        dateien.put(frei(texte.get(LATEST)).apkUrl(), apk);
        d.pruefen();
        JSONObject s = d.status("de");
        assertEquals("verfuegbar", s.getString("ergebnis"));
        assertEquals(9, s.getJSONObject("verfuegbar").getInt("versionCode"));
        assertEquals(1234, s.getJSONObject("verfuegbar").getLong("groesseBytes"));
        assertTrue(s.getBoolean("installierbar"));
        // Anderer Stand → abgelehnt
        assertEquals("anderer_stand", d.installieren(10));
        // sha256 passt nicht → abgebrochen, nichts gestartet, keine Sperre
        d.ausfuehren(frei(texte.get(LATEST)));
        assertEquals("pruefsumme", d.status("de").getString("fehler"));
        assertTrue(gestartet.isEmpty());
        assertFalse(new File(basis, ".deploy.lock").exists());
        assertEquals(0, dd.listFiles((x, n) -> n.endsWith(".apk") || n.endsWith(".part")).length);
    }

    @Test
    public void fremdesZertifikatWirdAbgelehnt() throws Exception {
        AppUpdateDienst d = dienst();
        File apk = tmp.newFile("x.apk");
        Files.write(apk.toPath(), "kein signaturblock".getBytes(StandardCharsets.UTF_8));
        String sha = AppUpdateDienst.sha256(apk);
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", sha));
        Freigabe f = frei(texte.get(LATEST));
        dateien.put(f.apkUrl(), apk);
        d.pruefen();
        d.ausfuehren(f);
        assertEquals("signatur", d.status("de").getString("fehler"));
        assertTrue(gestartet.isEmpty());
    }

    @Test
    public void nieBeiZuendungOderFahrtOderSperre() throws Exception {
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        autoAus = false;
        assertEquals("zuendung_an", d.installieren(9));
        autoAus = true;
        kmh = 20;
        assertEquals("faehrt", d.installieren(9));
        kmh = 0;
        assertTrue(new File(basis, ".deploy.lock").mkdir());
        assertEquals("laeuft_schon", d.installieren(9));
        assertEquals("laeuft_schon", d.status("de").getString("nichtWeil"));
    }

    @Test
    public void testKanalNimmtNeuesteVorabversion() throws Exception {
        AppUpdateDienst d = dienst();
        cfg.put("kanal", "test");
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        texte.put("https://api.github.com/repos/" + UpdateQuelle.STANDARD_REPO + "/releases?per_page=5",
                "[{\"tag_name\":\"v0.1.9-test.1\",\"draft\":true},{\"tag_name\":\"v0.1.9-test.0\",\"draft\":false}]");
        texte.put("https://github.com/" + UpdateQuelle.STANDARD_REPO + "/releases/download/v0.1.9-test.0/version.json",
                vj(10, "0.1.9", "test", "v0.1.9-test.0", SHA0));
        d.pruefen();
        assertEquals(10, d.status("de").getJSONObject("verfuegbar").getInt("versionCode"));
        cfg.put("kanal", "stabil");
        d.pruefen();
        assertEquals(9, d.status("de").getJSONObject("verfuegbar").getInt("versionCode"));
    }

    @Test
    public void rueckfallMarkeSperrtAutomatik() throws Exception {
        AppUpdateDienst d = dienst();
        cfg.put("modus", "automatisch");
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        d.takt();
        JSONObject s = d.status("de");
        assertEquals(jetzt + UpdateRegel.AUTOMATISCH_NACH_MS, s.getLong("automatischGeplant"));
        Files.write(new File(basis, ".appupdate-rueckfall").toPath(), "9 totmann\n".getBytes(StandardCharsets.UTF_8));
        s = d.status("de");
        assertTrue(s.isNull("automatischGeplant"));
        assertEquals("rueckfall", s.getString("phase"));
        assertTrue(s.getJSONObject("verfuegbar").getBoolean("zurueckgerollt"));
        jetzt += UpdateRegel.AUTOMATISCH_NACH_MS + 1;
        d.takt();
        assertTrue(gestartet.isEmpty());
    }

    @Test
    public void standUeberlebtNeustart() throws Exception {
        AppUpdateDienst d = dienst();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        AppUpdateDienst d2 = new AppUpdateDienst(d.netz, d.env);
        JSONObject s = d2.status("de");
        assertEquals("verfuegbar", s.getString("ergebnis"));
        assertEquals(9, s.getJSONObject("verfuegbar").getInt("versionCode"));
    }

    /** Echte APK (falls am PC vorhanden): Ablauf bis zum abgekoppelten Start mit Sperre (schluessel) und Skript. */
    @Test
    public void echteApkBisZumStart() throws Exception {
        File dir = new File("build/abgabe");
        File[] apks = dir.listFiles((x, n) -> n.startsWith("panoptes-kern-") && n.endsWith(".apk"));
        assumeTrue(apks != null && apks.length > 0);
        AppUpdateDienst d = dienst();
        String sha = AppUpdateDienst.sha256(apks[0]);
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", sha));
        Freigabe f = frei(texte.get(LATEST));
        dateien.put(f.apkUrl(), apks[0]);
        d.pruefen();
        assertNull(d.installieren(9));
        for (int i = 0; i < 200 && gestartet.isEmpty(); i++) Thread.sleep(50);
        assertEquals(1, gestartet.size());
        String[] t = gestartet.get(0).split("\\|");
        assertEquals("neu-appupdate-9.apk", t[0]);
        assertEquals("v0.1.8", t[2]);
        String info = new String(Files.readAllBytes(new File(basis, ".deploy.lock/info").toPath()), StandardCharsets.UTF_8);
        assertTrue(info.contains("sitzung=appupdate"));
        assertTrue(info.contains("schluessel=" + t[3]));
        assertTrue(new File(dd, "update-on-car.sh").exists());
        assertEquals("installiert", d.status("de").getString("phase"));   // bis der Daemon stirbt
        // nach dem Neustart (neuer Dienst, gleiche Dateien): Sperre von appupdate → „beobachtet“
        assertEquals("beobachtet", new AppUpdateDienst(d.netz, d.env).status("de").getString("phase"));
    }

    // ------------------------------------------------------------- Verträge

    /** Das Skript im APK ist byte-gleich mit deploy/update-on-car.sh (eine Quelle für Deploy und Updater). */
    @Test
    public void skriptImApkGleichDeploy() throws Exception {
        String a = Quelltext.lies(Paths.get("src/main/assets/deploy/update-on-car.sh"));
        String b = Quelltext.lies(Paths.get("../deploy/update-on-car.sh"));
        assertEquals(b, a);
        assertTrue(a.contains("AUTO_OK"));
        assertTrue(a.contains("DEADLINE_MIN=${DEADLINE_MIN:-30}"));
        assertTrue(a.contains("rueckfall_merken totmann"));
    }

    /** Kopplungs-Tokens: nur status lesen, nie suchen/installieren (ExternGuard + zweite Sperre im Handler). */
    @Test
    public void kopplungDarfNurLesen() throws Exception {
        assertTrue(at.greenchili.surfmate.server.ExternGuard.erlaubt("GET", "/api/appupdate/status"));
        assertFalse(at.greenchili.surfmate.server.ExternGuard.erlaubt("POST", "/api/appupdate/installieren"));
        assertFalse(at.greenchili.surfmate.server.ExternGuard.erlaubt("POST", "/api/appupdate/suchen"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AppUpdateApiHandler.java"));
        assertTrue(h.contains("AuthMiddleware.isExternRequest()"));
    }

    // ------------------------------------------------------------- zweiter Nachweis (fail-closed)

    @Test
    public void zweiterNachweisNurStabilUndVollstaendig() throws Exception {
        long t = 1_000_000L;
        long seit = UpdateRegel.zweiterNachweisSeit(-1, true, 0.0, t);
        assertEquals(t, seit);
        assertFalse(UpdateRegel.zweiterNachweisGilt(seit, t + UpdateRegel.ZWEITER_NACHWEIS_MS - 1));
        assertTrue(UpdateRegel.zweiterNachweisGilt(seit, t + UpdateRegel.ZWEITER_NACHWEIS_MS));
        // jeder fehlende oder schlechte Wert setzt zurück
        assertEquals(-1, UpdateRegel.zweiterNachweisSeit(seit, null, 0.0, t + 1));
        assertEquals(-1, UpdateRegel.zweiterNachweisSeit(seit, false, 0.0, t + 1));
        assertEquals(-1, UpdateRegel.zweiterNachweisSeit(seit, true, Double.NaN, t + 1));
        assertEquals(-1, UpdateRegel.zweiterNachweisSeit(seit, true, 3.0, t + 1));
        assertFalse(UpdateRegel.zweiterNachweisGilt(-1, t + 10 * UpdateRegel.ZWEITER_NACHWEIS_MS));
    }

    @Test
    public void zweiterNachweisUeberstimmtNieZuendungAn() throws Exception {
        Freigabe f = Freigabe.lies(vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        UpdateRegel.Lage l = lage(8, f);
        l.autoAus = false;
        l.gateGrund = "cloud_gate_car_on";
        l.zweiterNachweis = true;
        assertEquals("zuendung_an", UpdateRegel.nichtWeil(l));
        l.gateGrund = "cloud_gate_state_unknown";
        assertNull(UpdateRegel.nichtWeil(l));
        l.zweiterNachweis = false;
        assertEquals("zustand_unbekannt", UpdateRegel.nichtWeil(l));
    }

    /** Ohne power_level (Variante ohne Lesung): erst nach 15 min ACC aus + 0 km/h installierbar. */
    @Test
    public void dienstMitZweitemNachweis() throws Exception {
        AppUpdateDienst d = dienstOhnePowerLevel();
        texte.put(LATEST, vj(9, "0.1.8", "stabil", "v0.1.8", SHA0));
        d.pruefen();
        accAus = true;
        kmh = 0;
        assertEquals("zustand_unbekannt", d.status("de").getString("nichtWeil"));
        jetzt += UpdateRegel.ZWEITER_NACHWEIS_MS;
        assertTrue(d.status("de").getBoolean("installierbar"));
        accAus = null;                          // ACC nicht mehr autoritativ → sofort wieder zu
        assertEquals("zustand_unbekannt", d.status("de").getString("nichtWeil"));
        accAus = true;
        kmh = Double.NaN;
        jetzt += 2 * UpdateRegel.ZWEITER_NACHWEIS_MS;
        assertEquals("zustand_unbekannt", d.status("de").getString("nichtWeil"));
    }

    boolean ohnePowerLevel;

    AppUpdateDienst dienstOhnePowerLevel() throws Exception {
        ohnePowerLevel = true;
        return dienst();
    }
}
