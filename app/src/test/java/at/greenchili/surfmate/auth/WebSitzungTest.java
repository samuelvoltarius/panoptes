package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.20 Web-Sitzung der Handy-App (HANDY_WEB_SITZUNG_VERTRAG, Stufe B). */
public class WebSitzungTest {

    @Test
    public void einmalCodeGiltEinmalUnd60Sekunden() {
        WebSitzung w = new WebSitzung();
        String c = w.erzeugen("k1", 1000L);
        assertNotNull(c);
        assertEquals(43, c.length());
        assertNull("falscher Code", w.einloesen("x", 1001L));
        assertEquals("k1", w.einloesen(c, 1000L + WebSitzung.GUELTIG_CODE_MS));
        assertNull("nur einmal", w.einloesen(c, 1002L));
        String d = w.erzeugen("k1", 5000L);
        assertNull("abgelaufen", w.einloesen(d, 5000L + WebSitzung.GUELTIG_CODE_MS + 1));
        String e = w.erzeugen("k2", 9000L);
        w.kopplungWeg("k2");
        assertNull("Widerruf verwirft offene Codes", w.einloesen(e, 9001L));
    }

    @Test
    public void ratenlimitJeKopplung() {
        WebSitzung w = new WebSitzung();
        for (int i = 0; i < WebSitzung.MAX_JE_MINUTE; i++) assertNotNull(w.erzeugen("k1", 100L + i));
        assertNull(w.erzeugen("k1", 200L));
        assertNotNull("andere Kopplung unberührt", w.erzeugen("k2", 200L));
        assertNotNull("nach einer Minute wieder", w.erzeugen("k1", 100L + 60_000L));
    }

    @Test
    public void zielNurEigenerPfadMitEinbettung() {
        assertEquals("/index.html", WebSitzung.ziel(null));
        assertEquals("/local/wachter.html", WebSitzung.ziel("/local/wachter.html"));
        assertEquals("/a?x=1", WebSitzung.ziel("/a?x=1"));
        assertEquals("/ mit Query findet HttpServer nicht", "/index.html", WebSitzung.ziel("/"));
        assertEquals("/trips.html", WebSitzung.ziel("/trips.html"));
        assertEquals("fremde Seite", "/index.html", WebSitzung.ziel("https://boese.example/"));
        assertEquals("protokoll-relativ", "/index.html", WebSitzung.ziel("//boese.example/"));
        assertEquals("/index.html", WebSitzung.ziel("/\\boese.example"));
        assertEquals("/index.html", WebSitzung.ziel("/a\r\nSet-Cookie: x=1"));
        assertEquals("keine Schleife", "/index.html", WebSitzung.ziel("/auth/web?einmal=x"));
    }

    static Kopplungen kopplungen() {
        JSONArray[] platte = {new JSONArray()};
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
    }

    static AuthManager.JwtValidation gueltig(String scope) {
        AuthManager.JwtValidation v = new AuthManager.JwtValidation();
        v.valid = true;
        v.scope = scope;
        return v;
    }

    @Test
    public void webJwtNurMitHandyKopplungUndRechtWeb() throws Exception {
        Kopplungen k = kopplungen();
        String handy = k.anlegen("Handy", "handy", 1L);
        String cockpit = k.anlegen("Cockpit", "cockpit", 1L);
        assertTrue("Standard: web an", k.hatRecht(handy, Kopplungen.RECHT_WEB));

        AuthManager.JwtValidation v = AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE),
                new JSONObject().put("kid", handy), k, 2L);
        assertTrue(v.valid);
        assertEquals(handy, v.kid);

        assertFalse("ohne kid", AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE), new JSONObject(), k, 2L).valid);
        assertFalse("Cockpit bekommt keine Web-Sitzung", AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE),
                new JSONObject().put("kid", cockpit), k, 2L).valid);

        k.rechteSetzen(handy, java.util.Arrays.asList(Kopplungen.RECHT_KLIMA));
        assertFalse("Recht web entzogen beendet die Sitzung", AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE),
                new JSONObject().put("kid", handy), k, 3L).valid);
        k.rechteSetzen(handy, java.util.Arrays.asList(Kopplungen.RECHT_WEB));
        assertTrue(AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE), new JSONObject().put("kid", handy), k, 4L).valid);

        k.widerrufen(handy);
        assertFalse("Widerruf beendet die Sitzung", AuthManager.kopplungPruefen(gueltig(WebSitzung.SCOPE),
                new JSONObject().put("kid", handy), k, 5L).valid);
        assertFalse("unbekannter Scope", AuthManager.kopplungPruefen(gueltig("admin"), new JSONObject().put("kid", cockpit), k, 5L).valid);
    }
}
