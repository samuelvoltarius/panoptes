package at.greenchili.surfmate.notzugang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.14 (Kern 7): Notzugang – Eingaben, Standard aus, Skript-Vertrag, Einrichtung öffnet den Assistenten nicht neu. */
public class NotzugangTest {

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

    @Test
    public void skriptVertrag() throws Exception {
        File f = new File("src/main/assets/notzugang/notzugang.sh");
        if (!f.isFile()) f = new File("app/src/main/assets/notzugang/notzugang.sh");
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertFalse("LF, nicht CRLF", s.contains("\r\n"));
        assertTrue(s.contains("D=" + Notzugang.ORDNER));
        assertTrue("eigener Socket", s.contains("--socket 127.0.0.1:8633"));
        assertTrue("zufälliger UDP-Port, kein Konflikt mit Weg 1", s.contains("--port 0"));
        assertTrue("Userspace", s.contains("--tun userspace-networking"));
        assertTrue("nur eigener tailscaled per statedir", s.contains("grep -qF -- \"--statedir $S\""));
        assertFalse("kein Namens-Kill", s.contains("pkill") || s.contains("killall"));
        assertFalse("kein Web/Serve", s.contains("serve"));
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
}
