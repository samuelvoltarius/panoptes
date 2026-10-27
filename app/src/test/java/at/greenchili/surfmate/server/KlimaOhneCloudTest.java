package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter.CommandResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.20 P5 (Koordination): „Klima an“ bei ausgeschaltetem Auto ohne BYD-Cloud ist kein „vom Auto abgelehnt“, sondern
 * „Auto aus – BYD-Cloud einrichten“, mit Weg dorthin. Gilt für Display (routedResponse), /api/handy/v1 (Grund
 * cloud_nicht_eingerichtet, damit auch Cockpit), Automationen/Telegram (displayMessage).
 */
public class KlimaOhneCloudTest {

    HandyApiHandler.Umgebung vorher = HandyApiHandler.umgebung;

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void routerSagtCloudEinrichtenStattAbgelehnt() throws Exception {
        String src = lies("src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        int a = src.indexOf("private CommandResult runSdkFirst(VehicleCommand cmd)");
        int b = src.indexOf("CloudCallResult cr = runCloudCall(cmd);", a);
        String sdkFirst = src.substring(a, b);
        int frage = sdkFirst.indexOf("if (autoAusOhneCloud(sdkLeg))");
        int abgelehnt = sdkFirst.lastIndexOf("msg(\"local_failed\")");
        assertTrue("Auto-aus-Prüfung vor „local_failed“ im Zweig ohne Cloud", frage > 0 && frage < abgelehnt);
        assertTrue(sdkFirst.contains("CommandResult.cloudNichtEingerichtet(msg(autoAusSchluessel(cmd))"));
        // Auto an (wach/ACC) bleibt „local_failed“: Prüfung nur über isVehicleOccupiedOrAwake
        int h = src.indexOf("private boolean autoAusOhneCloud(SdkLeg leg)");
        String hilfe = src.substring(h, src.indexOf("}", src.indexOf("return !isVehicleOccupiedOrAwake();", h)));
        assertTrue(hilfe.contains("leg.blocked"));
        assertEquals("car_off_climate_needs_cloud", VehicleCommandRouter.autoAusSchluessel(new VehicleCommandRouter.ClimateOnCommand(22, 20)));
        assertEquals("car_off_needs_cloud", VehicleCommandRouter.autoAusSchluessel(new VehicleCommandRouter.ClimateOffCommand()));
    }

    @Test
    public void displayBekommtGrundUndWeg() throws Exception {
        CommandResult r = CommandResult.cloudNichtEingerichtet("Das Auto ist aus …", 12);
        assertEquals(VehicleCommandRouter.Outcome.AUTH_REQUIRED, r.outcome);
        JSONObject j = VehicleControlApiHandler.routedResponse(r, "power_on");
        assertFalse(j.getBoolean("success"));
        assertEquals("cloud_nicht_eingerichtet", j.getString("grund"));
        assertEquals("/byd-cloud.html", j.getString("einrichten"));
        assertEquals("Das Auto ist aus …", j.getString("message"));
        // andere Fehler unverändert ohne Grund
        JSONObject alt = VehicleControlApiHandler.routedResponse(CommandResult.authRequired("x"), "power_on");
        assertFalse(alt.has("grund"));
        assertFalse(alt.has("einrichten"));
    }

    @Test
    public void handyUndCockpitBekommenEigenenGrund() throws Exception {
        JSONArray[] g = {new JSONArray()};
        Kopplungen k = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return g[0]; }
            public boolean speichere(JSONArray n) { g[0] = n; return true; }
        });
        HandyApiHandler.kopplungen = k;
        final CommandResult[] antwort = {CommandResult.cloudNichtEingerichtet("Das Auto ist aus – direkt geht Vorheizen dann nicht.", 5)};
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) { return antwort[0]; }
            public CloudPowerGate.Decision gate(String befehl) { return CloudPowerGate.evaluate(befehl, 0, 0, true, false, 1); }
            public int echo(String aktion) { return 0; }
            public void telegram(String key, Object... args) { }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "x"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return 1_800_000_000_000L; }
        };
        String kid = k.anlegen("Pixel", Kopplungen.ART_HANDY, 1_800_000_000_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandyApiHandler.handle("POST", "/api/handy/v1/aktion",
                new JSONObject().put("aktion", "klima_an").put("tempC", 22).put("minuten", 20).toString(), out, kid);
        String roh = new String(out.toByteArray(), StandardCharsets.UTF_8);
        JSONObject j = new JSONObject(roh.substring(roh.indexOf("\r\n\r\n") + 4));
        assertFalse(j.getBoolean("ok"));
        assertEquals("cloud_nicht_eingerichtet", j.getString("grund"));
        assertTrue(j.getString("text").startsWith("Das Auto ist aus"));
        assertEquals("/byd-cloud.html", j.getString("einrichten"));
        // klassisches „kein Konto“ bleibt beim alten Grund
        antwort[0] = CommandResult.authRequired("Verbinde …");
        out.reset();
        HandyApiHandler.handle("POST", "/api/handy/v1/aktion", new JSONObject().put("aktion", "klima_aus").toString(), out, kid);
        roh = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("kein_cloud_konto", new JSONObject(roh.substring(roh.indexOf("\r\n\r\n") + 4)).getString("grund"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("vehicle_control");
            assertFalse(l, s.optString("car_off_climate_needs_cloud").isEmpty());
            assertFalse(l, s.optString("car_off_needs_cloud").isEmpty());
            JSONObject w = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("home");
            assertFalse(l, w.optString("setup_cloud_q").isEmpty());
        }
    }
}
