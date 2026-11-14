package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.6 (E2E R1): Geteilte JS/CSS-Dateien werden mit `?v=` geladen; die Auto-WebView hält sie bis zu einer Stunde
 * (HttpServer: max-age 1 h, ohne 304). Ändert sich eine Datei ohne neuen Zähler, läuft das Auto mit altem Code.
 *
 * Festgehalten in src/test/resources/web-asset-staende.json (Zähler + SHA-256 je Datei, erzeugt mit
 * tools/web_asset_staende.py). Verlangt wird:
 *  1. alle Seiten nutzen pro Datei denselben Zähler,
 *  2. geänderter Inhalt ⇒ geänderter Zähler (gegenüber dem festgehaltenen Vorgängerstand),
 *  3. der festgehaltene Stand ist aktuell (nach dem Erhöhen: Skript laufen lassen).
 */
public class WebAssetVersionTest {
    private static final Pattern VERWEIS = Pattern.compile("([\\w.-]+\\.(?:js|css))\\?v=([\\w.-]+)");

    @Test
    public void jederZaehlerEinheitlichUndInhaltNurMitNeuemZaehler() throws Exception {
        File web = new File(basis(), "src/main/assets/web");
        Map<String, TreeSet<String>> zaehler = new TreeMap<>();
        Map<String, List<String>> wo = new TreeMap<>();
        for (String ordner : new String[] { "local", "shared" }) {
            for (File f : alle(new File(web, ordner))) {
                String n = f.getName();
                if (!n.endsWith(".html") && !n.endsWith(".js")) continue;
                Matcher m = VERWEIS.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                while (m.find()) {
                    zaehler.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
                    wo.computeIfAbsent(m.group(1) + "?v=" + m.group(2), k -> new ArrayList<>()).add(ordner + "/" + n);
                }
            }
        }
        JSONObject stand = new JSONObject(new String(Files.readAllBytes(
                new File(basis(), "src/test/resources/web-asset-staende.json").toPath()), StandardCharsets.UTF_8));
        List<String> fehler = new ArrayList<>();
        int geprueft = 0;
        for (Map.Entry<String, TreeSet<String>> e : zaehler.entrySet()) {
            String name = e.getKey();
            File datei = finde(new File(web, "shared"), name);
            if (datei == null) continue;   // nicht unter shared/ oder mehrdeutig
            geprueft++;
            if (e.getValue().size() != 1) {
                StringBuilder b = new StringBuilder(name + ": uneinheitliche Zähler");
                for (String v : e.getValue()) b.append(' ').append(v).append(wo.get(name + "?v=" + v));
                fehler.add(b.toString());
                continue;
            }
            String v = e.getValue().first();
            String sha = sha(datei);
            JSONObject alt = stand.optJSONObject(name);
            if (alt == null) { fehler.add(name + ": neu – Stand mit tools/web_asset_staende.py festhalten"); continue; }
            boolean inhaltNeu = !sha.equals(alt.optString("sha256"));
            boolean zaehlerNeu = !v.equals(alt.optString("v"));
            if (inhaltNeu && !zaehlerNeu) fehler.add(name + ": Inhalt geändert, ?v=" + v + " aber nicht – Zähler auf allen Seiten erhöhen");
            else if (inhaltNeu || zaehlerNeu) fehler.add(name + ": Stand veraltet – tools/web_asset_staende.py laufen lassen");
        }
        assertTrue("geprüfte Dateien: " + geprueft, geprueft > 80);
        if (!fehler.isEmpty()) fail(String.join("\n", fehler));
    }

    @Test
    public void festgehalteneZaehlerDerRunde01436() throws Exception {
        JSONObject stand = new JSONObject(new String(Files.readAllBytes(
                new File(basis(), "src/test/resources/web-asset-staende.json").toPath()), StandardCharsets.UTF_8));
        assertEquals("R1: app-shell.js wurde in 7e5e5506 geändert", "pn38", stand.getJSONObject("app-shell.js").getString("v"));
        assertEquals("14", stand.getJSONObject("theme.js").getString("v"));
        assertNotNull(stand.optJSONObject("stil-launcher.css"));
        String theme = new String(Files.readAllBytes(new File(basis(), "src/main/assets/web/shared/theme.js").toPath()), StandardCharsets.UTF_8);
        assertTrue("stil-launcher.css wird aus theme.js mit Zähler geladen", theme.contains("var STIL_CSS = 'stil-launcher.css?v=1';"));
    }

    private static List<File> alle(File d) {
        List<File> l = new ArrayList<>();
        File[] k = d.listFiles();
        if (k == null) return l;
        for (File f : k) { if (f.isDirectory()) l.addAll(alle(f)); else l.add(f); }
        return l;
    }

    private static File finde(File shared, String name) {
        File treffer = null;
        for (File f : alle(shared)) {
            if (!f.getName().equals(name)) continue;
            if (treffer != null) return null;
            treffer = f;
        }
        return treffer;
    }

    private static String sha(File f) throws Exception {
        byte[] roh = Files.readAllBytes(f.toPath());
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(roh.length);
        for (int i = 0; i < roh.length; i++) {
            if (roh[i] == '\r' && i + 1 < roh.length && roh[i + 1] == '\n') continue;
            o.write(roh[i]);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(o.toByteArray())) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static File basis() {
        return new File("src/main").isDirectory() ? new File(".") : new File("app");
    }
}
