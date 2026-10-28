package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.server.WebHandyGuardTest;
import at.greenchili.surfmate.telegram.CloudUebergabeKnopf;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 0.1.25 „weiteres Gerät koppeln“ (KOPPLUNG_WEITERES_GERAET_VERTRAG): nie automatisch, QR genau einmal, ohne Brücke 409. */
public class WeiteresGeraetTest {

    long jetzt = 1_800_000_000_000L;
    boolean bereit = true;
    int qrAufrufe;
    final List<String> meldungen = new ArrayList<>();

    final WeiteresGeraet w = new WeiteresGeraet(() -> jetzt, new SecureRandom(), new WeiteresGeraet.QrQuelle() {
        public boolean brueckeBereit() { return bereit; }
        public String qr(long t) throws Exception {
            qrAufrufe++;
            if (!bereit) return null;
            return KopplungsQr.url("https://byd.serveone.at/p/",
                    KopplungsQr.inhalt("ABC123", null, "pP_-0123456789abcdefghijklmnopqrstuvwxyzAB", "aA_-0123456789abcdefghijklmnopqrstuvwxyzAB",
                            "zZ_-0123456789abcdefghijklmnopqrstuvwxyzAB"));
        }
    }, new WeiteresGeraet.Melder() {
        public void anfrage(String ref, String geraet, String pruefzahl) { meldungen.add("anfrage " + geraet + " " + pruefzahl); }
        public void entschieden(String ref, boolean erlaubt) { meldungen.add("entschieden " + erlaubt); }
        public void protokoll(String zeile) { }
    });

    static JSONObject body(String art, String pruefzahl) throws Exception {
        return new JSONObject().put("art", art).put("geraet", "Wohnzimmer-PC").put("pruefzahl", pruefzahl);
    }

    @Test
    public void anfrageNurCockpitHandyUndMitBruecke() throws Exception {
        assertEquals(401, w.anfrage(null, null, body("handy", "1234")).status);
        assertEquals("nur_cockpit", w.anfrage("k-h", "handy", body("handy", "1234")).json.getString("grund"));
        assertEquals("art", w.anfrage("k-c", "cockpit", body("cockpit", "1234")).json.getString("grund"));
        assertEquals("pruefzahl", w.anfrage("k-c", "cockpit", body("handy", "12a4")).json.getString("grund"));
        bereit = false;
        WeiteresGeraet.Antwort a = w.anfrage("k-c", "cockpit", body("handy", "1234"));
        assertEquals(409, a.status);
        assertEquals("bruecke_aus", a.json.getString("grund"));
        assertTrue("keine Rückfrage ohne Brücke", meldungen.isEmpty());
    }

    @Test
    public void erlaubenNurDurchDenBesitzerUndQrGenauEinmal() throws Exception {
        WeiteresGeraet.Antwort a = w.anfrage("k-c", "cockpit", body("handy", "4711"));
        assertEquals(200, a.status);
        String id = a.json.getString("id");
        assertTrue(id.matches(WeiteresGeraet.REF_MUSTER));
        assertEquals(jetzt + WeiteresGeraet.GUELTIG_MS, a.json.getLong("gueltigBis"));
        assertEquals("[anfrage Wohnzimmer-PC 4711]", meldungen.toString());
        assertEquals("nur eine offene Anfrage", 409, w.anfrage("k-c", "cockpit", body("handy", "1111")).status);
        assertEquals(1, w.offene().length());
        assertEquals("4711", w.offene().getJSONObject(0).getString("pruefzahl"));

        JSONObject s = w.status(id, "k-c").json;
        assertEquals("wartet", s.getString("status"));
        assertFalse("nie automatisch", s.has("qr"));
        assertEquals("fremde Kopplung sieht nichts", 404, w.status(id, "k-andere").status);
        assertEquals(0, qrAufrufe);

        assertEquals(200, w.entscheide(id, true).status);
        assertEquals(409, w.entscheide(id, false).status);
        s = w.status(id, "k-c").json;
        assertEquals("erlaubt", s.getString("status"));
        String qr = s.getString("qr");
        assertTrue(qr.startsWith("https://byd.serveone.at/p/#k="));
        assertTrue(qr.length() <= KopplungsQr.MAX_LAENGE);
        JSONObject j = new JSONObject(new String(Base64.getUrlDecoder().decode(qr.substring(qr.indexOf("#k=") + 3)), StandardCharsets.UTF_8));
        assertEquals(2, j.getInt("v"));
        assertEquals("ABC123", j.getString("c"));
        assertFalse("nur Brücke, keine Adresse", j.has("u"));
        assertTrue(j.getJSONObject("b").has("z"));
        s = w.status(id, "k-c").json;
        assertEquals("abgeholt", s.getString("status"));
        assertFalse("QR genau einmal", s.has("qr"));
        assertEquals(1, qrAufrufe);
        assertEquals("danach wieder eine neue Anfrage möglich", 200, w.anfrage("k-c", "cockpit", body("handy", "2222")).status);
    }

