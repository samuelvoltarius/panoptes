package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.storage.DateiQr;
import at.greenchili.surfmate.storage.Dateiablage;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.43.4 (F7): Datei per QR, Handler und Verdrahtung. Handy-Teil ohne Login nur mit Einmal-Kennung und nur aus dem
 * lokalen Netz; Auto-Teil nur am Auto selbst; die Datei landet in der Panoptes-Ablage.
 */
public class DateiQrApiHandlerTest {

    private final long[] jetzt = {System.currentTimeMillis()};
    private DateiQr qr;

    @Before
    public void vorher() throws Exception {
        qr = new DateiQr(() -> jetzt[0]);
        DateiQrApiHandler.setzeQr(qr);
        Path tmp = Files.createTempDirectory("dateiqr");
        DateienApiHandler.setzeAblage(new Dateiablage(tmp.resolve("d").toFile()));
    }

    @After
    public void nachher() {
        DateienApiHandler.setzeAblage(null);
        DateiQrApiHandler.setzeQr(new DateiQr());
    }

    private static String ohneKopf(String antwort) {
        int i = antwort.indexOf("\r\n\r\n");
        return i < 0 ? antwort : antwort.substring(i + 4);
    }

    private String aufruf(String methode, String pfad, JSONObject body, boolean lan) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DateiQrApiHandler.handleHandy(methode, pfad, body == null ? "" : body.toString(), out, lan);
        return out.toString("UTF-8");
    }

    private JSONObject post(String aktion, JSONObject b) throws Exception {
        return new JSONObject(ohneKopf(aufruf("POST", "/auth/datei-qr/" + aktion, b, true)));
    }

    @Test
    public void hochladenEndeZuEndeUndEinmal() throws Exception {
        DateiQr.Sitzung s = qr.neuHochladen();
        String k = s.kennung;
        JSONObject st = post("start", new JSONObject().put("k", k).put("name", "foto.jpg").put("groesse", 5));
        assertTrue(st.toString(), st.getBoolean("ok"));
        String id = st.getString("id");
        JSONObject zweiter = post("start", new JSONObject().put("k", k).put("name", "zwei.jpg").put("groesse", 5));
        assertEquals("eine Datei je Code", "belegt", zweiter.getString("grund"));
        JSONObject falscheId = post("teil", new JSONObject().put("k", k).put("id", "0123456789abcdef").put("nr", 0).put("daten", "aGFsbG8="));
        assertEquals("falsche_id", falscheId.getString("grund"));
        JSONObject t = post("teil", new JSONObject().put("k", k).put("id", id).put("nr", 0)
                .put("daten", java.util.Base64.getEncoder().encodeToString("hallo".getBytes("UTF-8"))));
        assertTrue(t.toString(), t.getBoolean("ok"));
        JSONObject f = post("fertig", new JSONObject().put("k", k).put("id", id));
        assertEquals("foto.jpg", f.getString("name"));
        assertEquals(DateiQr.FERTIG, qr.aktuell().zustand());
        // verbraucht: weder noch eine Datei noch Teile
        JSONObject nochmal = post("start", new JSONObject().put("k", k).put("name", "drei.jpg").put("groesse", 5));
        assertFalse(nochmal.getBoolean("ok"));
        assertEquals("abgelaufen", nochmal.getString("grund"));
        // die Datei liegt in der Panoptes-Ablage
        assertEquals(1, DateienApiHandler.ablage().liste().size());
        assertEquals("foto.jpg", DateienApiHandler.ablage().liste().get(0).name);
    }

    @Test
    public void ohneLokalesNetzNurDreiNullDrei() throws Exception {
        DateiQr.Sitzung s = qr.neuHochladen();
        String r = aufruf("POST", "/auth/datei-qr/start", new JSONObject().put("k", s.kennung).put("name", "a.txt").put("groesse", 1), false);
        assertTrue(r.startsWith("HTTP/1.1 403"));
        assertEquals("nur_lokales_netz", new JSONObject(ohneKopf(r)).getString("grund"));
        assertTrue("Kennung bleibt unberührt", qr.pruefe(s.kennung, DateiQr.HOCHLADEN).ok());
        assertTrue(aufruf("GET", "/auth/datei-qr/info?k=" + s.kennung, null, false).startsWith("HTTP/1.1 403"));
        assertTrue(aufruf("GET", "/auth/datei-qr/holen?k=" + s.kennung, null, false).startsWith("HTTP/1.1 403"));
    }

    @Test
    public void falscheOderFehlendeKennungUndAblauf() throws Exception {
        DateiQr.Sitzung s = qr.neuHochladen();
        JSONObject x = post("start", new JSONObject().put("k", "00000000000000000000000000000000").put("name", "a.txt").put("groesse", 1));
        assertEquals("falsch", x.getString("grund"));
        assertEquals("falsch", post("start", new JSONObject().put("name", "a.txt").put("groesse", 1)).getString("grund"));
        jetzt[0] += DateiQr.GUELTIG_MS + 1;
        assertEquals("abgelaufen", post("start", new JSONObject().put("k", s.kennung).put("name", "a.txt").put("groesse", 1)).getString("grund"));
    }

    @Test
    public void unbekannteAktionIstUnbekannt() throws Exception {
        DateiQr.Sitzung s = qr.neuHochladen();
        assertEquals("unbekannt", post("zzz", new JSONObject().put("k", s.kennung)).getString("grund"));
    }

    @Test
    public void apkUndZuGrossWerdenAbgelehnt() throws Exception {
        DateiQr.Sitzung s = qr.neuHochladen();
        assertEquals("apk_gesperrt", post("start", new JSONObject().put("k", s.kennung).put("name", "x.apk").put("groesse", 5)).getString("grund"));
        assertEquals("zu_gross", post("start", new JSONObject().put("k", s.kennung).put("name", "x.bin")
                .put("groesse", Dateiablage.MAX_BYTES + 1)).getString("grund"));
        assertEquals("Fehlstart verbraucht den Code nicht", DateiQr.OFFEN, qr.aktuell().zustand());
    }

    @Test
    public void holenEinmaligMitSicherenKoepfen() throws Exception {
        DateiQr.Sitzung s = qr.neuHolen("clip.mp4", new DateiQr.Quelle() {
            public String name() { return "clip.mp4"; }
            public long laenge() { return 5; }
            public java.io.InputStream oeffnen() { return new ByteArrayInputStream("hallo".getBytes()); }
        });
        JSONObject info = new JSONObject(ohneKopf(aufruf("GET", "/auth/datei-qr/info?k=" + s.kennung, null, true)));
        assertEquals("holen", info.getString("zweck"));
        assertEquals("clip.mp4", info.getString("name"));
        assertEquals(5, info.getLong("groesse"));
        assertEquals("info verbraucht nichts", DateiQr.OFFEN, qr.aktuell().zustand());
        String h = aufruf("GET", "/auth/datei-qr/holen?k=" + s.kennung, null, true);
        assertTrue(h.startsWith("HTTP/1.1 200"));
        assertTrue(h.contains("Content-Type: application/octet-stream"));
        assertTrue(h.contains("Content-Disposition: attachment"));
        assertTrue(h.contains("X-Content-Type-Options: nosniff"));
        assertTrue(h.endsWith("hallo"));
        // einmal
        String nochmal = aufruf("GET", "/auth/datei-qr/holen?k=" + s.kennung, null, true);
        assertTrue(nochmal.startsWith("HTTP/1.1 410"));
        // ein Hochlade-Code kann nichts holen
        DateiQr.Sitzung u = qr.neuHochladen();
        assertTrue(aufruf("GET", "/auth/datei-qr/holen?k=" + u.kennung, null, true).startsWith("HTTP/1.1 410"));
    }

    @Test
    public void autoTeilNurAmAutoSelbst() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(DateiQrApiHandler.handleAuto("POST", "/api/dateien/qr", "{}", out));
        assertTrue("ohne Direktzugriff am Auto", out.toString("UTF-8").startsWith("HTTP/1.1 403"));
        assertEquals("nur_am_auto", new JSONObject(ohneKopf(out.toString("UTF-8"))).getString("grund"));
        assertFalse(DateiQrApiHandler.handleAuto("POST", "/api/dateien/start", "{}", new ByteArrayOutputStream()));
        assertNull("kein Code ohne Zugriff", qr.aktuell());
    }

    @Test
    public void verdrahtungImServer() throws Exception {
        String hs = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int i = hs.indexOf("path.startsWith(DateiQrApiHandler.OEFFENTLICH)");
        assertTrue(i > 0);
        assertTrue("vor dem allgemeinen /auth/-Zweig", i < hs.indexOf("if (path.startsWith(\"/auth/\")) {"));
        String zweig = hs.substring(i, i + 400);
        assertTrue("echte Socket-Adresse, nie Tunnel-Merkmale", zweig.contains("!hasTunnelHeaders && DateiQr.lokalesNetz(client.getInetAddress())"));
        String d = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/DateienApiHandler.java"));
        assertTrue(d.contains("DateiQrApiHandler.handleAuto(method, p, body, out)"));
        // Kopplungen kommen nicht an die Auto-Wege: /api/dateien/qr steht in keiner Erlaubnisliste des ExternGuard
        String eg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/ExternGuard.java"));
        assertFalse(eg.contains("/api/dateien/qr"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/dateien/qr"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/dateien/qr"));
        // nur lokales Netz: Loopback zählt nicht (Tailscale kommt über 127.0.0.1)
        assertFalse(DateiQr.lokalesNetz("127.0.0.1"));
    }

    @Test
    public void handySeiteIstEigenstaendigUndMehrsprachig() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/assets/web/shared/datei-handy.html"));
        for (String sp : new String[] {"de:", "en:", "fr:", "it:", "nl:", "es:", "pt:", "tr:"}) {
            assertTrue(sp, h.contains("        " + sp + " {"));
        }
        assertTrue("Kennung nur aus dem Fragment, dann aus der Adresszeile", h.contains("history.replaceState(null, '', location.pathname)"));
        assertTrue(h.contains("'/auth/datei-qr/' + aktion"));
        assertFalse("keine fremden Server", h.contains("https://") || h.contains("<script src"));
        assertFalse("kein let/const/Pfeil im Skript", h.contains("=>") || h.contains(" let ") || h.contains(" const "));
    }
}
