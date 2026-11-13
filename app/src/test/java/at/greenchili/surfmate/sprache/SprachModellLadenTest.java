package at.greenchili.surfmate.sprache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 0.1.44 (P2b) Sprachmodell laden: nur auf Tipp, feste Quelle, feste SHA-256 (falsch = verworfen), Speicherplatz vorher,
 * Abbruch, Entpacken nur in den eigenen Ordner. Ohne Tipp geht nichts ins Netz.
 */
public class SprachModellLadenTest {

    private File ordner;
    private final List<String> aufrufe = new ArrayList<>();
    private byte[] liefern;
    private SprachModellLaden.Netz netzVorher;
    private SprachModellLaden.Platz platzVorher;
    private File basisVorher;

    @Before
    public void auf() throws Exception {
        ordner = Files.createTempDirectory("sprachmodell").toFile();
        netzVorher = SprachModellLaden.netz;
        platzVorher = SprachModellLaden.platz;
        basisVorher = SprachModellLaden.basis;
        SprachModellLaden.basis = ordner;
        SprachModellLaden.platz = f -> Long.MAX_VALUE;
        SprachModellLaden.netz = url -> { aufrufe.add(url); return new ByteArrayInputStream(liefern); };
    }

    @After
    public void ab() {
        SprachModellLaden.netz = netzVorher;
        SprachModellLaden.platz = platzVorher;
        SprachModellLaden.basis = basisVorher;
        SprachModellLaden.loeschen(ordner);
    }

