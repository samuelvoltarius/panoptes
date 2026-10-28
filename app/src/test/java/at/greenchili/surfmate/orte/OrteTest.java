package at.greenchili.surfmate.orte;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * ORTE_VERTRAG v1 (0.1.11, Kern 7). Pflichttest der Koordination: Bestand → orte → alte Leser sehen dasselbe,
 * zweiter Lauf idempotent. Dazu: alte Schreibwege werden übernommen, API-Änderungen gespiegelt, Wiederherstellung.
 */
public class OrteTest {

    /** Alte Speicher im Speicher, mit dem Verhalten der echten (ids, höchstens 10 Ruhezonen). */
    static final class Alt implements OrteDienst.Speicher {
        JSONArray ruhe = new JSONArray(), privat = new JSONArray(), tarife = new JSONArray();
        JSONObject abschnitt = new JSONObject();
        int schreibvorgaenge;
        int naechste = 1;

        Alt ruhe(String id, String name, double lat, double lon, int r, boolean an) throws Exception {
            ruhe.put(new JSONObject().put("id", id).put("name", name).put("lat", lat).put("lon", lon).put("r", r).put("an", an));
            return this;
        }

        Alt privat(double lat, double lon, int r) throws Exception {
            privat.put(new JSONObject().put("lat", lat).put("lon", lon).put("r", r));
            return this;
        }

        Alt tarif(String id, String label, double lat, double lon, int r, Double ac, Double dc) throws Exception {
            tarife.put(new JSONObject().put("id", id).put("label", label).put("lat", lat).put("lon", lon).put("r", r)
                    .put("acRate", ac == null ? JSONObject.NULL : (Object) ac).put("dcRate", dc == null ? JSONObject.NULL : (Object) dc)
                    .put("acGratis", ac != null && ac == 0).put("dcGratis", false).put("waehrung", "€"));
            return this;
        }

        @Override public JSONObject alteLesen() throws Exception {
            return new JSONObject().put("ruhe", new JSONArray(ruhe.toString())).put("privat", new JSONArray(privat.toString()))
                    .put("tarife", new JSONArray(tarife.toString()));
        }

        private int idx(JSONArray a, String id) {
            for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return i;
            return -1;
        }

        @Override public String ruheAnlegen(String name, double lat, double lon, int r, boolean an) throws Exception {
            if (ruhe.length() >= 10) return null;
            schreibvorgaenge++;
            String id = "z" + (naechste++);
            ruhe(id, name, lat, lon, r, an);
            return id;
        }

        @Override public void ruheAendern(String id, String name, double lat, double lon, int r, boolean an) throws Exception {
            schreibvorgaenge++;
            ruhe.put(idx(ruhe, id), new JSONObject().put("id", id).put("name", name).put("lat", lat).put("lon", lon).put("r", r).put("an", an));
        }

        @Override public void ruheEntfernen(String id) {
            schreibvorgaenge++;
            ruhe.remove(idx(ruhe, id));
        }

        @Override public void privatSetzen(JSONArray zonen) throws Exception {
            schreibvorgaenge++;
            privat = new JSONArray();
            for (int i = 0; i < zonen.length(); i++) {
                JSONObject z = zonen.getJSONObject(i);
                privat(z.getDouble("lat"), z.getDouble("lon"), z.getInt("radiusM"));
            }
        }

        @Override public String tarifAnlegen(JSONObject t) throws Exception {
            schreibvorgaenge++;
            String id = "t" + (naechste++);
            JSONObject n = new JSONObject(t.toString()).put("id", id);
            n.remove("ort");
            tarife.put(n);
            return id;
        }

        @Override public void tarifAendern(String id, JSONObject t) throws Exception {
            schreibvorgaenge++;
            JSONObject n = new JSONObject(t.toString()).put("id", id);
            n.remove("ort");
            tarife.put(idx(tarife, id), n);
        }

        @Override public void tarifEntfernen(String id) {
            schreibvorgaenge++;
            tarife.remove(idx(tarife, id));
        }

        @Override public JSONObject abschnittLesen() throws Exception { return new JSONObject(abschnitt.toString()); }

        @Override public void abschnittSchreiben(JSONObject a) throws Exception {
            // wie updateSection: Schlüssel zusammenführen
            java.util.Iterator<String> it = a.keys();
            while (it.hasNext()) { String k = it.next(); abschnitt.put(k, a.get(k)); }
        }
    }

    /** Typischer Bestand: Zuhause (Ruhezone + privater Kreis 8 m daneben + Hausstrom-Tarif), Büro (aus), Lidl. */
    private static Alt bestand() throws Exception {
        return new Alt()
                .ruhe("a1", "Zuhause", 48.30941, 14.29001, 80, true)
                .ruhe("a2", "Büro", 48.29000, 14.27500, 60, false)
                .privat(48.30948, 14.29001, 90)            // ~8 m vom Zuhause-Mittelpunkt
                .privat(48.20000, 16.37000, 50)            // eigener privater Ort
                .tarif("t_home", "Hausstrom", 48.30941, 14.29001, 80, 0.0, null)
                .tarif("t_lidl", "Lidl Leoben", 47.38000, 15.09000, 50, null, 0.29);
    }

