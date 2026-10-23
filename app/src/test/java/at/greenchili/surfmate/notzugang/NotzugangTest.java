package at.greenchili.surfmate.notzugang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/**
 * 0.1.14 (Kern 7): Notzugang – Eingaben, Standard aus, Skript-Vertrag, Einrichtung öffnet den Assistenten nicht neu.
 * 0.1.15 (Kern 8): adb-Weiterleitung per serve --tcp 5555 (echte Shell mit tailscale-Attrappe), Tag bzw. Key-Ablauf.
 */
public class NotzugangTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void eingabenPruefen() {
        assertTrue(Notzugang.keyGueltig("tskey-auth-kAbCdEf123456-XYZxyz7890"));
        assertFalse(Notzugang.keyGueltig("tskey-kurz"));
        assertFalse(Notzugang.keyGueltig("abc-auth-kAbCdEf123456-XYZxyz7890"));
        assertFalse(Notzugang.keyGueltig("tskey-auth-k; rm -rf /data"));
        assertFalse(Notzugang.keyGueltig(null));
        assertTrue(Notzugang.nameGueltig("dolphin-notzugang"));
        assertFalse(Notzugang.nameGueltig("-x"));
        assertFalse(Notzugang.nameGueltig("Auto Notzugang"));
        assertFalse(Notzugang.nameGueltig("a$(id)"));
    }

    @Test
    public void standardAus() throws Exception {
        assertFalse(Notzugang.an(null));
        assertFalse(Notzugang.an(new JSONObject()));
        assertTrue(Notzugang.an(new JSONObject().put("notzugang", new JSONObject().put("an", true))));
    }

    private static String skript() throws Exception {
        File f = new File("src/main/assets/notzugang/notzugang.sh");
        if (!f.isFile()) f = new File("app/src/main/assets/notzugang/notzugang.sh");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void skriptVertrag() throws Exception {
        String s = skript();
        assertFalse("LF, nicht CRLF", s.contains("\r\n"));
        assertTrue(s.contains("D=" + Notzugang.ORDNER));
        assertTrue("eigener Socket", s.contains("--socket 127.0.0.1:8633"));
        assertTrue("zufälliger UDP-Port, kein Konflikt mit Weg 1", s.contains("--port 0"));
        assertTrue("Userspace", s.contains("--tun userspace-networking"));
        assertTrue("nur eigener tailscaled per statedir", s.contains("grep -qF -- \"--statedir $S\""));
        assertFalse("kein Namens-Kill", s.contains("pkill") || s.contains("killall"));
        // 0.1.15: einzige Weiterleitung ist adb 5555 → Loopback; kein Web, kein HTTPS, nie serve reset
        assertTrue("adb-Weiterleitung", s.contains("serve --bg --tcp 5555 tcp://127.0.0.1:5555"));
        assertFalse("kein Web", s.contains("8180") || s.contains("--https") || s.contains("funnel"));
        assertFalse("nie serve reset", s.contains("serve reset"));
        int n = 0;
        for (int i = s.indexOf("serve --bg"); i >= 0; i = s.indexOf("serve --bg", i + 1)) n++;
        assertEquals("genau ein serve-Setzbefehl", 1, n);
    }

    @Test
    public void neuerSchrittOeffnetFertigeEinrichtungNichtWieder() throws Exception {
        // Nutzer mit art=neu, Einrichtung fertig, Zustand aus 0.1.13 (ohne extra_notzugang)
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) {
            if (!"extra_notzugang".equals(id)) schritte.put(id, "erledigt");
        }
        JSONObject root = new JSONObject().put("einrichtung", new JSONObject().put("version", 1).put("art", "neu")
                .put("schritte", schritte));
        JSONObject z = EinrichtungApiHandler.zustand(root);
        assertEquals("spaeter", z.getJSONObject("schritte").getString("extra_notzugang"));
        JSONObject sicht = EinrichtungApiHandler.sicht(root, z, null, null);
        assertEquals("fertig", sicht.getString("aktuell"));
        // ganz neuer Nutzer: Schritt ist ganz normal offen
        JSONObject frisch = EinrichtungApiHandler.zustand(new JSONObject());
        assertEquals("offen", frisch.getJSONObject("schritte").getString("extra_notzugang"));
    }

    // ------------------------------------------------------------------ 0.1.15 adb-Weiterleitung (echte Shell)

    private static boolean shDa() {
        try {
            return new ProcessBuilder("sh", "-c", "echo ja").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String SERVE_ADB =
            "{\n  \"TCP\": {\n    \"5555\": {\n      \"TCPForward\": \"127.0.0.1:5555\"\n    }\n  }\n}\n";

    /**
     * Echtes notzugang.sh mit eigenem Ordner statt /data/local/tmp und einer tailscale-Attrappe: liefert
     * {@code serve status --json} aus serve.json, protokolliert jeden Aufruf und schreibt bei
     * {@code serve --bg --tcp 5555 tcp://127.0.0.1:5555} die Weiterleitung (außer mit Datei „kaputt“).
     */
    private File ordner(String serveJson, boolean kaputt) throws Exception {
        File d = tmp.newFolder();
        String dp = d.getAbsolutePath().replace('\\', '/');
        String s = skript().replace("D=" + Notzugang.ORDNER + "\n", "D='" + dp + "'\n");
        assertTrue(s.contains("D='" + dp + "'"));
        Files.write(new File(d, "notzugang.sh").toPath(), s.getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(d, "bin").mkdirs());
        Files.write(new File(d, "serve.json").toPath(), serveJson.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(d, "serve_adb.json").toPath(), SERVE_ADB.getBytes(StandardCharsets.UTF_8));
        if (kaputt) Files.write(new File(d, "kaputt").toPath(), new byte[0]);
        String attrappe = "#!/bin/sh\n"
                + "D='" + dp + "'\n"
                + "echo \"$*\" >> \"$D/aufrufe.log\"\n"
                + "[ \"$1 $2\" = \"--socket 127.0.0.1:8633\" ] || exit 9\n"
                + "shift 2\n"
                + "if [ \"$*\" = \"serve status --json\" ]; then cat \"$D/serve.json\"; exit 0; fi\n"
                + "if [ \"$*\" = \"serve --bg --tcp 5555 tcp://127.0.0.1:5555\" ]; then\n"
                + "  [ -f \"$D/kaputt\" ] && { echo 'not logged in' >&2; exit 1; }\n"
                + "  cp \"$D/serve_adb.json\" \"$D/serve.json\"; exit 0\n"
                + "fi\n"
                + "exit 3\n";
        File t = new File(d, "bin/tailscale");
        Files.write(t.toPath(), attrappe.getBytes(StandardCharsets.UTF_8));
        //noinspection ResultOfMethodCallIgnored
        t.setExecutable(true);
        return d;
    }

    private static String lauf(File d, String arg) throws Exception {
        // Skript aus Datei: auf Windows verstümmelt ProcessBuilder Anführungszeichen in Argumenten
        Process p = new ProcessBuilder("sh", new File(d, "notzugang.sh").getAbsolutePath().replace('\\', '/'), arg)
                .redirectErrorStream(true).start();
        String out = new String(Notzugang.lesen(p.getInputStream()), StandardCharsets.UTF_8).trim();
        return out + "|rc=" + p.waitFor();
    }

    private static int setzAufrufe(File d) throws Exception {
        File log = new File(d, "aufrufe.log");
        if (!log.isFile()) return 0;
        int n = 0;
        for (String z : new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).split("\n")) {
            if (z.contains("serve --bg --tcp 5555 tcp://127.0.0.1:5555")) n++;
        }
        return n;
    }

    @Test
    public void serveWirdGesetztUndZurueckgelesen() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        File d = ordner("{}", false);
        assertEquals("serve=ok|rc=0", lauf(d, "serve"));
        assertEquals(1, setzAufrufe(d));
        assertTrue(Notzugang.serveOk("serve=ok"));
    }

    @Test
    public void serveIstIdempotent() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        // so, wie Deploy es bei Projektinhaber am 22.10. von Hand gesetzt hat (mehrzeiliges JSON der echten CLI)
        File d = ordner(SERVE_ADB, false);
        assertEquals("serve=ok|rc=0", lauf(d, "serve"));
        assertEquals("serve=ok|rc=0", lauf(d, "serve"));
        assertEquals("nie neu setzen, wenn vorhanden", 0, setzAufrufe(d));
    }

    @Test
    public void serveFehltWirdGemeldet() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        File d = ordner("{}", true);
        assertEquals("serve=fehlt|rc=1", lauf(d, "serve"));
        assertEquals(1, setzAufrufe(d));
        assertFalse(Notzugang.serveOk("serve=fehlt"));
        assertFalse(Notzugang.serveOk(""));
        assertFalse(Notzugang.serveOk(null));
    }

    @Test
    public void andereWeiterleitungZaehltNicht() throws Exception {
        assumeTrue("sh nicht vorhanden", shDa());
        // Port 5555 mit anderem Ziel ist keine adb-Weiterleitung → setzen
        File d = ordner("{\"TCP\":{\"5555\":{\"TCPForward\":\"127.0.0.1:8180\"}}}", false);
        assertEquals("serve=ok|rc=0", lauf(d, "serve"));
        assertEquals(1, setzAufrufe(d));
    }

    // ------------------------------------------------------------------ 0.1.15 Tag bzw. Key-Ablauf

    @Test
    public void ohneTagMitKeyAblauf() throws Exception {
        // Befund 22.10.: dolphin-notzugang ohne Tags, gehört nutzer@, KeyExpiry 2027-04-20T15:08:40Z
        JSONObject r = new JSONObject();
        long jetzt = Instant.parse("2026-10-22T18:00:00Z").toEpochMilli();
        Notzugang.schluessel(new JSONObject().put("HostName", "dolphin-notzugang")
                .put("KeyExpiry", "2027-04-20T15:08:40Z"), jetzt, r);
        assertFalse(r.getBoolean("getaggt"));
        assertEquals(0, r.getJSONArray("tags").length());
        assertEquals("2027-04-20T15:08:40Z", r.getString("keyAblauf"));
        assertEquals(179, r.getLong("keyAblaufTage"));
    }

    @Test
    public void getaggtOhneAblauf() throws Exception {
        JSONObject r = new JSONObject();
        Notzugang.schluessel(new JSONObject().put("Tags", new JSONArray().put("tag:notzugang")), 0L, r);
        assertTrue(r.getBoolean("getaggt"));
        assertEquals("tag:notzugang", r.getJSONArray("tags").getString(0));
        assertTrue(r.isNull("keyAblauf"));
        assertFalse(r.has("keyAblaufTage"));
        JSONObject r2 = new JSONObject();
        Notzugang.schluessel(new JSONObject().put("KeyExpiry", "0001-01-01T00:00:00Z"), 0L, r2);
        assertTrue("Go-Nullzeit = kein Ablauf", r2.isNull("keyAblauf"));
    }

    @Test
    public void abgelaufenIstNegativ() throws Exception {
        JSONObject r = new JSONObject();
        long jetzt = Instant.parse("2027-04-21T15:08:41Z").toEpochMilli();
        Notzugang.schluessel(new JSONObject().put("KeyExpiry", "2027-04-20T15:08:40Z"), jetzt, r);
        assertEquals(-2, r.getLong("keyAblaufTage"));
    }
}
