package at.greenchili.surfmate.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchiv;

import org.junit.Test;

import java.util.List;

/**
 * 0.1.38.10 (HARDRESET 41 s am Auto): Das Log-Archiv wurde nach JEDER verschobenen Logdatei einmal ganz durchgegangen
 * (zwei stat-Prozesse je Archivdatei). Jetzt: je Art nur EIN stat; der Hard-Reset archiviert gar nicht mehr, die
 * Wächter beim eigenen Start (EIN Aufräumen je Start). Dazu die Zeitmarken je Hard-Reset-Schritt.
 */
public class HardResetLogArchivTest {

    @Test
    public void aufraeumenNutztEinStatJeArtStattZweiJeDatei() {
        String f = String.join("\n", LogArchiv.shellFunktionen());
        String aufraeumen = f.substring(f.indexOf("log_archiv_aufraeumen() {"), f.indexOf("log_archiv_ziel() {"));
        assertTrue(aufraeumen.contains("stat -c \"%s %Y %n\" $LA_L"));
        assertFalse("keine stat-Aufrufe je Datei mehr", aufraeumen.contains("$(stat"));
    }

    @Test
    public void einzelneLogdateiAufrufeBehaltenDasAufraeumen() {
        List<String> z = LogArchiv.shellFunktionen();
        String f = String.join("\n", z);
        int i = f.indexOf("log_archivieren() {");
        assertTrue(i > 0);
        String block = f.substring(i, f.indexOf("}", i));
        assertTrue("log_archivieren = verschieben + Aufräumen", block.contains("log_archivieren_nur") && block.contains("log_archiv_aufraeumen"));
        assertTrue(f.contains("log_archivieren_nur() {"));
    }

    @Test
    public void schrittZeitenAusDerSkriptAusgabe() {
        String aus = "HR_BEGIN 1000\nHR_T sentinels 0\nHR_T kill 2\nHR_T settle 4\nHR_T gesamt 6\ndone\n";
        // Java begann bei Sekunde 997: 3 s Vorlauf (ADB-Verbindung + Skript schreiben)
        assertEquals("vorlauf=3;sentinels=0;kill=2;settle=4;gesamt=6;", UpdateLifecycle.schritteAusAusgabe(aus, 997));
        assertEquals("", UpdateLifecycle.schritteAusAusgabe("done\n", 0));
        assertEquals("", UpdateLifecycle.schritteAusAusgabe(null, 0));
        assertEquals("anzahl=1;exe_geloescht=ja", UpdateLifecycle.tailscaledAusAusgabe("x\nHR_TS laeuft 101 ja\n"));
        assertEquals("anzahl=2;exe_geloescht=nein", UpdateLifecycle.tailscaledAusAusgabe("HR_TS laeuft 1 nein\nHR_TS laeuft 2 nein"));
        assertEquals("", UpdateLifecycle.tailscaledAusAusgabe("HR_T kill 2\nHR_TS laeuft x ja\n"));
        assertEquals("", UpdateLifecycle.tailscaledAusAusgabe(null));
        // Unsinn wird ignoriert
        assertEquals("", UpdateLifecycle.schritteAusAusgabe("HR_T bad;x 1\nHR_T kill abc\nHR_T a b c d\n", 0));
    }
}
