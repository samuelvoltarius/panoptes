package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.38.9: Erkennung fremder BYD-Werkzeug-Apps (nur lesend). */
public class FremdAppsTest {

    @Test
    public void nichtsGefunden() {
        FremdApps.Befund b = FremdApps.auswerten("FERTIG\n");
        assertFalse(b.getGefunden());
        assertFalse(b.getLaeuft());
        assertFalse(FremdApps.auswerten(null).getGefunden());
    }

    @Test
    public void paketNurInstalliertStoertNicht() {
        FremdApps.Befund b = FremdApps.auswerten("PAKET com.overdrive.app\nFERTIG\n");
        assertTrue(b.getGefunden());
        assertFalse("installiert, aber kein fremder Prozess", b.getLaeuft());
        assertEquals("com.overdrive.app", b.anzeigeName());
    }

    @Test
    public void fremderProzessLaeuftMitNamenAusKlassenpfad() {
        String z = "FREMD 4321 byd_cam_daemon CLASSPATH=/data/app/~~AbC123==/com.example.electron-XyZ==/base.apk\nFERTIG\n";
        FremdApps.Befund b = FremdApps.auswerten(z);
        assertTrue(b.getLaeuft());
        assertEquals("com.example.electron", b.anzeigeName());
        assertTrue(b.protokollZeile().contains("fremde_prozesse=1"));
        assertFalse("kein Pfad im Protokoll", b.protokollZeile().contains("/data/app"));
    }

    @Test
    public void unbekanntesPaketWirdIgnoriert() {
        assertFalse(FremdApps.auswerten("PAKET com.irgendwas.anderes\n").getGefunden());
    }

    @Test
    public void skriptListetNurUndBeendetNichts() {
        String s = FremdApps.abfrageSkript();
        assertTrue(s.contains("pm list packages"));
        assertTrue(s.contains("package:com.overdrive.app"));
        assertTrue(s.contains("echo FERTIG"));
        assertFalse(s.contains("kill -9"));
        for (String n : FremdApps.PROZESSNAMEN) assertTrue(n, s.contains("'" + n + "'"));
    }
}
