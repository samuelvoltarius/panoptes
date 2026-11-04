package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.monitor.GearMonitor;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;

/**
 * 0.1.38.1 („warum läuft Spotify nur in P?“): Musik-, Radio-, Podcast- und Hörbuch-Apps sind keine Video-Apps
 * und dürfen ab Werk auch beim Fahren laufen, wie Navi-Apps. Video-Apps bleiben nur in P bzw. nach eigenem Hinweis.
 */
public class TachoAudioFahrtTest {

    private final java.util.function.Predicate<String> vorher = TachoNaviApp.audioErkennung;

    @After
    public void zurueck() { TachoNaviApp.audioErkennung = vorher; }

    @Test
    public void musikAbWerkAuchBeimFahren() {
        TachoNaviApp.audioErkennung = p -> false;
        JSONObject leer = new JSONObject();
        for (String p : new String[] {"com.spotify.music", "deezer.android.app", "com.aspiro.tidal", "com.amazon.mp3",
                "com.google.android.apps.youtube.music", "com.audible.application", "tunein.player", "de.radio.android"}) {
            assertTrue(p, TachoNaviApp.istAudio(p));
            assertTrue(p, TachoNaviApp.fahrtErlaubt(p, leer));
            assertFalse("bleibt beim Fahren an: " + p,
                    TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, p, true, TachoNaviApp.fahrtErlaubt(p, leer)));
            assertFalse(p, TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, p, true));
        }
        // nicht freigegeben bleibt gesperrt, auch fuer Musik
        assertTrue(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, "com.spotify.music", false));
    }

    @Test
    public void videoBleibtNurInP() {
        TachoNaviApp.audioErkennung = p -> true;   // selbst wenn Android „Audio“ meldet: Video gewinnt
        JSONObject leer = new JSONObject();
        for (String p : new String[] {"com.google.android.youtube", "com.netflix.mediaclient", "org.videolan.vlc",
                "com.disney.disneyplus", "tv.twitch.android.app"}) {
            assertFalse(p, TachoNaviApp.istAudio(p));
            assertFalse(p, TachoNaviApp.fahrtErlaubt(p, leer));
            assertTrue(p, TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, p, true));
        }
        TachoNaviApp.audioErkennung = p -> false;
        assertFalse("Unbekanntes bleibt nur in P", TachoNaviApp.fahrtErlaubt("org.beliebig.spiel", leer));
    }

    @Test
    public void nachEinmaligemHinweisJedeFreigegebeneApp() throws Exception {
        // Projektinhaber 04.11.: „am Tacho alle Apps, es ist die eigene Entscheidung des Users“
        TachoNaviApp.audioErkennung = p -> false;
        JSONObject leer = new JSONObject();
        for (String p : new String[] {"com.google.android.youtube", "com.netflix.mediaclient", "org.beliebig.spiel"}) {
            assertFalse("ohne Hinweis nur in P: " + p, TachoNaviApp.fahrtErlaubt(p, leer, false));
            assertTrue("nach dem einen Hinweis auch beim Fahren: " + p, TachoNaviApp.fahrtErlaubt(p, leer, true));
            assertFalse(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, p, true, TachoNaviApp.fahrtErlaubt(p, leer, true)));
            assertTrue("nicht freigegeben bleibt gesperrt",
                    TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_D, p, false, TachoNaviApp.fahrtErlaubt(p, leer, true)));
        }
        // Eigene Entscheidung je App bleibt: ausdruecklich „aus“
        assertFalse(TachoNaviApp.fahrtErlaubt("com.netflix.mediaclient",
                new JSONObject().put("com.netflix.mediaclient", false), true));
        // Hinweis gilt nur in der aktuellen Fassung (Version + Zeit), alte Fassung zaehlt nicht
        assertFalse(TachoNaviApp.fahrtHinweisBestaetigt(new JSONObject().put("version", "2026-11-03").put("zeit", 5L)));
        assertTrue(TachoNaviApp.fahrtHinweisBestaetigt(
                new JSONObject().put("version", TachoNaviApp.FAHRT_HINWEIS_VERSION).put("zeit", 5L)));
        String n = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(n.contains("return fahrtErlaubt(paket, fahrtListe(), fahrtHinweisBestaetigt());"));
        String w = TachoFaehigkeitTest.lies("app/src/main/assets/web/shared/tacho-appwahl.js");
        assertTrue("keine Nachfrage je App nach dem Hinweis",
                w.contains("if (data && data.fahrtHinweis && data.fahrtHinweis.bestaetigt) { weiter(); return; }"));
    }

    @Test
    public void erkennungUndEigeneEntscheidung() throws Exception {
        TachoNaviApp.audioErkennung = p -> p.equals("org.radio.erkannt");
        assertTrue(TachoNaviApp.istAudio("org.radio.erkannt"));
        assertTrue(TachoNaviApp.fahrtErlaubt("org.radio.erkannt", new JSONObject()));
        // Der Nutzer kann Musik ausdruecklich auf „nur in P“ stellen
        assertFalse(TachoNaviApp.fahrtErlaubt("com.spotify.music", new JSONObject().put("com.spotify.music", false)));
        // Wieder-Einschalten braucht fuer Musik keinen Haftungshinweis (Navi ebenso), Video schon
        assertNull(TachoNaviApp.fahrtSperreSetzen("com.spotify.music", true, true,
                TachoNaviApp.fahrtStandard("com.spotify.music")));
        assertTrue("fahrt_hinweis_noetig".equals(TachoNaviApp.fahrtSperreSetzen("com.google.android.youtube", true, true,
                TachoNaviApp.fahrtStandard("com.google.android.youtube"))));
    }

    @Test
    public void erkennungNurUeberAndroidKategorieOderMediendienst() throws IOException {
        String l = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/launcher/AppLauncher.java");
        int i = l.indexOf("public static boolean istAudioApp(String pkg)");
        assertTrue(i > 0);
        String m = l.substring(i, l.indexOf("\n    }\n", i));
        assertTrue(m.contains("CATEGORY_VIDEO) return false"));
        assertTrue(m.contains("CATEGORY_AUDIO) return true"));
        assertTrue(m.contains("android.media.browse.MediaBrowserService"));
        String n = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(n.contains("fahrtHinweisBestaetigt() || fahrtStandard(p)"));
        assertTrue(n.contains("return hinweisBestaetigt || fahrtStandard(p);"));
    }
}
