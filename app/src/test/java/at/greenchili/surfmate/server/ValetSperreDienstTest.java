package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Daemon-Seite der Display-Sperre: weicht bei R, Fahrt und Neustart; am Display selbst nur mit PIN aufzuheben. */
public class ValetSperreDienstTest {

    private static final class FalscheLage implements ValetSperreDienst.Lage {
        String grund;
        boolean r;
        String vorne;

        @Override public String bewegungsGrund() { return grund; }
        @Override public boolean rueckwaerts() { return r; }
        @Override public String vordergrund() { return vorne; }
    }

    private final FalscheLage lage = new FalscheLage();
    private final List<JSONObject> gesendet = new ArrayList<>();
    private final ValetSperreDienst d = new ValetSperreDienst(lage, gesendet::add);

    private boolean zuletztGezeigt() {
        return gesendet.get(gesendet.size() - 1).optBoolean("zeigen");
    }

    @Test
    public void anNurImStandUndOverlayWirdBestaetigt() {
        lage.grund = "moving";
        assertEquals("nicht_in_p", d.anschalten("1357"));
        assertTrue(gesendet.isEmpty());
        lage.grund = null;
        assertNull(d.anschalten("1357"));
        assertTrue(zuletztGezeigt());
        assertEquals("valet", gesendet.get(0).optString("command"));
        assertTrue(gesendet.get(0).optJSONObject("texte").has("titel"));
    }

    @Test
    public void weichtBeiRueckwaertsgang() {
        lage.grund = null;
        assertNull(d.anschalten("1357"));
        lage.r = true;
        lage.grund = "not_park";
        d.tick();
        assertFalse(zuletztGezeigt());
        assertFalse(d.regel().aktiv());
    }

    @Test
    public void weichtBeiFahrt() {
        lage.grund = null;
        assertNull(d.anschalten("1357"));
        lage.grund = "moving";
        d.tick();
        assertFalse(zuletztGezeigt());
        assertFalse(d.regel().aktiv());
        lage.grund = null;
        d.tick();
        assertFalse("nach der Fahrt nicht von selbst wieder an", zuletztGezeigt());
    }

    @Test
    public void weichtBeiNeustartDesDaemons() {
        lage.grund = null;
        assertNull(d.anschalten("1357"));
        assertTrue(d.regel().aktiv());
        ValetSperreDienst neu = new ValetSperreDienst(lage, gesendet::add);
        assertFalse(neu.regel().aktiv());
        neu.tick();
        assertFalse(zuletztGezeigt());
    }

    @Test
    public void kameraZiehtZurueckOhneAufzuheben() {
        lage.grund = null;
        assertNull(d.anschalten("1357"));
        lage.vorne = "com.byd.avm";
        d.tick();
        assertFalse(zuletztGezeigt());
        assertTrue(d.regel().aktiv());
        lage.vorne = "com.spotify.music";
        d.tick();
        assertTrue(zuletztGezeigt());
    }

    @Test
    public void vordergrundAusDumpsys() {
        assertEquals("com.byd.avm", ValetSperreDienst.vordergrundAus(
                "  mResumedActivity: ActivityRecord{1a2b u0 com.byd.avm/.MainActivity t12}"));
        assertNull(ValetSperreDienst.vordergrundAus(null));
        assertNull(ValetSperreDienst.vordergrundAus("nichts"));
    }

    @Test
    public void amDisplaySelbstHebtAusOhnePinNichtAuf() throws Exception {
        DisplayApiHandler.DirektAmAuto vorher = DisplayApiHandler.direkt;
        try {
            DisplayApiHandler.direkt = () -> true;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(DisplayApiHandler.handle("POST", "/api/display/sperre/aus", "{}", out));
            String antwort = out.toString(StandardCharsets.UTF_8.name());
            assertTrue(antwort, antwort.startsWith("HTTP/1.1 403"));
            assertTrue(antwort, antwort.contains("\"ok\":false"));
        } finally {
            DisplayApiHandler.direkt = vorher;
        }
    }
}