    @Test
    public void bestandMigrationAlteLeserSehenDasselbeUndZweiterLaufIdempotent() throws Exception {
        Alt alt = bestand();
        String vorher = alt.alteLesen().toString();
        OrteDienst d = new OrteDienst(alt);
        JSONArray l = d.abgleichen(1000);
        assertEquals("alte Speicher bytegleich", vorher, alt.alteLesen().toString());
        assertEquals("keine Schreibvorgänge in alte Speicher", 0, alt.schreibvorgaenge);
        assertEquals("Zuhause zusammengeführt, Büro, privat Wien, Lidl", 4, l.length());
        JSONObject zuhause = ort(l, "Zuhause");
        JSONObject e = zuhause.getJSONObject("eigenschaften");
        assertTrue(e.getBoolean("ruhezone"));
        assertTrue(e.getBoolean("zuhause"));
        assertTrue(e.getBoolean("privat"));
        assertEquals("Hausstrom", e.getJSONObject("tarif").getString("label"));
        assertTrue(e.getJSONObject("tarif").getBoolean("acGratis"));
        assertFalse("Büro war aus", ort(l, "Büro").getJSONObject("eigenschaften").getBoolean("ruhezone"));
        assertTrue(ort(l, "Büro").getJSONObject("eigenschaften").getBoolean("zuhause"));
        assertTrue(ort(l, "Privater Ort").getJSONObject("eigenschaften").getBoolean("privat"));
        assertEquals(0.29, ort(l, "Lidl Leoben").getJSONObject("eigenschaften").getJSONObject("tarif").getDouble("dcRate"), 0);
        // jeder alte Eintrag genau einem Ort zugeordnet
        assertEquals(2, zaehle(l, "privat"));
        assertEquals(2, zaehle(l, "ruhe"));
        assertEquals(2, zaehle(l, "tarif"));

        String abschnitt = alt.abschnitt.toString();
        JSONArray l2 = d.abgleichen(2000);
        assertEquals("zweiter Lauf: nichts geändert", abschnitt, alt.abschnitt.toString());
        assertEquals(l.toString(), l2.toString());
        assertEquals(vorher, alt.alteLesen().toString());
        assertEquals(0, alt.schreibvorgaenge);
    }

    @Test
    public void alteSchreibwegeWerdenUebernommenNieGeloescht() throws Exception {
        Alt alt = bestand();
        OrteDienst d = new OrteDienst(alt);
        d.abgleichen(1000);
        // alte Web-Seite: privaten Ort Wien entfernt, neuen dazu, Büro-Ruhezone gelöscht, Lidl-Kreis 5 km verschoben
        alt.privat.remove(1);
        alt.privat(46.62, 14.30, 40);
        alt.ruhe.remove(1);
        alt.tarife.getJSONObject(1).put("lat", 47.42).put("lon", 15.09);
        JSONArray l = d.abgleichen(3000);
        assertNotNull("Ort Wien bleibt (nur Eigenschaft weg)", ortNah(l, 48.2, 16.37));
        assertFalse(ortNah(l, 48.2, 16.37).getJSONObject("eigenschaften").getBoolean("privat"));
        assertTrue(ortNah(l, 46.62, 14.30).getJSONObject("eigenschaften").getBoolean("privat"));
        JSONObject buero = ort(l, "Büro");
        assertFalse(buero.getJSONObject("eigenschaften").getBoolean("zuhause"));
        assertEquals("Lidl-Ort mitgezogen (nur Tarif)", 47.42, ort(l, "Lidl Leoben").getDouble("lat"), 1e-9);
        String alteJetzt = alt.alteLesen().toString();
        d.abgleichen(4000);
        assertEquals("danach ruhig", alteJetzt, alt.alteLesen().toString());
    }

    @Test
    public void apiAenderungenWerdenGespiegelt() throws Exception {
        Alt alt = bestand();
        OrteDienst d = new OrteDienst(alt);
        d.abgleichen(1000);
        JSONObject neu = d.anlegen(new JSONObject().put("name", "Oma").put("lat", 47.1).put("lon", 15.4).put("r", 100)
                .put("eigenschaften", new JSONObject().put("ruhezone", true).put("privat", true)
                        .put("tarif", new JSONObject().put("acRate", 0.25).put("waehrung", "€"))), 5000);
        assertEquals(3, alt.ruhe.length());
        assertEquals(3, alt.privat.length());
        assertEquals(3, alt.tarife.length());
        assertTrue("Antwort enthält schon die Verweise", neu.getJSONObject("verweise").has("ruhe"));
        JSONArray l = d.abgleichen(5100);
        JSONObject oma = ort(l, "Oma");
        assertTrue(oma.getJSONObject("verweise").has("ruhe"));
        assertTrue(oma.getJSONObject("verweise").has("tarif"));
        // verschieben → alte Speicher folgen
        d.aendern(oma.getString("id"), new JSONObject().put("lat", 47.2).put("eigenschaften", new JSONObject().put("privat", false)), 6000);
        assertEquals(47.2, alt.ruhe.getJSONObject(2).getDouble("lat"), 1e-9);
        assertEquals(2, alt.privat.length());
        // löschen → Spiegel weg, andere bleiben
        d.loeschen(oma.getString("id"), 7000);
        assertEquals(2, alt.ruhe.length());
        assertEquals(2, alt.tarife.length());
        assertEquals(4, d.abgleichen(8000).length());
    }