    private static byte[] zip(String... namenUndInhalt) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (int i = 0; i < namenUndInhalt.length; i += 2) {
                z.putNextEntry(new ZipEntry(namenUndInhalt[i]));
                z.write(namenUndInhalt[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    private static String sha(byte[] d) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    private SprachModellLaden.Modell modell(byte[] inhalt, String sha) {
        return new SprachModellLaden.Modell("de", "test-modell", sha, inhalt.length, 10_000, "Apache-2.0");
    }

    private byte[] gutesZip() throws Exception {
        return zip("test-modell/am/final.mdl", "AM", "test-modell/conf/model.conf", "CONF", "test-modell/COPYING", "Apache");
    }

    private String[] rest() {
        String[] r = ordner.list();
        return r == null ? new String[0] : r;
    }

    @Test
    public void festeQuelleUndPruefsummen() {
        assertEquals("https://alphacephei.com/vosk/models/", SprachModellLaden.QUELLE);
        SprachModellLaden.Modell de = SprachModellLaden.MODELLE.get("de"), en = SprachModellLaden.MODELLE.get("en");
        assertEquals("https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", de.url);
        assertEquals("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", en.url);
        assertEquals("b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43", de.sha256);
        assertTrue("gleich wie Weckwort-Messung Stufe 1 (b7e53c90…5e43)", de.sha256.startsWith("b7e53c90") && de.sha256.endsWith("5e43"));
        assertEquals("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", en.sha256);
        assertEquals(46_499_967L, de.zipBytes);
        assertEquals(41_205_931L, en.zipBytes);
        assertEquals("Modellnamen wie die Erkennung", SprachBefehle.MODELL.get("de"), de.name);
        assertEquals(SprachBefehle.MODELL.get("en"), en.name);
        assertEquals(SprachBefehle.MODELL.keySet(), SprachModellLaden.MODELLE.keySet());
    }

    @Test
    public void pruefsummeFalschWirdVerworfen() throws Exception {
        liefern = gutesZip();
        SprachModellLaden.Modell m = modell(liefern, sha("etwas anderes".getBytes(StandardCharsets.UTF_8)));
        assertEquals("fehler", SprachModellLaden.laden(m, new AtomicBoolean(false)));
        assertEquals("pruefsumme", SprachModellLaden.stand().getJSONObject("laden").getString("grund"));
        assertFalse(SprachModellLaden.da(m));
        assertEquals("nichts bleibt liegen: " + String.join(",", rest()), 0, rest().length);
    }

    @Test
    public void richtigeDateiWirdInstalliert() throws Exception {
        liefern = gutesZip();
        SprachModellLaden.Modell m = modell(liefern, sha(liefern));
        assertEquals("fertig", SprachModellLaden.laden(m, new AtomicBoolean(false)));
        assertTrue(SprachModellLaden.da(m));
        assertEquals(java.util.Collections.singletonList("https://alphacephei.com/vosk/models/test-modell.zip"), aufrufe);
        assertEquals("nur der Modellordner bleibt", 1, rest().length);
        // Schon da (auch von Hand abgelegt): kein zweites Laden, nie überschreiben
        assertEquals("schon_da", SprachModellLaden.vorpruefen(m));
    }

    @Test
    public void zuGrossOderZuKleinWirdVerworfen() throws Exception {
        liefern = gutesZip();
        SprachModellLaden.Modell m = new SprachModellLaden.Modell("de", "test-modell", sha(liefern), liefern.length - 5, 10_000, "x");
        assertEquals("fehler", SprachModellLaden.laden(m, new AtomicBoolean(false)));
        assertEquals("groesse", SprachModellLaden.stand().getJSONObject("laden").getString("grund"));
        assertEquals(0, rest().length);
    }

    @Test
    public void entpackenNurInDenEigenenOrdner() throws Exception {
        for (byte[] boese : new byte[][] {zip("test-modell/../../ausbruch.txt", "x"), zip("anderer-ordner/a.txt", "x"),
                zip("/abs/a.txt", "x")}) {
            liefern = boese;
            SprachModellLaden.Modell m = modell(boese, sha(boese));
            assertEquals("fehler", SprachModellLaden.laden(m, new AtomicBoolean(false)));
            assertEquals("entpacken", SprachModellLaden.stand().getJSONObject("laden").getString("grund"));
            assertEquals(0, rest().length);
            assertFalse(new File(ordner.getParentFile(), "ausbruch.txt").exists());
        }
    }

    @Test
    public void abbruchLaesstNichtsLiegen() throws Exception {
        liefern = gutesZip();
        SprachModellLaden.Modell m = modell(liefern, sha(liefern));
        assertEquals("abgebrochen", SprachModellLaden.laden(m, new AtomicBoolean(true)));
        assertFalse(SprachModellLaden.da(m));
        assertEquals(0, rest().length);
    }

    @Test
    public void zuWenigPlatzOhneNetzUndOrdnerGrund() throws Exception {
        SprachModellLaden.platz = f -> 10_000_000L;
        assertEquals("kein_platz", SprachModellLaden.vorpruefen(SprachModellLaden.MODELLE.get("de")));
        assertEquals(400, SprachModellLaden.RESERVE / 125_000);   // 50 MB Reserve
        assertTrue("kein Netz ohne Platz", aufrufe.isEmpty());
        // 0.1.43.2 (Fix E2E G1): Ordner nicht anlegbar = eigener Grund, nie „Internet?“
        SprachModellLaden.basis = new File(new File(ordner, "datei-statt-ordner.txt"), "sprache");
        Files.write(new File(ordner, "datei-statt-ordner.txt").toPath(), new byte[] {1});
        assertEquals("ordner", SprachModellLaden.vorpruefen(SprachModellLaden.MODELLE.get("de")));
        assertTrue(aufrufe.isEmpty());
        SprachModellLaden.basis = ordner;
    }

    @Test
    public void ohneTippKeinNetz() throws Exception {
        JSONObject s = SprachModellLaden.stand();
        assertTrue(s.getJSONObject("modelle").has("de") && s.getJSONObject("modelle").has("en"));
        assertFalse(s.getJSONObject("modelle").getJSONObject("de").getBoolean("da"));
        assertTrue("Stand lesen geht nie ins Netz", aufrufe.isEmpty());
        File wurzel = new File("src/main/java");
        if (!wurzel.isDirectory()) wurzel = new File("app/src/main/java");
        // Laden (Netz) nur im App-Dienst, und dort nur auf die Aktion „laden“
        assertEquals(java.util.Collections.singletonList("SprachModellDienst.java"), sammeln(wurzel, "SprachModellLaden.laden("));
        String dienst = lies(new File(wurzel, "at/greenchili/surfmate/sprache/SprachModellDienst.java"));
        assertTrue(dienst.contains("} else if (\"laden\".equals(aktion) && !LAEUFT.get()) {"));
        // Den Dienst mit „laden“ startet nur der Kern, und den nur POST {aktion:"laden"}
        assertEquals(java.util.Collections.singletonList("SprachModellKern.java"), sammeln(wurzel, "--es aktion laden"));
        assertEquals(java.util.Collections.singletonList("SprachApiHandler.java"), sammeln(wurzel, "SprachModellKern.starten("));
        String h = lies(new File(wurzel, "at/greenchili/surfmate/server/SprachApiHandler.java"));
        int laden = h.indexOf("if (!\"laden\".equals(aktion)) return new Object[]{400,");
        assertTrue(laden > 0 && laden < h.indexOf("SprachModellKern.starten(in.optString(\"sprache\", \"\"), in.optString(\"quelle\", \"netz\"), jetzt)"));
        assertTrue("GET liest nur", h.contains("if (\"GET\".equals(method)) return new Object[]{200, SprachModellKern.stand(jetzt)};"));
        // Seite: beim Öffnen nur GET; POST laden nur aus den Knöpfen
        File web = new File("src/main/assets/web/shared/sprachmodell.js");
        if (!web.isFile()) web = new File("app/src/main/assets/web/shared/sprachmodell.js");
        String js = new String(Files.readAllBytes(web.toPath()), StandardCharsets.UTF_8);
        assertEquals(1, js.split("aktion: 'laden'", -1).length - 1);
        assertTrue(js.contains("b.onclick = function () { laden(sp, m); };"));
        assertTrue(js.contains("h.onclick = function () { laden(sp, m, 'hand'); };"));
        for (String v : new String[] {"=>", "let ", "const ", "`", "?.", "??", "inset:", "gap:"}) assertFalse("ES5: " + v, js.contains(v));
    }

    private static String lies(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> sammeln(File wurzel, String text) throws Exception {
        List<String> treffer = new ArrayList<>();
        sammeln(wurzel, text, treffer);
        java.util.Collections.sort(treffer);
        return treffer;
    }

    private static void sammeln(File f, String text, List<String> treffer) throws Exception {
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) sammeln(x, text, treffer); return; }
        if (!f.getName().endsWith(".java") && !f.getName().endsWith(".kt")) return;
        if (f.getName().equals("SprachModellLaden.java")) return;
        if (new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(text)) treffer.add(f.getName());
    }

    @Test
    public void lizenzEhrlichEingetragen() throws Exception {
        File n = new File("../THIRD_PARTY_NOTICES.md");
        if (!n.isFile()) n = new File("THIRD_PARTY_NOTICES.md");
        String s = new String(Files.readAllBytes(n.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"));
        assertTrue(s.contains("The archive itself contains **no** `COPYING`/license file"));
        assertNull(null);
    }
}
