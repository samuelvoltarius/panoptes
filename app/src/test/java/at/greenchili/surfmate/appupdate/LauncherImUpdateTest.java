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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.38.9 Vertrag (04.11.: der Launcher soll dort stehen, wo man Panoptes updatet): Status-JSON trägt
 * {@code launcher} mit installiert/verfügbar/neuer/mitPanoptes; Meldungen nennen ihn, wenn er mitkommt; keine zweite
 * Launcher-Meldung, wenn die Panoptes-Meldung ihn schon nennt; nicht installiert ist kein Fehler.
 */
public class LauncherImUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    long jetzt = 1_800_000_000_000L;
    int launcherVc = 5;
    final Map<String, String> cfg = new HashMap<>();
    final Map<String, String> texte = new HashMap<>();
    final List<String> meldungen = new ArrayList<>();
    final List<String> gemeldet = new ArrayList<>();
    File basis;
    AppUpdateDienst d;

    @Before
    public void auf() throws Exception {
        basis = tmp.newFolder("b");
        Files.write(new File(basis, AppUpdateDienst.KLEIN_MARKE).toPath(), "x\n".getBytes());
    }

    AppUpdateDienst neu() throws Exception {
        File dd = tmp.newFolder();
        d = new AppUpdateDienst(new AppUpdateDienst.Netz() {
            @Override public String text(String url) throws Exception {
                String t = texte.get(url);
                if (t == null) throw new java.io.IOException("404 " + url);
                return t;
            }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer p) throws Exception {
                throw new java.io.IOException("kein Download im Test");
            }
            @Override public long groesse(String url) { return 1234; }
        }, new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() { return new Object[]{false, "cloud_gate_car_on"}; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return false; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { }
            @Override public long jetzt() { return jetzt; }
            @Override public void melde(Freigabe f, long g) { gemeldet.add(f.versionName); }
            @Override public void meldeUpdate(UpdateMeldung.Meldung m) { meldungen.add(m.art); }
            @Override public LauncherPaket.Stand launcherStand() {
                return LauncherPaket.ausListe(launcherVc > 0
                        ? "package:" + LauncherPaket.PAKET + " versionCode:" + launcherVc + " uid:10123" : "");
            }
        });
        return d;
    }

    /** Panoptes {@code name} (vC 9) mit Launcher vC 6 im selben Release. */
    void freigabe(String name, int vc) throws Exception {
        String v = AppUpdateTest.vj(vc, name, "stabil", "v" + name, AppUpdateTest.SHA0);
        texte.put(AppUpdateTest.LATEST, v.substring(0, v.length() - 1) + ",\"launcher\":{\"versionCode\":6,"
                + "\"versionName\":\"0.2.1\",\"apk\":\"panoptes-launcher-0.2.1-abc.apk\",\"sha256\":\""
                + AppUpdateTest.SHA0 + "\"}}");
        neu();
        d.pruefen();
    }

    @Test
    public void statusTraegtLauncherInstalliertUndVerfuegbar() throws Exception {
        freigabe("0.1.8", 9);
        JSONObject s = d.status("de");
        JSONObject l = s.getJSONObject("launcher");
        assertEquals(5, l.getJSONObject("installiert").getInt("versionCode"));
        assertEquals(6, l.getJSONObject("verfuegbar").getInt("versionCode"));
        assertEquals("0.2.1", l.getJSONObject("verfuegbar").getString("version"));
        assertTrue(l.getBoolean("neuer"));
        assertTrue("kommt mit dem Panoptes-Update mit", l.getBoolean("mitPanoptes"));
        String satz = s.getJSONObject("verfuegbar").getString("launcherSatz");
        assertTrue(satz, satz.contains("0.2.1"));
    }

    @Test
    public void nichtInstalliertIstKeinFehler() throws Exception {
        launcherVc = 0;
        freigabe("0.1.8", 9);
        JSONObject s = d.status("de");
        JSONObject l = s.getJSONObject("launcher");
        assertTrue(l.isNull("installiert"));
        assertFalse(l.isNull("verfuegbar"));
        assertFalse(l.getBoolean("neuer"));
        assertFalse(l.getBoolean("mitPanoptes"));
        assertEquals("", s.getJSONObject("verfuegbar").getString("launcherSatz"));
        assertTrue("Panoptes bleibt installierbar oder hat seinen eigenen Grund", s.has("installierbar"));
    }

    @Test
    public void nurDerLauncherIstNeuer() throws Exception {
        freigabe("0.1.7", 8);                            // Panoptes aktuell
        JSONObject s = d.status("de");
        JSONObject l = s.getJSONObject("launcher");
        assertTrue(s.isNull("verfuegbar"));
        assertTrue(l.getBoolean("neuer"));
        assertFalse("kein Panoptes-Update, also eigener Knopf", l.getBoolean("mitPanoptes"));
    }

    @Test
    public void launcherSchonAktuell() throws Exception {
        launcherVc = 6;
        freigabe("0.1.8", 9);
        JSONObject l = d.status("de").getJSONObject("launcher");
        assertFalse(l.getBoolean("neuer"));
        assertFalse(l.getBoolean("mitPanoptes"));
    }

    @Test
    public void keineZweiteLauncherMeldungWennPanoptesSieNennt() throws Exception {
        freigabe("0.1.8", 9);                            // groß, wird gemeldet
        assertEquals(1, gemeldet.size());
        d.updateMeldungTakt(jetzt);
        assertFalse(meldungen.toString(), meldungen.contains(UpdateMeldung.LAUNCHER_VERFUEGBAR));
    }

    @Test
    public void stillesPanoptesUpdateMeldetDenLauncherSelbst() throws Exception {
        cfg.put("modus", UpdateRegel.MODUS_KORREKTUREN);
        freigabe("0.1.7.1", 9);                          // klein, kommt still, der Launcher kommt nie von selbst mit
        assertEquals(0, gemeldet.size());
        d.updateMeldungTakt(jetzt);
        assertTrue(meldungen.toString(), meldungen.contains(UpdateMeldung.LAUNCHER_VERFUEGBAR));
    }

    @Test
    public void handyTextNenntDenLauncher() throws Exception {
        freigabe("0.1.8", 9);
        FernUpdate fern = new FernUpdate(new FernUpdate.Umgebung() {
            @Override public JSONObject status(String sprache) { return d.status(sprache); }
            @Override public String installieren(int versionCode) { return null; }
            @Override public String fahrsperre() { return null; }
            @Override public void telegram(String version, String geraet) { }
            @Override public File basis() { return basis; }
            @Override public long jetzt() { return jetzt; }
        });
        JSONObject st = fern.stand("de", true);
        assertTrue(st.getString("text"), st.getString("text").contains("0.2.1"));
        assertTrue(st.getJSONObject("neueste").getString("launcherSatz").contains("0.2.1"));
    }

    @Test
    public void textUndWegeImCode() throws Exception {
        String dienst = UpdateHinweisTest.lies("src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java");
        assertTrue("Telegram + Push", dienst.contains("String mit = UpdateHinweis.launcherSatz(f, installierterVc(), launcherStand());"));
        // 0.1.43.3 (U1): darf nur aus Tipp/Vormerkung; ob er mitkommt, entscheidet der frische Stand vor dem Skript
        assertTrue("von selbst nie: nur Tipp oder Vormerkung",
                dienst.contains("laufDarfLauncher = alsVorgemerkt;")
                        && dienst.contains("if (!darf) return UEBERSPRUNGEN_NICHT_GETIPPT;"));
        // 0.1.38.10: eine Mobilfunk-Quelle für Panoptes und Launcher, im Lauf dieselbe Einwilligung wie Panoptes
        assertTrue(dienst.contains("mobil = l.mobilEinmal;"));
        assertTrue(dienst.contains("l.nurWlan = nurWlan();\n        l.laeuft = launcherLaeuft;")
                || dienst.contains("l.nurWlan = nurWlan();\r\n        l.laeuft = launcherLaeuft;"));
        assertFalse("keine zweite Lesung von update.mobilfunk", dienst.replace("return !UpdateRegel.an(env.config(\"mobilfunk\"));", "")
                .contains("env.config(\"mobilfunk\")"));
        String regel = UpdateHinweisTest.lies("src/main/java/at/greenchili/surfmate/appupdate/LauncherRegel.java");
        assertTrue(regel.contains("UpdateRegel.wartetWlan(l.nurWlan, mobil, l.netz)"));
        assertFalse("keine eigene WLAN-Prüfung", regel.contains("UpdateRegel.NETZ_WLAN.equals"));
        int mit = dienst.indexOf("launcherMitnehmen(f);");
        int letzte = dienst.indexOf("// Letzte Prüfung direkt vor dem Start");
        assertTrue("Launcher vor der letzten Prüfung und dem Skriptstart", mit > 0 && mit < letzte);
        String bot = UpdateHinweisTest.lies("src/main/java/at/greenchili/surfmate/daemon/telegram/UpdateCommandHandler.java");
        assertTrue(bot.contains("n.optString(\"launcherSatz\""));
        String js = UpdateHinweisTest.lies("src/main/assets/web/shared/app-updates.js");
        assertTrue(js.contains("d.launcher") && js.contains("updLauncherInstall") && js.contains("upd_launcher_mit"));
        String html = UpdateHinweisTest.lies("src/main/assets/web/local/system.html");
        assertTrue(html.contains("id=\"updLauncherHinweis\"") && html.contains("href=\"launcher.html\""));
        for (String sp : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject w = new JSONObject(UpdateHinweisTest.lies("src/main/assets/web/i18n/" + sp + ".json")).getJSONObject("system");
            for (String k : new String[]{"upd_launcher", "upd_launcher_none", "upd_launcher_hinweis", "upd_launcher_mit",
                    "upd_launcher_btn", "upd_launcher_neu", "upd_regeln"}) {
                assertFalse(sp + " " + k, w.getString(k).isEmpty());
            }
            assertTrue(sp, w.getString("upd_launcher_mit").contains("{v}"));
            JSONObject h = new JSONObject(UpdateHinweisTest.lies("src/main/assets/server-i18n/" + sp + ".json"))
                    .getJSONObject("update_hinweis");
            assertTrue(sp, h.getString("launcher_mit").contains("{0}"));
        }
    }
}
