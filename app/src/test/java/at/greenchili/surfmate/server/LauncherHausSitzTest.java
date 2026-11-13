package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** 0.1.44 (P4): Haus-Kachel (HA, nur lesen) und Sitzposition für Fahrerprofile (nur lesen) im Kern. */
public class LauncherHausSitzTest {

    private static JSONObject bestand() throws Exception {
        JSONArray l = new JSONArray();
        l.put(new JSONObject().put("entity", "cover.garage").put("domaene", "cover").put("art", "tor")
                .put("titel", "Garage").put("raum", "Haus").put("zustand", "open").put("plattform", "x")
                .put("faehigkeiten", new JSONObject().put("minTemp", 5)));
        l.put(new JSONObject().put("entity", "light.flur").put("art", "licht").put("titel", "Flur").put("zustand", "on"));
        l.put(new JSONObject().put("entity", "switch.wallbox").put("art", "schalter").put("titel", "Wallbox")
                .put("zustand", JSONObject.NULL));
        return new JSONObject().put("v", 1).put("zeit", 100_000L).put("geraete", l)
                .put("freigaben", new JSONObject().put("cover.garage", true));
    }

    @Test
    public void listeOhneIdsHatNurAnzeigefelder() throws Exception {
        JSONObject r = LauncherHaus.antwort(bestand(), null, 160_000L);
        assertEquals(3, r.getJSONArray("geraete").length());
        JSONObject g = r.getJSONArray("geraete").getJSONObject(0);
        assertEquals("open", g.getString("zustand"));
        assertEquals("Haus", g.getString("raum"));
        for (String verboten : new String[]{"faehigkeiten", "plattform", "steuern", "freigaben"}) assertFalse(g.has(verboten));
        assertFalse(r.has("freigaben"));
        assertEquals(60, r.getLong("alterSek"));
        assertTrue(r.getJSONArray("geraete").getJSONObject(2).isNull("zustand"));
    }

    @Test
    public void auswahlHoechstensSechsInReihenfolgeUndFehlendeGemeldet() throws Exception {
        assertEquals(Arrays.asList("light.flur", "cover.garage"), LauncherHaus.ids("light.flur, cover.garage,light.flur,xx,"));
        assertEquals(6, LauncherHaus.ids("a.a,a.b,a.c,a.d,a.e,a.f,a.g,a.h").size());
        assertTrue(LauncherHaus.ids("Haus;DROP,../x").isEmpty());
        JSONObject r = LauncherHaus.antwort(bestand(), Arrays.asList("light.flur", "light.weg", "cover.garage"), 0L);
        assertEquals("light.flur", r.getJSONArray("geraete").getJSONObject(0).getString("entity"));
        assertEquals(2, r.getJSONArray("geraete").length());
        assertEquals("light.weg", r.getJSONArray("fehlt").getString(0));
    }

    @Test
    public void leererBestandIstOkMitLeererListe() throws Exception {
        JSONObject r = LauncherHaus.antwort(null, null, 5L);
        assertTrue(r.getBoolean("ok"));
        assertEquals(0, r.getJSONArray("geraete").length());
        assertTrue(r.isNull("zeit"));
    }

    private static JSONArray positionen() throws Exception {
        JSONArray a = new JSONArray();
        a.put(new JSONObject().put("id", "p1").put("name", "Anna - Pos 1").put("alias", "Anna")
                .put("axes", new JSONObject().put("HORIZONTAL", 40).put("VERTICAL", 20).put("BACK", 30).put("ST_H", 127.5)));
        a.put(new JSONObject().put("id", "p2").put("name", "Ben")
                .put("axes", new JSONObject().put("HORIZONTAL", 60).put("VERTICAL", 25).put("BACK", 50)));
        a.put(new JSONObject().put("id", "leer").put("name", "nur Licht").put("ambient", new JSONObject().put("x", 1)));
        return a;
    }

