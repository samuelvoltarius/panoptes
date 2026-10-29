package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.recording.ClipExport;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 0.1.28 P5 „Clips am Handy“ (CLIP_VERTRAG_0128): Liste ohne Pfade/Orte, Behalten, Ereignis-Clips nie über HTTP. */
public class HandyClipsApiHandlerTest {

    static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    static final class U implements HandyClipsApiHandler.Umgebung {
        final List<JSONObject> rows = new ArrayList<>();
        final Map<String, Boolean> behalten = new HashMap<>();
        final Set<String> offen = new HashSet<>();
        boolean kamera = true, extern;

        public List<JSONObject> zeilen(int limit, int offset) {
            return rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()));
        }
        public String dateiname(String id) {
            for (JSONObject r : rows) if (r.optString("id").equals(id)) return r.optString("filename");
            return null;
        }
        public boolean istHandy(String kid) { return kid.startsWith("h"); }
        public boolean hatKamera(String kid) { return kamera; }
        public boolean istBehalten(String d) { return Boolean.TRUE.equals(behalten.get(d.replace(".mp4", ""))); }
        public Set<String> offeneFragen() { return offen; }
        public boolean behalten(String stamm, boolean b) { behalten.put(stamm, b); offen.remove(stamm); return true; }
        public JSONArray protokoll(int n) { return new JSONArray().put(new JSONObject()); }
        public boolean extern() { return extern; }
    }

    U u;
    HandyClipsApiHandler.Umgebung vorher;

    static JSONObject row(String id, String name, String type) throws Exception {
        return new JSONObject().put("id", id).put("filename", name).put("type", type).put("timestamp", 1_800_000_000_000L)
                .put("size", 12345).put("available", true).put("path", "/storage/usb/x/" + name)
                .put("startLat", 48.2).put("startLng", 16.37).put("volumeId", "v").put("deleteUrl", "/api/recordings/id/" + id);
    }

    @Before
    public void auf() throws Exception {
        vorher = HandyClipsApiHandler.umgebung;
        u = new U();
        u.rows.add(row(E, "ereignis_20261029_101500.mp4", "ereignis"));
        u.rows.add(row(A, "event_20261029_090000.mp4", "sentry"));
        u.offen.add("ereignis_20261029_101500");
        HandyClipsApiHandler.umgebung = u;
    }

    @After
    public void ab() {
        HandyClipsApiHandler.umgebung = vorher;
    }

    static final class Antw {
        int status;
        JSONObject j;
    }

    static Antw rufe(String m, String p, String body, String kid) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(HandyClipsApiHandler.handle(m, p, body, out, kid));
        String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
        Antw a = new Antw();
        a.status = Integer.parseInt(s.substring(9, 12));
        a.j = new JSONObject(s.substring(s.indexOf("\r\n\r\n") + 4));
        return a;
    }

    @Test
    public void listeOhnePfadeUndOrte() throws Exception {
        Antw a = rufe("GET", "/api/handy/v1/clips?seite=0&anzahl=20", null, "h1");
        assertEquals(200, a.status);
        assertEquals(1, a.j.getInt("v"));
        JSONArray c = a.j.getJSONArray("clips");
        assertEquals(2, c.length());
        JSONObject ereignis = c.getJSONObject(0), aufnahme = c.getJSONObject(1);
        assertEquals("ereignis", ereignis.getString("typ"));
        assertEquals("bruecke_medien", ereignis.getString("weg"));
        assertTrue(ereignis.getBoolean("frageOffen"));
        assertFalse("kein Direkt-Link für Ereignis-Clips", ereignis.has("videoUrl"));
        assertEquals("aufnahme", aufnahme.getString("typ"));
        assertEquals("/video/id/" + A, aufnahme.getString("videoUrl"));
        String alles = a.j.toString();
        for (String verboten : new String[] {"/storage", "startLat", "48.2", "16.37", "volumeId", "deleteUrl", "filename"}) {
            assertFalse(verboten, alles.contains(verboten));
        }
        assertEquals(ClipExport.SCHEIN_MS / 60_000L, a.j.getLong("scheinMinuten"));
    }

    @Test
    public void seitenweise() throws Exception {
        Antw a = rufe("GET", "/api/handy/v1/clips?anzahl=1", null, "h1");
        assertTrue(a.j.getBoolean("mehr"));
        assertEquals(1, a.j.getJSONArray("clips").length());
        Antw b = rufe("GET", "/api/handy/v1/clips?anzahl=1&seite=1", null, "h1");
        assertFalse(b.j.getBoolean("mehr"));
        assertEquals(A, b.j.getJSONArray("clips").getJSONObject(0).getString("id"));
    }

    @Test
    public void nurHandyMitKamera() throws Exception {
        assertEquals("nur_handy", rufe("GET", "/api/handy/v1/clips", null, "c1").j.getString("grund"));
        assertEquals("nur_handy", rufe("GET", "/api/handy/v1/clips", null, null).j.getString("grund"));
        u.kamera = false;
        Antw a = rufe("GET", "/api/handy/v1/clips", null, "h1");
        assertEquals(403, a.status);
        assertEquals("kamera", a.j.getString("recht"));
    }

    @Test
    public void behaltenUndFrist() throws Exception {
        Antw a = rufe("POST", "/api/handy/v1/clips/behalten",
                new JSONObject().put("id", E).put("behalten", true).toString(), "h1");
        assertEquals(200, a.status);
        assertEquals(Boolean.TRUE, u.behalten.get("ereignis_20261029_101500"));
        assertFalse("Frage erledigt", u.offen.contains("ereignis_20261029_101500"));
        rufe("POST", "/api/handy/v1/clips/behalten", new JSONObject().put("id", E).put("behalten", false).toString(), "h1");
        assertEquals(Boolean.FALSE, u.behalten.get("ereignis_20261029_101500"));
        assertEquals(400, rufe("POST", "/api/handy/v1/clips/behalten",
                new JSONObject().put("id", E).put("behalten", "ja").toString(), "h1").status);
        assertEquals(404, rufe("POST", "/api/handy/v1/clips/behalten",
                new JSONObject().put("id", "../../x").put("behalten", true).toString(), "h1").status);
    }

    @Test
    public void ereignisWegNieUeberHttp() throws Exception {
        for (String p : new String[] {"/api/handy/v1/clips/freigabe", "/api/handy/v1/clips/datei/" + E + "?schein=x"}) {
            Antw a = rufe(p.endsWith("freigabe") ? "POST" : "GET", p,
                    new JSONObject().put("id", E).put("knopf", true).toString(), "h1");
            assertEquals(p, 403, a.status);
            assertEquals("nur_bruecke_medien", a.j.getString("grund"));
        }
    }

    @Test
    public void protokollNurAmAuto() throws Exception {
        assertEquals(200, rufe("GET", "/api/handy/v1/clips/protokoll", null, null).status);
        assertEquals(403, rufe("GET", "/api/handy/v1/clips/protokoll", null, "h1").status);
        u.extern = true;
        assertEquals(403, rufe("GET", "/api/handy/v1/clips/protokoll", null, null).status);
    }

    @Test
    public void externGuard() {
        assertFalse(ExternGuard.blocks("GET", "/api/handy/v1/clips", "handy"));
        assertFalse(ExternGuard.blocks("GET", "/api/handy/v1/clips?seite=1", "handy"));
        assertFalse(ExternGuard.blocks("POST", "/api/handy/v1/clips/behalten", "handy"));
        assertFalse(ExternGuard.blocks("GET", "/api/handy/v1/update", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/clips", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1/clips/freigabe", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/clips/datei/" + E, "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/clips/protokoll", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/aktion", "handy"));
    }

    @Test
    public void fremderPfadNichtZustaendig() throws Exception {
        assertFalse(HandyClipsApiHandler.handle("GET", "/api/handy/v1/clipsx", null, new ByteArrayOutputStream(), "h1"));
    }
}
