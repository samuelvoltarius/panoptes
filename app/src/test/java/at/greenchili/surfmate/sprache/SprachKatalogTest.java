package at.greenchili.surfmate.sprache;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.43.3 (Rang 7) „Was kann ich sagen?“: Der Katalog für den Launcher kommt aus denselben Sätzen wie die Erkennung,
 * nur Befehle, die es gibt, ehrliche Art (Klima immer mit Rückfrage), angetippte Sätze laufen über denselben Weg.
 */
public class SprachKatalogTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("..", rel);
        if (!f.isFile()) f = new File(rel.replaceFirst("^app/", ""));
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void katalogAusDenSelbenSaetzen() throws Exception {
        for (String sp : SprachBefehle.SPRACHEN) {
            JSONObject k = SprachBefehle.katalog(sp);
            assertEquals(sp, k.getString("sprache"));
            assertEquals(SprachBefehle.erkennbar(sp), k.getBoolean("erkennbar"));
            JSONArray b = k.getJSONArray("befehle");
            assertEquals(SprachBefehle.BEFEHLE.size(), b.length());
            for (int i = 0; i < b.length(); i++) {
                JSONObject e = b.getJSONObject(i);
                String befehl = e.getString("befehl");
                assertEquals(SprachBefehle.BEFEHLE.get(i), befehl);
                JSONArray s = e.getJSONArray("saetze");
                assertEquals(sp + " " + befehl, SprachBefehle.saetze(sp, befehl).size(), s.length());
                for (int j = 0; j < s.length(); j++) assertEquals(SprachBefehle.saetze(sp, befehl).get(j), s.getString(j));
            }
            assertEquals(SprachBefehle.saetze(sp, SprachBefehle.VERBOTEN).size(), k.getJSONArray("nie").length());
        }
        assertFalse(SprachBefehle.katalog("fr").getBoolean("erkennbar"));
    }

    @Test
    public void ehrlicheArtKlimaImmerMitRueckfrage() {
        for (String b : SprachBefehle.BEFEHLE) {
            assertTrue(b, SprachBefehle.KATEGORIEN.contains(SprachBefehle.KATEGORIE.get(b)));
            String art = SprachBefehle.ART.get(b);
            if (SprachBefehle.FAHRZEUG.contains(b)) assertEquals(b, "rueckfrage", art);
            else assertFalse(b, "rueckfrage".equals(art));
        }
        assertEquals("lesen", SprachBefehle.ART.get(SprachBefehle.REICHWEITE));
        assertEquals("lesen", SprachBefehle.ART.get(SprachBefehle.WO_BIN_ICH));
        assertEquals("vorschau", SprachBefehle.ART.get(SprachBefehle.NAVI_HEIM));
        assertEquals("medien", SprachBefehle.ART.get(SprachBefehle.MUSIK_PAUSE));
        assertFalse("keine Kategorie ohne Befehl", SprachBefehle.KATEGORIEN.contains("fenster") || SprachBefehle.KATEGORIEN.contains("fahrzeug"));
    }

    @Test
    public void angetippterSatzNurAusDerListeUndMitRueckfrage() throws Exception {
        assertEquals("klima_an", SprachStart.vorgabe("klima_an"));
        for (String b : new String[] {"verboten", "ja", "nein", "hupe", "licht_an", "", "KLIMA_AN", null})
            assertNull(String.valueOf(b), SprachStart.vorgabe(b));
        String act = lies("app/src/main/java/at/greenchili/surfmate/sprache/SprachActivity.java");
        assertTrue("angetippt: nicht zuhören", act.contains("String gehoert = vorgabe != null ? ersterSatz(vorgabe)"));
        assertTrue("Rückfrage bleibt", act.contains("String antwort = antwortHolen();")
                && act.contains("if (!SprachBefehle.JA.equals(antwort)) { post(frage(befehl), getString(R.string.sprache_abgebrochen), 3000); return; }"));
        assertTrue("ohne Antwort = Nein", act.contains("while (rueckfrageAntwort == null && System.currentTimeMillis() < ende) schlafenKurz();"));
        assertTrue(act.contains("vorgabe = SprachStart.vorgabe(getIntent().getStringExtra(SprachStart.EXTRA_BEFEHL));"));
        String st = lies("app/src/main/java/at/greenchili/surfmate/sprache/SprachStart.java");
        assertTrue("Prüfungen im App-Prozess bleiben vorn", st.indexOf("SprachRegeln.startVerweigert(") < st.indexOf(".putExtra(EXTRA_BEFEHL, vorgabe("));
    }

    @Test
    public void launcherRouteNurLesen() throws Exception {
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        int r = h.indexOf("if (cleanPath.equals(SPRACHE_PFAD) && \"GET\".equals(method)) {");
        assertTrue(r > 0);
        assertTrue("nur die Launcher-APK", h.indexOf("if (!launcherDarf(out)) return true;", r) - r < 120);
        int m = h.indexOf("static JSONObject spracheKatalog()");
        String meth = h.substring(m, h.indexOf("\n    }\n", m));
        for (String v : new String[] {"VehicleCommandRouter", "ausfuehren", "starten(", "Klima", "RECORD_AUDIO"}) assertFalse(v, meth.contains(v));
        assertTrue(h.contains("static final String SPRACHE_PFAD = \"/api/launcher/v1/sprache\";"));
    }

    private static List<String> ktListe(String kt, String name) {
        Matcher m = Pattern.compile(name + " = listOf\\(([^)]*)\\)").matcher(kt);
        assertTrue(name, m.find());
        List<String> l = new ArrayList<>();
        Matcher w = Pattern.compile("\"([a-z_]+)\"").matcher(m.group(1));
        while (w.find()) l.add(w.group(1));
        return l;
    }

    @Test
    public void launcherKenntDieselbenGruppenUndArten() throws Exception {
        String kt = lies("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/Sprache.kt");
        assertEquals(SprachBefehle.KATEGORIEN, ktListe(kt, "KATEGORIEN"));
        List<String> arten = new ArrayList<>(new java.util.LinkedHashSet<>(SprachBefehle.ART.values()));
        java.util.Collections.sort(arten);
        List<String> l = ktListe(kt, "ARTEN");
        java.util.Collections.sort(l);
        assertEquals(arten, l);
        assertTrue(kt.contains("const val ZIEL = \".sprache.SprachStart\""));
        assertTrue(kt.contains("const val EXTRA_BEFEHL = \"" + SprachStart.EXTRA_BEFEHL + "\""));
    }
}
