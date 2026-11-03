package at.greenchili.surfmate.gesundheit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Rechte-Selbstheilung: erkennen, mit pm grant/appops wiederherstellen, sonst einmal am Tag verständlich melden. */
public class RechteHeilungTest {

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
        assertTrue("Display", d.contains("MessageOverlayController.showDialog("));
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
