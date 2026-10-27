package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.handy.HandySignatur;
import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Aufsperren: 0.1.9 komplett entfernt (Projektinhaber „nie“). 0.1.18 (27.10.: „es soll aufsperren können“,
 * Koordination): Aufsperren gibt es wieder – aber NUR über die Handy-App mit gültiger Biometrie-Signatur
 * ({@code /api/handy/v1/aktion}, HandyApiHandler → HandySignatur.Freigabe → HandyUnlockCommand →
 * BydCloudClient.aufsperrenSigniert, Cloud nur hinter CloudPowerGate). Alle anderen Wege bleiben verboten:
 * Web/Fern ({@code /api/vehicle/unlock}), Kopplung (Cockpit/HA), Regeln/Automationen, Tastenbelegung, Launcher,
 * HA/MQTT, Telegram, Cloud-Client direkt, AutoService-Debug.
 */
public class AufsperrenEntferntTest {

    static List<Path> quellen() throws Exception {
        List<Path> l = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt")).forEach(l::add);
        }
        return l;
    }

    private static String name(Path p) {
        return p.getFileName().toString();
    }

    @Test
    public void keinAndererWegAlsHandyMitSignatur() throws Exception {
        List<String> treffer = new ArrayList<>();
        for (Path p : quellen()) {
            String s = Quelltext.lies(p);
            String n = name(p);
            if (s.contains("equals(\"/api/vehicle/unlock\")")) treffer.add(p + ": Route");
            if (s.contains("return \"/api/vehicle/unlock\"")) treffer.add(p + ": Launcher");
            if (s.contains("\"POST\", \"/api/vehicle/unlock\"")) treffer.add(p + ": Regel-Aktion");
            if (s.contains("new VehicleCommandRouter.UnlockCommand") || s.contains("class UnlockCommand")) {
                treffer.add(p + ": allgemeiner UnlockCommand");
            }
            if (s.contains("unlockCar()")) treffer.add(p + ": AutoService unlockCar");
            if (s.contains("executeRemoteCommand(vin, \"OPENDOOR\"")) treffer.add(p + ": Cloud OPENDOOR direkt");
            if (p.toString().contains("mqtt") && s.contains("return \"UNLOCK\";")) treffer.add(p + ": MQTT UNLOCK");
            // Der Handy-Befehl darf NUR im HandyApiHandler gebaut werden (nach der Signaturprüfung)
            if (s.contains("new VehicleCommandRouter.HandyUnlockCommand(") || s.contains("new HandyUnlockCommand(")) {
                if (!n.equals("HandyApiHandler.java")) treffer.add(p + ": HandyUnlockCommand außerhalb des Handy-Wegs");
            }
            // Der einzige OPENDOOR-Einstieg darf NUR vom HandyUnlockCommand gerufen werden
            if (s.contains(".aufsperrenSigniert(") && !n.equals("VehicleCommandRouter.java")) {
                treffer.add(p + ": aufsperrenSigniert außerhalb des Routers");
            }
            if (s.contains("\"OPENDOOR\"") && !n.equals("BydCloudClient.java")) treffer.add(p + ": OPENDOOR-Literal");
        }
        assertTrue("Aufsperren außerhalb des Handy-Wegs erreichbar: " + treffer, treffer.isEmpty());
    }

    @Test
    public void freigabeNurAusDerSignaturpruefung() {
        // kein öffentlicher Konstruktor: eine Freigabe gibt es nur aus HandySignatur.pruefeAufsperren
        assertEquals(0, HandySignatur.Freigabe.class.getConstructors().length);
        try {
            new VehicleCommandRouter.HandyUnlockCommand(null);
            fail("HandyUnlockCommand ohne Freigabe gebaut");
        } catch (IllegalArgumentException erwartet) {
            // ok
        }
    }

    @Test
    public void harteSperrenAlsZweiteLinie() throws Exception {
        String bridge = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/AutoServiceBridge.kt"));
        assertFalse(bridge.contains("fun remoteControl("));
        assertFalse(bridge.contains("TX_REMOTE_CONTROL"));
        String cloud = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/cloud/BydCloudClient.java"));
        // OPENDOOR für jeden Aufrufer ohne Handy-Freigabe verweigert
        assertTrue(cloud.contains("if (\"OPENDOOR\".equalsIgnoreCase(commandType) && !handyFreigabe) {"));
        int a = cloud.indexOf("public CloudCommandResult aufsperrenSigniert(");
        String rumpf = cloud.substring(a, cloud.indexOf("\n    }\n", a));
        assertTrue(rumpf.contains("if (freigabe == null || !freigabe.frisch(System.currentTimeMillis())) {"));
        assertTrue(cloud.contains("executeRemoteCommandIntern(vin, commandType, extraParams, waitForResult, false);"));
        String dbg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/AutoServiceDebugApiHandler.java"));
        assertFalse(dbg.contains("equals(\"/api/debug/autoservice/sdk-remote-control\")"));
        assertFalse(dbg.contains("equals(\"/api/debug/autoservice/remote-control\")"));
    }

    @Test
    public void cloudTorNurFuerDenHandyBefehlUndNurBeiAutoAus() {
        long t = 10_000_000L;
        // allgemeines „unlock“ (und jede andere Schreibweise) nie – auch nicht bei frisch belegtem „Auto aus“
        for (String n : new String[]{"unlock", "UNLOCK", " unlock ", "unlock-door", "door-unlock", "unlock-handy-x"}) {
            assertFalse(n, CloudPowerGate.evaluate(n, 0, t - 1_000, false, false, t).allowed);
        }
        // der Handy-Befehl: nur bei frischem power_level 0 und ACC nicht an
        assertTrue(CloudPowerGate.evaluate(VehicleCommandRouter.HandyUnlockCommand.NAME, 0, t - 1_000, true, false, t)
                .allowed);
        assertFalse("Auto an", CloudPowerGate.evaluate(VehicleCommandRouter.HandyUnlockCommand.NAME, 0, t - 1_000,
                true, true, t).allowed);
        assertFalse("power_level 2", CloudPowerGate.evaluate(VehicleCommandRouter.HandyUnlockCommand.NAME, 2,
                t - 1_000, false, false, t).allowed);
        assertFalse("unbekannt", CloudPowerGate.evaluate(VehicleCommandRouter.HandyUnlockCommand.NAME, 0, 0L,
                false, false, t).allowed);
        assertFalse("veraltet", CloudPowerGate.evaluate(VehicleCommandRouter.HandyUnlockCommand.NAME, 0,
                t - CloudPowerGate.MAX_AGE_MS - 1, false, false, t).allowed);
    }

    @Test
    public void regelnUndKopplungBietenEsNichtAn() {
        assertNull(new at.greenchili.surfmate.automation.action.Actions().getAction("unlock"));
        for (String m : new String[]{"GET", "POST", "PUT"}) {
            assertFalse(ExternGuard.erlaubt(m, "/api/vehicle/unlock"));
            for (String art : new String[]{null, "cockpit", "homeassistant", "handy"}) {
                assertTrue(m + " " + art, ExternGuard.blocks(m, "/api/vehicle/unlock", art));
            }
        }
        // Handy-Aktion nur für Handy-Kopplungen, nur POST
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/aktion", "handy"));
        for (String art : new String[]{null, "cockpit", "homeassistant", "HANDY", ""}) {
            assertTrue(String.valueOf(art), ExternGuard.blocks("POST", "/api/handy/v1/aktion", art));
        }
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/aktion", "handy"));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1/../vehicle/unlock", "handy"));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1//aktion", "handy"));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1/aktion/x", "handy"));
    }
}
