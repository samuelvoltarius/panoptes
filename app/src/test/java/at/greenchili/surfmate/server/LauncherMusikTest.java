package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** M1 (13.11.): Titel/Interpret für den Launcher aus dumpsys media_session, nur lesen. */
public class LauncherMusikTest {

    /** Aufbau wie MediaSessionRecord.dump (Android 10); Telecom immer da, CarPlay mit verbundenem iPhone. */
    static final String ABBILD = String.join("\n",
            "MEDIA SESSION SERVICE (dumpsys media_session)",
            "",
            "4 sessions listeners.",
            "Global priority session is com.android.server.telecom/HeadsetMediaButton (userId=0)",
            "  HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton (userId=0)",
            "    ownerPid=1234, ownerUid=1000, userId=0",
            "    package=com.android.server.telecom",
            "    launchIntent=null",
            "    mediaButtonReceiver=null",
            "    active=false",
            "    flags=65536",
            "    rating type=0",
            "    controllers: 0",
            "    state=null",
            "    audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x800 tags= bundle=null",
            "    volumeType=1, controlType=2, max=0, current=0",
            "    metadata: null",
            "    queueTitle=null, size=0",
            "Sessions Stack - have 3 sessions:",
            "  MediaManager com.byd.carplay.ui/MediaManager (userId=0)",
            "    ownerPid=3166, ownerUid=1000, userId=0",
            "    package=com.byd.carplay.ui",
            "    active=true",
            "    flags=1",
            "    state=PlaybackState {state=2, position=0, buffered position=0, speed=0.0, updated=1, actions=53, custom actions=[], active item id=-1, error=null}",
            "    metadata: size=3, description=Bohemian Rhapsody, Queen, null",
            "  Spotify com.spotify.music/spotify-media-session (userId=0)",
            "    ownerPid=5000, ownerUid=10120, userId=0",
            "    package=com.spotify.music",
            "    active=true",
            "    state=PlaybackState {state=1, position=0, buffered position=0, speed=0.0, updated=1, actions=0, custom actions=[], active item id=-1, error=null}",
            "    metadata: size=5, description=Song A, Artist A, Album A",
            "Media button session is com.byd.carplay.ui/MediaManager (userId=0)");

    @Test
    public void parserLiestSitzungen() {
        List<LauncherMusik.Sitzung> l = LauncherMusik.parse(ABBILD);
        assertEquals(3, l.size());
        assertEquals("com.android.server.telecom", l.get(0).paket);
        assertFalse(l.get(0).aktiv);
        assertEquals(-1, l.get(0).zustand);
        assertNull(l.get(0).beschreibung);
        assertEquals("com.byd.carplay.ui", l.get(1).paket);
        assertTrue(l.get(1).aktiv);
        assertEquals(2, l.get(1).zustand);
        assertEquals("Bohemian Rhapsody, Queen, null", l.get(1).beschreibung);
        assertEquals(1, l.get(2).zustand);
    }

    @Test
    public void wahlWieImLauncher() {
        List<LauncherMusik.Sitzung> l = LauncherMusik.parse(ABBILD);
        // keine spielt: CarPlay vor dem Rest, Telecom nie
        assertEquals("com.byd.carplay.ui", LauncherMusik.waehle(l, null).paket);
        assertEquals("com.byd.carplay.ui", LauncherMusik.waehle(l, "com.spotify.music").paket);
        // spielende Sitzung gewinnt
        List<LauncherMusik.Sitzung> s = LauncherMusik.parse(ABBILD.replace("state=1,", "state=3,"));
        assertEquals("com.spotify.music", LauncherMusik.waehle(s, null).paket);
        // nur Telecom (inaktiv): nichts
        assertNull(LauncherMusik.waehle(LauncherMusik.parse(ABBILD.substring(0, ABBILD.indexOf("Sessions Stack"))), null));
        // inaktive Sitzung zählt nicht
        assertEquals("com.spotify.music", LauncherMusik.waehle(LauncherMusik.parse(
                ABBILD.replace("package=com.byd.carplay.ui\n    active=true", "package=com.byd.carplay.ui\n    active=false")), null).paket);
    }

    @Test
    public void titelUndInterpretOhneRaten() {
        String[] a = LauncherMusik.titelKuenstler("Bohemian Rhapsody, Queen, null");
        assertEquals("Bohemian Rhapsody", a[0]);
        assertEquals("Queen", a[1]);
        a = LauncherMusik.titelKuenstler("Song A, Artist A, Album A");
        assertEquals("Song A", a[0]);
        assertEquals("Artist A", a[1]);
        a = LauncherMusik.titelKuenstler("null, null, null");
        assertNull(a[0]);
        assertNull(a[1]);
        // Komma im Titel: mehrdeutig, ganze Zeile als Titel, kein Interpret
        a = LauncherMusik.titelKuenstler("Hello, Goodbye, The Beatles, null");
        assertEquals("Hello, Goodbye, The Beatles", a[0]);
        assertNull(a[1]);
        assertNull(LauncherMusik.titelKuenstler(null)[0]);
    }

    @Test
    public void antwortEhrlich() throws Exception {
        JSONObject o = LauncherMusik.antwort(ABBILD, null);
        assertTrue(o.getBoolean("ok"));
        assertTrue(o.getBoolean("lesbar"));
        assertEquals("com.byd.carplay.ui", o.getString("paket"));
        assertEquals("Bohemian Rhapsody", o.getString("titel"));
        assertEquals("Queen", o.getString("kuenstler"));
        assertFalse(o.getBoolean("spielt"));
        // ohne Rechte oder ohne Dienst: nicht lesbar, keine erfundenen Werte
        JSONObject n = LauncherMusik.antwort("Permission Denial: can't dump MediaSessionService from pid=1", null);
        assertFalse(n.getBoolean("lesbar"));
        assertFalse(n.has("paket"));
        assertFalse(LauncherMusik.antwort(null, null).getBoolean("lesbar"));
        assertFalse(LauncherMusik.antwort("", null).getBoolean("lesbar"));
        // lesbar, aber keine Sitzung
        JSONObject leer = LauncherMusik.antwort("MEDIA SESSION SERVICE (dumpsys media_session)\nSessions Stack - have 0 sessions:\n", null);
        assertTrue(leer.getBoolean("lesbar"));
        assertTrue(leer.isNull("paket"));
    }

    @Test
    public void wegNurLesenNurLauncher() throws Exception {
        String h = quelle("server/LauncherApiHandler.java");
        int i = h.indexOf("if (cleanPath.equals(LauncherMusik.PFAD))");
        assertTrue(i > 0);
        String block = h.substring(i, h.indexOf("LauncherHaus.PFAD", i));
        assertTrue(block.contains("launcherDarf(out)"));
        assertTrue(block.contains("!\"GET\".equals(method)"));
        String m = quelle("server/LauncherMusik.java");
        assertTrue(m.contains("new ProcessBuilder(\"dumpsys\", \"media_session\")"));
        // nur lesen: kein Steuern, keine Rechte, kein Log, keine Einstellungen
        for (String f : new String[]{"dispatch", "allow_listener", "appops", "settings put", "BefehlsLog", "DaemonLogger", "transportControls"}) {
            assertFalse(f, m.contains(f));
        }
        assertEquals("/api/launcher/v1/musik", LauncherMusik.PFAD);
    }

    private static String quelle(String rel) throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/" + rel);
        if (!f.isFile()) f = new File("app/src/main/java/at/greenchili/surfmate/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
