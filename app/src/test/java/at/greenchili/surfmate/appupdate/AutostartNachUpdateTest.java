package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 0.1.21 Pflichtpunkt: Autostart nach dem In-App-Update einmal je Installation automatisch freigeben. */
public class AutostartNachUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    final List<String> befehle = new ArrayList<>();
    String antwortEnable = "Broadcasting: Intent { act=at.greenchili.surfmate.action.AUTOSTART_ENABLE flg=0x400000 }\n"
            + "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false\"\n";
    String antwortStatus = "Broadcast completed: result=0, data=\"autostartMissing=true\"\n";

    AutostartNachUpdate neu(File ok) {
        return new AutostartNachUpdate(ok, new File(tmp.getRoot(), "merk.json"), (cmd, t) -> {
            befehle.add(cmd);
            return cmd.contains("AUTOSTART_ENABLE") ? antwortEnable : antwortStatus;
        });
    }

    File bestaetigt(long zeit) throws Exception {
        File f = new File(tmp.getRoot(), ".update-ok");
        Files.write(f.toPath(), new byte[0]);
        assertTrue(f.setLastModified(zeit));
        return f;
    }

    @Test
    public void auswertenDerAmBroadcastAusgabe() throws Exception {
        JSONObject a = AutostartNachUpdate.auswerten(antwortEnable);
        assertTrue(a.getBoolean("frei"));
        assertEquals("SUCCESS", a.getString("ergebnis"));
        JSONObject b = AutostartNachUpdate.auswerten(
                "Broadcast completed: result=0, data=\"ergebnis=FLIP_UNCONFIRMED ok=false autostartMissing=true\"");
        assertFalse(b.getBoolean("frei"));
        assertEquals("FLIP_UNCONFIRMED", b.getString("ergebnis"));
        assertEquals("NO_SERVICE", AutostartNachUpdate.auswerten(
                "Broadcast completed: result=0, data=\"ergebnis=NO_SERVICE ok=false\"").getString("ergebnis"));
        JSONObject c = AutostartNachUpdate.auswerten("Broadcast completed: result=0");
        assertFalse("ohne Daten (App alt) = nicht frei", c.getBoolean("frei"));
        assertEquals("keine_antwort", AutostartNachUpdate.auswerten(null).getString("ergebnis"));
        assertEquals("keine_antwort", AutostartNachUpdate.auswerten("Error: Bad component").getString("ergebnis"));
        assertTrue("Status-Broadcast: frei aus autostartMissing=false", AutostartNachUpdate.auswerten(
                "Broadcast completed: result=1, data=\"autostartMissing=false\"").getBoolean("frei"));
    }

    @Test
    public void einmalJeBestaetigterInstallation() throws Exception {
        File ok = new File(tmp.getRoot(), ".update-ok");
        AutostartNachUpdate a = neu(ok);
        assertFalse("vor der Bestätigung nichts", a.pruefen(1000L));
        assertTrue(befehle.isEmpty());

        bestaetigt(1_800_000_000_000L);
        assertTrue(a.pruefen(2000L));
        assertEquals(1, befehle.size());
        assertTrue(befehle.get(0).startsWith("am broadcast -a at.greenchili.surfmate.action.AUTOSTART_ENABLE -n "
                + "at.greenchili.surfmate/.receiver.AutoStartCheckReceiver"));
        JSONObject z = a.zustand(3000L);
        assertTrue(z.getBoolean("frei"));
        assertEquals(2000L, z.getLong("zeit"));
        assertTrue(z.getBoolean("aktuell"));
        for (int i = 0; i < 5; i++) assertFalse("nicht bei jedem Durchlauf/Reconnect", a.pruefen(4000L + i));
        assertEquals(1, befehle.size());

        bestaetigt(1_800_000_600_000L);   // nächstes Update
        assertTrue(a.pruefen(5000L));
        assertEquals(2, befehle.size());
    }

    @Test
    public void nichtBereitWirdWiederholtEchterFehlschlagNicht() throws Exception {
        AutostartNachUpdate a = neu(bestaetigt(1_800_000_000_000L));
        antwortEnable = "Broadcast completed: result=0, data=\"ergebnis=NO_SERVICE ok=false\"";
        for (int i = 0; i < 10; i++) a.pruefen(1000L + i);
        assertEquals("höchstens " + AutostartNachUpdate.MAX_VERSUCHE, AutostartNachUpdate.MAX_VERSUCHE, befehle.size());

        befehle.clear();
        AutostartNachUpdate b = new AutostartNachUpdate(bestaetigt(1_800_000_900_000L),
                new File(tmp.getRoot(), "merk2.json"), (cmd, t) -> {
                    befehle.add(cmd);
                    return "Broadcast completed: result=0, data=\"ergebnis=FLIP_UNCONFIRMED ok=false autostartMissing=true\"";
                });
        for (int i = 0; i < 5; i++) b.pruefen(1000L + i);
        assertEquals("kein Dauerversuch: Knopf am Display übernimmt", 1, befehle.size());
        JSONObject z = b.zustand(2000L);
        assertFalse(z.getBoolean("frei"));
        assertEquals("FLIP_UNCONFIRMED", z.getString("ergebnis"));
    }

    @Test
    public void knopfUndLiveNachsehen() throws Exception {
        antwortEnable = "Broadcast completed: result=0, data=\"ergebnis=NO_ROW ok=false autostartMissing=true\"";
        AutostartNachUpdate a = neu(bestaetigt(1_800_000_000_000L));
        assertEquals("ohne Ergebnis: frei null", JSONObject.NULL, a.zustand(1L).get("frei"));
        a.pruefen(1000L);
        // Status live: weiter fehlt → bleibt false, höchstens einmal je Minute nachsehen
        befehle.clear();
        assertFalse(a.zustand(100_000L).getBoolean("frei"));
        assertFalse(a.zustand(100_500L).getBoolean("frei"));
        assertEquals(1, befehle.size());
        assertTrue(befehle.get(0).contains("AUTOSTART_STATUS"));
        // Knopf: Erfolg
        antwortEnable = "Broadcast completed: result=1, data=\"ergebnis=SUCCESS ok=true autostartMissing=false\"";
        JSONObject r = a.freigeben(200_000L);
        assertTrue(r.getBoolean("frei"));
        JSONObject z = a.zustand(200_100L);
        assertTrue(z.getBoolean("frei"));
        assertEquals("knopf", z.getString("quelle"));
        // Nutzer hat selbst im BYD-Dialog freigegeben: live erkannt
        AutostartNachUpdate b = new AutostartNachUpdate(bestaetigt(1_800_001_000_000L),
                new File(tmp.getRoot(), "merk3.json"), (cmd, t) -> cmd.contains("ENABLE")
                        ? "Broadcast completed: result=0, data=\"ergebnis=NO_DIALOG ok=false autostartMissing=true\""
                        : "Broadcast completed: result=1, data=\"autostartMissing=false\"");
        b.pruefen(1000L);
        assertTrue(b.zustand(90_000L).getBoolean("frei"));
        assertTrue(new String(Files.readAllBytes(new File(tmp.getRoot(), "merk3.json").toPath()), StandardCharsets.UTF_8)
                .contains("\"quelle\":\"live\""));
    }

    @Test
    public void knopfNurAmAutoUndKeinDauerDialog() throws Exception {
        String h = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/server/AppUpdateApiHandler.java");
        int i = h.indexOf("/api/appupdate/autostart/freigeben");
        assertTrue(h.substring(i, i + 400).contains("AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()"));
        String idx = at.greenchili.surfmate.server.WebHandyGuardTest.quelle("assets/web/local/index.html");
        assertTrue("Karte nur am Display", idx.contains("getAttribute('data-anzeige') !== 'auto'"));
        assertTrue(idx.contains("a.frei !== false || a.aktuell !== true"));
        String d = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/overlay/SetupGuideDialog.java");
        assertTrue(d.contains("if (!nurAutostartDialog(needs))"));
        String daemon = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("AutostartNachUpdate.starteWaechter()"));
    }
}
