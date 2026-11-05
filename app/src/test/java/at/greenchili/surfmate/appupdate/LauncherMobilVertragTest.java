package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.38.10 Vertrag (Befund Deploy 3, 04.11. 23:41: App über LTE installiert, Launcher blieb auf {@code wartet_wlan}):
 * Der Launcher folgt derselben Mobilfunk-Regel wie Panoptes, eine Quelle ({@code update.mobilfunk},
 * {@link UpdateRegel#wartetWlan}). Mobil erlaubt = beide auch über Mobilnetz, im selben Lauf; Mobil verboten = beide
 * erst im WLAN (gefragt wird nur bei neuer Einrichtung, für beide gleich); WLAN = beide sofort.
 */
public class LauncherMobilVertragTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final Map<String, String> texte = new HashMap<>();
    final Map<String, String> cfg = new HashMap<>();
    final List<File> installiert = new ArrayList<>();
    String netzArt = UpdateRegel.NETZ_WLAN;

    AppUpdateDienst dienst() throws Exception {
        File basis = tmp.newFolder();
        File dd = tmp.newFolder();
        byte[] daten = "keine echte apk".getBytes(StandardCharsets.UTF_8);
        AppUpdateDienst d = new AppUpdateDienst(new AppUpdateTest.RangeNetz(daten, texte), new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return true; }
            @Override public String config(String key) { return cfg.get(key); }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return Freigabe.ZERTIFIKAT; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { }
            @Override public long jetzt() { return 100_000_000L; }
            @Override public String netz() { return netzArt; }
            @Override public String fahrsperre() { return null; }
            @Override public Boolean gangP() { return true; }
            @Override public LauncherPaket.Stand launcherStand() {
                return LauncherPaket.ausListe("package:" + LauncherPaket.PAKET + " versionCode:2 uid:10123");
            }
            @Override public String launcherInstallieren(File apk) { installiert.add(apk); return null; }
        });
        texte.put(AppUpdateTest.LATEST, LauncherUpdateTest.vjMitLauncher(LauncherUpdateTest.launcher(3,
                LauncherUpdateTest.SHA1)));
        d.pruefen();
        return d;
    }

    /** Fälle: Netz × {@code update.mobilfunk} (null = Bestand = an). */
    static final String[][] FAELLE = {
            {UpdateRegel.NETZ_WLAN, null}, {UpdateRegel.NETZ_WLAN, "false"},
            {UpdateRegel.NETZ_MOBIL, null}, {UpdateRegel.NETZ_MOBIL, "true"}, {UpdateRegel.NETZ_MOBIL, "false"},
            {UpdateRegel.NETZ_UNBEKANNT, null}, {UpdateRegel.NETZ_UNBEKANNT, "false"},
    };

    static boolean wartetErwartet(String netz, String mobilfunk) {
        return "false".equals(mobilfunk) && !UpdateRegel.NETZ_WLAN.equals(netz);
    }

    @Test
    public void appUndLauncherEntscheidenGleich() throws Exception {
        for (String merker : new String[]{null, "true"}) {
            for (String[] f : FAELLE) {
                cfg.clear();
                if (f[1] != null) cfg.put("mobilfunk", f[1]);
                if (merker != null) cfg.put("mobilfunkEinmal", merker);
                netzArt = f[0];
                AppUpdateDienst d = dienst();
                String fall = f[0] + "/" + f[1] + "/merker=" + merker;
                JSONObject app = d.status("de");
                JSONObject lnc = d.launcherStatus();
                boolean wartet = wartetErwartet(f[0], f[1]);
                assertEquals(fall, wartet ? UpdateRegel.WARTET_WLAN : null, app.isNull("nichtWeil") ? null
                        : app.getString("nichtWeil"));
                assertEquals(fall, wartet ? LauncherRegel.WARTET_WLAN : null, lnc.isNull("nichtWeil") ? null
                        : lnc.getString("nichtWeil"));
                assertEquals(fall, app.getBoolean("installierbar"), lnc.getBoolean("installierbar"));
                assertEquals(fall + " gleiche Frage", app.getBoolean("mobilFrage"), lnc.getBoolean("mobilFrage"));
                assertEquals(fall, wartet && merker != null, lnc.getBoolean("mobilFrage"));
                assertEquals(fall + " kommt mit", true, d.status("de").getJSONObject("launcher").getBoolean("mitPanoptes"));
            }
        }
    }

    /** Die reine Regel: Launcher = Panoptes für jede Kombination aus nurWlan × Einwilligung × Netz. */
    @Test
    public void regelEineQuelle() throws Exception {
        for (boolean nurWlan : new boolean[]{false, true}) {
            for (boolean einwilligung : new boolean[]{false, true}) {
                for (String netz : new String[]{UpdateRegel.NETZ_WLAN, UpdateRegel.NETZ_MOBIL, UpdateRegel.NETZ_UNBEKANNT}) {
                    UpdateRegel.Lage a = new UpdateRegel.Lage();
                    a.installiert = 8;
                    a.verfuegbar = Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateTest.SHA0));
                    a.autoAus = true;
                    a.nurWlan = nurWlan;
                    a.mobilEinmal = einwilligung;
                    a.netz = netz;
                    LauncherRegel.Lage l = new LauncherRegel.Lage();
                    l.verfuegbar = Freigabe.lies(LauncherUpdateTest.vjMitLauncher(LauncherUpdateTest.launcher(3,
                            LauncherUpdateTest.SHA1))).mitLauncherUrl("u").launcher;
                    l.nurWlan = nurWlan;
                    l.netz = netz;
                    String fall = nurWlan + "/" + einwilligung + "/" + netz;
                    assertEquals(fall, UpdateRegel.nichtWeil(a), LauncherRegel.nichtWeil(l, einwilligung));
                }
            }
        }
    }

    /**
     * Der Lauf: der Launcher-Download im Panoptes-Update bekommt dieselbe Einwilligung wie Panoptes
     * ({@code mobilEinmal}); bei erlaubtem Mobilfunk lädt er über Mobilnetz (scheitert hier erst an der Prüfsumme),
     * bei verbotenem bleibt er auf {@code wartet_wlan} stehen.
     */
    @Test
    public void launcherLaedtImLaufUeberMobilnetzWennErlaubt() throws Exception {
        netzArt = UpdateRegel.NETZ_MOBIL;
        AppUpdateDienst d = dienst();
        d.launcherAusfuehren(d.launcherFreigabe(false), false);
        assertEquals("geladen, nicht gewartet", "pruefsumme", d.launcherStatus().getString("fehler"));

        cfg.put("mobilfunk", "false");
        d = dienst();
        d.launcherAusfuehren(d.launcherFreigabe(false), false);
        assertEquals(LauncherRegel.WARTET_WLAN, d.launcherStatus().getString("fehler"));
        assertTrue(installiert.isEmpty());
    }

    /** Ein Tipp mit „mobil“ ohne vorherige Frage (Bestand ohne Merker) lädt bei „nur WLAN“ nicht, wie bei Panoptes. */
    @Test
    public void einwilligungNurNachDerFrage() throws Exception {
        netzArt = UpdateRegel.NETZ_MOBIL;
        cfg.put("mobilfunk", "false");
        AppUpdateDienst d = dienst();
        assertEquals(LauncherRegel.WARTET_WLAN, d.launcherInstallieren(3, true));
        assertEquals(UpdateRegel.WARTET_WLAN, d.installieren(9, true));
        cfg.put("mobilfunkEinmal", "true");
        assertNotEquals(LauncherRegel.WARTET_WLAN, d.launcherInstallieren(3, true));
        assertFalse(installiert.size() > 1);
    }
}
