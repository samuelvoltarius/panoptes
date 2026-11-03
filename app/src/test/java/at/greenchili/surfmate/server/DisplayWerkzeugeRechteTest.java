package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.genai.KiSperre;
import at.greenchili.surfmate.overlay.HintergrundRegel;
import at.greenchili.surfmate.storage.Wechselspeicher;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * 0.1.37 Display-Werkzeuge: KI, Automationen, Kopplungen und Handy-Web kommen an keine neue Aktion; folgenreiche
 * Schritte brauchen die Rückfrage-Marke; das Hintergrundbild nimmt nur echte Bilder bis 20 MB.
 */
public class DisplayWerkzeugeRechteTest {

    static final String[] NEUE_AKTIONEN = {
            "/api/display/hintergrund/start", "/api/display/hintergrund/teil", "/api/display/hintergrund/fertig",
            "/api/display/hintergrund/abbrechen", "/api/display/hintergrund/standard",
            "/api/display/sperre/an", "/api/display/sperre/aus", "/api/display/sperre/entsperren",
            "/api/apps/verwaltung/frage", "/api/apps/verwaltung/deaktivieren", "/api/apps/verwaltung/aktivieren",
            "/api/dateien/wechsel/frage", "/api/dateien/wechsel/loeschen", "/api/dateien/wechsel/kopieren",
            "/api/dateien/wechsel/holen", "/api/dateien/wechsel/liste"};

    @Test
    public void kiErlaubnislisteUndAutomationenSperrenAlleNeuenAktionen() throws Exception {
        Method erlaubt = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        erlaubt.setAccessible(true);
        for (String p : NEUE_AKTIONEN) {
            assertFalse("Automation/KI erreicht " + p, (Boolean) erlaubt.invoke(null, p));
            assertFalse("Automation/KI erreicht " + p + "?x", (Boolean) erlaubt.invoke(null, p + "?x=1"));
        }
        assertFalse((Boolean) erlaubt.invoke(null, "/api/display"));
        assertFalse((Boolean) erlaubt.invoke(null, "/api/apps/verwaltung"));
        // Die KI darf nur ihre Erlaubnisliste – keine Aktionsart, die Routen frei ansprechen könnte
        for (String typ : new String[]{"api", "apiaction", "openapp", "launchapp", "shell", "display", "wallpaper",
                "valet", "displaylock", "appdisable", "disableapp", "files", "deletefile"}) {
            assertFalse(typ, KiSperre.erlaubterTyp(typ));
        }
    }

    @Test
    public void kopplungenUndHandyWebKommenNichtRan() {
        for (String p : NEUE_AKTIONEN) {
            assertTrue("Kopplung handy " + p, ExternGuard.blocks("POST", p, "handy"));
            assertTrue("Kopplung dienst " + p, ExternGuard.blocks("POST", p, "dienst"));
            assertTrue("Kopplung " + p, ExternGuard.blocks("POST", p));
        }
        assertTrue(ExternGuard.blocks("GET", "/api/display", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/apps/verwaltung", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/dateien/wechsel", "handy"));
        for (String p : new String[]{"/api/display", "/api/display/sperre/an", "/api/display/hintergrund/start",
                "/api/apps/verwaltung", "/api/apps/verwaltung/deaktivieren", "/api/apps/verwaltung/aktivieren"}) {
            assertTrue("Handy-Web " + p, WebHandyGuard.blocks("POST", p));
            assertFalse("auch nicht mit Recht dateien " + p, WebHandyGuard.dateienErlaubt(p, true));
        }
        // SD/USB nur mit Recht „dateien“, wie die Ablage
        assertTrue(WebHandyGuard.blocks("POST", "/api/dateien/wechsel/liste"));
        assertFalse(WebHandyGuard.dateienErlaubt("/api/dateien/wechsel/liste", false));
        assertTrue(WebHandyGuard.dateienErlaubt("/api/dateien/wechsel/liste", true));
    }

    @Test
    public void befehlsLogNimmtJedeNeueAktionAlsPflicht() {
        for (String p : NEUE_AKTIONEN) {
            boolean pflicht = false;
            for (String q : BefehlsLog.PFLICHT_PFADE) if (p.startsWith(q)) pflicht = true;
            assertTrue(p, pflicht);
        }
    }

