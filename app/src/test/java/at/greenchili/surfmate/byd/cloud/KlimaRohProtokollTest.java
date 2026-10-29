package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.1.26 Klima-Rohprotokoll. Die Antwort hier ist ERFUNDEN (Feldnamen wie in pyBYD/BydCloudClient, Werte beliebig):
 * eine echte Aufnahme gibt es erst nach 0.1.26 am Auto. Geprüft wird nur, was ins Protokoll darf und wann.
 */
public class KlimaRohProtokollTest {

    static final long T0 = 1_800_000_000_000L;

    JSONObject vi() throws Exception {
        return new JSONObject()
                .put("vin", "TESTVIN0000000000").put("plateNo", "S-TEST1").put("time", 1_800_000_000L)
                .put("airRunState", 1).put("mainSettingTemp", 13).put("copilotSettingTemp", 13)
                .put("tempInCar", 0).put("tempOutCar", 8).put("elecPercent", 77).put("speed", 0)
                .put("latitude", 48.2085).put("longitude", 16.3731)   // Wien Stephansplatz (öffentlich)
                .put("statusNow", new JSONObject().put("acSwitch", 1).put("userId", "u1").put("token", "geheim"));
    }

    @Before
    public void auf() {
        KlimaRohProtokoll.zuruecksetzen();
    }

    @Test
    public void nurKlimaFelderNieVinKennzeichenTokenOrt() throws Exception {
        String z = KlimaRohProtokoll.pruefen(vi(), new JSONObject().put("tempInCar", 21.5).put("deviceId", "d"), T0);
        assertNotNull(z);
        for (String soll : new String[]{"airRunState=1", "mainSettingTemp=13", "copilotSettingTemp=13", "tempInCar=0",
                "tempOutCar=8", "statusNow.acSwitch=1", "hvac.tempInCar=21.5", "time=1800000000"}) {
            assertTrue(soll + " in " + z, z.contains(soll));
        }
        for (String nie : new String[]{"TESTVIN", "S-TEST1", "geheim", "u1", "48.2085", "elecPercent", "deviceId", "plate"}) {
            assertFalse(nie + " in " + z, z.contains(nie));
        }
    }

    @Test
    public void gedrosseltAusserNachEinemKlimabefehl() throws Exception {
        assertNotNull(KlimaRohProtokoll.pruefen(vi(), null, T0));
        assertNull("binnen 10 min nichts", KlimaRohProtokoll.pruefen(vi(), null, T0 + 60_000));
        assertNull(KlimaRohProtokoll.pruefen(vi(), null, T0 + 9 * 60_000));
        assertNotNull("nach 10 min wieder", KlimaRohProtokoll.pruefen(vi(), null, T0 + 10 * 60_000));
        // Cloud-Befehl: die nächste Antwort binnen 5 min kommt trotz Drossel, aber nur einmal
        KlimaRohProtokoll.befehlZeile("OPENAIR", null, true, "0", T0 + 11 * 60_000);
        String z = KlimaRohProtokoll.pruefen(vi(), null, T0 + 12 * 60_000);
        assertNotNull(z);
        assertTrue(z.contains("(nach Befehl)"));
        assertNull(KlimaRohProtokoll.pruefen(vi(), null, T0 + 13 * 60_000));
    }

    @Test
    public void openairMitSollwertUndUmrechnung() throws Exception {
        // 0.1.26 Dolphin Surf: SDK 27 = Stufe 11 = Roh 11 (vorher °C − 14 = 13)
        at.greenchili.surfmate.byd.KlimaStufe.fuerTest(Boolean.TRUE);
        String z;
        try {
            z = KlimaRohProtokoll.befehlZeile("OPENAIR", BydCloudClient.climateStartParams(27, 20), true, "0", T0);
        } finally {
            at.greenchili.surfmate.byd.KlimaStufe.fuerTest(null);
        }
        assertEquals("Cloud OPENAIR ok: mainSettingTemp=11 (= SDK 27, Heizen, Roh = Stufe (Annahme)) copilotSettingTemp=11"
                + " timeSpan=3 remoteMode=4 cycleMode=2", z);
        // anderes Modell: pyBYD °C − 14
        assertEquals("Cloud OPENAIR ok: mainSettingTemp=13 (= SDK 27, 27 °C, Roh = °C − 14) copilotSettingTemp=13"
                + " timeSpan=3 remoteMode=4 cycleMode=2",
                KlimaRohProtokoll.befehlZeile("OPENAIR", BydCloudClient.climateStartParams(27, 20), true, "0", T0));
        assertTrue(KlimaRohProtokoll.befehlZeile("CLOSEAIR", null, false, "1002", T0).endsWith("fehlgeschlagen code=1002"));
        assertNull("andere Befehle nicht", KlimaRohProtokoll.befehlZeile("LOCKDOOR", null, true, "0", T0));
    }
}
