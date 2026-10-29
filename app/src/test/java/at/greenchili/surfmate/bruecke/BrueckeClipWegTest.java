package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.recording.ClipExport;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.28 P5: Ereignis-Clip aufs eigene Handy – nur auf Knopfdruck, nur Medien-Kanal, nur mit Schein derselben
 * Kopplung, jede Freigabe und Übertragung im Protokoll; der HTTP-Server bekommt diese Pfade nie.
 */
public class BrueckeClipWegTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final String ID = "0123456789abcdef0123456789abcdef";

    static final class Speicher implements ClipExport.Speicher {
        JSONArray z = new JSONArray();
        public JSONArray lade() { return z; }
        public void speichere(JSONArray a) { z = a; }
    }

    static final class Quelle implements BrueckeClipWeg.Quelle {
        File datei;
        long jetzt = 1_800_000_000_000L;
        final List<String> log = new ArrayList<>();
        boolean erlaubt = true;
        public boolean istHandy(String kid) { return kid.startsWith("k"); }
        public boolean ereignisErlaubt(String kid) { return erlaubt; }
        public String geraet(String kid) { return "Handy " + kid; }
        public File ereignisDatei(String id) { return ID.equals(id) ? datei : null; }
        public long jetzt() { return jetzt; }
        public void log(String zeile) { log.add(zeile); }
    }

    byte[] inhalt;
    Quelle quelle;
    Speicher speicher;
    BrueckeV2Test.Quelle http;

    BrueckeWeiterleitung weiter() throws Exception {
        inhalt = new byte[300_000];
        new java.util.Random(3).nextBytes(inhalt);
        File f = tmp.newFile("ereignis_20261029_101500.mp4");
        java.nio.file.Files.write(f.toPath(), inhalt);
        quelle = new Quelle();
        quelle.datei = f;
        speicher = new Speicher();
        BrueckeClipWeg weg = new BrueckeClipWeg(quelle, new ClipExport(speicher));
        http = new BrueckeV2Test.Quelle(new byte[] {1});
        return new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null,
                new BrueckeTest.Mitschnitt(), kid -> null, (kid, r) -> true)
                .mitStrom(http, n -> true).mitClipWeg(weg::oeffnen);
    }

    static byte[] req(String m, String p, String jwt, String body, String range) throws Exception {
        JSONObject h = new JSONObject().put("Authorization", "Bearer " + jwt);
        if (range != null) h.put("Range", range);
        JSONObject r = new JSONObject().put("id", 5).put("v", 2).put("m", m).put("p", p).put("h", h);
        if (body != null) r.put("b", body);
        return r.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Kopf-JSON (oder Fehler-JSON) und Datenbytes eines v2-Stroms. */
    static final class Ergebnis {
        JSONObject kopf;
        int status;
        String grund;
        final ByteArrayOutputStream daten = new ByteArrayOutputStream();
        boolean ende;
    }

    static Ergebnis lauf(BrueckeWeiterleitung w, byte[] anfrage, String kid, boolean medien) throws Exception {
        BrueckeV2Test.Ausgang aus = new BrueckeV2Test.Ausgang();
        w.strom(anfrage, kid, medien, aus, id -> false);
        Ergebnis e = new Ergebnis();
        for (byte[] s : aus.stuecke) {
            if (s[0] == BrueckeWeiterleitung.V2_KOPF) {
                e.kopf = BrueckeV2Test.json(s);
                e.status = e.kopf.getInt("s");
            } else if (s[0] == BrueckeWeiterleitung.V2_DATEN) {
                e.daten.write(s, 5, s.length - 5);
            } else if (s[0] == BrueckeWeiterleitung.V2_ENDE) {
                e.ende = true;
            } else {
                JSONObject f = BrueckeV2Test.json(s);
                e.status = f.getInt("s");
                e.grund = f.optString("grund");
            }
        }
        return e;
    }

    static JSONObject body(Ergebnis e) throws Exception {
        return new JSONObject(new String(e.daten.toByteArray(), StandardCharsets.UTF_8));
    }

    String freigabe(BrueckeWeiterleitung w) throws Exception {
        Ergebnis e = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", ID).put("knopf", true).toString(), null), "k1", true);
        assertEquals(200, e.status);
        JSONObject b = body(e);
        assertTrue(b.getBoolean("ok"));
        assertEquals(inhalt.length, b.getLong("groesse"));
        return b.getString("schein");
    }

    @Test
    public void freigabeUndBereicheBisZumEnde() throws Exception {
        BrueckeWeiterleitung w = weiter();
        String schein = freigabe(w);
        assertEquals(32, schein.length());
        ByteArrayOutputStream ganz = new ByteArrayOutputStream();
        long von = 0;
        while (von < inhalt.length) {
            Ergebnis e = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID + "?schein=" + schein, "kid-k1", null,
                    "bytes=" + von + "-" + (von + 99_999)), "k1", true);
            assertEquals(206, e.status);
            assertTrue(e.ende);
            assertEquals("video/mp4", e.kopf.getJSONObject("h").getString("content-type"));
            assertTrue(e.kopf.getJSONObject("h").getString("content-range").endsWith("/" + inhalt.length));
            ganz.write(e.daten.toByteArray());
            von += e.daten.size();
        }
        assertArrayEquals(inhalt, ganz.toByteArray());
        assertNull("HTTP-Server bekam nichts", http.kopf);
        // Protokoll: Freigabe + einmal „übertragen“ (nicht je Bereich)
        JSONArray p = speicher.z;
        assertEquals(2, p.length());
        assertEquals("freigabe", p.getJSONObject(0).getString("was"));
        assertEquals("uebertragen", p.getJSONObject(1).getString("was"));
        assertEquals(inhalt.length, p.getJSONObject(1).getLong("bytes"));
        assertEquals("Handy k1", p.getJSONObject(1).getString("geraet"));
        assertFalse(p.toString().contains("lat"));
    }

    @Test
    public void nurAufDemMedienKanal() throws Exception {
        BrueckeWeiterleitung w = weiter();
        Ergebnis e = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", ID).put("knopf", true).toString(), null), "k1", false);
        assertEquals(403, e.status);
        assertEquals("nur_bruecke_medien", e.grund);
        assertEquals(0, speicher.z.length());
        // v1 (bearbeiten) geht an den HTTP-Server, der für diese Pfade immer 403 gibt (HandyClipsApiHandlerTest)
        assertTrue(BrueckeClipWeg.zustaendig(BrueckeClipWeg.DATEI + ID + "?schein=x"));
        assertFalse(BrueckeClipWeg.zustaendig("/api/handy/v1/clips"));
        assertTrue(BrueckeWeiterleitung.istKameraPfad("/api/handy/v1/clips/freigabe"));
    }

    @Test
    public void ohneKnopfOhneScheinFremdeKopplung() throws Exception {
        BrueckeWeiterleitung w = weiter();
        Ergebnis ohneKnopf = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", ID).toString(), null), "k1", true);
        assertEquals(403, ohneKnopf.status);
        assertEquals("nur_auf_knopfdruck", body(ohneKnopf).getString("grund"));
        assertEquals("abgelehnt", speicher.z.getJSONObject(0).getString("was"));

        Ergebnis ohneSchein = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID, "kid-k1", null, "bytes=0-9"), "k1", true);
        assertEquals(403, ohneSchein.status);
        assertEquals("schein", body(ohneSchein).getString("grund"));

        String schein = freigabe(w);
        // anderes Handy mit gestohlenem Schein: abgewiesen
        Ergebnis fremd = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID + "?schein=" + schein, "kid-k2", null, "bytes=0-9"),
                "k2", true);
        assertEquals(403, fremd.status);
        assertEquals("schein", body(fremd).getString("grund"));
        // JWT einer anderen Kopplung auf diesem Kanal: schon vorher 401
        Ergebnis falschesJwt = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID + "?schein=" + schein, "kid-k2", null, null),
                "k1", true);
        assertEquals(401, falschesJwt.status);
        // abgelaufen
        quelle.jetzt += ClipExport.SCHEIN_MS + 1;
        Ergebnis alt = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID + "?schein=" + schein, "kid-k1", null, "bytes=0-9"),
                "k1", true);
        assertEquals(403, alt.status);
    }

    @Test
    public void keinEreignisClipUndKeinHandy() throws Exception {
        BrueckeWeiterleitung w = weiter();
        Ergebnis anderer = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", "ffffffffffffffffffffffffffffffff").put("knopf", true).toString(), null), "k1", true);
        assertEquals(404, anderer.status);
        Ergebnis cockpit = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-c1",
                new JSONObject().put("id", ID).put("knopf", true).toString(), null), "c1", true);
        assertEquals(403, cockpit.status);
        assertEquals("nur_handy", body(cockpit).getString("grund"));
    }

    /** 0.1.28 Koordination: eigener Schalter je Kopplung „Unfall-Clips aufs Handy erlauben“, Standard aus. */
    @Test
    public void ohneSchalterEhrlich403() throws Exception {
        BrueckeWeiterleitung w = weiter();
        String schein = freigabe(w);
        quelle.erlaubt = false;
        Ergebnis e = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", ID).put("knopf", true).toString(), null), "k1", true);
        assertEquals(403, e.status);
        assertEquals("ereignisclip_aus", body(e).getString("grund"));
        assertEquals("ereignisclip", body(e).getString("recht"));
        assertEquals("abgelehnt", speicher.z.getJSONObject(speicher.z.length() - 1).getString("was"));
        // auch ein schon ausgegebener Schein hilft nach dem Ausschalten nicht mehr
        Ergebnis d = lauf(w, req("GET", BrueckeClipWeg.DATEI + ID + "?schein=" + schein, "kid-k1", null, "bytes=0-9"),
                "k1", true);
        assertEquals(403, d.status);
        assertTrue(at.greenchili.surfmate.auth.Kopplungen.RECHTE.contains("ereignisclip"));
    }

    @Test
    public void ereignisclipNichtImStandard() throws Exception {
        java.lang.reflect.Field f = at.greenchili.surfmate.auth.Kopplungen.class.getDeclaredField("HANDY_STANDARD");
        f.setAccessible(true);
        assertFalse(((java.util.List<?>) f.get(null)).contains("ereignisclip"));
        java.io.File s = new java.io.File("src/main/assets/web/local/system.html");
        if (!s.isFile()) s = new java.io.File("app/" + s.getPath());
        String html = new String(java.nio.file.Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("'update', 'ereignisclip'"));
        assertTrue("eigene Rückfrage", html.contains("system.handy_ereignisclip_confirm"));
    }

    @Test
    public void standardOhneClipWegIstZu() throws Exception {
        BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.substring(4), new BrueckeTest.Mitschnitt(),
                kid -> null, (kid, r) -> true).mitStrom(new BrueckeV2Test.Quelle(new byte[] {1}), n -> true);
        Ergebnis e = lauf(w, req("POST", BrueckeClipWeg.FREIGABE, "kid-k1",
                new JSONObject().put("id", ID).put("knopf", true).toString(), null), "k1", true);
        assertEquals(403, e.status);
    }

    @Test
    public void bereiche() {
        assertArrayEquals(new long[] {0, 9}, BrueckeClipWeg.bereich("bytes=0-9", 100));
        assertArrayEquals(new long[] {90, 99}, BrueckeClipWeg.bereich("bytes=90-", 100));
        assertArrayEquals(new long[] {95, 99}, BrueckeClipWeg.bereich("bytes=-5", 100));
        assertArrayEquals(new long[] {50, 99}, BrueckeClipWeg.bereich("bytes=50-500", 100));
        assertNull(BrueckeClipWeg.bereich("bytes=100-", 100));
        assertNull(BrueckeClipWeg.bereich("bytes=5-1", 100));
        assertNull(BrueckeClipWeg.bereich("items=0-1", 100));
    }
}
