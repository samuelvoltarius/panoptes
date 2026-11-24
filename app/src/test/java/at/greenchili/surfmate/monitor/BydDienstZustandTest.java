package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.43.20: BYD-Dienste als zusätzliches Park/ACC-Signal – nur lesen, nur bremsen, unbekannt = altes Signal. */
public class BydDienstZustandTest {

    /** Aufbau wie in der Inventur vom 20.11.2026 (Dolphin Surf, DiLink 3.0), ohne Uhrzeiten und Orte. */
    private static final String CAR = "*Dump BydCar service*\n*Dump all services*\n*BydActivityMonitorService*\n"
            + " Top Tasks per display:\n*BydDrivingStateService*\nDriving state change log:\n"
            + "BydDrivingState Boot:  changed from 255 to 3\nCurrent Driving State: 3\n"
            + "*BydPackageManagerService*\nmHasParsedPackages:true\n Blocked activity log:\n";
    private static final String ACC = "POWER MANAGER (dumpsys acc)\n\nAcc Manager State:\n\n"
            + "mDirty=0 (1:acc,2:restore,4:acclock)\n\nmPadStatusDirty=1 (1:pad_on)\n\nmSuspending=0\n\nAcc Locks: size=0\n";

    @Test
    public void fahrzustandAusDemDump() {
        assertEquals(Integer.valueOf(3), BydDienstZustand.fahrzustand(CAR));
        assertEquals(Integer.valueOf(3), BydDienstZustand.fahrzustand("Current Driving State: 3"));   // grep-Zeile
    }

    @Test
    public void fahrzustandUnbekannt() {
        assertNull(BydDienstZustand.fahrzustand(null));
        assertNull(BydDienstZustand.fahrzustand(""));
        assertNull(BydDienstZustand.fahrzustand("Can't find service: byd_car_service"));
        assertNull("255 = ungültig (Boot)", BydDienstZustand.fahrzustand("Current Driving State: 255"));
        assertNull(BydDienstZustand.fahrzustand("Current Driving State: -1"));
    }

    @Test
    public void accZustand() {
        assertEquals(Boolean.FALSE, BydDienstZustand.schlaeft(ACC));
        assertEquals(Integer.valueOf(0), BydDienstZustand.accSperren(ACC));
        assertEquals(Boolean.TRUE, BydDienstZustand.schlaeft(ACC.replace("mSuspending=0", "mSuspending=1")));
        assertEquals(Integer.valueOf(2), BydDienstZustand.accSperren(ACC.replace("size=0", "size=2")));
        assertNull(BydDienstZustand.schlaeft("Can't find service: accmodemanager"));
        assertNull(BydDienstZustand.accSperren(null));
    }

    @Test
    public void nurBelegtesEinschlafenBremst() {
        assertTrue(BydDienstZustand.bremst(new BydDienstZustand.Stand(3, Boolean.TRUE, 0, 1L)));
        assertFalse(BydDienstZustand.bremst(new BydDienstZustand.Stand(3, Boolean.FALSE, 0, 1L)));
        assertFalse("unlesbar → altes Signal allein", BydDienstZustand.bremst(new BydDienstZustand.Stand(null, null, null, 1L)));
        assertFalse(BydDienstZustand.bremst(null));
    }

    @Test
    public void ausDerAppNieLesen_keinBefehl() {
        // Im JVM-Test ist die uid weder shell noch system: es wird nichts ausgeführt, alles unbekannt.
        BydDienstZustand.vergessen();
        BydDienstZustand.Stand s = BydDienstZustand.jetzt();
        assertNull(s.fahrzustand);
        assertNull(s.schlaeft);
        assertFalse(BydDienstZustand.schlaeftGleich());
    }

    @Test
    public void nurLesendeBefehle() throws Exception {
        String quelle = new String(java.nio.file.Files.readAllBytes(quelle().toPath()), "UTF-8");
        assertFalse("keine Fahrzeugbefehle", quelle.contains("service call") || quelle.contains("\"am\"") || quelle.contains("setprop"));
        assertTrue(quelle.contains("\"dumpsys\""));
    }

    private static java.io.File quelle() {
        String rel = "src/main/java/at/greenchili/surfmate/monitor/BydDienstZustand.java";
        java.io.File f = new java.io.File(rel);
        return f.isFile() ? f : new java.io.File("app/" + rel);
    }
}
