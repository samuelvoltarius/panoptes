package at.greenchili.surfmate.tacho;

import org.json.JSONObject;
import org.junit.Test;

import static at.greenchili.surfmate.tacho.TachoKameraAnsicht.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 0.1.42 (T3): Kamera- und Video-Ansicht im Tacho. Vertrag: ab Werk aus, Seitenblick nach Kern-Regeln,
 * Heckbild nur ohne BYD-Bild (im Zweifel nichts), Rundumsicht und Video nur in P über den DrivingSafetyGuard.
 */
public class TachoKameraAnsichtTest {

    private static Wahl alles(boolean heckOhneByd) {
        return new Wahl(true, true, heckOhneByd, true, true, true, "0123456789abcdef0123456789abcdef");
    }

    @Test
    public void abWerkAllesAus() throws Exception {
        Wahl w = Wahl.aus(null);
        assertFalse(w.kamera || w.heck || w.heckOhneByd || w.seite || w.rundum || w.video);
        assertEquals("", w.videoId);
        assertEquals(KEINE, entscheide(w, "P", KEINE, Boolean.FALSE, false, null));
        assertEquals(KEINE, entscheide(w, "D", LINKS, Boolean.FALSE, false, null));
        assertFalse(videoErlaubt(w, "P", 0, null, KEINE));
        assertEquals(7, w.json().length());
    }

    @Test
    public void blinkerWieImKern() throws Exception {
        assertEquals(LINKS, blinkerSeite(1));
        assertEquals(RECHTS, blinkerSeite(2));
        assertEquals("Warnblinker = nichts", KEINE, blinkerSeite(3));
        assertEquals("unbekannt = nichts", KEINE, blinkerSeite(-1));
        assertEquals(KEINE, blinkerSeite(0));
        assertEquals(800L, NACHLAUF_MS);
        assertEquals(LINKS, mitNachlauf(KEINE, LINKS, 1000, 1799));
        assertEquals("0,8 s nach dem Blinken weg", KEINE, mitNachlauf(KEINE, LINKS, 1000, 1800));
        assertEquals("Seitenwechsel sofort", RECHTS, mitNachlauf(RECHTS, LINKS, 1000, 1001));
    }

    @Test
    public void seitenblickNieImRueckwaertsgangUndNieDoppelt() throws Exception {
        Wahl w = alles(true);
        assertEquals(LINKS, entscheide(w, "D", LINKS, Boolean.FALSE, false, "not_park"));
        assertEquals(RECHTS, entscheide(w, "D", RECHTS, null, false, "moving"));
        assertEquals("suppressInReverse", HECK, entscheide(w, "R", LINKS, Boolean.FALSE, false, "not_park"));
        assertEquals("Kern-Seitenblick an → Tacho zeigt nichts", KEINE, entscheide(w, "D", LINKS, Boolean.FALSE, true, null));
        assertEquals("Gang unbekannt → nichts", KEINE, entscheide(w, null, LINKS, Boolean.FALSE, false, null));
    }

    @Test
    public void heckbildNurWennBydSicherNichtSelbstZeigt() throws Exception {
        assertEquals(HECK, entscheide(alles(true), "R", KEINE, Boolean.FALSE, false, "not_park"));
        assertEquals("BYD hat die Kamera", KEINE, entscheide(alles(true), "R", KEINE, Boolean.TRUE, false, "not_park"));
        assertEquals("im Zweifel nichts", KEINE, entscheide(alles(true), "R", KEINE, null, false, "not_park"));
        assertEquals("ohne Bestätigung nichts", KEINE, entscheide(alles(false), "R", KEINE, Boolean.FALSE, false, "not_park"));
    }

    @Test
    public void fahrsperreRundumsichtNurInPUeberDenGuard() throws Exception {
        Wahl w = alles(true);
        assertEquals(RUNDUM, entscheide(w, "P", KEINE, Boolean.FALSE, false, null));
        assertEquals(KEINE, entscheide(w, "P", KEINE, Boolean.FALSE, false, "moving"));
        assertEquals(KEINE, entscheide(w, "P", KEINE, Boolean.FALSE, false, "state_unknown"));
        assertEquals(KEINE, entscheide(w, "N", KEINE, Boolean.FALSE, false, null));
        assertEquals(KEINE, entscheide(w, "D", KEINE, Boolean.FALSE, false, null));
        String q = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoKameraAnsicht.java");
        assertTrue("vorhandener Guard", q.contains("DrivingSafetyGuard.getMovementBlockReason()"));
        assertTrue("Guard-Fehler = gesperrt", q.contains("catch (Throwable t) { return \"state_unknown\"; }"));
    }

