package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.1 Update-Meldungen (Koordination 04.11., Projektinhaber: „bekommen wir eine Nachricht, wenn Launcher, Panoptes …
 * geupdatet werden?“): Panoptes „installiert und läuft“ erst nach grüner Selbstprüfung, Launcher verfügbar und
 * installiert, je Version höchstens einmal, 5 Sprachen, ohne Fachwort.
 */
public class UpdateMeldungTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Freigabe.Launcher launcher(int vc, String name) {
        return new Freigabe.Launcher(vc, name, "panoptes-launcher-" + name + ".apk", "00", null);
    }

    private static List<String> arten(List<UpdateMeldung.Meldung> l) {
        List<String> out = new ArrayList<>();
        for (UpdateMeldung.Meldung m : l) out.add(m.art + ":" + m.version);
        return out;
    }

    @Test
    public void neuinstallationMeldetNichts() {
        UpdateMeldung m = new UpdateMeldung();
        assertTrue(m.pruefe(65, "0.1.38.1", 0, "gruen", false, 0, null).isEmpty());
        assertEquals(65, m.panoptesVc);
        assertTrue("je Version einmal", m.pruefe(65, "0.1.38.1", 0, "gruen", false, 0, null).isEmpty());
    }

    @Test
    public void ersterLaufNachGemeldetemUpdateMeldetEinmal() {
        UpdateMeldung m = new UpdateMeldung();
        // 0.1.38 hatte 0.1.38.1 (vC 65) als „neu verfügbar“ gemeldet → nach dem Update ist das ein Update
        assertEquals(List.of("panoptes_installiert:0.1.38.1"), arten(m.pruefe(65, "0.1.38.1", 65, "gruen", false, 0, null)));
        assertTrue(m.pruefe(65, "0.1.38.1", 65, "gruen", false, 0, null).isEmpty());
    }

    @Test
    public void erstWennSelbstpruefungGruenUndUpdaterFertig() {
        UpdateMeldung m = new UpdateMeldung();
        m.panoptesVc = 64;
        assertTrue("Selbstprüfung noch offen", m.pruefe(65, "0.1.38.1", 65, "offen", false, 0, null).isEmpty());
        assertTrue("Updater beobachtet noch", m.pruefe(65, "0.1.38.1", 65, "gruen", true, 0, null).isEmpty());
        assertEquals(64, m.panoptesVc);
        assertEquals(1, m.pruefe(65, "0.1.38.1", 65, "gruen", false, 0, null).size());
        assertEquals(65, m.panoptesVc);
    }

    @Test
    public void beiStoerungKeineErfolgsmeldung() {
        UpdateMeldung m = new UpdateMeldung();
        m.panoptesVc = 64;
        assertTrue(m.pruefe(65, "0.1.38.1", 65, "stoerung", false, 0, null).isEmpty());
        assertEquals("gilt als erledigt, die Störungsmeldung ist schon draußen", 65, m.panoptesVc);
        assertTrue(m.pruefe(65, "0.1.38.1", 65, "gruen", false, 0, null).isEmpty());
    }

    @Test
    public void rueckfallStillUndNeuerVersuchMeldetWieder() {
        UpdateMeldung m = new UpdateMeldung();
        m.panoptesVc = 65;
        assertTrue(m.pruefe(64, "0.1.38", 65, "gruen", false, 0, null).isEmpty());
        assertEquals(64, m.panoptesVc);
        assertEquals(1, m.pruefe(65, "0.1.38.1", 65, "gruen", false, 0, null).size());
    }

    @Test
    public void launcherVerfuegbarNurWennInstalliertUndJeVersionEinmal() {
        UpdateMeldung m = new UpdateMeldung();
        m.panoptesVc = 65;
        assertTrue("ohne Launcher keine Werbung", m.pruefe(65, "0.1.38.1", 0, "gruen", false, 0, launcher(4, "0.1.38.1")).isEmpty());
        assertEquals(List.of("launcher_verfuegbar:0.1.38.1"),
                arten(m.pruefe(65, "0.1.38.1", 0, "gruen", false, 3, launcher(4, "0.1.38.1"))));
        assertTrue(m.pruefe(65, "0.1.38.1", 0, "gruen", false, 3, launcher(4, "0.1.38.1")).isEmpty());
        assertTrue("gleich alt: nichts", new UpdateMeldung().pruefe(65, "x", 0, "gruen", false, 4, launcher(4, "a")).isEmpty());
    }

    @Test
    public void launcherInstalliertNachUpdateEinmal() {
        UpdateMeldung m = new UpdateMeldung();
        m.panoptesVc = 65;
        assertTrue("erster Blick merkt nur", m.pruefe(65, "x", 0, "gruen", false, 3, null).isEmpty());
        assertEquals(List.of("launcher_installiert:0.1.38.1"),
                arten(m.pruefe(65, "x", 0, "gruen", false, 4, launcher(4, "0.1.38.1"))));
        assertTrue(m.pruefe(65, "x", 0, "gruen", false, 4, launcher(4, "0.1.38.1")).isEmpty());
        assertEquals("ohne Freigabe der versionCode", List.of("launcher_installiert:5"),
                arten(m.pruefe(65, "x", 0, "gruen", false, 5, null)));
    }

    @Test
    public void zustandUeberstehtNeustart() throws Exception {
        File b = tmp.newFolder("b");
        UpdateMeldung m = new UpdateMeldung();
        m.pruefe(65, "0.1.38.1", 65, "gruen", false, 4, launcher(5, "0.1.39"));
        m.speichern(b);
        UpdateMeldung n = UpdateMeldung.lade(b);
        assertEquals(65, n.panoptesVc);
        assertEquals(4, n.launcherVc);
        assertEquals(5, n.launcherVerfuegbarVc);
        assertTrue(n.pruefe(65, "0.1.38.1", 65, "gruen", false, 4, launcher(5, "0.1.39")).isEmpty());
        assertFalse(new File(b, UpdateMeldung.DATEI + ".neu").exists());
    }

    // ------------------------------------------------------------- im Dienst

    int vc = 64;
    String selbst = "gruen";
    long jetzt = 1_000_000L;
    final List<UpdateMeldung.Meldung> gesendet = new ArrayList<>();

    private AppUpdateDienst dienst(File basis, File dd) {
        AppUpdateDienst.Umgebung env = new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return vc; }
            @Override public String installierteVersion() { return vc == 65 ? "0.1.38.1" : "0.1.38"; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return null; }
            @Override public String config(String key) { return null; }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return Freigabe.ZERTIFIKAT; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int v, String tag) { }
            @Override public long jetzt() { return jetzt; }
            @Override public String selbstpruefung() { return selbst; }
            @Override public void meldeUpdate(UpdateMeldung.Meldung m) { gesendet.add(m); }
        };
        return new AppUpdateDienst(new AppUpdateDienst.Netz() {
            @Override public String text(String url) { return null; }
            @Override public long groesse(String url) { return -1; }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer f) { }
        }, env);
    }

    @Test
    public void dienstMeldetNachDemUpdateEinmalUndNurAlleFuenfMinuten() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        dienst(basis, dd).updateMeldungTakt(jetzt);           // 0.1.38 läuft: merken
        assertTrue(gesendet.isEmpty());
        vc = 65;                                              // Update, neuer Daemon-Prozess
        selbst = "offen";
        AppUpdateDienst d = dienst(basis, dd);
        d.updateMeldungTakt(jetzt);
        assertTrue("Selbstprüfung noch nicht grün", gesendet.isEmpty());
        selbst = "gruen";
        d.updateMeldungTakt(jetzt + 60_000L);
        assertTrue("höchstens alle 5 min", gesendet.isEmpty());
        d.updateMeldungTakt(jetzt + AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(1, gesendet.size());
        assertEquals("0.1.38.1", gesendet.get(0).version);
        dienst(basis, dd).updateMeldungTakt(jetzt + 3 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("je Version einmal, auch nach Neustart", 1, gesendet.size());
    }

    @Test
    public void echteUmgebungSendetTelegramUndPushOhneDoppel() throws Exception {
        String src = lies("app/src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java");
        String fn = src.substring(src.indexOf("public void meldeUpdate(UpdateMeldung.Meldung m) {"));
        fn = fn.substring(0, fn.indexOf("\n        }\n"));
        assertTrue(fn.contains("\"UPDATE\""));
        assertTrue(fn.contains("\"system.updates\""));
        String sink = lies("app/src/main/java/at/greenchili/surfmate/notifications/sinks/TelegramSink.java");
        assertTrue(sink.contains("\"system.updates\".equals(event.category)"));
        String bot = lies("app/src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertFalse("alte Sofort-Meldung ohne Selbstprüfung", bot.contains("tr(\"update.installed\",\n                                mdEscape(postUpdateVersion))"));
        assertFalse(bot.contains("msg = tr(\"update.installed_tunnel_online\""));
        JSONObject kat = new JSONObject(lies("app/src/main/assets/notifications-categories.json"));
        boolean da = false;
        for (int i = 0; i < kat.getJSONArray("categories").length(); i++) {
            da |= "system.updates".equals(kat.getJSONArray("categories").getJSONObject(i).getString("id"));
        }
        assertTrue("Push-Kategorie", da);
    }

    @Test
    public void textInFuenfSprachenOhneFachwort() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json"));
            for (String art : new String[] {UpdateMeldung.PANOPTES_INSTALLIERT, UpdateMeldung.LAUNCHER_VERFUEGBAR,
                    UpdateMeldung.LAUNCHER_INSTALLIERT}) {
                String tg = d.getJSONObject("telegram").getJSONObject("update")
                        .getString(UpdateMeldung.telegramSchluessel(art).substring("update.".length()));
                String push = d.getJSONObject("notifications")
                        .getString(UpdateMeldung.pushSchluessel(art).substring("notifications.".length()));
                for (String t : new String[] {tg, push}) {
                    assertTrue(l + ": " + t, t.contains("{0}"));
                    String k = t.toLowerCase();
                    for (String fach : new String[] {"daemon", "watchdog", "skript", "script", "adb", "apk", "versioncode"}) {
                        assertFalse(l + " " + fach + ": " + t, k.contains(fach));
                    }
                }
            }
            assertTrue(d.getJSONObject("notifications").getJSONObject("registry").has("system_updates"));
            JSONObject w = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"));
            assertTrue(w.getJSONObject("notif").getJSONObject("cat").has("system_updates"));
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
