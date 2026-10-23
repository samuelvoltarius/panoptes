package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 0.1.16 Sicherheit: /api/debug/autoservice/sdk-getter und probe-getters rufen per Reflexion nur lesende Methoden
 * (get/is/has) auf. Aktionen werden mit HTTP 400 abgelehnt, bevor irgendein Gerät angefasst wird.
 */
public class AutoServiceDebugNurLesendTest {

    static final String[] AKTIONEN = {
            "start", "stop", "set", "open", "close", "lock", "unlock", "flash", "remote", "send", "factoryReset",
            "setAcPower", "startCharge", "openWindow", "closeSunroof", "lockDoor", "unlockDoor", "flashLights",
            "remoteControlCar", "sendCommand", "voiceCtlLockCar", "getaway", "isx", "has", "", "get"
    };

    static String antwort(String pfad) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AutoServiceDebugApiHandler.handle("GET", pfad, null, out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void nurGetterNamen() {
        for (String a : AKTIONEN) assertFalse(a, AutoServiceDebugApiHandler.nurLesend(a));
        assertFalse(AutoServiceDebugApiHandler.nurLesend(null));
        for (String g : new String[]{"getDoorLock", "isAcOn", "hasSunroof", "getChargePower", "get12VVoltage"}) {
            assertTrue(g, AutoServiceDebugApiHandler.nurLesend(g));
        }
    }

    @Test
    public void sdkGetterLehntAktionenMit400Ab() throws Exception {
        for (String a : AKTIONEN) {
            if (a.isEmpty()) continue;
            String r = antwort("/api/debug/autoservice/sdk-getter?class=Bodywork&method=" + a);
            assertTrue(a + ": " + r, r.startsWith("HTTP/1.1 400"));
            assertTrue(a, r.contains("nur_lesend"));
        }
    }

    @Test
    public void probeGettersNurMitLesePraefix() throws Exception {
        for (String p : new String[]{"start", "stop", "set", "open", "close", "lock", "unlock", "flash", "remote",
                "send", "factoryReset", "s", "g"}) {
            String r = antwort("/api/debug/autoservice/probe-getters?prefix=" + p);
            assertTrue(p + ": " + r, r.startsWith("HTTP/1.1 400"));
        }
        assertTrue(AutoServiceDebugApiHandler.lesePraefix("get"));
        assertTrue(AutoServiceDebugApiHandler.lesePraefix("getDoor"));
        assertTrue(AutoServiceDebugApiHandler.lesePraefix("is"));
        assertFalse(AutoServiceDebugApiHandler.lesePraefix("getter"));
    }
}
