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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.43.3 (U1, Befund Surf 13.11.: Launcher kam beim Update auf 0.1.43.1 still nicht mit, blieb auf vC 16):
 * <ul>
 *   <li>Der Stand wird direkt vor dem Panoptes-Skript frisch gelesen (ohne Zwischenspeicher); leer oder Zeit
 *       abgelaufen (Ende-Zeichen fehlt) wird genau einmal nachgelesen.</li>
 *   <li>Unbekannt zählt nie als „nicht installiert“: überspringen mit Grund {@code stand_unbekannt}
 *       (Koordination 13.11.), nie merken (kein 30-s-Zwischenspeicher).</li>
 *   <li>Sicher installiert und älter: er kommt wie bisher mit.</li>
 *   <li>Jeder Grund fürs Überspringen steht in update.log und im Status ({@code launcher.uebersprungenGrund}).</li>
 * </ul>
 */
public class LauncherMitnehmenTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String ENDE = LauncherPaket.ENDE;
    /** Antwort, bei der das Gerät eine Ausnahme wirft. */
    static final String FEHLER = "!FEHLER";
    static final String ZEILE = "package:" + LauncherPaket.PAKET + " versionCode:%d uid:10123";

    /** Antworten des Geräts auf den Lesebefehl, der Reihe nach; danach immer die letzte. */
    final Deque<String> listeAntworten = new ArrayDeque<>();
    String letzteAntwort = "";
    int listeAufrufe;
    int pfadAufrufe;
    final List<String> updateLog = new ArrayList<>();

    final LauncherPaket.Geraet geraet = (befehl, sek) -> {
        if (befehl.equals(LauncherPaket.LISTE_BEFEHL)) {
            listeAufrufe++;
            if (!listeAntworten.isEmpty()) letzteAntwort = listeAntworten.poll();
            if (FEHLER.equals(letzteAntwort)) throw new RuntimeException("Fehler beim Lesen");
            return letzteAntwort;
        }
        if (befehl.startsWith("pm path ")) {
            pfadAufrufe++;
            return "package:/data/app/~~x/" + LauncherPaket.PAKET + "-1/base.apk\n";
        }
        throw new AssertionError("unerwarteter Befehl: " + befehl);
    };

    static String installiert(int vc) {
        return String.format(ZEILE, vc) + "\n" + ENDE + "\n";
    }

    static String sicherNicht() {
        return ENDE + "\n";
    }

    /** Wie die Zeit abgelaufen ist: Ausgabe abgebrochen, ohne Ende-Zeichen (auch wenn die Paketzeile schon da war). */
    static String abgebrochen(int vc) {
        return String.format(ZEILE, vc) + "\n";
    }

    AppUpdateDienst dienst() throws Exception {
        File basis = tmp.newFolder();
        File dd = tmp.newFolder();
        Map<String, String> texte = new HashMap<>();
        byte[] daten = "keine echte apk".getBytes(StandardCharsets.UTF_8);
        AppUpdateDienst d = new AppUpdateDienst(new AppUpdateTest.RangeNetz(daten, texte), new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return 8; }
            @Override public String installierteVersion() { return "0.1.7"; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return 0; }
            @Override public Boolean accAus() { return true; }
            @Override public String config(String key) { return null; }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return Freigabe.ZERTIFIKAT; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int vc, String tag) { }
            @Override public long jetzt() { return 100_000_000L; }
            @Override public String netz() { return UpdateRegel.NETZ_WLAN; }
            @Override public String fahrsperre() { return null; }
            @Override public Boolean gangP() { return true; }
            @Override public LauncherPaket.Stand launcherStand() { return LauncherPaket.stand(geraet); }
            @Override public LauncherPaket.Stand launcherStandFrisch() { return LauncherPaket.standFrisch(geraet); }
            @Override public String launcherInstallieren(File apk) { return null; }
            @Override public void updateLog(String zeile) { updateLog.add(zeile); }
        });
        // Panoptes vC 9 (installiert 8) mit Launcher vC 17 im selben Release
        texte.put(AppUpdateTest.LATEST, LauncherUpdateTest.vjMitLauncher(LauncherUpdateTest.launcher(17,
                LauncherUpdateTest.SHA1)));
        d.pruefen();
        listeAufrufe = 0;
        pfadAufrufe = 0;
        return d;
    }

    /** Ein Lauf aus einem Tipp: launcherMitnehmen direkt vor dem Skript. */
    static void laufGetippt(AppUpdateDienst d) {
        d.laufDarfLauncher = true;
        try {
            d.launcherMitnehmen(d.verfuegbarJetzt());
        } finally {
            d.laufDarfLauncher = false;
        }
    }

    static String grund(AppUpdateDienst d) throws Exception {
        JSONObject l = d.status("de").getJSONObject("launcher");
        return l.isNull("uebersprungenGrund") ? null : l.getString("uebersprungenGrund");
    }

    /** Der Launcher-Download lief an (scheitert im Test erst an der Prüfsumme der Attrappe). */
    static boolean downloadLief(AppUpdateDienst d) throws Exception {
        JSONObject s = d.launcherStatus();
        return !s.isNull("fehler") && "pruefsumme".equals(s.getString("fehler"));
    }

    // ------------------------------------------------------------- leerer Stand

    @Test
    public void leerZweimal_unbekannt_ueberspringenMitGrund() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add("");
        listeAntworten.add("");
        laufGetippt(d);
        assertEquals("genau einmal nachgelesen", 2, listeAufrufe);
        assertFalse("kein Download bei unbekanntem Stand", downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, grund(d));
        assertEquals(1, updateLog.size());
        assertTrue(updateLog.get(0), updateLog.get(0).contains("stand_unbekannt"));
        assertTrue(updateLog.get(0), updateLog.get(0).contains("unbekannt"));
        assertFalse("unbekannt ist nicht „nicht installiert“", updateLog.get(0).contains("nicht installiert"));
    }

    @Test
    public void leerDannInstalliertAelter_kommtMit() throws Exception {
        // der Surf-Fall: beim Tipp (oder im Zwischenspeicher) leer, frisch vor dem Skript sicher vC 16
        AppUpdateDienst d = dienst();
        listeAntworten.add("");
        listeAntworten.add(installiert(16));
        laufGetippt(d);
        assertEquals(2, listeAufrufe);
        assertTrue("Download angelaufen", downloadLief(d));
        assertNull(grund(d));
        assertEquals(1, updateLog.size());
        assertTrue(updateLog.get(0), updateLog.get(0).contains("kommt mit dem Panoptes-Update mit"));
        assertTrue(updateLog.get(0), updateLog.get(0).contains("vC 16"));
    }

    @Test
    public void lesefehlerZweimal_unbekannt() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(FEHLER);   // Ausnahme beim Lesen
        listeAntworten.add(FEHLER);
        laufGetippt(d);
        assertEquals(2, listeAufrufe);
        assertFalse(downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, grund(d));
    }

    // ------------------------------------------------------------- Zeit abgelaufen

    @Test
    public void zeitAbgelaufenZweimal_unbekannt_auchMitPaketzeile() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(abgebrochen(16));
        listeAntworten.add(abgebrochen(16));
        laufGetippt(d);
        assertEquals("genau einmal nachgelesen", 2, listeAufrufe);
        assertEquals("ohne Ende-Zeichen kein pm path", 0, pfadAufrufe);
        assertFalse(downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, grund(d));
        assertEquals(1, updateLog.size());
    }

    @Test
    public void zeitAbgelaufenDannOk_kommtMit() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(abgebrochen(16));
        listeAntworten.add(installiert(16));
        laufGetippt(d);
        assertEquals(2, listeAufrufe);
        assertTrue(downloadLief(d));
        assertNull(grund(d));
    }

    // ------------------------------------------------------------- Stand gleich / älter / neuer / nicht installiert

    @Test
    public void standGleich_schonAktuell_keinDownload() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(installiert(17));
        laufGetippt(d);
        assertEquals("sicher installiert: kein Nachlesen", 1, listeAufrufe);
        assertFalse(downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_SCHON_AKTUELL, grund(d));
        assertEquals(1, updateLog.size());
        assertTrue(updateLog.get(0), updateLog.get(0).contains("schon_aktuell"));
    }

    @Test
    public void standAelter_sicherInstalliert_kommtWieBisherMit() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(installiert(16));
        laufGetippt(d);
        assertEquals(1, listeAufrufe);
        assertEquals(1, pfadAufrufe);
        assertTrue(downloadLief(d));
        assertNull(grund(d));
        assertTrue(updateLog.get(0), updateLog.get(0).contains("kommt mit"));
    }

    @Test
    public void standNeuer_installiertNeuer_keinDownload() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(installiert(18));
        laufGetippt(d);
        assertFalse(downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_INSTALLIERT_NEUER, grund(d));
    }

    @Test
    public void sicherNichtInstalliert_einmalNachgelesen_nieNeuInstalliert() throws Exception {
        AppUpdateDienst d = dienst();
        listeAntworten.add(sicherNicht());
        listeAntworten.add(sicherNicht());
        laufGetippt(d);
        assertEquals(2, listeAufrufe);
        assertFalse("ein nicht installierter Launcher kommt nie neu aufs Gerät", downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_NICHT_INSTALLIERT, grund(d));
    }

    // ------------------------------------------------------------- von selbst / Status / Zwischenspeicher

    @Test
    public void vonSelbst_liestNicht_keinUpdateLog() throws Exception {
        AppUpdateDienst d = dienst();
        d.laufDarfLauncher = false;
        d.launcherMitnehmen(d.verfuegbarJetzt());
        assertEquals(0, listeAufrufe);
        assertFalse(downloadLief(d));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_NICHT_GETIPPT, grund(d));
        assertTrue("Normalfall gehört nicht ins update.log", updateLog.isEmpty());
    }

    @Test
    public void statusZeigtUnbekanntenStand_karteBehaeltAktualisieren() throws Exception {
        AppUpdateDienst d = dienst();
        letzteAntwort = "";
        JSONObject s = d.status("de").getJSONObject("launcher");
        assertFalse(s.getBoolean("standBekannt"));
        assertTrue(s.isNull("uebersprungenGrund"));
        assertTrue(s.isNull("uebersprungenUm"));
        JSONObject k = d.launcherStatus();
        assertFalse(k.getBoolean("standBekannt"));
        assertTrue("Karte bietet weiter an", k.getBoolean("installierbar"));
        // nach einem Lauf mit unbekanntem Stand: Grund und Zeit im Status
        laufGetippt(d);
        s = d.status("de").getJSONObject("launcher");
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, s.getString("uebersprungenGrund"));
        assertEquals(100_000_000L, s.getLong("uebersprungenUm"));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, d.launcherStatus().getString("uebersprungenGrund"));
    }

    @Test
    public void unbekanntWirdNieGemerkt() throws Exception {
        AppUpdateDienst d = dienst();
        letzteAntwort = "";
        assertFalse(d.launcherStandGemerkt().bekannt);
        assertFalse(d.launcherStandGemerkt().bekannt);
        assertEquals("jedes Mal neu gelesen", 2, listeAufrufe);
        listeAntworten.add(installiert(16));
        LauncherPaket.Stand st = d.launcherStandGemerkt();
        assertTrue(st.bekannt);
        assertEquals(16, st.versionCode);
        int vorher = listeAufrufe;
        assertEquals(16, d.launcherStandGemerkt().versionCode);
        assertEquals("sicherer Stand wird gemerkt", vorher, listeAufrufe);
    }

    // ------------------------------------------------------------- Regel rein

    @Test
    public void regel_unbekanntIstNieNichtInstalliert() throws Exception {
        AppUpdateDienst d = dienst();
        Freigabe f = d.verfuegbarJetzt();
        assertNotNull(f.launcher);
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT,
                AppUpdateDienst.launcherNichtMitWeil(true, f, 8, LauncherPaket.unbekannt()));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_UNBEKANNT, AppUpdateDienst.launcherNichtMitWeil(true, f, 8, null));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_NICHT_INSTALLIERT,
                AppUpdateDienst.launcherNichtMitWeil(true, f, 8, LauncherPaket.ausAusgabe(sicherNicht())));
        assertNull(AppUpdateDienst.launcherNichtMitWeil(true, f, 8, LauncherPaket.ausAusgabe(installiert(16))));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_PANOPTES_NICHT_NEUER,
                AppUpdateDienst.launcherNichtMitWeil(true, f, 9, LauncherPaket.ausAusgabe(installiert(16))));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_NICHT_GETIPPT,
                AppUpdateDienst.launcherNichtMitWeil(false, f, 8, LauncherPaket.ausAusgabe(installiert(16))));
        assertEquals(AppUpdateDienst.UEBERSPRUNGEN_KEIN_LAUNCHER, AppUpdateDienst.launcherNichtMitWeil(true, null, 8, null));
    }

    // ------------------------------------------------------------- LauncherPaket: Lesen mit Ende-Zeichen

    @Test
    public void paket_ausAusgabe_dreiZustaende() {
        assertFalse(LauncherPaket.ausAusgabe(null).bekannt);
        assertFalse(LauncherPaket.ausAusgabe("").bekannt);
        assertFalse(LauncherPaket.ausAusgabe(abgebrochen(16)).bekannt);
        assertFalse(LauncherPaket.ausAusgabe(abgebrochen(16)).installiert());
        LauncherPaket.Stand nicht = LauncherPaket.ausAusgabe(sicherNicht());
        assertTrue(nicht.bekannt);
        assertTrue(nicht.sicherNichtInstalliert());
        LauncherPaket.Stand ja = LauncherPaket.ausAusgabe(installiert(16));
        assertTrue(ja.bekannt);
        assertTrue(ja.installiert());
        assertEquals(16, ja.versionCode);
        // Teilname (Filter von list packages trifft auch andere Pakete) mit Ende-Zeichen: sicher nicht installiert
        LauncherPaket.Stand fremd = LauncherPaket.ausAusgabe("package:" + LauncherPaket.PAKET + ".fremd uid:10200\n" + ENDE);
        assertTrue(fremd.bekannt);
        assertFalse(fremd.installiert());
        assertFalse(LauncherPaket.unbekannt().sicherNichtInstalliert());
    }

    @Test
    public void paket_befehlTraegtEndeZeichen() {
        assertTrue(LauncherPaket.LISTE_BEFEHL.startsWith("cmd package list packages -U --show-versioncode "
                + LauncherPaket.PAKET + ";"));
        assertTrue(LauncherPaket.LISTE_BEFEHL.endsWith("echo " + ENDE));
    }

    @Test
    public void paket_standFrisch_nachlesenRegeln() {
        // installiert beim ersten Mal: ein Lesen, dann pm path
        listeAntworten.add(installiert(16));
        LauncherPaket.Stand s = LauncherPaket.standFrisch(geraet);
        assertEquals(16, s.versionCode);
        assertEquals(1, listeAufrufe);
        assertEquals(1, pfadAufrufe);
        assertNotNull(s.apkPfad);

        // sicher nicht, dann unbekannt: bleibt sicher nicht installiert
        listeAufrufe = 0;
        listeAntworten.add(sicherNicht());
        listeAntworten.add("");
        s = LauncherPaket.standFrisch(geraet);
        assertEquals(2, listeAufrufe);
        assertTrue(s.sicherNichtInstalliert());

        // unbekannt, dann sicher nicht: sicher nicht installiert
        listeAufrufe = 0;
        listeAntworten.add("");
        listeAntworten.add(sicherNicht());
        s = LauncherPaket.standFrisch(geraet);
        assertEquals(2, listeAufrufe);
        assertTrue(s.sicherNichtInstalliert());

        // dreimal leer: nur zweimal gelesen, unbekannt
        listeAufrufe = 0;
        listeAntworten.add("");
        listeAntworten.add("");
        listeAntworten.add("");
        s = LauncherPaket.standFrisch(geraet);
        assertEquals("genau einmal nachgelesen", 2, listeAufrufe);
        assertFalse(s.bekannt);
        assertFalse(s.installiert());
    }
}
