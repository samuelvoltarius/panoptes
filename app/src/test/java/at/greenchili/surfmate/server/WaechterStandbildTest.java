package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.notifications.NotificationEvent;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

/** 0.1.30 Kern Sicherheit: ein Standbild je Wächter-Ereignis, ereignisId im SSE-Ereignis, Brücke prüft „standbild“. */
public class WaechterStandbildTest {

    @Before
    public void leer() {
        WaechterStandbild.leeren();
    }

    private static NotificationEvent ev(String kat, String tag, String snapshot) throws Exception {
        JSONObject d = new JSONObject().put("personCount", 1);
        if (snapshot != null) d.put("snapshot", snapshot);
        return new NotificationEvent(kat, NotificationEvent.Severity.WARN, "t", "b", tag, null, d);
    }

    @Test
    public void ereignisIdIstStabilUndVerraetNichts() {
        String a = WaechterStandbild.ereignisId("motion:event_20261030_101500.mp4");
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertEquals(a, WaechterStandbild.ereignisId("motion:event_20261030_101500.mp4"));
        assertNotEquals(a, WaechterStandbild.ereignisId("motion:event_20261030_101501.mp4"));
        assertFalse(a.contains("event"));
        assertNull(WaechterStandbild.ereignisId(null));
    }

    @Test
    public void bildNameNurAlsJpeg() {
        assertEquals("event_1.jpg", WaechterStandbild.bildAusSnapshot("/thumb/event_1.jpg?t=abc"));
        assertEquals("event_1.jpg", WaechterStandbild.bildAusSnapshot("/thumb/event_1.mp4?t=abc"));
        assertNull(WaechterStandbild.bildAusSnapshot("/video/event_1.mp4"));
        assertNull(WaechterStandbild.bildAusSnapshot("/thumb/..%2Fx.jpg"));
        assertNull(WaechterStandbild.bildAusSnapshot("/thumb/a%2Fb.jpg"));
        assertNull(WaechterStandbild.bildAusSnapshot("/thumb/event_1.txt"));
        assertNull(WaechterStandbild.bildAusSnapshot(null));
    }

    @Test
    public void startUndEndmeldungTeilenSichDieId() throws Exception {
        String tag = "motion:event_x.mp4";
        String id1 = WaechterStandbild.merke(ev("surveillance.motion", tag, null));
        assertEquals("noch_nicht", WaechterStandbild.stand(id1, System.currentTimeMillis())[0]);
        String id2 = WaechterStandbild.merke(ev("surveillance.motion.alert", tag, "/thumb/event_x.jpg?t=1"));
        assertEquals(id1, id2);
        String[] s = WaechterStandbild.stand(id1, System.currentTimeMillis());
        assertEquals("ok", s[0]);
        assertEquals("event_x.jpg", s[1]);
    }

    @Test
    public void nichtWaechterUndZuAlt() throws Exception {
        assertNull(WaechterStandbild.merke(ev("charging.started", "laden:1", null)));
        assertEquals("nicht_gefunden", WaechterStandbild.stand("0123456789abcdef0123456789abcdef", 0L)[0]);
        String id = WaechterStandbild.merke(ev("surveillance.proximity", "motion:p.mp4", "/thumb/p.jpg"));
        long spaeter = System.currentTimeMillis() + WaechterStandbild.MAX_ALTER_MS + 1000;
        assertEquals("zu_alt", WaechterStandbild.stand(id, spaeter)[0]);
    }

    @Test
    public void nurDieLetztenEreignisseBleiben() throws Exception {
        String erste = WaechterStandbild.merke(ev("surveillance.motion", "motion:0.mp4", "/thumb/0.jpg"));
        for (int i = 1; i <= WaechterStandbild.MAX_EINTRAEGE; i++) {
            WaechterStandbild.merke(ev("surveillance.motion", "motion:" + i + ".mp4", "/thumb/" + i + ".jpg"));
        }
        assertEquals("nicht_gefunden", WaechterStandbild.stand(erste, System.currentTimeMillis())[0]);
    }

    @Test
    public void hoechstensZehnAbrufeJeMinute() {
        long t = 1_000_000L;
        for (int i = 0; i < WaechterStandbild.MAX_JE_MINUTE; i++) assertTrue(WaechterStandbild.abrufErlaubt("k1", t + i));
        assertFalse(WaechterStandbild.abrufErlaubt("k1", t + 100));
        assertTrue(WaechterStandbild.abrufErlaubt("k2", t + 100));
        assertTrue(WaechterStandbild.abrufErlaubt("k1", t + 61_000));
    }

    @Test
    public void brueckePrueftStandbildEigens() {
        String p = ExternGuard.STANDBILD_PFAD + "0123456789abcdef0123456789abcdef";
        assertTrue(at.greenchili.surfmate.bruecke.BrueckeTestZugang.istStandbildPfad(p));
        assertFalse(at.greenchili.surfmate.bruecke.BrueckeTestZugang.istKameraPfad(p));
        assertFalse(at.greenchili.surfmate.bruecke.BrueckeTestZugang.istStandbildPfad("/thumb/id/x"));
    }

    /** Standbild liefert nur ein vorhandenes Hero-JPEG – nie Video, nie Unfall-Clips; ereignisId geht ins SSE. */
    @Test
    public void nurJpegUndEreignisIdImSse() throws IOException {
        String h = ExternGuardKameraRechtTest.lies("app/src/main/java/at/greenchili/surfmate/server/WaechterStandbild.java");
        assertTrue(h.contains("HttpResponse.sendImage(out, f, \"image/jpeg\")"));
        assertFalse(h.contains("streamVideo"));
        assertFalse(h.contains("video/mp4"));
        String r = ExternGuardKameraRechtTest.lies("app/src/main/java/at/greenchili/surfmate/server/RecordingsApiHandler.java");
        String hilfe = r.substring(r.indexOf("static File standbildDatei("), r.indexOf("private static boolean ereignisVonAussen("));
        assertTrue(hilfe.contains("endsWith(\".jpg\")"));
        assertTrue(hilfe.contains("EreignisClip.istEreignisPfad"));
        String dienst = ExternGuardKameraRechtTest.lies("app/src/main/java/at/greenchili/surfmate/ha/HaDienst.java");
        int merke = dienst.indexOf("WaechterStandbild.merke(e)");
        int entprellen = dienst.indexOf("durchlassen(art, e.timestamp)");
        assertTrue(merke > 0 && merke < entprellen);
        assertTrue(dienst.contains("HaEreignisse.waechterEreignis(art, e.timestamp, ereignisId)"));
        String ha = ExternGuardKameraRechtTest.lies("app/src/main/java/at/greenchili/surfmate/ha/HaEreignisse.java");
        assertTrue(ha.contains("d.put(\"ereignisId\", ereignisId)"));
    }
}