    @Test
    public void rueckfrageGiltEinmalNurFuerDasselbeZiel() {
        Rueckfrage r = new Rueckfrage();
        String m = r.frage("deaktivieren", "com.a.b", 1000);
        assertFalse(r.bestaetigt(m, "deaktivieren", "com.x.y", 1001));
        assertFalse("verbraucht", r.bestaetigt(m, "deaktivieren", "com.a.b", 1002));
        String m2 = r.frage("deaktivieren", "com.a.b", 1000);
        assertFalse(r.bestaetigt(m2, "loeschen", "com.a.b", 1001));
        String m3 = r.frage("loeschen", "usb:a.txt", 1000);
        assertFalse("abgelaufen", r.bestaetigt(m3, "loeschen", "usb:a.txt", 1000 + Rueckfrage.GILT_MS + 1));
        String m4 = r.frage("loeschen", "usb:a.txt", 1000);
        assertFalse(r.bestaetigt(null, "loeschen", "usb:a.txt", 1001));
        assertFalse(r.bestaetigt("", "loeschen", "usb:a.txt", 1001));
        assertTrue(r.bestaetigt(m4, "loeschen", "usb:a.txt", 1001));
    }

    @Test
    public void loeschenAufUsbOhneRueckfrageWirdAbgelehnt() throws Exception {
        Path tmp = Files.createTempDirectory("wechselapi");
        Path f = tmp.resolve("brief.txt");
        Files.write(f, "x".getBytes(StandardCharsets.UTF_8));
        try {
            DateienApiHandler.setzeWechsel(new Wechselspeicher(
                    Collections.singletonMap(Wechselspeicher.USB, tmp.toFile())));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DateienApiHandler.handle("POST", "/api/dateien/wechsel/loeschen",
                    "{\"speicher\":\"usb\",\"pfad\":\"brief.txt\"}", out);
            assertTrue(out.toString("UTF-8"), out.toString("UTF-8").contains("rueckfrage"));
            assertTrue(Files.exists(f));
            // mit Marke für eine ANDERE Datei: auch nicht
            String fremd = DateienApiHandler.RUECKFRAGE.frage("loeschen",
                    DateienApiHandler.wechselZiel("loeschen", "usb", "anders.txt", ""), System.currentTimeMillis());
            out.reset();
            DateienApiHandler.handle("POST", "/api/dateien/wechsel/loeschen",
                    "{\"speicher\":\"usb\",\"pfad\":\"brief.txt\",\"marke\":\"" + fremd + "\"}", out);
            assertTrue(Files.exists(f));
            // richtige Marke: genau diese eine Datei
            String marke = DateienApiHandler.RUECKFRAGE.frage("loeschen",
                    DateienApiHandler.wechselZiel("loeschen", "usb", "brief.txt", ""), System.currentTimeMillis());
            out.reset();
            DateienApiHandler.handle("POST", "/api/dateien/wechsel/loeschen",
                    "{\"speicher\":\"usb\",\"pfad\":\"brief.txt\",\"marke\":\"" + marke + "\"}", out);
            assertTrue(out.toString("UTF-8"), out.toString("UTF-8").contains("\"ok\":true"));
            assertFalse(Files.exists(f));
        } finally {
            DateienApiHandler.setzeWechsel(null);
            Files.deleteIfExists(f);
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void appDeaktivierenOhneMarkeWirdAbgelehnt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        at.greenchili.surfmate.launcher.AppSperre.Shell vorher = AppsApiHandler.shell;
        final boolean[] pm = {false};
        try {
            AppsApiHandler.shell = argv -> {
                if (argv.contains("disable-user")) pm[0] = true;
                return "package:com.spotify.music\n";
            };
            AppsApiHandler.handle("POST", "/api/apps/verwaltung/deaktivieren", "{\"package\":\"com.spotify.music\"}", out);
            assertTrue(out.toString("UTF-8"), out.toString("UTF-8").contains("rueckfrage"));
            assertFalse("ohne Rückfrage kein pm-Befehl", pm[0]);
        } finally {
            AppsApiHandler.shell = vorher;
        }
    }

    @Test
    public void hintergrundNurEchteBilderBis20Mb() {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        byte[] html = "<html><script>".getBytes(StandardCharsets.UTF_8);
        assertEquals(null, HintergrundRegel.grundNachher("a.JPG", 100, jpg));
        assertEquals(null, HintergrundRegel.grundNachher("a.jpeg", 100, jpg));
        assertEquals(null, HintergrundRegel.grundNachher("a.png", 100, png));
        assertEquals(null, HintergrundRegel.grundNachher("a.webp", 100, webp));
        assertEquals("art", HintergrundRegel.grundNachher("a.png", 100, jpg));
        assertEquals("art", HintergrundRegel.grundNachher("a.jpg", 100, html));
        assertEquals("art", HintergrundRegel.grundVorher("a.mp4", 100));
        assertEquals("art", HintergrundRegel.grundVorher("a.svg", 100));
        assertEquals("art", HintergrundRegel.grundVorher("a.apk", 100));
        assertEquals("zu_gross", HintergrundRegel.grundVorher("a.jpg", 20L * 1024 * 1024 + 1));
        assertEquals(null, HintergrundRegel.grundVorher("a.jpg", 20L * 1024 * 1024));
        assertEquals("leer", HintergrundRegel.grundVorher("a.jpg", 0));
        assertFalse("Video-Hintergrund nicht belegt", HintergrundRegel.VIDEO_BELEGT);
    }

    @Test
    public void hintergrundUploadPrueftInhaltVorDemSetzen() throws Exception {
        Path tmp = Files.createTempDirectory("hgapi");
        File ziel = tmp.resolve("ziel").toFile();
        File vorherZiel = DisplayApiHandler.zielOrdner;
        DisplayApiHandler.AppBruecke vorher = DisplayApiHandler.bruecke;
        final int[] gesendet = {0};
        final String[] marke = {null};
        try {
            DisplayApiHandler.setzeUpload(new at.greenchili.surfmate.storage.Dateiablage(tmp.resolve("up").toFile()));
            DisplayApiHandler.zielOrdner = ziel;
            DisplayApiHandler.bruecke = b -> {
                if ("setzen".equals(b.optString("aktion"))) {
                    gesendet[0]++;
                    marke[0] = b.optString("marke");
                }
                try {
                    return new org.json.JSONObject().put("success", true);
                } catch (Exception e) {
                    return null;
                }
            };
            // Ein „Bild“, das in Wahrheit HTML ist: wird nicht gesetzt
            String id = start("bild.jpg", 14);
            teil(id, "<html><script>".getBytes(StandardCharsets.UTF_8));
            String antwort = post("/api/display/hintergrund/fertig", "{\"id\":\"" + id + "\"}");
            assertTrue(antwort, antwort.contains("\"grund\":\"art\""));
            assertEquals(0, gesendet[0]);
            assertFalse(new File(ziel, HintergrundRegel.DATEI).exists());
            // echtes JPEG-Kopf: wird in den App-Ordner gelegt und über die Brücke gesetzt
            byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0};
            String id2 = start("bild.jpg", jpg.length);
            teil(id2, jpg);
            antwort = post("/api/display/hintergrund/fertig", "{\"id\":\"" + id2 + "\"}");
            assertTrue(antwort, antwort.contains("\"ok\":true"));
            assertEquals(1, gesendet[0]);
            assertTrue(new File(ziel, HintergrundRegel.DATEI).isFile());
            // Abholen nur mit der Marke aus dem Brücken-Befehl, genau einmal
            assertTrue(post("/api/display/hintergrund/bild", "{}").startsWith("HTTP/1.1 403"));
            assertTrue(post("/api/display/hintergrund/bild", "{\"marke\":\"" + marke[0] + "\"}")
                    .startsWith("HTTP/1.1 200"));
            assertTrue(post("/api/display/hintergrund/bild", "{\"marke\":\"" + marke[0] + "\"}")
                    .startsWith("HTTP/1.1 403"));
            // zu groß wird gar nicht erst angenommen
            antwort = post("/api/display/hintergrund/start", "{\"name\":\"x.jpg\",\"groesse\":" + (21L * 1024 * 1024) + "}");
            assertTrue(antwort, antwort.contains("zu_gross"));
        } finally {
            DisplayApiHandler.setzeUpload(null);
            DisplayApiHandler.zielOrdner = vorherZiel;
            DisplayApiHandler.bruecke = vorher;
            try (java.util.stream.Stream<Path> s = Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String post(String pfad, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(DisplayApiHandler.handle("POST", pfad, body, out));
        return out.toString("UTF-8");
    }

    private static String start(String name, long groesse) throws Exception {
        String a = post("/api/display/hintergrund/start", "{\"name\":\"" + name + "\",\"groesse\":" + groesse + "}");
        assertTrue(a, a.contains("\"ok\":true"));
        String rumpf = a.substring(a.indexOf("\r\n\r\n") + 4);
        return new org.json.JSONObject(rumpf).getString("id");
    }

    private static void teil(String id, byte[] daten) throws Exception {
        String a = post("/api/display/hintergrund/teil", "{\"id\":\"" + id + "\",\"nr\":0,\"daten\":\""
                + java.util.Base64.getEncoder().encodeToString(daten) + "\"}");
        assertTrue(a, a.contains("\"ok\":true"));
    }
}
