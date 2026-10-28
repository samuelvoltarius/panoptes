package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.appupdate.FernUpdate;
import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.KopplungsAnfragen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 0.1.26.1 Recht „update“ und Update vom Handy/Cockpit/Web (HANDY_API_VERTRAG §8). */
public class HandyUpdateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    final List<Integer> installiert = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();

    @Before
    public void auf() throws Exception {
        kopplungen = neu();
        HandyApiHandler.kopplungen = kopplungen;
        File basis = tmp.newFolder("b");
        HandyApiHandler.fernUpdate = new FernUpdate(new FernUpdate.Umgebung() {
            public JSONObject status(String sprache) {
                try {
                    return new JSONObject()
                            .put("aktuell", new JSONObject().put("version", "0.1.26").put("versionCode", 41))
                            .put("verfuegbar", new JSONObject().put("version", "0.1.26.1").put("versionCode", 42))
                            .put("installierbar", true).put("phase", JSONObject.NULL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public String installieren(int vc) { installiert.add(vc); return null; }
            public String fahrsperre() { return null; }
            public void telegram(String version, String geraet) { telegram.add(version + " " + geraet); }
            public File basis() { return basis; }
            public long jetzt() { return jetzt; }
        });
    }

    @After
    public void ab() {
        HandyApiHandler.kopplungen = null;
        HandyApiHandler.fernUpdate = null;
    }

    Kopplungen neu() {
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
    }

    @Test
    public void ersteHandyKopplungDarfWeitereNicht() {
        String cockpit = kopplungen.anlegen("PC", "cockpit", jetzt);
        String a = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt + 1);
        String b = kopplungen.anlegen("iPhone", Kopplungen.ART_HANDY, jetzt + 2);
        assertFalse(kopplungen.hatRecht(cockpit, Kopplungen.RECHT_UPDATE));
        assertTrue("erste Handy-Kopplung", kopplungen.hatRecht(a, Kopplungen.RECHT_UPDATE));
        assertFalse("weitere: AUS", kopplungen.hatRecht(b, Kopplungen.RECHT_UPDATE));
        // Häkchen später
        List<String> r = kopplungen.rechte(b);
        r.add(Kopplungen.RECHT_UPDATE);
        assertTrue(kopplungen.rechteSetzen(b, r));
        assertTrue(kopplungen.hatRecht(b, Kopplungen.RECHT_UPDATE));
    }

    @Test
    public void bestandBekommtUpdateNurAnDerAeltestenUndBleibtDort() throws Exception {
        // Stand 2 (0.1.25): zwei Handys, das ältere zuerst angelegt
        JSONArray alt = new JSONArray()
                .put(new JSONObject().put("id", "jung").put("art", "handy").put("angelegt", 200L).put("zuletzt", jetzt)
                        .put("rechte", new JSONArray().put("klima").put("route")).put("rechteStand", 2))
                .put(new JSONObject().put("id", "alt").put("art", "handy").put("angelegt", 100L).put("zuletzt", jetzt)
                        .put("rechte", new JSONArray().put("klima")).put("rechteStand", 1));
        gespeichert[0] = alt;
        Kopplungen k = neu();
        assertTrue(k.hatRecht("alt", Kopplungen.RECHT_UPDATE));
        assertTrue("Stand 1 bekommt route weiter dazu", k.hatRecht("alt", Kopplungen.RECHT_ROUTE));
        assertFalse(k.hatRecht("jung", Kopplungen.RECHT_UPDATE));
        // festgeschrieben: nach Widerruf der ältesten wandert „update“ nicht zum jüngeren
        assertEquals(3, gespeichert[0].getJSONObject(0).getInt("rechteStand"));
        k.widerrufen("alt");
        assertFalse(neu().hatRecht("jung", Kopplungen.RECHT_UPDATE));
    }

    @Test
    public void anfrageAmDisplayMitUpdateTelegramOhne() throws Exception {
        kopplungen.anlegen("Erstes", Kopplungen.ART_HANDY, jetzt);   // Besitzer-Handy gibt es schon
        List<String[]> rechte = new ArrayList<>();
        KopplungsAnfragen a = new KopplungsAnfragen(new KopplungsAnfragen.Ausgeber() {
            public String[] ausstellen(String geraet, String art, long now) {
                return new String[]{kopplungen.anlegen(geraet, art, now), "jwt"};
            }
            public void zusatzRechte(String kid, boolean auf, boolean wb, boolean upd) {
                List<String> r = kopplungen.rechte(kid);
                if (upd) r.add(Kopplungen.RECHT_UPDATE);
                kopplungen.rechteSetzen(kid, r);
                rechte.add(new String[]{kid, String.valueOf(upd)});
            }
        });
        KopplungsAnfragen.Neu d = a.anlegen("Display-Handy", "1234", "handy", "ip1", jetzt);
        String dRef = a.offeneListe(jetzt).optJSONObject(0).optString("ref");
        assertEquals(KopplungsAnfragen.Entscheid.OK, a.entscheiden(dRef, true, false, false, true, jetzt));
        KopplungsAnfragen.Neu t = a.anlegen("Telegram-Handy", "5678", "handy", "ip2", jetzt);
        String tRef = a.offeneListe(jetzt).optJSONObject(0).optString("ref");
        assertEquals(KopplungsAnfragen.Entscheid.OK, a.entscheiden(tRef, true, jetzt));
        String kd = a.abholen(d.id, jetzt).kid;
        String kt = a.abholen(t.id, jetzt).kid;
        assertTrue(kopplungen.hatRecht(kd, Kopplungen.RECHT_UPDATE));
        assertFalse(kopplungen.hatRecht(kt, Kopplungen.RECHT_UPDATE));
    }

    @Test
    public void aktionNurMitRecht() throws Exception {
        kopplungen.anlegen("Erstes", Kopplungen.ART_HANDY, jetzt);
        String k = kopplungen.anlegen("Zweites", Kopplungen.ART_HANDY, jetzt + 1);
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "update_starten")
                .put("version", "0.1.26.1").put("bestaetigt", true), k);
        assertEquals("nicht_erlaubt", r.getString("grund"));
        assertTrue(installiert.isEmpty());
    }

    @Test
    public void aktionStartetFreigegebeneVersionMitGeraetename() throws Exception {
        String k = kopplungen.anlegen("Pixel 8", Kopplungen.ART_HANDY, jetzt);
        JSONObject ohne = HandyApiHandler.aktion(new JSONObject().put("aktion", "update_starten")
                .put("version", "0.1.26.1"), k);
        assertEquals("bestaetigung_fehlt", ohne.getString("grund"));
        JSONObject r = HandyApiHandler.aktion(new JSONObject().put("aktion", "update_starten")
                .put("version", "0.1.26.1").put("bestaetigt", true), k);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(42, (int) installiert.get(0));
        assertEquals("0.1.26.1 Pixel 8", telegram.get(0));
    }

    @Test
    public void getUpdateFuerHandy() throws Exception {
        String k = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(HandyApiHandler.handle("GET", "/api/handy/v1/update?sprache=de", "", out, k));
        String s = out.toString("UTF-8");
        JSONObject j = new JSONObject(s.substring(s.indexOf("\r\n\r\n") + 4));
        assertTrue(j.getBoolean("darf"));
        assertEquals("0.1.26.1", j.getJSONObject("neueste").getString("version"));
        // andere GETs unter /api/handy/v1 bleiben 405
        out = new ByteArrayOutputStream();
        HandyApiHandler.handle("GET", "/api/handy/v1/aktion", "", out, k);
        assertTrue(out.toString("UTF-8").startsWith("HTTP/1.1 405"));
        // Cockpit-Kopplung: 403
        String c = kopplungen.anlegen("PC", "cockpit", jetzt);
        out = new ByteArrayOutputStream();
        HandyApiHandler.handle("GET", "/api/handy/v1/update", "", out, c);
        assertTrue(out.toString("UTF-8").startsWith("HTTP/1.1 403"));
    }

    @Test
    public void externGuardNurDieserEineGet() {
        assertFalse(ExternGuard.blocks("GET", "/api/handy/v1/update", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/update", "cockpit"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/aktion", "handy"));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1/update", "handy"));
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/aktion", "handy"));
        // Installieren über den Login-Weg bleibt für Kopplungen zu
        assertTrue(ExternGuard.blocks("POST", "/api/appupdate/installieren", "handy"));
    }

    @Test
    public void webSitzungNurMitRechtUndNurDieseDreiPfade() {
        assertTrue(WebHandyGuard.blocks("POST", "/api/appupdate/installieren"));
        assertTrue(WebHandyGuard.updateErlaubt("POST", "/api/appupdate/installieren", true));
        assertTrue(WebHandyGuard.updateErlaubt("GET", "/api/appupdate/status?lang=de", true));
        assertTrue(WebHandyGuard.updateErlaubt("POST", "/api/appupdate/suchen", true));
        assertFalse(WebHandyGuard.updateErlaubt("POST", "/api/appupdate/installieren", false));
        assertFalse(WebHandyGuard.updateErlaubt("POST", "/api/update/install", true));
        assertFalse(WebHandyGuard.updateErlaubt("POST", "/api/settings/unified", true));
        assertFalse(WebHandyGuard.updateErlaubt("GET", "/api/appupdate/installieren", true));
    }

    @Test
    public void weiteresGeraetCodeOhneUpdate() throws Exception {
        java.lang.reflect.Method reset = at.greenchili.surfmate.auth.PairingCodes.class.getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
        String c = at.greenchili.surfmate.auth.PairingCodes.create(jetzt, true);
        assertEquals(at.greenchili.surfmate.auth.PairingCodes.WEITERES, at.greenchili.surfmate.auth.PairingCodes.einloesen(c, jetzt));
        c = at.greenchili.surfmate.auth.PairingCodes.create(jetzt);
        assertEquals(at.greenchili.surfmate.auth.PairingCodes.BESITZER, at.greenchili.surfmate.auth.PairingCodes.einloesen(c, jetzt));
        assertEquals(at.greenchili.surfmate.auth.PairingCodes.UNGUELTIG, at.greenchili.surfmate.auth.PairingCodes.einloesen(c, jetzt));
    }

    @Test
    public void webOberflaecheHatKnopfUndRueckfrage() throws Exception {
        String js = lies("src/main/assets/web/shared/app-updates.js");
        assertTrue(js.contains("bestaetigt: true"));
        assertTrue(js.contains("system.upd_confirm_fern"));
        String html = lies("src/main/assets/web/local/system.html");
        assertTrue(html.contains("'route', 'update'"));
        assertTrue(html.contains("system.handy_update_confirm"));
        assertTrue(html.contains("update: !!(upd && upd.checked)"));
    }

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
