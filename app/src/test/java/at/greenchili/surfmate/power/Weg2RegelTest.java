package at.greenchili.surfmate.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Weg-2-Regel (0.1.13); seit 0.1.15 meldet der Wächter nur noch (kein CONNECT_VPN), die Regel bleibt gleich. */
public class Weg2RegelTest {

    private static Weg2Regel.Lage bereit() {
        Weg2Regel.Lage l = new Weg2Regel.Lage();
        l.schalterAn = true;
        l.autoSteht = true;
        l.alwaysOnPaket = "com.tailscale.ipn";
        l.warSchonAktiv = true;
        l.jetztMs = 100 * 60_000L;
        l.totSeitMs = l.jetztMs - 11 * 60_000L;
        l.displayAus = true;
        return l;
    }

    @Test
    public void alleBedingungenErfuelltSendet() {
        assertNull(Weg2Regel.grund(bereit()));
        assertEquals("am broadcast --include-stopped-packages -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.CONNECT_VPN",
                Weg2Regel.befehl());
    }

    @Test
    public void jedeBedingungSperrt() {
        Weg2Regel.Lage l;
        l = bereit(); l.displayAus = false; assertEquals("display_an", Weg2Regel.grund(l));
        l = bereit(); l.autoSteht = false; assertEquals("auto_nicht_geparkt", Weg2Regel.grund(l));
        l = bereit(); l.alwaysOnPaket = "null"; assertEquals("kein_always_on", Weg2Regel.grund(l));
        l = bereit(); l.warSchonAktiv = false; assertEquals("nie_aktiv", Weg2Regel.grund(l));
        l = bereit(); l.prozessDa = true; assertNull("leerer Prozess nach BYD-forceStop zählt nicht als lebendig", Weg2Regel.grund(l));
        l = bereit(); l.vpnNetzDa = true; assertEquals("lebt", Weg2Regel.grund(l));
        l = bereit(); l.totSeitMs = l.jetztMs - 9 * 60_000L; assertEquals("noch_nicht_lange_tot", Weg2Regel.grund(l));
        l = bereit(); l.schalterAn = false; assertEquals("aus", Weg2Regel.grund(l));
    }

    @Test
    public void ersterVersuchNachDemParkenSofortSonstNach10Min() {
        Weg2Regel.Lage l = bereit();
        l.totSeitMs = l.jetztMs - 60_000L;          // gerade erst tot (BYD-Kill beim Abstellen)
        l.ersterVersuchDiesesParks = true;
        assertNull(Weg2Regel.grund(l));
        l.ersterVersuchDiesesParks = false;
        assertEquals("noch_nicht_lange_tot", Weg2Regel.grund(l));
        l.ersterVersuchDiesesParks = true;
        l.displayAus = false;
        assertEquals("auch sofort nur bei Display aus", "display_an", Weg2Regel.grund(l));
        l.displayAus = true;
        l.letzterVersuchMs = l.jetztMs - 5 * 60_000L;
        assertEquals("Bremsen bleiben", "abstand", Weg2Regel.grund(l));
    }

    @Test
    public void bremsen() {
        Weg2Regel.Lage l = bereit();
        l.letzterVersuchMs = l.jetztMs - 29 * 60_000L;
        assertEquals("abstand", Weg2Regel.grund(l));
        l.letzterVersuchMs = l.jetztMs - 31 * 60_000L;
        assertNull(Weg2Regel.grund(l));
        l.versucheHeute = 6;
        assertEquals("tageslimit", Weg2Regel.grund(l));
        l.versucheHeute = 1;
        l.fehlschlaegeHeute = 2;
        assertEquals("heute_aufgegeben", Weg2Regel.grund(l));
    }

    @Test
    public void displayZustandAusDumpsys() {
        assertTrue(Weg2Regel.displayAus("Power Manager State:\n  mWakefulness=Awake\nDisplay Power: state=OFF\n"));
        assertFalse(Weg2Regel.displayAus("Display Power: state=ON\n"));
        assertFalse("unbekannt = nicht aus", Weg2Regel.displayAus("irgendwas"));
        assertFalse(Weg2Regel.displayAus(null));
    }

    @Test
    public void waechterSendetNichtMehr() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/power/Weg2Waechter.java");
        if (!f.isFile()) f = new java.io.File("app/src/main/java/at/greenchili/surfmate/power/Weg2Waechter.java");
        String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("0.1.15: kein CONNECT_VPN mehr (Praxisbefund 22.10.)", s.contains("Weg2Regel.befehl()"));
        assertFalse(s.contains("am broadcast"));
    }
}
