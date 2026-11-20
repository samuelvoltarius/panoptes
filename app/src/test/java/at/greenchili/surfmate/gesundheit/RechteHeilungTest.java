package at.greenchili.surfmate.gesundheit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Rechte-Selbstheilung: erkennen, mit pm grant/appops wiederherstellen, sonst einmal am Tag verständlich melden. */
public class RechteHeilungTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PKG = "at.greenchili.surfmate";

    private static final String DUMP_FEHLT = String.join("\n",
            "    install permissions:",
            "      android.permission.SYSTEM_ALERT_WINDOW: granted=true",
            "    User 0: ceDataInode=1 installed=true",
            "      runtime permissions:",
            "        android.permission.CAMERA: granted=false, flags=[ USER_SET]",
            "        android.permission.RECORD_AUDIO: granted=true",
            "        android.permission.ACCESS_FINE_LOCATION: granted=false",
            "        android.permission.BYDAUTO_AC_GET: granted=false",
            "");
    private static final String DUMP_OK = String.join("\n",
            "      runtime permissions:",
            "        android.permission.CAMERA: granted=true",
            "        android.permission.ACCESS_FINE_LOCATION: granted=true",
            "");
    private static final String OPS_GESPERRT = String.join("\n",
            "Uid mode: RUN_ANY_IN_BACKGROUND: ignore",
            "CAMERA: allow; time=+1d2h ago",
            "SYSTEM_ALERT_WINDOW: deny; rejectTime=+3h ago",
            "COARSE_LOCATION: foreground",
            "TOAST_WINDOW: ignore",
            "");
    private static final String OPS_OK = "CAMERA: allow\nSYSTEM_ALERT_WINDOW: allow\nRUN_ANY_IN_BACKGROUND: allow\n";

    @Test
    public void erkenntNurAusdruecklichVerweigerteRechte() {
        assertEquals(Arrays.asList("android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION"),
                RechteHeilung.fehlendeRechte(DUMP_FEHLT));
        // BYD-Signaturrechte und fremde Namen sind nicht unsere Sache
        assertFalse(RechteHeilung.fehlendeRechte(DUMP_FEHLT).contains("android.permission.BYDAUTO_AC_GET"));
        assertTrue(RechteHeilung.fehlendeRechte(DUMP_OK).isEmpty());
        assertTrue("nicht lesbar = unbekannt, nie fehlt", RechteHeilung.fehlendeRechte(null).isEmpty());
        assertTrue(RechteHeilung.fehlendeRechte("Müll").isEmpty());
        // false in irgendeinem Benutzer-Block gewinnt
        assertEquals(Collections.singletonList("android.permission.CAMERA"), RechteHeilung.fehlendeRechte(
                "android.permission.CAMERA: granted=true\n  android.permission.CAMERA: granted=false\n"));
    }

    @Test
    public void erkenntGesperrteAppops() {
        assertEquals(Arrays.asList("SYSTEM_ALERT_WINDOW", "RUN_ANY_IN_BACKGROUND"),
                RechteHeilung.gesperrteAppops(OPS_GESPERRT));
        assertTrue("foreground/allow/default sind kein Verlust", RechteHeilung.gesperrteAppops(OPS_OK).isEmpty());
        assertTrue(RechteHeilung.gesperrteAppops(null).isEmpty());
    }

    @Test
    public void befehleNurVorhandeneMittelUndNurGueltigesPaket() {
        List<String> b = RechteHeilung.befehle(PKG, Collections.singletonList("android.permission.CAMERA"),
                Collections.singletonList("SYSTEM_ALERT_WINDOW"));
        assertEquals(Arrays.asList("pm grant " + PKG + " android.permission.CAMERA",
                "appops set " + PKG + " SYSTEM_ALERT_WINDOW allow"), b);
        for (String x : b) assertTrue(x, x.startsWith("pm grant ") || x.startsWith("appops set "));
        // Unbekanntes wird verworfen, Shell-Einschleusung im Paketnamen nie
        assertTrue(RechteHeilung.befehle(PKG, Collections.singletonList("android.permission.X; reboot"),
                Collections.singletonList("CAMERA; reboot")).isEmpty());
        assertTrue(RechteHeilung.befehle("a.b; reboot", Collections.singletonList("android.permission.CAMERA"),
                Collections.emptyList()).isEmpty());
        assertFalse(RechteHeilung.paketGueltig("$(id)"));
        assertTrue(RechteHeilung.paketGueltig(PKG));
    }

    /** Falsche Shell: liefert vorher/nachher, merkt sich die Befehle. */
    private static final class FalscheShell implements RechteHeilung.Shell {
        final List<String> befehle = new ArrayList<>();
        final String dumpVorher, dumpNachher, opsVorher, opsNachher;
        int dumps, ops;

        FalscheShell(String dumpVorher, String dumpNachher, String opsVorher, String opsNachher) {
            this.dumpVorher = dumpVorher;
            this.dumpNachher = dumpNachher;
            this.opsVorher = opsVorher;
            this.opsNachher = opsNachher;
        }

        @Override
        public String lauf(String befehl) {
            befehle.add(befehl);
            if (befehl.startsWith("dumpsys")) return dumps++ == 0 ? dumpVorher : dumpNachher;
            if (befehl.startsWith("appops get")) return ops++ == 0 ? opsVorher : opsNachher;
            return "";
        }
    }

    @Test
    public void heiltMitVorhandenenMitteln() {
        FalscheShell s = new FalscheShell(DUMP_FEHLT, DUMP_OK, OPS_GESPERRT, OPS_OK);
        RechteHeilung.Ergebnis e = RechteHeilung.durchgang(PKG, s);
        assertEquals("geheilt", e.zustand);
        assertEquals(Arrays.asList("kamera", "standort", "ueberlagern", "hintergrund"), e.repariert);
        assertTrue(e.offen.isEmpty());
        assertTrue(s.befehle.contains("pm grant " + PKG + " android.permission.CAMERA 2>&1"));
        assertTrue(s.befehle.contains("appops set " + PKG + " RUN_ANY_IN_BACKGROUND allow 2>&1"));
        for (String b : s.befehle) {
            assertTrue("nur lesen oder Rechte der App: " + b, b.startsWith("dumpsys -t 5 package ")
                    || b.startsWith("appops get ") || b.startsWith("pm grant ") || b.startsWith("appops set "));
            assertFalse(b, b.contains("su ") || b.contains("reboot") || b.contains("am start"));
        }
    }

    @Test
    public void bleibtOffenWennDasAutoNichtMitspielt() {
        FalscheShell s = new FalscheShell(DUMP_FEHLT, DUMP_FEHLT, OPS_GESPERRT, OPS_GESPERRT);
        RechteHeilung.Ergebnis e = RechteHeilung.durchgang(PKG, s);
        assertEquals("offen", e.zustand);
        assertEquals(Arrays.asList("kamera", "standort", "ueberlagern", "hintergrund"), e.offen);
        assertTrue(e.repariert.isEmpty());
    }

    @Test
    public void allesInOrdnungOderNichtLesbar() {
        FalscheShell ok = new FalscheShell(DUMP_OK, DUMP_OK, OPS_OK, OPS_OK);
        assertEquals("ok", RechteHeilung.durchgang(PKG, ok).zustand);
        assertEquals("nur lesen, nichts setzen", 2, ok.befehle.size());
        FalscheShell leer = new FalscheShell(null, null, "", "");
        assertEquals("unbekannt", RechteHeilung.durchgang(PKG, leer).zustand);
        assertEquals(2, leer.befehle.size());
        assertEquals("unbekannt", RechteHeilung.durchgang("x; reboot", ok).zustand);
    }

    // ------------------------------------------------------------- 0.1.43.16 nur im Stand (E2E 0.1.43.13)

    /** Falsche Wege nach außen: Stand umschaltbar, zählt Display und Telegram. */
    private static class FalscheWege implements RechteHeilungsDienst.Wege {
        boolean stand;
        boolean quittiert = true;
        /** 0.1.43.17: wie der gezeigte Dialog ausgeht (Standard: von selbst zu, gilt als gemeldet). */
        at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang ausgang =
                at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.ABGELAUFEN;
        int display, telegram;

        @Override public boolean stand() { return stand; }

        @Override public at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang display(String titel, String text) {
            display++;
            return quittiert ? ausgang : at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.NICHT_GEZEIGT;
        }

        @Override public void telegram(String titel, String text) { telegram++; }
    }

    private static final long MIN = 60_000L;

    @Test
    public void waehrendDerFahrtKeinDialogTelegramSofortEinmalDanachImStand() {
        FalscheWege w = new FalscheWege();
        w.stand = false;                                      // Fahrt oder Gang/Tempo unbekannt (fail-closed)
        RechteHeilungsDienst d = new RechteHeilungsDienst(PKG, w,
                new FalscheShell(DUMP_FEHLT, DUMP_FEHLT, OPS_GESPERRT, OPS_GESPERRT));
        long t = 1_000_000_000L;
        d.tick(t);
        assertEquals("kein Dialog während der Fahrt", 0, w.display);
        assertEquals("Telegram sofort", 1, w.telegram);
        assertTrue("offen, nicht als gemeldet verbucht", d.meldungOffen());
        d.tick(t + 5 * MIN);
        d.tick(t + 10 * MIN);
        assertEquals(0, w.display);
        assertEquals("Telegram nur einmal", 1, w.telegram);
        w.stand = true;                                       // nach 15 min steht das Auto
        d.tick(t + 15 * MIN);
        assertEquals("nach 5 min erneut versucht, nicht erst nach 24 h", 1, w.display);
        assertFalse("nach der Quittung gemeldet", d.meldungOffen());
        d.tick(t + 20 * MIN);
        d.tick(t + 60 * MIN);
        assertEquals("danach Ruhe", 1, w.display);
        assertEquals(1, w.telegram);
    }

    @Test
    public void imStandSofortUndOhneQuittungHoechstensDreiVersuche() {
        FalscheWege w = new FalscheWege();
        w.stand = true;
        w.quittiert = false;                                  // z. B. Overlay-Recht fehlt gerade
        RechteHeilungsDienst d = new RechteHeilungsDienst(PKG, w,
                new FalscheShell(DUMP_FEHLT, DUMP_FEHLT, OPS_GESPERRT, OPS_GESPERRT));
        long t = 1_000_000_000L;
        d.tick(t);
        assertEquals(1, w.display);
        assertTrue(d.meldungOffen());
        d.tick(t + 5 * MIN);
        d.tick(t + 10 * MIN);
        assertEquals(RechteHeilungsDienst.DISPLAY_VERSUCHE, w.display);
        assertFalse("nach drei Versuchen gemeldet (Telegram war da)", d.meldungOffen());
        d.tick(t + 15 * MIN);
        assertEquals(RechteHeilungsDienst.DISPLAY_VERSUCHE, w.display);
        assertEquals(1, w.telegram);
    }

    // ------------------------------------------------------------- 0.1.43.17 Meldestand übersteht einen Neustart

    private static FalscheShell fehlt() {
        return new FalscheShell(DUMP_FEHLT, DUMP_FEHLT, OPS_GESPERRT, OPS_GESPERRT);
    }

    @Test
    public void neustartKeinZweitesTelegramUndDisplayPflichtBleibt() throws Exception {
        java.io.File basis = tmp.newFolder("surfmate");
        long t = 1_000_000_000L;
        FalscheWege w1 = new FalscheWege();
        w1.stand = false;                                     // Fahrt: nur Telegram, Display offen
        new RechteHeilungsDienst(PKG, w1, fehlt(), basis).tick(t);
        assertEquals(1, w1.telegram);
        assertEquals(0, w1.display);
        // Kern-Neustart: neuer Dienst, dieselbe Meldung
        FalscheWege w2 = new FalscheWege();
        w2.stand = false;
        RechteHeilungsDienst d2 = new RechteHeilungsDienst(PKG, w2, fehlt(), basis);
        assertTrue("Display-Pflicht überlebt den Neustart", d2.meldungOffen());
        d2.tick(t + 10 * MIN);
        assertEquals("kein zweites Telegram nach dem Neustart", 0, w2.telegram);
        w2.stand = true;
        d2.tick(t + 15 * MIN);
        assertEquals("Display kommt im Stand nach", 1, w2.display);
        assertFalse(d2.meldungOffen());
        // noch ein Neustart am selben Tag: gemeldet ist gemeldet
        FalscheWege w3 = new FalscheWege();
        w3.stand = true;
        RechteHeilungsDienst d3 = new RechteHeilungsDienst(PKG, w3, fehlt(), basis);
        d3.tick(t + 60 * MIN);
        assertEquals("Tagesgrenze gilt auch nach dem Neustart", 0, w3.telegram);
        assertEquals(0, w3.display);
        // am nächsten Tag: wieder (Tagesgrenze aus meldenNoetig bleibt)
        FalscheWege w4 = new FalscheWege();
        w4.stand = true;
        new RechteHeilungsDienst(PKG, w4, fehlt(), basis).tick(t + RechteHeilung.MELDE_ABSTAND_MS + 60 * MIN);
        assertEquals(1, w4.telegram);
        assertEquals(1, w4.display);
    }

    @Test
    public void nachNeustartAndereRechteOffenNeueMeldung() throws Exception {
        java.io.File basis = tmp.newFolder("surfmate");
        long t = 1_000_000_000L;
        FalscheWege w1 = new FalscheWege();
        w1.stand = true;
        new RechteHeilungsDienst(PKG, w1, fehlt(), basis).tick(t);
        assertEquals(1, w1.telegram);
        assertEquals(1, w1.display);
        // Neustart, jetzt fehlt nur noch die Kamera (anderer Befund): neue Meldung, auch am selben Tag
        String nurKamera = "      runtime permissions:\n        android.permission.CAMERA: granted=false\n";
        FalscheWege w2 = new FalscheWege();
        w2.stand = true;
        new RechteHeilungsDienst(PKG, w2, new FalscheShell(nurKamera, nurKamera, OPS_OK, OPS_OK), basis).tick(t + 30 * MIN);
        assertEquals("andere Rechte offen: neues Telegram", 1, w2.telegram);
        assertEquals(1, w2.display);
    }

    @Test
    public void kaputterMeldestandBeginntLeer() throws Exception {
        java.io.File basis = tmp.newFolder("surfmate");
        java.nio.file.Files.write(new java.io.File(basis, RechteHeilungsDienst.DATEI).toPath(),
                "{kaputt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FalscheWege w = new FalscheWege();
        w.stand = true;
        RechteHeilungsDienst d = new RechteHeilungsDienst(PKG, w, fehlt(), basis);
        assertFalse(d.meldungOffen());
        d.tick(1_000_000_000L);
        assertEquals(1, w.telegram);
        assertTrue("wieder lesbar geschrieben", new String(java.nio.file.Files.readAllBytes(
                new java.io.File(basis, RechteHeilungsDienst.DATEI).toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"gemeldet\""));
    }

    // ------------------------------------------------------------- 0.1.43.17b losgefahren / belegt / verdrängt

    @Test
    public void losgefahrenBelegtOderVerdraengtKostetKeinenVersuch() {
        FalscheWege w = new FalscheWege();
        w.stand = true;
        RechteHeilungsDienst d = new RechteHeilungsDienst(PKG, w, fehlt());
        long t = 1_000_000_000L;
        w.ausgang = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.ABBRUCH_FAHRT;
        d.tick(t);
        w.ausgang = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.BELEGT;
        d.tick(t + 5 * MIN);
        w.ausgang = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.VERDRAENGT;
        d.tick(t + 10 * MIN);
        w.ausgang = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.ABBRUCH_FAHRT;
        d.tick(t + 15 * MIN);
        assertEquals(4, w.display);
        assertTrue("mehr als 3 Mal: keiner davon zählte, Meldung bleibt offen", d.meldungOffen());
        assertEquals("Telegram trotzdem nur einmal", 1, w.telegram);
        w.ausgang = at.greenchili.surfmate.byd.MessageOverlayController.DialogAusgang.OK;
        d.tick(t + 20 * MIN);
        assertFalse("im Stand gezeigt: gemeldet", d.meldungOffen());
    }

    @Test
    public void standFehlerZaehltAlsNichtSicher() {
        FalscheWege w = new FalscheWege() {
            @Override public boolean stand() { throw new IllegalStateException("kein Wert"); }
        };
        RechteHeilungsDienst d = new RechteHeilungsDienst(PKG, w,
                new FalscheShell(DUMP_FEHLT, DUMP_FEHLT, OPS_GESPERRT, OPS_GESPERRT));
        d.tick(1_000_000_000L);
        assertEquals(0, w.display);
        assertTrue(d.meldungOffen());
    }

    @Test
    public void meldetNeuenBefundSofortDenselbenHoechstensTaeglich() {
        long t = 1_000_000_000L;
        List<String> a = Collections.singletonList("kamera");
        assertFalse(RechteHeilung.meldenNoetig(Collections.emptyList(), null, 0, t));
        assertTrue(RechteHeilung.meldenNoetig(a, null, 0, t));
        assertFalse(RechteHeilung.meldenNoetig(a, a, t, t + 3600_000L));
        assertTrue(RechteHeilung.meldenNoetig(a, a, t, t + RechteHeilung.MELDE_ABSTAND_MS));
        assertTrue("neuer Befund", RechteHeilung.meldenNoetig(Arrays.asList("kamera", "mikrofon"), a, t, t + 1));
        assertTrue("Uhr zurück", RechteHeilung.meldenNoetig(a, a, t, t - 1));
    }

    @Test
    public void meldungSagtWasZuTunIst() {
        String[] m = RechteHeilungsDienst.meldung(Arrays.asList("kamera", "mikrofon"));
        assertTrue(m[0].length() > 5);
        assertTrue(m[1], m[1].contains("Panoptes") && m[1].length() > 60);
    }

    @Test
    public void startUndMeldewegeVerdrahtet() throws Exception {
        String cd = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java"));
        assertTrue(cd.contains("gesundheit.RechteHeilungsDienst.start(APP_PACKAGE_NAME())"));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/gesundheit/RechteHeilungsDienst.java"));
        // 0.1.43.16: Display mit Quittung, 180 s, nie mehr ohne Zeitlimit; nur im sicheren Stand
        // 0.1.43.17: Display mit Ausgang und Stand-Prüfung bei offenem Dialog (losgefahren → zu)
        assertTrue("Display mit Ausgang", d.contains("MessageOverlayController.showDialogMitAusgang("));
        assertTrue(d.contains("DIALOG_SEK, at.greenchili.surfmate.appupdate.AppUpdateDienst::sichererStandJetzt);"));
        assertTrue("180 s", d.contains("DIALOG_SEK = 180"));
        assertFalse("kein Dialog ohne Zeitlimit", d.contains("\"warning\", 0)"));
        assertTrue("Standprüfung wie die Wächter-Frage", d.contains("AppUpdateDienst.sichererStandJetzt()"));
        String au = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/appupdate/AppUpdateDienst.java"));
        assertTrue(au.contains("WaechterStandardHinweis.standFuerFrage(u.fahrsperre(), u.gangP(), u.kmh())"));
        assertTrue("Telegram", d.contains("TelegramNotifier.sendMessage("));
        assertTrue("täglich", d.contains("24L * 3600"));
        assertFalse("kein Root", d.contains("\"su\"") || d.contains("su -c"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject g = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/server-i18n/" + l + ".json")))
                    .getJSONObject("rechte_heilung");
            for (String k : new String[] {"titel", "text", "wort_kamera", "wort_mikrofon", "wort_standort",
                    "wort_speicher", "wort_ueberlagern", "wort_hintergrund", "wort_einstellungen"}) {
                assertTrue(l + " " + k, g.optString(k).length() > 2);
            }
            assertTrue(l, g.getString("text").contains("{0}"));
        }
    }
}