    @Test
    public void sitzMeldetNurDieEinzigePassendePosition() throws Exception {
        JSONObject live = new JSONObject().put("HORIZONTAL", 41).put("VERTICAL", 20.5).put("BACK", 29).put("ST_H", 3);
        assertEquals("p1", LauncherSitz.passend(live, positionen()));
        assertNull("außerhalb der Toleranz", LauncherSitz.passend(new JSONObject().put("HORIZONTAL", 50).put("VERTICAL", 22)
                .put("BACK", 40), positionen()));
        assertNull("zu wenige vergleichbare Achsen", LauncherSitz.passend(new JSONObject().put("HORIZONTAL", 40)
                .put("VERTICAL", 20), positionen()));
        assertNull("nicht lesbar", LauncherSitz.passend(null, positionen()));
        // Platzhalterwert 127,5 = „unbekannt“ zählt nie als Vergleich
        assertNull(LauncherSitz.passend(new JSONObject().put("HORIZONTAL", 127.5).put("VERTICAL", 127.5).put("BACK", 127.5),
                positionen()));
    }

    @Test
    public void zweiGleichNaheSindMehrdeutigUndWerdenNichtGemeldet() throws Exception {
        JSONArray a = new JSONArray();
        for (String id : new String[]{"a", "b"})
            a.put(new JSONObject().put("id", id).put("name", id)
                    .put("axes", new JSONObject().put("X", 10).put("Y", 10).put("Z", 10)));
        assertNull(LauncherSitz.passend(new JSONObject().put("X", 10).put("Y", 10).put("Z", 10), a));
    }

    @Test
    public void sitzAntwortListeNurPositionenMitGeometrieAliasZuerst() throws Exception {
        JSONObject r = LauncherSitz.antwort(positionen(), new JSONObject().put("HORIZONTAL", 61).put("VERTICAL", 25)
                .put("BACK", 50));
        assertEquals(2, r.getJSONArray("positionen").length());
        assertEquals("Anna", r.getJSONArray("positionen").getJSONObject(0).getString("name"));
        assertEquals("p2", r.getString("aktuell"));
        assertTrue(r.getBoolean("lesbar"));
        JSONObject n = LauncherSitz.antwort(positionen(), null);
        assertFalse(n.getBoolean("lesbar"));
        assertTrue(n.isNull("aktuell"));
    }

    @Test
    public void wegeSindNurLesenUndNurFuerDieLauncherApk() throws Exception {
        String h = quelle("server/LauncherApiHandler.java");
        int i = h.indexOf("LauncherHaus.PFAD) || cleanPath.equals(LauncherSitz.PFAD)");
        assertTrue(i > 0);
        String block = h.substring(i, h.indexOf("TACHO_NORMAL_PFAD", i));
        assertTrue(block.contains("launcherDarf(out)"));
        assertTrue(block.contains("!\"GET\".equals(method)"));
        // nichts Schreibendes im Kern-Teil: kein Setzen, kein Anwenden, kein HA-Steuern
        for (String f : new String[]{"HaSteuer", "apply", "schreibe", "setze", "capture"}) {
            assertFalse(f, quelle("server/LauncherHaus.java").contains(f) && !f.equals("apply"));
            assertFalse(f, quelle("server/LauncherSitz.java").contains(f + "("));
        }
        assertTrue(at.greenchili.surfmate.server.LauncherLayoutPruefung.KACHELN.contains("haus"));
        assertTrue(at.greenchili.surfmate.server.LauncherLayoutPruefung.KACHELN.contains("profil"));
        // Web-Editor darf die neuen Kacheln beim Speichern nicht verlieren; Namen in fünf Sprachen
        String web = new String(Files.readAllBytes(webDatei("local/launcher.html").toPath()), StandardCharsets.UTF_8);
        assertTrue(web.contains("KACHELN.push('haus', 'profil')"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            String j = new String(Files.readAllBytes(webDatei("i18n/" + l + ".json").toPath()), StandardCharsets.UTF_8);
            assertTrue(l, j.contains("\"k_haus\"") && j.contains("\"k_profil\""));
        }
    }

    private static File webDatei(String rel) {
        File f = new File("src/main/assets/web/" + rel);
        return f.isFile() ? f : new File("app/src/main/assets/web/" + rel);
    }

    private static String quelle(String rel) throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/" + rel);
        if (!f.isFile()) f = new File("app/src/main/java/at/greenchili/surfmate/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
