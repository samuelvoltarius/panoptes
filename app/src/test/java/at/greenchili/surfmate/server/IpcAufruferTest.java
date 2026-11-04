package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 0.1.38.8 Paket A: Aufrufer-Prüfung der Wächter-IPC 19977. */
public class IpcAufruferTest {

    static final int APP = 10123;

    static IpcAufrufer.Quelle q(int uid, boolean tailscaled, int appUidAlt, int appUidNeu, boolean notschalter) {
        return new IpcAufrufer.Quelle() {
            @Override public int uid(int c, int s) { return uid; }
            @Override public boolean gehoertTailscaled(int c, int s) { return tailscaled; }
            @Override public int appUid(boolean neu) { return neu ? appUidNeu : appUidAlt; }
            @Override public boolean notschalter() { return notschalter; }
        };
    }

    private static String server() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java")), StandardCharsets.UTF_8);
    }

    @Test
    public void eigeneWerkzeugeBleibenDrin() {
        assertTrue("SentryDaemon/AccSentryDaemon", IpcAufrufer.vertraut(1, 19977, q(1000, false, APP, APP, false)));
        assertTrue("Kamera-/Telegram-Daemon, adb shell, Deploy", IpcAufrufer.vertraut(1, 19977, q(2000, false, APP, APP, false)));
        assertTrue("App", IpcAufrufer.vertraut(1, 19977, q(APP, false, APP, APP, false)));
        assertTrue("App neu installiert", IpcAufrufer.vertraut(1, 19977, q(10200, false, APP, 10200, false)));
    }

    @Test
    public void fremdeUndWeitergereichteDraussen() {
        assertFalse("tailscaled (Tailnet → 127.0.0.1)", IpcAufrufer.vertraut(1, 19977, q(2000, true, APP, APP, false)));
        assertFalse("fremde App", IpcAufrufer.vertraut(1, 19977, q(10999, false, APP, APP, false)));
        assertFalse("uid 0 / TIME_WAIT", IpcAufrufer.vertraut(1, 19977, q(0, false, APP, APP, false)));
        assertFalse("nicht gefunden", IpcAufrufer.vertraut(1, 19977, q(-1, false, APP, APP, false)));
        assertFalse("App-uid unbekannt", IpcAufrufer.vertraut(1, 19977, q(APP, false, -1, -1, false)));
        assertFalse("andere System-uid", IpcAufrufer.vertraut(1, 19977, q(1001, false, APP, APP, false)));
    }

    @Test
    public void notschalterOeffnet() {
        assertTrue(IpcAufrufer.vertraut(1, 19977, q(10999, true, APP, APP, true)));
    }

    @Test
    public void einstufung() {
        assertEquals(IpcAufrufer.Stufe.FREI, IpcAufrufer.stufe("UPDATE_GPS"));
        assertEquals(IpcAufrufer.Stufe.FREI, IpcAufrufer.stufe("IMU_BATCH"));
        assertEquals(IpcAufrufer.Stufe.FREI, IpcAufrufer.stufe("DETERRENT_INPUT_CAPTURE"));
        assertEquals(IpcAufrufer.Stufe.APP_SYSTEM, IpcAufrufer.stufe("AUTH_ZUGANG"));
        assertEquals(IpcAufrufer.Stufe.LOKAL, IpcAufrufer.stufe(""));
        assertEquals(IpcAufrufer.Stufe.LOKAL, IpcAufrufer.stufe("unbekannt"));
    }

    /** Jeder Befehl im Server ist eingestuft; frei sind nur die Sensor-Ströme (und die selbst geprüften Wege). */
    @Test
    public void alleBefehleDesServersSindGeprueft() throws Exception {
        String src = server();
        int a = src.indexOf("private JSONObject handleCommand(");
        Matcher m = Pattern.compile("\\n                case \"([A-Z0-9_]+)\"").matcher(src.substring(a));
        Set<String> frei = new TreeSet<>();
        int n = 0;
        while (m.find()) {
            n++;
            if (IpcAufrufer.stufe(m.group(1)) != IpcAufrufer.Stufe.LOKAL) frei.add(m.group(1));
        }
        assertTrue("Befehle gefunden: " + n, n > 50);
        assertEquals("[UPDATE_GPS]", frei.toString());   // IMU_BATCH steht als Konstante im switch
        for (String geheim : new String[] {"GET_CONFIG", "EXPORT_CONFIG", "REPLACE_CONFIG", "SET_CONFIG", "UPDATE_SECTION",
                "UPDATE_VALUES", "INSTALL_UPDATE", "SET_CHANNEL", "GET_ABRP_CONFIG", "GET_MQTT_CONNECTIONS", "UPLOAD_LOG",
                "DISABLE_SURVEILLANCE", "GET_SAFE_LOCATIONS", "GET_VEHICLE_DATA"}) {
            assertEquals(geheim, IpcAufrufer.Stufe.LOKAL, IpcAufrufer.stufe(geheim));
        }
    }

    @Test
    public void pruefungStehtVorJederAusfuehrung() throws Exception {
        String src = server();
        int pruef = src.indexOf("IpcAufrufer.stufe(befehl) == IpcAufrufer.Stufe.LOKAL");
        assertTrue(pruef > 0);
        assertTrue(src.indexOf("AuthZugangIpc.antwort(", pruef) > pruef);
        assertTrue(src.indexOf("handleDeterrentInputCapture(client", pruef) > pruef);
        assertTrue(src.indexOf("JSONObject response = handleCommand(request", pruef) > pruef);
        // einmal je Verbindung, nicht je Zeile
        assertTrue(src.contains("if (vertraut == null) vertraut = IpcAufrufer.vertraut(client.getPort(), client.getLocalPort());"));
    }

    @Test
    public void tailscaledErkennungFailClosed() {
        // kein passender Socket in der Tabelle → gilt als weitergereicht
        assertTrue(LoopbackPeer.gehoertTailscaled(1, 2));
    }
}
