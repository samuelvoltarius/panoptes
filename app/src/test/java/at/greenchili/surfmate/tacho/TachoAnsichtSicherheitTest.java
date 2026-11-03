package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.monitor.GearMonitor;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 0.1.37: Panoptes-Tacho-Ansicht, einmaliger Hinweis und Tacho-Rueckmeldung.
 * Prueft: kein eigener Startweg, Video nur in P, Rueckmeldung nur mit Einwilligung und ohne
 * Kennung, keine Opcodes/Blinker/Licht/Hupe in den neuen Teilen.
 */
public class TachoAnsichtSicherheitTest {

    private static final String AC = "1\tAutoContainer: [android.os.IAutoContainer]\n";
    private static final String P = TachoAnsicht.PAKET;
    private static final String JS = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String HTML = "app/src/main/assets/web/local/tacho-ansicht.html";

    private static JSONObject urteil(String status, String code) throws Exception {
        return new JSONObject().put("status", status).put("code", code);
    }

    private static JSONObject andereApps(TachoFaehigkeit.Eingaben e) throws Exception {
        return TachoFaehigkeit.bewerte(e).getJSONObject("andereApps");
    }

    private static TachoFaehigkeit.Eingaben dualOs() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "0"; e.sdk = 29; e.dienste = AC; e.uid = "2000"; e.modell = "BYD Seal";
        return e;
    }

    private static TachoFaehigkeit.Eingaben surf() {
        TachoFaehigkeit.Eingaben e = dualOs();
        e.singleOs = "1"; e.modell = "BYD Dolphin Surf";
        return e;
    }

    private static TachoFaehigkeit.Eingaben dilink5() {
        TachoFaehigkeit.Eingaben e = dualOs();
        e.dilink5Gewaehlt = true; e.sdk = 31;
        return e;
    }

    // ------------------------------------------------------------ Startweg

    @Test
    public void ansichtStartetNurUeberDieVorhandeneSperre() throws Exception {
        assertTrue(TachoNaviApp.gueltigesZiel(P));
        assertFalse("Panoptes bleibt sonst kein Fremdpaket", TachoNaviApp.gueltigesFremdpaket(P));
        assertFalse(TachoNaviApp.gueltigesZiel(P + ".evil"));
        JSONObject frei = urteil("ja", "warnleuchten_frei");
        // Ohne Freigabe nie, auch nicht im Stand.
        assertEquals("nicht_freigegeben", TachoNaviApp.fahrtSperreAuswertung(P, frei, null, true, false, false));
        // Surf und DiLink 5 bleiben gesperrt, selbst mit gespeicherter Freigabe.
        TachoFaehigkeit.Eingaben s = surf(); s.warnleuchtenFrei = "ja";
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.fahrtSperreAuswertung(P, andereApps(s), null, true, true, false));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.probeSperre(P, andereApps(surf()), null, true, true));
        TachoFaehigkeit.Eingaben d5 = dilink5(); d5.warnleuchtenFrei = "ja";
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.fahrtSperreAuswertung(P, andereApps(d5), null, true, true, false));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.probeSperre(P, andereApps(dilink5()), null, true, true));
        // Probe nur in P.
        assertEquals("nur_im_stand", TachoNaviApp.probeSperre(P, andereApps(dualOs()), "not_park", true, true));
        // Passendes Auto, freigegeben: frei (auch in der Fahrt, weil Navigation).
        TachoFaehigkeit.Eingaben ok = dualOs(); ok.warnleuchtenFrei = "ja";
        assertNull(TachoNaviApp.fahrtSperreAuswertung(P, andereApps(ok), null, true, true, false));
        assertNull(TachoNaviApp.fahrtSperreAuswertung(P, andereApps(ok), "not_park", true, true, false));
    }

    @Test
    public void klusterCastSetztDieAnsichtErstNachDerFahrtSperreEin() throws IOException {
        String cast = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");
        String start = cast.substring(cast.indexOf("public static synchronized boolean start(String pkg)"));
        int sperre = start.indexOf("TachoNaviApp.fahrtSperre(");
        int ansicht = start.indexOf("TachoAnsicht.KOMPONENTE");
        assertTrue(sperre >= 0 && ansicht > sperre);
        assertTrue(ansicht < start.indexOf("acquireSustained(TOKEN)"));
    }

    @Test
    public void ansichtHatKeinenZweitenStartweg() throws IOException {
        Path wurzel = quelle();
        List<String> komponente = new ArrayList<>(), activity = new ArrayList<>();
        try (Stream<Path> s = Files.walk(wurzel)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String n = f.getFileName().toString();
                if (!(n.endsWith(".java") || n.endsWith(".kt"))) continue;
                String txt = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8);
                if (txt.contains("TachoAnsicht.KOMPONENTE")) komponente.add(n);
                if (txt.contains("TachoAnsichtActivity") && !n.startsWith("TachoAnsicht")) activity.add(n);
            }
        }
        assertEquals("nur ClusterCast startet die Ansicht", List.of("ClusterCast.java"), komponente);
        assertTrue("niemand ausser ClusterCast nennt die Activity: " + activity, activity.isEmpty());
        String manifest = TachoFaehigkeitTest.lies("app/src/main/AndroidManifest.xml");
        String eintrag = manifest.substring(manifest.indexOf("tacho.TachoAnsichtActivity"));
        eintrag = eintrag.substring(0, eintrag.indexOf("/>"));
        assertFalse("kein Launcher-Eintrag", eintrag.contains("intent-filter"));
        String act = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        assertTrue("beendet sich auf Display 0", act.contains("aufDisplayNull()) { beenden()"));
        assertTrue("beendet sich ohne aktive Projektion", act.contains("!a.optBoolean(\"aktiv\", false)) { beenden()"));
        assertTrue("beendet sich ohne Daemon", act.contains("++fehlversuche >= 3) { beenden()"));
    }

    // ------------------------------------------------------------ Video nur in P

    @Test
    public void videoIstAusserhalbVonPAus() throws Exception {
        JSONObject frei = urteil("ja", "warnleuchten_frei");
        String video = "com.google.android.youtube";
        assertNull(TachoNaviApp.fahrtSperreAuswertung(video, frei, null, true, true, false));
        assertEquals("nur_im_stand", TachoNaviApp.fahrtSperreAuswertung(video, frei, "not_park", true, true, false));
        assertEquals("nur_im_stand", TachoNaviApp.fahrtSperreAuswertung(video, frei, "state_unknown", true, true, false));
        assertTrue(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, video, true));
        assertTrue(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_R, video, true));
        assertFalse(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_P, video, true));
        // Die Ansicht ist Navigation und bleibt nach Freigabe an.
        assertFalse(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, P, true));
        assertTrue(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, P, false));
    }

    @Test
    public void ansichtEnthaeltKeinVideoUndKeineKamera() throws IOException {
        for (String f : neueDateien()) {
            String t = TachoFaehigkeitTest.lies(f);
            for (String verboten : new String[] {"VideoView", "SurfaceView", "TextureView", "MediaPlayer",
                    "ExoPlayer", "android.hardware.camera", "Camera2", "CameraManager", "getFrame"}) {
                assertFalse(f + " enthaelt " + verboten, t.contains(verboten));
            }
        }
    }

    // ------------------------------------------------------------ Hinweis

    @Test
    public void ohneVerstandenKeineProbeUndKeinStart() throws Exception {
        JSONObject apps = andereApps(dualOs());
        assertEquals("hinweis_noetig", TachoNaviApp.probeSperre(P, apps, null, true, false));
        assertNull(TachoNaviApp.probeSperre(P, apps, null, true, true));
        JSONObject frei = urteil("ja", "warnleuchten_frei");
        assertEquals("hinweis_noetig", TachoNaviApp.einschaltSperre(P, frei, null, true, true, false));
        assertNull(TachoNaviApp.einschaltSperre(P, frei, null, true, true, true));
        String policy = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(policy.contains("istStartbar(p), istFreigegeben(p), hinweisVerstanden());"));
        assertTrue(policy.replaceAll("\\s+", " ").contains(
                "probeSperre(p, andereApps(p), bewegungsGrund(), istStartbar(p), hinweisVerstanden());"));
        assertFalse("kein Beta-Schalter", policy.contains("tacho.beta"));
    }

    // ------------------------------------------------------------ Rueckmeldung

    @Test
    public void rueckmeldungOhneEinwilligungSendetNichts() throws Exception {
        AtomicInteger gesendet = new AtomicInteger();
        TachoRueckmeldung.Sender sender = text -> {
            gesendet.incrementAndGet();
            return new JSONObject().put("ok", true);
        };
        String text = TachoRueckmeldung.text(TachoFaehigkeit.bewerte(dualOs()));
        for (Object ohne : new Object[] {null, false, "true", 1, "ja"}) {
            JSONObject r = TachoRueckmeldung.senden(ohne, text, text, sender);
            assertEquals("keine_einwilligung", r.getString("grund"));
        }
        assertEquals("vorschau_alt", TachoRueckmeldung.senden(true, text + "x", text, sender).getString("grund"));
        assertEquals("vorschau_alt", TachoRueckmeldung.senden(true, "", "", sender).getString("grund"));
        assertEquals(0, gesendet.get());
        assertTrue(TachoRueckmeldung.senden(true, text, text, sender).getBoolean("ok"));
        assertEquals(1, gesendet.get());
    }

    @Test
    public void rueckmeldungEnthaeltKeineVinKeineImeiKeinenOrt() throws Exception {
        TachoFaehigkeit.Eingaben e = dualOs();
        e.modell = "BYD Seal LGXCE6CB0P0123456 356938035643809";
        e.clusterFirmware = "dilink3.0_6125f_ivi_cluster lat=48.20849 lon=16.37208";
        String text = TachoRueckmeldung.text(TachoFaehigkeit.bewerte(e));
        assertFalse(text, text.contains("LGXCE6CB0P0123456"));
        assertFalse(text, text.contains("356938035643809"));
        assertFalse(text, text.contains("="));
        assertFalse(text, text.matches("(?s).*\\d{1,3}\\.\\d{4,}.*"));
        assertTrue(text.contains("Modell: BYD Seal"));
        assertTrue(text.contains("DiLink: 3"));
        assertTrue(text.contains("Tacho-Firmware: dilink3.0_6125f_ivi_cluster"));
        assertTrue(text.contains("Andere Apps am Tacho: offen"));
        // Nur die erlaubten Zeilen.
        for (String zeile : text.split("\n")) {
            assertTrue(zeile, zeile.equals("Panoptes Tacho-Test") || zeile.startsWith("Modell: ")
                    || zeile.startsWith("DiLink: ") || zeile.startsWith("Tacho-Firmware: ")
                    || zeile.startsWith("Single-OS: ") || zeile.startsWith("Projektionsdienst: ")
                    || zeile.startsWith("Totwinkel am Tacho: ") || zeile.startsWith("Karte am Tacho: ")
                    || zeile.startsWith("Andere Apps am Tacho: ") || zeile.startsWith("Warnleuchten in der Probe frei: "));
        }
        String api = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        assertTrue(api.contains("TachoRueckmeldung.senden(in.opt(\"einwilligung\")"));
        String web = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/testfunktionen.html");
        assertTrue(web.contains("ttText = d.rueckmeldung || '';"));
        assertTrue(web.contains("einwilligung: true, text: ttText"));
    }

    @Test
    public void datenFuerDieAnsichtEnthaltenKeinePosition() throws Exception {
        JSONObject a = TachoAnsicht.antwort(true, 87.4, 64.0, 312, "sport");
        for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
            String k = it.next();
            assertTrue(k, List.of("status", "aktiv", "design", "kmh", "soc", "reichweiteKm").contains(k));
        }
        assertEquals(87, a.getInt("kmh"));
        assertEquals("klar", TachoAnsicht.antwort(false, Double.NaN, -1, 0, "unsinn").getString("design"));
        assertFalse(TachoAnsicht.antwort(false, Double.NaN, -1, 0, "x").has("soc"));

        JSONObject voll = TachoAnsicht.antwort(true, 50, 70, 300, "horizont", -12.34, 4, 14.4, 18342);
        for (java.util.Iterator<String> it = voll.keys(); it.hasNext(); ) {
            String k = it.next();
            assertTrue(k, List.of("status", "aktiv", "design", "kmh", "soc", "reichweiteKm",
                    "kw", "gang", "aussenC", "kmStand").contains(k));
        }
        assertEquals(-12.3, voll.getDouble("kw"), 1e-9);
        assertEquals("D", voll.getString("gang"));
        assertEquals("horizont", voll.getString("design"));
        JSONObject unsinn = TachoAnsicht.antwort(true, 50, 70, 300, "klar", Double.NaN, 99, 300, -1);
        assertFalse(unsinn.has("kw") || unsinn.has("gang") || unsinn.has("aussenC") || unsinn.has("kmStand"));
    }

    // ------------------------------------------------------------ Designs

    @Test
    public void fuenfDesignsUndEinRendererFuerAnsichtUndVorschau() throws IOException {
        assertEquals(List.of("klar", "sport", "nacht", "horizont", "route"), TachoAnsicht.DESIGNS);
        String js = TachoFaehigkeitTest.lies(JS);
        assertTrue(js.contains("var DESIGNS = ['klar', 'sport', 'nacht', 'horizont', 'route'];"));
        String vc = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/vehicle-control.html");
        // 0.1.37: Die Web-Auswahl ist „Was zeigt der Tacho?“ (tacho-auswahl.js), Designs kommen aus der Kern-API.
        assertTrue(vc.contains("../shared/tacho-auswahl.js") && vc.contains("id=\"tachoAuswahlKacheln\""));
        assertTrue("Vorschau nutzt denselben Renderer", vc.contains("../shared/tacho-designs.js"));
        assertTrue(TachoFaehigkeitTest.lies(HTML).contains("../shared/tacho-designs.js"));
        assertEquals("file:///android_asset/web/local/tacho-ansicht.html", TachoAnsicht.SEITE);
    }

    @Test
    public void seiteLaedtNichtsAusDemNetzUndKennKeinePosition() throws IOException {
        for (String f : new String[] {JS, HTML}) {
            String t = TachoFaehigkeitTest.lies(f);
            for (String verboten : new String[] {"fetch(", "XMLHttpRequest", "WebSocket", "http://", "https://",
                    "geolocation", "importScripts", "<iframe"}) {
                assertFalse(f + " enthaelt " + verboten, t.contains(verboten));
            }
            assertFalse(f + " kennt Koordinaten", t.matches("(?s).*\\b(lat|lng|lon|latitude|longitude)\\b.*"));
        }
        String act = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        assertTrue(act.contains("settings.blockNetworkLoads = true"));
        assertTrue(act.contains("setGeolocationEnabled(false)"));
        assertTrue(act.contains("seite.loadUrl(TachoAnsicht.SEITE)"));
        assertFalse("keine JS-Bruecke in die App", act.contains("addJavascriptInterface"));
        assertTrue("Absturz der Seite beendet die Ansicht",
                act.replaceAll("\\s+", " ").contains("onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean { beenden()"));
    }

    @Test
    public void routenskizzeIstRelativUndFahrtrichtungsoben() throws Exception {
        // Kurs Ost: ein Punkt 100 m oestlich liegt 100 m voraus.
        int[] s = TachoAnsichtManoever.skizze(List.of(new double[] {0, 0}, new double[] {100, 0}), 90.0);
        assertEquals(0, s[2]);
        assertEquals(100, s[3]);
        // Kurs Nord: 50 m oestlich = 50 m rechts.
        s = TachoAnsichtManoever.skizze(List.of(new double[] {50, 10}), 0.0);
        assertEquals(50, s[0]);
        assertEquals(10, s[1]);
        assertEquals("NO", TachoAnsichtManoever.richtung(44.0));
        assertEquals("N", TachoAnsichtManoever.richtung(359.0));
        assertEquals("SW", TachoAnsichtManoever.richtung(-135.0));
        List<double[]> viele = new ArrayList<>();
        for (int i = 0; i < 200; i++) viele.add(new double[] {i, i});
        assertEquals(TachoAnsichtManoever.SKIZZE_MAX * 2, TachoAnsichtManoever.skizze(viele, null).length);
        TachoAnsichtManoever.Stand st = new TachoAnsichtManoever.Stand(10, 350, 0L, 12400, 900, "O", new int[] {0, 0, 5, 80});
        JSONObject j = TachoAnsichtManoever.json(st);
        for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
            String k = it.next();
            assertTrue(k, List.of("typ", "distanzM", "restM", "restS", "richtung", "skizze").contains(k));
        }
    }

    // ------------------------------------------------------------ Flaeche

    @Test
    public void inhaltNurImVerkleinertenMittelbereich() {
        for (int[] g : new int[][] {{1920, 720}, {1280, 480}, {1024, 600}, {1, 1}}) {
            int[] r = TachoAnsicht.inhalt(g[0], g[1]);
            assertTrue(r[0] >= 0 && r[1] >= 0);
            assertTrue(r[0] + r[2] <= g[0] && r[1] + r[3] <= g[1]);
            assertTrue(r[2] <= g[0] * TachoAnsicht.BREITE_ANTEIL && r[3] <= g[1] * TachoAnsicht.HOEHE_ANTEIL);
            assertTrue("mittig", Math.abs(r[0] - (g[0] - r[0] - r[2])) <= 1);
        }
        assertTrue(TachoAnsicht.BREITE_ANTEIL < 1.0 && TachoAnsicht.HOEHE_ANTEIL < 1.0);
        assertEquals(0, TachoAnsicht.inhalt(0, 720)[2]);
    }

    // ------------------------------------------------------------ Opcodes 2/3, Regel A+

    @Test
    public void neueTeileSendenKeineOpcodesUndSchaltenNieBlinkerLichtHupe() throws IOException {
        for (String f : neueDateien()) {
            String t = TachoFaehigkeitTest.lies(f);
            for (String verboten : new String[] {"sendInfo", "service call", "AutoContainer", "IBinder",
                    "VehicleActuator", "LightDevice", "HornDevice", "setHorn", "setTurn", "turnSignal",
                    "flash", "Alarm"}) {
                assertFalse(f + " enthaelt " + verboten, t.contains(verboten));
            }
        }
        // Erlaubt-Liste im Projektionsweg unveraendert: 2/3 nie, OEM-Transport nur 5/0 und 4.
        String ctrl = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        assertFalse(ctrl.matches("(?s).*i32 1000 i32 [23]\\b.*"));
        String oem = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationTransport.java");
        assertTrue(oem.contains("static boolean istErlaubtSendInfo(int typ, int info)"));
    }

    private static String[] neueDateien() {
        String b = "app/src/main/java/at/greenchili/surfmate/tacho/";
        return new String[] {b + "TachoAnsicht.java", b + "TachoAnsichtActivity.kt",
                b + "TachoAnsichtManoever.kt", b + "TachoRueckmeldung.java", JS, HTML};
    }

    private static Path quelle() {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            for (String r : new String[] {"app/src/main/java", "src/main/java"}) {
                Path c = cur.resolve(r);
                if (Files.isDirectory(c)) return c;
            }
            cur = cur.getParent();
        }
        throw new AssertionError("Quellordner nicht gefunden");
    }
}
