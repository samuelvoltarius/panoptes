package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Koordination 0.1.9: Aufsperren ist in Panoptes KOMPLETT entfernt (Projektinhaber „nie“; /api/vehicle/unlock war
 * CLOUD_ONLY). Kein Weg darf es mehr auslösen: Web/Fern, Kopplung, Regeln, Tastenbelegung, Launcher, HA/MQTT,
 * Cloud-Client, AutoService-Debug.
 */
public class AufsperrenEntferntTest {

    static List<Path> quellen() throws Exception {
        List<Path> l = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(l::add);
        }
        return l;
    }

    @Test
    public void keinEndpunktKeinBefehlKeinAufruf() throws Exception {
        List<String> treffer = new ArrayList<>();
        for (Path p : quellen()) {
            String s = Quelltext.lies(p);
            if (s.contains("equals(\"/api/vehicle/unlock\")")) treffer.add(p + ": Route");
            if (s.contains("return \"/api/vehicle/unlock\"")) treffer.add(p + ": Launcher");
            if (s.contains("\"POST\", \"/api/vehicle/unlock\"")) treffer.add(p + ": Regel-Aktion");
            if (s.contains("new VehicleCommandRouter.UnlockCommand") || s.contains("class UnlockCommand")) {
                treffer.add(p + ": UnlockCommand");
            }
            if (s.contains("unlockCar()")) treffer.add(p + ": AutoService unlockCar");
            if (s.contains("executeRemoteCommand(vin, \"OPENDOOR\"")) treffer.add(p + ": Cloud OPENDOOR");
            if (p.toString().contains("mqtt") && s.contains("return \"UNLOCK\";")) treffer.add(p + ": MQTT UNLOCK");
        }
        assertTrue("Aufsperren noch erreichbar: " + treffer, treffer.isEmpty());
    }

    @Test
    public void harteSperrenAlsZweiteLinie() throws Exception {
        // AutoService-Fernsteuerweg ist seit 6425b1c ganz entfernt (stärker als die frühere OP_UNLOCK-Sperre)
        String bridge = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/AutoServiceBridge.kt"));
        assertFalse(bridge.contains("fun remoteControl("));
        String cloud = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudClient.java"));
        assertTrue(cloud.contains("\"OPENDOOR\".equalsIgnoreCase(commandType)"));
        String dbg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AutoServiceDebugApiHandler.java"));
        assertFalse(dbg.contains("equals(\"/api/debug/autoservice/sdk-remote-control\")"));
        assertFalse(dbg.contains("equals(\"/api/debug/autoservice/remote-control\")"));
        // Cloud-Tor sperrt „unlock“ weiterhin
        assertFalse(at.greenchili.surfmate.byd.routing.CloudPowerGate.evaluate("unlock", 0, 0L, false, false, 0L).allowed);
    }

    @Test
    public void regelnUndKopplungBietenEsNichtAn() {
        assertNull(new at.greenchili.surfmate.automation.action.Actions().getAction("unlock"));
        for (String m : new String[] {"GET", "POST", "PUT"}) {
            assertFalse(ExternGuard.erlaubt(m, "/api/vehicle/unlock"));
        }
    }
}
