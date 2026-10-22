package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Gegenprobe gegen tools/wallbox/tuya_simulator.py (Geräteseite, mit dem tinytuya-Client als Orakel geprüft).
 * Läuft nur mit {@code WALLBOX_TUYA_SIM="3.3:PORT,3.4:PORT,3.5:PORT"} (Simulatoren vorher starten); nie gegen echte Geräte.
 * Bericht optional nach {@code WALLBOX_TUYA_BERICHT}.
 */
public class WallboxTuyaSimTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void panoptesGegenSimulator_alleVersionen() throws Exception {
        String ziele = System.getenv("WALLBOX_TUYA_SIM");
        assumeTrue(ziele != null && !ziele.isEmpty());
        JSONArray bericht = new JSONArray();
        for (String teil : ziele.split(",")) {
            String v = teil.split(":")[0];
            int port = Integer.parseInt(teil.split(":")[1]);
            File k = new File(tmp.getRoot(), "wb-" + v + ".json");
            TuyaLan.Verbinder zumSim = (ip, p) -> {
                Socket s = new Socket("127.0.0.1", port);
                s.setSoTimeout(4000);
                return new TuyaLan.Kanal() {
                    @Override public InputStream ein() { try { return s.getInputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                    @Override public OutputStream aus() { try { return s.getOutputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                    @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
                };
            };
            WallboxDienst d = new WallboxDienst(k, new File(tmp.getRoot(), "p-" + v), WallboxTest.GEHEIM, WallboxHttp.STANDARD,
                    WallboxTest.DNS, () -> "steht", System::currentTimeMillis, WallboxQuelle.ECHT, zumSim, () -> null);
            assertEquals(200, d.speichereEinstellungen(WallboxTuyaTest.lanKonfig(v, WallboxTuyaTest.zuordnung(), null,
                    TuyaGeraetAttrappe.SCHLUESSEL)).status);
            JSONObject s = d.status().json;
            assertTrue(v + s, s.getBoolean("erreichbar"));
            JSONObject vorher = s.getJSONArray("ladepunkte").getJSONObject(0);
            WallboxTuyaTest.gibFrei(d, "tuya:garage");
            JSONObject r1 = WallboxTuyaTest.fuehreAus(d, "tuya:garage", "strom", 10);
            JSONObject r2 = WallboxTuyaTest.fuehreAus(d, "tuya:garage", "modus", "sofort");
            JSONObject r3 = WallboxTuyaTest.fuehreAus(d, "tuya:garage", "modus", "aus");
            bericht.put(new JSONObject().put("version", v).put("vorher", vorher)
                    .put("strom10", r1.getBoolean("uebernommen")).put("sofort", r2.getBoolean("uebernommen"))
                    .put("laedtNachSofort", r2.getJSONObject("ladepunkt").get("laedt"))
                    .put("leistungNachSofort", r2.getJSONObject("ladepunkt").get("leistungW"))
                    .put("aus", r3.getBoolean("uebernommen")));
            assertTrue(v, r1.getBoolean("uebernommen") && r2.getBoolean("uebernommen") && r3.getBoolean("uebernommen"));
            assertEquals(v, 2300, r2.getJSONObject("ladepunkt").getLong("leistungW"));
        }
        String ziel = System.getenv("WALLBOX_TUYA_BERICHT");
        if (ziel != null && !ziel.isEmpty()) Files.write(new File(ziel).toPath(), bericht.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
