package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.7 (Handy-App-Befund): {@code fern.stufe} ist die Balkenstufe 1–17 (Mitte 9). Die Weboberfläche zeigt den Abstand zur
 * Mitte: Stufe 12 heißt „Heizen Stufe 3“, nicht „Heizen Stufe 12“. Der Test führt {@code PanoptesKlimaStufe.fernText} in Node
 * wirklich aus (ohne Node auf dem Rechner: übersprungen, dann bleibt der Quelltext-Test).
 */
public class FernKlimaStufeWebTest {

    private static Path js() {
        Path p = Paths.get("src/main/assets/web/shared/klima-stufe.js");
        return Files.exists(p) ? p : Paths.get("app/src/main/assets/web/shared/klima-stufe.js");
    }

    @Test
    public void quelltextRechnetMitAbstandZurMitte() throws Exception {
        String s = new String(Files.readAllBytes(js()), StandardCharsets.UTF_8);
        assertTrue(s.contains("Math.abs(f.stufe + VERSATZ - SDK_MITTE)"));
        assertTrue("nicht mehr die rohe Balkenstufe", !s.contains("{ n: f.stufe }"));
    }

    private static String node(String script) throws Exception {
        Process p = new ProcessBuilder("node", "-e", script).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        p.waitFor();
        return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    @Test
    public void stufeZwoelfImBrowserCodeIstHeizenStufeDrei() throws Exception {
        String probe;
        try {
            probe = node("console.log('ok')");
        } catch (Exception e) {
            probe = "";
        }
        Assume.assumeTrue("Node nicht vorhanden", "ok".equals(probe));
        String pfad = js().toAbsolutePath().toString().replace("\\", "\\\\");
        String script = "var fs=require('fs');var src=fs.readFileSync('" + pfad + "','utf8');"
                + "var win={fetch:undefined};new Function('window',src)(win);var K=win.PanoptesKlimaStufe;"
                + "function f(stufe,art){return K.fernText({laeuft:{zustand:'unbekannt'},fern:{aktiv:true,stufe:stufe,art:art,gestartet:1800000000000,bis:1800000600000}}).text;}"
                + "console.log(JSON.stringify({h12:f(12,'heizen'),k6:f(6,'kuehlen'),k1:f(1,'kuehlen'),h17:f(17,'heizen'),n9:f(9,'neutral'),h10:f(10,'heizen')}));";
        JSONObject r = new JSONObject(node(script));
        assertTrue(r.getString("h12"), r.getString("h12").contains("Heizen Stufe 3"));
        assertTrue(r.getString("h12"), !r.getString("h12").contains("Stufe 12"));
        assertTrue(r.getString("k6"), r.getString("k6").contains("Kühlen Stufe 3"));
        assertTrue(r.getString("k1"), r.getString("k1").contains("Kühlen Stufe 8"));
        assertTrue(r.getString("h17"), r.getString("h17").contains("Heizen Stufe 8"));
        assertTrue(r.getString("h10"), r.getString("h10").contains("Heizen Stufe 1"));
        assertTrue(r.getString("n9"), r.getString("n9").contains("Neutral"));
        assertEquals(true, r.getString("h12").contains("laut Befehl"));
    }
}