    @Test
    public void ablehnenAblaufBrueckeWegUndRatenlimit() throws Exception {
        String id = w.anfrage("k-c", "cockpit", body("handy", "1000")).json.getString("id");
        w.entscheide(id, false);
        assertEquals("abgelehnt", w.status(id, "k-c").json.getString("status"));
        assertFalse(w.status(id, "k-c").json.has("qr"));

        id = w.anfrage("k-c", "cockpit", body("handy", "1001")).json.getString("id");
        jetzt += WeiteresGeraet.GUELTIG_MS + 1;
        assertEquals("abgelaufen", w.status(id, "k-c").json.getString("status"));
        assertEquals(410, w.entscheide(id, true).status);

        id = w.anfrage("k-c", "cockpit", body("handy", "1002")).json.getString("id");
        w.entscheide(id, true);
        bereit = false;   // Brücke zwischen Erlauben und Abholen ausgeschaltet
        assertEquals("bruecke_aus", w.status(id, "k-c").json.getString("grund"));
        bereit = true;
        assertEquals("erlaubt + noch nicht abgeholt: keine zweite", 409, w.anfrage("k-c", "cockpit", body("handy", "1003")).status);
        assertTrue(w.status(id, "k-c").json.has("qr"));
        assertEquals("3 je Stunde und Kopplung", 429, w.anfrage("k-c", "cockpit", body("handy", "1003")).status);
    }

    @Test
    public void schrankenUndWege() throws Exception {
        // ExternGuard: Cockpit darf anfragen + Status, nie entscheiden; Handy/Kopplung ohne Art gar nicht
        assertFalse(ExternGuard.blocks("POST", "/api/kopplung/weiteres-geraet", "cockpit"));
        assertFalse(ExternGuard.blocks("GET", "/api/kopplung/weiteres-geraet/r-0123456789abcdef", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/weiteres-geraet/r-0123456789abcdef/erlauben", "cockpit"));
        assertTrue(ExternGuard.blocks("GET", "/api/kopplung/weiteres-geraet", "cockpit"));
        assertTrue(ExternGuard.blocks("POST", "/api/kopplung/weiteres-geraet", "handy"));
        // Telegram-Knopf: eigene Vorsilbe, eigener Pfad
        String[] k = CloudUebergabeKnopf.lies(CloudUebergabeKnopf.datenWeiteres("0123456789abcdef", true));
        assertEquals("wg", k[2]);
        assertEquals("/api/kopplung/weiteres-geraet/r-0123456789abcdef/erlauben", CloudUebergabeKnopf.pfad(k, true));
        assertEquals("weiteres_geraet.erlaubt", CloudUebergabeKnopf.ergebnisSchluessel(k, 200, true));
        String[] c = CloudUebergabeKnopf.lies(CloudUebergabeKnopf.daten("0123456789abcdef", false));
        assertEquals("/api/cloud-uebergabe/r-0123456789abcdef/ablehnen", CloudUebergabeKnopf.pfad(c, false));
        assertNull(CloudUebergabeKnopf.lies("wg:zzz:j"));
        // Overlay nimmt den Pfad an, Web-Sitzung und Kopplungen dürfen nicht entscheiden
        assertTrue(WebHandyGuardTest.quelle("java/at/greenchili/surfmate/overlay/MessageOverlayService.java")
                .contains("pfad.equals(\"/api/kopplung/weiteres-geraet/r-\" + ref)"));
        assertTrue(WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/WebHandyGuard.java")
                .contains("\"/api/kopplung/weiteres-geraet\""));
        String h = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/WeiteresGeraetApiHandler.java");
        assertTrue(h.contains("if (extern) a = fehler(403, \"nur_besitzer\");"));
        assertTrue("QR nur mit Brücke + Zustimmung", h.contains("Bruecke.get().ein() && at.greenchili.surfmate.bruecke.Zustimmung.get().erteilt()"));
        // Texte in allen Sprachen
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(WebHandyGuardTest.quelle("assets/server-i18n/" + l + ".json"));
            assertTrue(l, d.getJSONObject("telegram").getJSONObject("weiteres_geraet").getString("anfrage").contains("{1}"));
            assertTrue(l, d.getJSONObject("notifications").getString("weiteres_geraet_titel").contains("{0}"));
            assertFalse(l, d.getJSONObject("telegram").getJSONObject("weiteres_geraet").getString("anfrage").contains("'"));
        }
    }
}