    @Test
    public void videoNurInPBeiNullKmh() throws Exception {
        Wahl w = alles(true);
        assertTrue(videoErlaubt(w, "P", 0, null, KEINE));
        assertTrue(videoErlaubt(w, "P", 0.4, null, KEINE));
        assertFalse(videoErlaubt(w, "P", 0.5, null, KEINE));
        assertFalse(videoErlaubt(w, "P", Double.NaN, null, KEINE));
        for (String g : new String[] {"R", "N", "D", "M", "S", null}) assertFalse(String.valueOf(g), videoErlaubt(w, g, 0, null, KEINE));
        assertFalse("Guard sperrt", videoErlaubt(w, "P", 0, "not_park", KEINE));
        assertFalse("Kamerabild hat Vorrang", videoErlaubt(w, "P", 0, null, RUNDUM));
        assertFalse("ohne Aufnahme", videoErlaubt(new Wahl(false, false, false, false, false, true, ""), "P", 0, null, KEINE));
        // Ansicht: zusätzlich eigener P-Blick, ohne Kern-Antwort sofort weg (TachoAnsichtSicherheitTest)
        assertEquals("", gueltigeId("../x"));
        assertEquals("0123456789abcdef0123456789abcdef", gueltigeId("0123456789ABCDEF0123456789ABCDEF"));
    }

    @Test
    public void einschaltenNurImStand() throws Exception {
        assertEquals("nur_im_stand", speichern(new JSONObject().put("kameraAn", true), false));
        assertEquals("nur_im_stand", speichern(new JSONObject().put("videoAn", true), false));
        assertEquals("ungueltige_aufnahme", speichern(new JSONObject().put("videoId", "a/b"), true));
    }

    @Test
    public void nurUeberDenVorhandenenKameraWeg() throws Exception {
        String q = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoKameraAnsicht.java");
        assertEquals("/api/camview/show?cam=rear&target=cluster&geometry=10/20/300/200&persist=0&autoHide=0",
                showPfad(HECK, new int[] {10, 20, 300, 200}));
        // Nur im freien Feld der Einhaengestelle (T2): ohne gueltiges Feld nichts
        assertTrue(q.contains("ziel = aktiv && feld != null ? entscheide("));
        assertTrue(q.contains("boolean video = aktiv && feld != null && videoErlaubt("));
        assertEquals(null, bereich(null));
        assertEquals(null, bereich(new org.json.JSONArray("[0,0,10,10]")));
        assertEquals(null, bereich(new org.json.JSONArray("[-1,0,300,200]")));
        assertEquals(300, bereich(new org.json.JSONArray("[0,0,300.4,200]"))[2]);
        assertTrue(q.contains("StreamingApiHandler.handleBlindSpot("));
        for (String v : new String[] {"android.hardware.camera", "Camera2", "CameraManager", "AudioTrack", "AvasController", "setTurn"})
            assertFalse(v, q.contains(v));
        assertEquals("rear", cam(HECK));
        assertEquals("bs_left", cam(LINKS));
        assertEquals("bs_right", cam(RECHTS));
        assertEquals("all", cam(RUNDUM));
        String act = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt");
        String a = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        assertTrue(a.contains("TachoKameraAnsicht.schritt(aktiv, gang(gang), kmh,"));
        assertTrue(act.contains("window.tachoInstanz.ansicht($an)"));
        String tcp = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/TcpCommandServer.java");
        assertTrue(tcp.contains("case \"tachoAnsichtEnde\":"));
        assertTrue("Pause → Kamera weg", act.contains("videoStopp()\n        kameraEnde()\n        super.onPause()"));
    }

    @Test
    public void webTeilEs5UndFuenfSprachen() throws Exception {
        String js = TachoFaehigkeitTest.lies("app/src/main/assets/web/shared/tacho-ansichten.js");
        for (String verboten : new String[] {"?.", "??", "=>", "inset:", "`", "let ", "const ", "gap:"})
            assertFalse("ES5: " + verboten, js.contains(verboten));
        String html = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/display.html");
        assertTrue(html.contains("<section id=\"tachoAnsichten\"") && html.contains("tacho-ansichten.js"));
        String[] keys = {"titel", "hilfe", "kamera", "seite", "heck", "heck_ohne_byd", "rundum", "video",
                "video_waehlen", "video_hilfe", "nur_im_stand", "fehler"};
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject o = new JSONObject(TachoFaehigkeitTest.lies("app/src/main/assets/web/i18n/" + l + ".json"))
                    .getJSONObject("tacho_ansichten");
            for (String k : keys) {
                assertTrue(l + "/" + k, o.optString(k, "").length() > 0);
                assertTrue("Schlüssel im JS: " + k, js.contains("'tacho_ansichten." + k + "'"));
            }
        }
    }
}