    @Test
    public void wiederherstellungOhneSpiegelSchreibtAlteSpeicherNeu() throws Exception {
        Alt alt = bestand();
        OrteDienst d = new OrteDienst(alt);
        d.abgleichen(1000);
        JSONObject gesichert = new JSONObject(alt.abschnitt.toString());
        gesichert.remove("spiegel");   // so steht es im Export
        // Gerät zurückgesetzt: Ruhezonen-Datei leer, private Orte und Tarife kommen aus der Sicherung
        Alt neuGeraet = new Alt();
        neuGeraet.privat = new JSONArray(alt.privat.toString());
        neuGeraet.tarife = new JSONArray(alt.tarife.toString());
        neuGeraet.abschnitt = gesichert;
        new OrteDienst(neuGeraet).abgleichen(9000);
        assertEquals("Ruhezonen wiederhergestellt", 2, neuGeraet.ruhe.length());
        assertEquals(2, neuGeraet.privat.length());
        assertEquals(2, neuGeraet.tarife.length());
    }

    @Test
    public void pruefungUndGrenzen() throws Exception {
        OrteDienst d = new OrteDienst(new Alt());
        erwarte(d, new JSONObject().put("name", "").put("lat", 47).put("lon", 13).put("r", 50), "name");
        erwarte(d, new JSONObject().put("name", "X").put("lat", 0).put("lon", 0).put("r", 50), "koordinaten");
        erwarte(d, new JSONObject().put("name", "X").put("lat", 47).put("lon", 13).put("r", 5000), "radius");
        erwarte(d, new JSONObject().put("name", "X").put("lat", 47).put("lon", 13).put("r", 1000)
                .put("eigenschaften", new JSONObject().put("privat", true)), "radius_eigenschaft");
        erwarte(d, new JSONObject().put("name", "X").put("lat", 47).put("lon", 13).put("r", 50)
                .put("eigenschaften", new JSONObject().put("tarif", new JSONObject())), "tarif_ohne_preis");
        for (int i = 0; i < 10; i++) {
            d.anlegen(new JSONObject().put("name", "R" + i).put("lat", 47 + i * 0.01).put("lon", 13).put("r", 50)
                    .put("eigenschaften", new JSONObject().put("ruhezone", true)), 100 + i);
        }
        erwarte(d, new JSONObject().put("name", "R11").put("lat", 46).put("lon", 13).put("r", 50)
                .put("eigenschaften", new JSONObject().put("ruhezone", true)), "ruhezonen_voll");
    }

    @Test
    public void cockpitSiehtKeineKoordinaten() throws Exception {
        JSONObject o = Orte.neuerOrt("Zuhause", 48.3, 14.28, 80, 1);
        JSONObject c = Orte.ohneKoordinaten(o, 48.3001, 14.28);
        assertFalse(c.has("lat"));
        assertFalse(c.has("lon"));
        assertFalse(c.has("r"));
        assertTrue(c.getBoolean("imKreis"));
        assertFalse(Orte.ohneKoordinaten(o, null, null).has("imKreis"));
    }

    private static void erwarte(OrteDienst d, JSONObject w, String code) throws Exception {
        try {
            d.anlegen(w, 1);
            fail("erwartet " + code);
        } catch (OrteDienst.Fehler f) {
            assertEquals(code, f.antwort.getString("error"));
        }
    }

    private static JSONObject ort(JSONArray l, String name) {
        for (int i = 0; i < l.length(); i++) if (name.equals(l.optJSONObject(i).optString("name"))) return l.optJSONObject(i);
        throw new AssertionError("kein Ort " + name + " in " + l);
    }

    private static JSONObject ortNah(JSONArray l, double lat, double lon) {
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (Orte.meter(lat, lon, o.optDouble("lat"), o.optDouble("lon")) < 50) return o;
        }
        return null;
    }

    private static int zaehle(JSONArray l, String art) throws Exception {
        int n = 0;
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.getJSONObject(i);
            if ("privat".equals(art) ? o.getJSONObject("eigenschaften").getBoolean("privat") : o.getJSONObject("verweise").has(art)) n++;
        }
        return n;
    }
}
