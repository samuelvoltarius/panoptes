package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Praxistest gegen die öffentliche evcc-Demo (https://demo.evcc.io, Demo-Modus, Schreiben ohne Anmeldung erlaubt).
 * Läuft nur mit {@code WALLBOX_DEMO=1}; nie gegen eine echte Wallbox. Jeder geänderte Wert wird am Ende auf den
 * Ausgangswert zurückgestellt. Ergebnis (ohne Zugangsdaten) optional nach {@code WALLBOX_DEMO_BERICHT}.
 */
public class WallboxEvccDemoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void evccDemo_lesenSteuernZuruecklesenZurueckstellen() throws Exception {
        assumeTrue("1".equals(System.getenv("WALLBOX_DEMO")));
        WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "wallbox.json"), new File(tmp.getRoot(), "p.jsonl"),
                WallboxTest.GEHEIM, WallboxHttp.STANDARD, WallboxAdresse.DNS, () -> "steht",
                System::currentTimeMillis, WallboxQuelle.ECHT);
        JSONArray bericht = new JSONArray();

        // ohne Schalter abgelehnt (öffentliche Adresse)
        assertEquals("adresse_oeffentlich", d.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "https://demo.evcc.io"))).json.getString("grund"));
        assertEquals(200, d.speichereEinstellungen(new JSONObject().put("quelle", "evcc")
                .put("evcc", new JSONObject().put("url", "https://demo.evcc.io").put("oeffentlichErlaubt", true))).status);

        JSONObject s = d.status().json;
        bericht.put(new JSONObject().put("schritt", "status").put("antwort", s));
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        JSONArray lps = s.getJSONArray("ladepunkte");
        JSONObject ziel = null, heizung = null;
        for (int i = 0; i < lps.length(); i++) {
            JSONObject lp = lps.getJSONObject(i);
            if (ziel == null && "wallbox".equals(lp.getString("art"))) ziel = lp;
            if (heizung == null && !"wallbox".equals(lp.getString("art"))) heizung = lp;
        }
        assertTrue("Demo ohne Wallbox-Ladepunkt", ziel != null);
        String id = ziel.getString("id");
        String modusVorher = ziel.getString("modus");
        int zielVorher = ziel.getInt("zielSoc");

        // ohne Freigabe: 403, nichts gesendet
        assertEquals(403, d.steuern(WallboxTest.steuer(id, "modus", "sofort", null), "demo").status);
        // Heizung/Gerät nie freigebbar
        if (heizung != null) {
            assertEquals(400, d.freigabe(new JSONObject().put("ladepunkt", heizung.getString("id")).put("an", true), "demo").status);
            bericht.put(new JSONObject().put("schritt", "heizung_freigabe_abgelehnt").put("ladepunkt", heizung.getString("id")));
        }
        // Freigabe zweistufig
        WallboxDienst.Ergebnis f = d.freigabe(new JSONObject().put("ladepunkt", id).put("an", true), "demo");
        assertEquals(409, f.status);
        assertEquals(200, d.freigabe(new JSONObject().put("ladepunkt", id).put("an", true)
                .put("bestaetigung", f.json.getString("bestaetigung")), "demo").status);

        try {
            for (String m : new String[]{"minpv", "sofort", "aus", "pv"}) {
                JSONObject r = fuehreAus(d, id, "modus", m);
                bericht.put(new JSONObject().put("schritt", "modus " + m).put("uebernommen", r.getBoolean("uebernommen"))
                        .put("modusRoh", r.getJSONObject("ladepunkt").opt("modusRoh")));
                assertTrue(m + " " + r, r.getBoolean("uebernommen"));
            }
            int neu = zielVorher >= 90 ? zielVorher - 10 : zielVorher + 10;
            JSONObject r = fuehreAus(d, id, "ziel", neu);
            bericht.put(new JSONObject().put("schritt", "ziel " + neu).put("uebernommen", r.getBoolean("uebernommen")));
            assertTrue(r.toString(), r.getBoolean("uebernommen"));
            // Fahrt-Sperre greift auch gegen die Demo (nichts gesendet)
            WallboxDienst fahrend = new WallboxDienst(new File(tmp.getRoot(), "wallbox.json"), new File(tmp.getRoot(), "p.jsonl"),
                    WallboxTest.GEHEIM, WallboxHttp.STANDARD, WallboxAdresse.DNS, () -> "faehrt",
                    System::currentTimeMillis, WallboxQuelle.ECHT);
            assertEquals(423, fahrend.steuern(WallboxTest.steuer(id, "modus", "sofort", null), "demo").status);
        } finally {
            JSONObject r1 = fuehreAus(d, id, "ziel", zielVorher);
            JSONObject r2 = fuehreAus(d, id, "modus", modusVorher);
            bericht.put(new JSONObject().put("schritt", "zurueckgestellt").put("ziel", zielVorher).put("modus", modusVorher)
                    .put("ok", r1.getBoolean("uebernommen") && r2.getBoolean("uebernommen")));
        }
        JSONObject nach = d.status().json;
        JSONObject lpNach = null;
        for (int i = 0; i < nach.getJSONArray("ladepunkte").length(); i++) {
            if (id.equals(nach.getJSONArray("ladepunkte").getJSONObject(i).getString("id"))) lpNach = nach.getJSONArray("ladepunkte").getJSONObject(i);
        }
        assertEquals(modusVorher, lpNach.getString("modus"));
        assertEquals(zielVorher, lpNach.getInt("zielSoc"));
        JSONArray prot = d.protokoll().json.getJSONArray("eintraege");
        bericht.put(new JSONObject().put("schritt", "protokoll").put("eintraege", prot));
        assertFalse(prot.toString().contains("demo.evcc.io"));
        String ziel2 = System.getenv("WALLBOX_DEMO_BERICHT");
        if (ziel2 != null && !ziel2.isEmpty()) {
            Files.write(new File(ziel2).toPath(), bericht.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    static JSONObject fuehreAus(WallboxDienst d, String id, String aktion, Object wert) throws Exception {
        WallboxDienst.Ergebnis f = d.steuern(WallboxTest.steuer(id, aktion, wert, null), "demo");
        assertEquals(f.json.toString(), 409, f.status);
        WallboxDienst.Ergebnis r = d.steuern(WallboxTest.steuer(id, aktion, wert, f.json.getString("bestaetigung")), "demo");
        assertEquals(r.json.toString(), 200, r.status);
        return r.json;
    }
}
