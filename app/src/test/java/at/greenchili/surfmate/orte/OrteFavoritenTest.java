package at.greenchili.surfmate.orte;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.1.12 (Kern 7): Navi-Favoriten in der Ortsliste. Pflichttest: Bestand → orte → App-Speicher bleibt unverändert,
 * zweiter Lauf idempotent; App- und Web-Änderungen gehen nie verloren; Leser privat aus orte.
 */
public class OrteFavoritenTest {

    private static JSONObject fav(String art, String label, double lat, double lon) throws Exception {
        return new JSONObject().put("art", art).put("label", label).put("lat", lat).put("lon", lon);
    }

    /** App-Speicher wie navmap_saved_places: Zuhause (8 m neben der Ruhezone), Arbeit, zwei eigene. */
    private static JSONArray appBestand() throws Exception {
        return new JSONArray()
                .put(fav("home", "Daheim", 48.30948, 14.29001))
                .put(fav("work", "Firma", 48.20000, 14.33500))
                .put(fav("custom", "Oma", 47.10000, 15.40000))
                .put(fav("custom", "Fitness", 48.31000, 14.29500));
    }

    @Test
    public void migrationAppBleibtUnveraendertUndIdempotent() throws Exception {
        OrteTest.Alt alt = new OrteTest.Alt().ruhe("a1", "Zuhause", 48.30941, 14.29001, 80, true);
        OrteDienst d = new OrteDienst(alt);
        String alteVorher = alt.alteLesen().toString();
        JSONObject r = d.favoritenAbgleichen(appBestand(), -1, 1000);
        assertFalse("App muss nichts schreiben", r.getBoolean("aendern"));
        assertFalse(r.has("liste"));
        assertEquals(4, r.getInt("uebernommen"));
        assertEquals("alte Speicher unverändert", alteVorher, alt.alteLesen().toString());
        assertEquals(0, alt.schreibvorgaenge);
        JSONArray l = d.abgleichen(1100);
        JSONObject zuhause = ort(l, "Zuhause");
        assertTrue("Daheim mit der Ruhezone zusammengeführt", zuhause.getJSONObject("eigenschaften").getBoolean("favorit"));
        assertEquals("home", zuhause.getJSONObject("verweise").getString("favorit"));
        assertEquals(4, l.length());   // Zuhause, Firma, Oma, Fitness
        assertFalse("home setzt zuhause nicht neu", ort(l, "Firma").getJSONObject("eigenschaften").getBoolean("zuhause"));

        long v = r.getLong("version");
        String abschnitt = alt.abschnitt.toString();
        JSONObject r2 = d.favoritenAbgleichen(appBestand(), v, 2000);
        assertFalse(r2.getBoolean("aendern"));
        assertEquals(0, r2.getInt("uebernommen"));
        assertEquals(v, r2.getLong("version"));
        assertEquals("zweiter Lauf: Ortsliste unverändert", new JSONObject(abschnitt).getJSONArray("liste").toString(),
                alt.abschnitt.getJSONArray("liste").toString());
    }

    @Test
    public void appAenderungenWerdenUebernommen() throws Exception {
        OrteTest.Alt alt = new OrteTest.Alt();
        OrteDienst d = new OrteDienst(alt);
        long v = d.favoritenAbgleichen(appBestand(), -1, 1000).getLong("version");
        JSONArray neu = appBestand();
        neu.remove(3);                                       // Fitness gelöscht
        neu.put(fav("custom", "See", 48.40000, 14.43500));  // See neu
        JSONObject r = d.favoritenAbgleichen(neu, v, 2000);
        assertFalse(r.getBoolean("aendern"));
        JSONArray l = d.abgleichen(2100);
        assertNotNull("Ort Fitness bleibt (nur favorit weg)", ort(l, "Fitness"));
        assertFalse(ort(l, "Fitness").getJSONObject("eigenschaften").getBoolean("favorit"));
        assertTrue(ort(l, "See").getJSONObject("eigenschaften").getBoolean("favorit"));
    }

