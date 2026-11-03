package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.37 Stufe 4: Der Updater kennt ein zweites Paket, die Launcher-APK. version.json trägt sie optional unter
 * {@code launcher}; installiert wird nur auf Tipp, nicht während der Fahrt, ohne WLAN nur mit Einwilligung, nur nach
 * sha256 und mit derselben Signatur wie Panoptes. Panoptes setzt den Launcher nie als Standard-Startbildschirm.
 */
public class LauncherUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String SHA0 = AppUpdateTest.SHA0;
    static final String SHA1 = "1111111111111111111111111111111111111111111111111111111111111111";
    static final String LATEST = AppUpdateTest.LATEST;

    static String vjMitLauncher(String launcherJson) {
        String v = AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", SHA0);
        return v.substring(0, v.length() - 1) + ",\"launcher\":" + launcherJson + "}";
    }

    static String launcher(int vc, String sha) {
        return "{\"versionCode\":" + vc + ",\"versionName\":\"0.1.8\",\"apk\":\"panoptes-launcher-0.1.8-abc.apk\","
                + "\"sha256\":\"" + sha + "\"}";
    }

    // ------------------------------------------------------------- version.json

    @Test
    public void versionJsonMitLauncher() throws Exception {
        Freigabe f = Freigabe.lies(vjMitLauncher(launcher(3, SHA1)));
        assertNotNull(f.launcher);
        assertEquals(3, f.launcher.versionCode);
        assertEquals("panoptes-launcher-0.1.8-abc.apk", f.launcher.apk);
        assertNull("Adresse nie aus version.json", f.launcher.apkUrl());
        // Rundreise über den gespeicherten Stand
        Freigabe g = Freigabe.lies(f.alsJson().toString());
        assertEquals(SHA1, g.launcher.sha256);
        // Adresse setzt der Dienst; null = im Release nicht erreichbar → Launcher fällt weg
        assertEquals("u", f.mitLauncherUrl("u").launcher.apkUrl());
        assertNull(f.mitLauncherUrl(null).launcher);
    }

    @Test
    public void fehlerhafterLauncherStoertPanoptesNicht() throws Exception {
        String[] boese = {
                launcher(0, SHA1), launcher(3, "xyz"),
                "{\"versionCode\":3,\"versionName\":\"0.1\",\"apk\":\"../x.apk\",\"sha256\":\"" + SHA1 + "\"}",
                "{\"versionCode\":3,\"versionName\":\"beta\",\"apk\":\"l.apk\",\"sha256\":\"" + SHA1 + "\"}",
                // nie dieselbe Datei wie Panoptes
                "{\"versionCode\":3,\"versionName\":\"0.1.8\",\"apk\":\"panoptes-0.1.8-abc.apk\",\"sha256\":\"" + SHA1 + "\"}",
                "\"text\""};
        for (String b : boese) {
            Freigabe f = Freigabe.lies(vjMitLauncher(b));
            assertEquals(b, 9, f.versionCode);
            assertNull(b, f.launcher);
        }
        assertNull(Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", SHA0)).launcher);
    }

    // ------------------------------------------------------------- Regeln

    @Test
    public void regelnNichtWaehrendDerFahrtUndOhneWlanNurMitEinwilligung() throws Exception {
        LauncherRegel.Lage l = new LauncherRegel.Lage();
        assertEquals(LauncherRegel.KEIN_LAUNCHER, LauncherRegel.nichtWeil(l, false));
        l.verfuegbar = Freigabe.lies(vjMitLauncher(launcher(3, SHA1))).mitLauncherUrl("https://github.com/x/y/z.apk").launcher;
        l.netz = UpdateRegel.NETZ_WLAN;
        assertNull(LauncherRegel.nichtWeil(l, false));
        l.kmh = 12;
        assertEquals(LauncherRegel.FAEHRT, LauncherRegel.nichtWeil(l, true));
        l.kmh = Double.NaN;
        l.gangP = Boolean.FALSE;
        assertEquals(LauncherRegel.NICHT_IN_P, LauncherRegel.nichtWeil(l, true));
        l.gangP = null;
        l.fahrsperre = "moving";
        assertEquals(LauncherRegel.FAEHRT, LauncherRegel.nichtWeil(l, true));
        l.fahrsperre = "state_unknown";   // ohne Bewegungshinweis: ACC nicht lesbar sperrt nicht
        assertNull(LauncherRegel.nichtWeil(l, false));
        l.netz = UpdateRegel.NETZ_MOBIL;
        assertEquals(LauncherRegel.WARTET_WLAN, LauncherRegel.nichtWeil(l, false));
        assertNull("Einwilligung für genau diesen Download", LauncherRegel.nichtWeil(l, true));
        l.netz = UpdateRegel.NETZ_UNBEKANNT;
        assertEquals(LauncherRegel.WARTET_WLAN, LauncherRegel.nichtWeil(l, false));
        l.laeuft = true;
        assertEquals(LauncherRegel.LAEUFT_SCHON, LauncherRegel.nichtWeil(l, true));
        assertTrue(LauncherRegel.neuer(l.verfuegbar, -1));
        assertTrue(LauncherRegel.neuer(l.verfuegbar, 2));
        assertFalse(LauncherRegel.neuer(l.verfuegbar, 3));
    }

    // ------------------------------------------------------------- Gerät

    @Test
    public void paketStandNurGenauDiesesPaket() {
        LauncherPaket.Stand s = LauncherPaket.ausListe(
                "package:at.greenchili.panoptes.launcher.fremd versionCode:9 uid:10200\n"
                        + "package:at.greenchili.panoptes.launcher versionCode:3 uid:10123\n");
        assertTrue(s.installiert());
        assertEquals(3, s.versionCode);
        assertEquals(10123, s.uid);
        assertFalse(LauncherPaket.ausListe("package:at.greenchili.panoptes.launcher.fremd uid:10200").installiert());
        assertFalse(LauncherPaket.ausListe("").installiert());
        assertFalse(LauncherPaket.ausListe(null).installiert());
        assertEquals("/data/app/x/base.apk", LauncherPaket.ausPfad("package:/data/app/x/base.apk\n"));
        assertNull(LauncherPaket.ausPfad("package:/data/app/../x/base.apk"));
        assertNull(LauncherPaket.ausPfad("Error"));
    }

    @Test
    public void geraeteBefehleNieStandardStartbildschirm() throws Exception {
        List<String> befehle = new ArrayList<>();
        LauncherPaket.Geraet g = (b, s) -> { befehle.add(b); return "Success\n"; };
        File apk = new File("/data/local/tmp/surfmate-deploy/neu-launcher-3.apk");
        assertNull(LauncherPaket.installieren(g, apk));
        assertNull(LauncherPaket.entfernen(g));
        assertNull(LauncherPaket.oeffnen(g));
        assertEquals("pm install -r '" + apk.getAbsolutePath() + "'", befehle.get(0));
        assertEquals("pm uninstall at.greenchili.panoptes.launcher", befehle.get(1));
        assertEquals("am start -n at.greenchili.panoptes.launcher/.MainActivity", befehle.get(2));
        assertEquals("pfad", LauncherPaket.installieren(g, new File("/tmp/a'b.apk")));
        assertTrue(LauncherPaket.installieren((b, s) -> "Failure [INSTALL_FAILED]", new File("/x.apk")).startsWith("install:"));
        // Quelltext: kein Weg zum Standard-Startbildschirm, kein Abschalten des BYD-Launchers
        String src = lies("app/src/main/java/at/greenchili/surfmate/appupdate/LauncherPaket.java")
                .replaceAll("(?s)/\\*.*?\\*/", "");
        for (String verboten : new String[]{"set-home-activity", "pm disable", "clear-preferred", "HOME"}) {
            assertFalse(verboten, src.contains(verboten));
        }
    }

    // ------------------------------------------------------------- Dienst

    final Map<String, String> texte = new HashMap<>();
    final Map<String, String> cfg = new HashMap<>();
    final List<File> installiert = new ArrayList<>();
    String netzArt = UpdateRegel.NETZ_WLAN;
    double kmh = 0;
    String fahrsperre;
    int launcherVc = -1;
    long jetzt = 100_000_000L;

    AppUpdateDienst dienst(AppUpdateDienst.Netz netz) throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        AppUpdateDienst.Umgebung env = new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return kmh; }
            @Override public Boolean accAus() { return null; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return Freigabe.ZERTIFIKAT; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { }
            @Override public long jetzt() { return jetzt; }
            @Override public String netz() { return netzArt; }
            @Override public String fahrsperre() { return fahrsperre; }
            @Override public Boolean gangP() { return null; }
            @Override public LauncherPaket.Stand launcherStand() {
                return LauncherPaket.ausListe(launcherVc > 0
                        ? "package:" + LauncherPaket.PAKET + " versionCode:" + launcherVc + " uid:10123" : "");
            }
            @Override public String launcherInstallieren(File apk) { installiert.add(apk); return null; }
        };
        return new AppUpdateDienst(netz, env);
    }

    @Test
    public void statusUndAdresseAusDemSelbenRelease() throws Exception {
        byte[] daten = "keine echte apk".getBytes(StandardCharsets.UTF_8);
        File q = tmp.newFile("l.apk");
        Files.write(q.toPath(), daten);
        texte.put(LATEST, vjMitLauncher(launcher(3, AppUpdateDienst.sha256(q))));
        AppUpdateDienst d = dienst(new AppUpdateTest.RangeNetz(daten, texte));
        assertTrue(d.launcherStatus().isNull("verfuegbar"));
        d.pruefen();
        Freigabe.Launcher f = d.launcherFreigabe(false);
        assertEquals("https://github.com/" + UpdateQuelle.STANDARD_REPO
                + "/releases/download/v0.1.8/panoptes-launcher-0.1.8-abc.apk", f.apkUrl());
        JSONObject s = d.launcherStatus();
        assertTrue(s.isNull("installiert"));
        assertEquals(3, s.getJSONObject("verfuegbar").getInt("versionCode"));
        assertEquals(daten.length, s.getJSONObject("verfuegbar").getLong("groesseBytes"));
        assertTrue(s.getBoolean("neuer"));
        assertTrue(s.getBoolean("installierbar"));
        launcherVc = 3;
        s = d.launcherStatus();
        assertEquals(3, s.getInt("installiert"));
        assertFalse(s.getBoolean("neuer"));
        assertFalse(s.getBoolean("installierbar"));
        // Neustart des Daemons: Stand samt Launcher-Adresse bleibt
        AppUpdateDienst d2 = new AppUpdateDienst(d.netz, d.env);
        assertEquals(f.apkUrl(), d2.launcherFreigabe(false).apkUrl());
    }

    @Test
    public void ohneWlanErstFragenWaehrendDerFahrtNie() throws Exception {
        texte.put(LATEST, vjMitLauncher(launcher(3, SHA1)));
        AppUpdateDienst d = dienst(new AppUpdateTest.RangeNetz(new byte[]{1}, texte));
        d.pruefen();
        netzArt = UpdateRegel.NETZ_MOBIL;
        JSONObject s = d.launcherStatus();
        assertTrue(s.getBoolean("mobilFrage"));
        assertEquals("wartet_wlan", d.launcherInstallieren(3, false));
        kmh = 30;
        assertEquals("faehrt", d.launcherInstallieren(3, true));
        assertFalse(d.launcherStatus().getBoolean("mobilFrage"));
        kmh = 0;
        fahrsperre = "gear_not_park";
        assertEquals("faehrt", d.launcherInstallieren(3, true));
        fahrsperre = null;
        assertEquals("anderer_stand", d.launcherInstallieren(2, true));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void falschePruefsummeUndFremdeSignaturWerdenNichtInstalliert() throws Exception {
        byte[] daten = "keine echte apk".getBytes(StandardCharsets.UTF_8);
        File q = tmp.newFile("l.apk");
        Files.write(q.toPath(), daten);
        // erst falsche Prüfsumme
        texte.put(LATEST, vjMitLauncher(launcher(3, SHA1)));
        AppUpdateDienst d = dienst(new AppUpdateTest.RangeNetz(daten, texte));
        d.pruefen();
        d.launcherAusfuehren(d.launcherFreigabe(false), false);
        assertEquals("pruefsumme", d.launcherStatus().getString("fehler"));
        // dann richtige Prüfsumme, aber ohne Panoptes-Signatur
        texte.put(LATEST, vjMitLauncher(launcher(4, AppUpdateDienst.sha256(q))));
        jetzt += 60 * 60_000L;
        d.pruefen();
        Freigabe.Launcher f = d.launcherFreigabe(false);
        assertEquals(4, f.versionCode);
        d.launcherAusfuehren(f, false);
        assertEquals("signatur", d.launcherStatus().getString("fehler"));
        assertFalse("geprüfte Datei weg", d.launcherTeil(f).exists());
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void releaseWegSigniertUndBeschreibtLauncher() throws Exception {
        String yml = lies(".github/workflows/release.yml");
        assertTrue(yml.contains("apk-signieren.sh \"$LAPK\""));
        assertTrue(yml.contains("for A in \"$APK\" \"$LAPK\"; do"));
        assertTrue(yml.contains("\"launcher\": {"));
        assertTrue(yml.contains("\"sha256\": \"$LSHA\""));
        String pub = lies("deploy/release-veroeffentlichen.sh");
        assertTrue(pub.contains("Launcher-Zertifikat"));
        assertTrue(pub.contains("Launcher-APK sha256"));
    }

    private static String lies(String rel) throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (Path p : new Path[]{cur.resolve(rel), cur.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = cur.getParent();
        }
        throw new AssertionError("Nicht gefunden: " + rel);
    }
}