    @Test
    public void webAenderungGehtZurAppUndAlteAppListeUeberschreibtSieNicht() throws Exception {
        OrteTest.Alt alt = new OrteTest.Alt();
        OrteDienst d = new OrteDienst(alt);
        JSONArray app = appBestand();
        long v = d.favoritenAbgleichen(app, -1, 1000).getLong("version");
        // Web: Oma ist kein Favorit mehr, ein neuer Ort „Werkstatt“ wird Favorit
        JSONObject oma = ort(d.abgleichen(1100), "Oma");
        d.aendern(oma.getString("id"), new JSONObject().put("eigenschaften", new JSONObject().put("favorit", false)), 1200);
        d.anlegen(new JSONObject().put("name", "Werkstatt").put("lat", 48.0).put("lon", 14.735).put("r", 50)
                .put("eigenschaften", new JSONObject().put("favorit", true)), 1300);
        // App meldet ihre unveränderte (alte) Liste mit der alten Version
        JSONObject r = d.favoritenAbgleichen(app, v, 2000);
        assertTrue(r.getBoolean("aendern"));
        JSONArray soll = r.getJSONArray("liste");
        assertFalse("Oma nicht wieder hineingeholt", enthaelt(soll, "Oma"));
        assertTrue(enthaelt(soll, "Werkstatt"));
        assertTrue(enthaelt(soll, "Daheim"));
        assertFalse(ort(d.abgleichen(2100), "Oma").getJSONObject("eigenschaften").getBoolean("favorit"));
        // App übernimmt und meldet die neue Version: Ruhe
        JSONObject r2 = d.favoritenAbgleichen(soll, r.getLong("version"), 3000);
        assertFalse(r2.getBoolean("aendern"));
        assertEquals(0, r2.getInt("uebernommen"));
    }

    @Test
    public void appHatAntwortNichtUebernommenNichtsGehtVerloren() throws Exception {
        OrteTest.Alt alt = new OrteTest.Alt();
        OrteDienst d = new OrteDienst(alt);
        JSONArray app = appBestand();
        long v = d.favoritenAbgleichen(app, -1, 1000).getLong("version");
        JSONObject oma = ort(d.abgleichen(1100), "Oma");
        d.aendern(oma.getString("id"), new JSONObject().put("eigenschaften", new JSONObject().put("favorit", false)), 1200);
        d.favoritenAbgleichen(app, v, 2000);                 // Antwort geht verloren (App schreibt nicht)
        JSONObject r = d.favoritenAbgleichen(app, v, 3000);  // gleiche alte Liste + alte Version
        assertTrue(r.getBoolean("aendern"));
        assertFalse(enthaelt(r.getJSONArray("liste"), "Oma"));
    }

    @Test
    public void privatZonenFuerAlteLeser() throws Exception {
        JSONArray l = new JSONArray();
        JSONObject p = Orte.neuerOrt("Privat", 47.1, 13.1, 90, 1);
        p.getJSONObject("eigenschaften").put("privat", true);
        l.put(p).put(Orte.neuerOrt("Nur Name", 47.2, 13.2, 50, 1));
        JSONArray z = Orte.privatZonen(l);
        assertEquals(1, z.length());
        assertEquals(47.1, z.getJSONObject(0).getDouble("lat"), 0);
        assertEquals(13.1, z.getJSONObject(0).getDouble("lon"), 0);
        assertEquals(90, z.getJSONObject(0).getInt("radiusM"));
    }

    private static boolean enthaelt(JSONArray a, String label) {
        for (int i = 0; i < a.length(); i++) if (label.equals(a.optJSONObject(i).optString("label"))) return true;
        return false;
    }

    private static JSONObject ort(JSONArray l, String name) {
        for (int i = 0; i < l.length(); i++) if (name.equals(l.optJSONObject(i).optString("name"))) return l.optJSONObject(i);
        throw new AssertionError("kein Ort " + name + " in " + l);
    }
}
