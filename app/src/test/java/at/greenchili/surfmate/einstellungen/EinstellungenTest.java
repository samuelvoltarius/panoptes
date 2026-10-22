package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Vertrag EINSTELLUNGEN_SCHEMA.md v1.1 (Koordination 21.10.2026). */
public class EinstellungenTest {

    private static final Einstellungen.Texte TEXTE = new Einstellungen.Texte() {
        public String text(String key) { return "T:" + key; }
        public JSONObject unter(String p) {
            try { return new JSONObject().put("title", "Wann?").put("a.label", "Nur Erschütterung"); }
            catch (Exception e) { return new JSONObject(); }
        }
    };

    private static final class Merker implements Einstellungen.Schreiber {
        final Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        public boolean schreibe(String a, Map<String, Object> w) {
            geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w);
            return true;
        }
    }

    private static Katalog.Kontext kontext(Laenderprofil.Land land) {
        Katalog.Kontext k = new Katalog.Kontext();
        k.land = land;
        k.akkuChemie = AkkuChemie.LFP;
        return k;
    }

    private static JSONObject feld(JSONObject schema, String key) throws Exception {
        JSONArray g = schema.getJSONArray("gruppen");
        for (int i = 0; i < g.length(); i++) {
            JSONArray f = g.getJSONObject(i).getJSONArray("felder");
            for (int j = 0; j < f.length(); j++) if (f.getJSONObject(j).getString("key").equals(key)) return f.getJSONObject(j);
        }
        throw new AssertionError("fehlt: " + key);
    }

    @Test
    public void schemaCarriesContractFields() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        JSONObject s = Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, TEXTE);
        assertEquals(1, s.getInt("version"));
        assertTrue(s.getString("etag").startsWith("s-"));
        JSONObject v12 = feld(s, "guardStop.v12Below");
        assertEquals(12.8, v12.getDouble("standard"), 1e-9);   // LFP-Standard
        assertEquals(0.1, v12.getDouble("schritt"), 1e-9);
        assertEquals("V", v12.getString("einheit"));
        assertTrue(v12.getBoolean("nullIstAus"));
        assertTrue(feld(s, "guardStop.socBelow").getBoolean("nullIstAus"));
        assertFalse(feld(s, "guardStop.v12Resume").has("nullIstAus"));
        assertEquals("stopp", v12.getJSONObject("abschnitt").getString("id"));
        JSONObject trig = feld(s, "surveillance.triggerMode");
        assertEquals("guardLegal.trigger", trig.getString("bestaetigung"));
        assertEquals("Nur Erschütterung", trig.getJSONObject("bestaetigungText").getString("a.label"));
        assertTrue(trig.isNull("standard"));
        assertTrue(feld(s, "deterrent.horn").getBoolean("erweitert"));
        assertTrue(feld(s, "deterrent.light").getBoolean("gefahr"));
        assertFalse("privateZones nur bei ai_private sichtbar", feld(s, "surveillance.privateZones").getBoolean("sichtbar"));
        assertTrue(feld(s, "planner.autoId").getBoolean("nurLesendExtern"));
        assertTrue(feld(s, "planner.memberToken").getBoolean("nurLesendExtern"));
    }

    @Test
    public void retentionIsUnlimitedByDefaultWithRecommendation() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        JSONObject h = feld(Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, TEXTE),
                "surveillance.clipRetentionHours");
        assertEquals(0, h.getInt("standard"));
        assertTrue(h.getBoolean("nullIstAus"));
        assertEquals(72, h.getInt("empfohlen"));
    }

    @Test
    public void chBlocksAiEverywhereOption() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.SCHWEIZ);
        JSONObject trig = feld(Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, TEXTE),
                "surveillance.triggerMode");
        JSONArray o = trig.getJSONArray("optionen");
        assertTrue(o.getJSONObject(1).getBoolean("gesperrt"));
        assertEquals("ai_everywhere", o.getJSONObject(1).getString("wert"));
        assertFalse(o.getJSONObject(0).getBoolean("gesperrt"));
    }

    @Test
    public void readingNeverWritesAndLegacyTriggerIsEffectiveAiEverywhere() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        JSONObject root = new JSONObject().put("surveillance",
                new JSONObject().put("deterrentAction", "flash_lights").put("screenDeterrentEnabled", true));
        String vorher = root.toString();
        JSONObject w = Einstellungen.werte(Katalog.felder(k), root, k).getJSONObject("werte");
        assertEquals(vorher, root.toString());
        JSONObject t = w.getJSONObject("surveillance.triggerMode");
        assertFalse(t.getBoolean("gesetzt"));
        assertTrue(t.isNull("wert"));
        // 0.1.9.1 (SETUP_WIZARD Teil A): nicht gewählt = bis zur Wahl nur Erschütterung
        assertEquals("shock", t.getString("wirksam"));
        assertEquals("nicht_gewaehlt", t.getString("status"));
        assertTrue(w.getJSONObject("deterrent.light").getBoolean("wert"));
        assertFalse(w.getJSONObject("deterrent.horn").getBoolean("wert"));
        assertTrue(w.getJSONObject("deterrent.display").getBoolean("wert"));
        assertFalse(w.getJSONObject("planner.memberToken").has("wert"));   // geheim
        assertEquals(20, w.getJSONObject("guardStop.socBelow").getInt("wert"));
        assertFalse(w.getJSONObject("guardStop.socBelow").getBoolean("gesetzt"));
    }

    @Test
    public void putWritesOnlySentFieldsAndValidatesEach() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        Merker m = new Merker();
        JSONObject body = new JSONObject().put("werte", new JSONObject()
                .put("guardStop.socBelow", 25)
                .put("guardStop.maxParkHours", 9999)
                .put("guardStop.v12Below", 12.84)
                .put("gibtsnicht.x", 1));
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, false, m, 0L);
        assertEquals(200, e.status);
        assertFalse(e.ok());
        assertEquals(25, e.gespeichert.getInt("guardStop.socBelow"));
        assertEquals(12.8, e.gespeichert.getDouble("guardStop.v12Below"), 1e-9);
        assertEquals("max 720", e.fehler.getString("guardStop.maxParkHours"));
        assertEquals("unbekannt", e.fehler.getString("gibtsnicht.x"));
        assertEquals(1, m.geschrieben.size());
        assertEquals(2, m.geschrieben.get("guardStop").size());
    }

    @Test
    public void externCannotWriteRuleBFields() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        Merker m = new Merker();
        JSONObject body = new JSONObject().put("werte", new JSONObject()
                .put("planner.memberToken", "abc").put("guardStop.socBelow", 30));
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k, body, true, m, 0L);
        assertEquals("nurLesendExtern", e.fehler.getString("planner.memberToken"));
        assertEquals(30, e.gespeichert.getInt("guardStop.socBelow"));
        assertFalse(m.geschrieben.containsKey("planner"));
        // lokal geht der Schlüssel, autoId nirgends
        Einstellungen.Ergebnis lokal = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("planner.memberToken", "abc")
                        .put("planner.autoId", "x")), false, new Merker(), 0L);
        assertTrue(lokal.gespeichert.has("planner.memberToken"));
        assertEquals("nurLesendExtern", lokal.fehler.getString("planner.autoId"));
    }

    @Test
    public void staleEtagIs409AndNothingWritten() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        Merker m = new Merker();
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("etag", "w-alt").put("werte", new JSONObject().put("guardStop.socBelow", 30)),
                false, m, 0L);
        assertEquals(409, e.status);
        assertTrue(m.geschrieben.isEmpty());
        String aktuell = Einstellungen.werte(Katalog.felder(k), new JSONObject(), k).getString("etag");
        Einstellungen.Ergebnis ok = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("etag", aktuell).put("werte", new JSONObject().put("guardStop.socBelow", 30)),
                false, m, 0L);
        assertTrue(ok.ok());
        assertNotEquals(aktuell, ok.etag);
    }

    @Test
    public void triggerNeedsConfirmationAndStoresConsentTime() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        Merker m = new Merker();
        Einstellungen.Ergebnis ohne = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("surveillance.triggerMode", "shock")), false, m, 0L);
        assertEquals("bestaetigungFehlt", ohne.fehler.getString("surveillance.triggerMode"));
        assertTrue(m.geschrieben.isEmpty());
        Einstellungen.Ergebnis mit = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("surveillance.triggerMode", "ai_private"))
                        .put("bestaetigt", new JSONArray().put("surveillance.triggerMode")), false, m, 1_800_000_000_000L);
        assertTrue(mit.ok());
        assertEquals("ai_private", m.geschrieben.get("surveillance").get("triggerMode"));
        assertEquals("2027-01-15T08:00:00Z", m.geschrieben.get("surveillance").get("triggerConsentAt"));
    }

    @Test
    public void disclaimerVersionHiddenNeedsConfirmationAndIsReadOnlyForPairedDevices() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        JSONObject f = feld(Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, TEXTE), "hinweise.version");
        assertFalse(f.getBoolean("sichtbar"));
        assertEquals(Katalog.HINWEISE_VERSION, f.getString("aktuell"));
        assertEquals("disclaimer.setup", f.getString("bestaetigung"));
        assertTrue(f.getBoolean("nurLesendExtern"));
        Merker m = new Merker();
        Einstellungen.Ergebnis ohne = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("hinweise.version", Katalog.HINWEISE_VERSION)), false, m, 0L);
        assertEquals("bestaetigungFehlt", ohne.fehler.getString("hinweise.version"));
        Einstellungen.Ergebnis extern = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("hinweise.version", Katalog.HINWEISE_VERSION))
                        .put("bestaetigt", new JSONArray().put("hinweise.version")), true, m, 0L);
        assertTrue("Kopplungs-Token darf nicht bestätigen", extern.fehler.has("hinweise.version"));
        assertTrue(m.geschrieben.isEmpty());
        Einstellungen.Ergebnis mit = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("hinweise.version", Katalog.HINWEISE_VERSION))
                        .put("bestaetigt", new JSONArray().put("hinweise.version")), false, m, 1_800_000_000_000L);
        assertTrue(mit.ok());
        assertEquals(Katalog.HINWEISE_VERSION, m.geschrieben.get("hinweise").get("version"));
        assertEquals("2027-01-15T08:00:00Z", m.geschrieben.get("hinweise").get("bestaetigtAm"));
    }

    @Test
    public void chRejectsAiEverywhereOnPut() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.SCHWEIZ);
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("surveillance.triggerMode", "ai_everywhere"))
                        .put("bestaetigt", new JSONArray().put("surveillance.triggerMode")), false, new Merker(), 0L);
        assertEquals("gesperrt", e.fehler.getString("surveillance.triggerMode"));
    }

    @Test
    public void deterrentAliasesKeepOneTruth() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        JSONObject root = new JSONObject().put("surveillance", new JSONObject().put("deterrentAction", "flash_lights"));
        Merker m = new Merker();
        Einstellungen.aendern(Katalog.felder(k), root, k,
                new JSONObject().put("werte", new JSONObject().put("deterrent.light", false)), false, m, 0L);
        assertEquals("silent", m.geschrieben.get("surveillance").get("deterrentAction"));
        Merker m2 = new Merker();
        Einstellungen.Ergebnis beide = Einstellungen.aendern(Katalog.felder(k), root, k,
                new JSONObject().put("werte", new JSONObject().put("deterrent.light", true).put("deterrent.horn", true)),
                false, m2, 0L);
        assertTrue(beide.fehler.has("deterrent.light"));     // horn kam zuerst (sortiert), light kollidiert
        assertEquals("find_car", m2.geschrieben.get("surveillance").get("deterrentAction"));
        Merker m3 = new Merker();
        Einstellungen.aendern(Katalog.felder(k), root, k,
                new JSONObject().put("werte", new JSONObject().put("deterrent.display", true)), false, m3, 0L);
        assertEquals(true, m3.geschrieben.get("surveillance").get("screenDeterrentEnabled"));
    }

    @Test
    public void geozoneAndWindowsAreValidated() throws Exception {
        Katalog.Kontext k = kontext(Laenderprofil.Land.ANDERES);
        Merker m = new Merker();
        JSONArray zonen = new JSONArray().put(new JSONObject().put("name", "Garage").put("lat", 47.8).put("lon", 13.04).put("radiusM", 50));
        JSONArray schlecht = new JSONArray().put(new JSONObject().put("lat", 47.8).put("lon", 13.04).put("radiusM", 5000));
        JSONArray fenster = new JSONArray().put(new JSONObject().put("days", new JSONArray().put(1).put(7)).put("from", "22:00").put("to", "06:00"));
        Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject()
                        .put("surveillance.privateZones", zonen)
                        .put("guardStop.windows", fenster)), false, m, 0L);
        assertTrue(e.fehler.toString(), e.ok());
        JSONArray z = (JSONArray) m.geschrieben.get("surveillance").get("privateZones");
        assertTrue(z.getJSONObject(0).getString("id").startsWith("z-"));
        Einstellungen.Ergebnis e2 = Einstellungen.aendern(Katalog.felder(k), new JSONObject(), k,
                new JSONObject().put("werte", new JSONObject().put("surveillance.privateZones", schlecht)), false, new Merker(), 0L);
        assertEquals("radiusM 20-500", e2.fehler.getString("surveillance.privateZones"));
    }

    // ---------------------------------------------------------------- Bausteine

    @Test
    public void triggerModeEffectiveRules() {
        JSONArray zone = new JSONArray().put(new JSONObject(java.util.Map.of("lat", 47.8, "lon", 13.04, "radiusM", 50)));
        Laenderprofil.Land at = Laenderprofil.Land.ANDERES, ch = Laenderprofil.Land.SCHWEIZ, un = Laenderprofil.Land.UNBEKANNT;
        // Alt-Installation: wie heute, auch ohne GPS
        assertFalse(TriggerModus.kiErlaubt(null, at, null, 0, 0, false));   // nicht gewählt: keine KI
        assertFalse(TriggerModus.kiErlaubt(null, un, null, 0, 0, false));
        assertFalse(TriggerModus.kiErlaubt("aus", at, null, 0, 0, false));
        // (a)
        assertFalse(TriggerModus.kiErlaubt("shock", at, zone, 47.8, 13.04, true));
        // (c) Zonengrenze: innen ja, 60 m daneben nein, ohne Fix nein
        assertTrue(TriggerModus.kiErlaubt("ai_private", at, zone, 47.8003, 13.04, true));
        assertFalse(TriggerModus.kiErlaubt("ai_private", at, zone, 47.80054, 13.04, true));
        assertFalse(TriggerModus.kiErlaubt("ai_private", at, zone, 47.8, 13.04, false));
        assertFalse(TriggerModus.kiErlaubt("ai_private", at, new JSONArray(), 47.8, 13.04, true));
        // CH: (b) wirkungslos → (c) bzw. (a); gilt auch für die Alt-Installation
        assertFalse(TriggerModus.kiErlaubt("ai_everywhere", ch, null, 47.37, 8.54, true));
        assertTrue(TriggerModus.kiErlaubt("ai_everywhere", ch, zone, 47.8, 13.04, true));
        assertEquals("shock", TriggerModus.wirksam(null, ch, false));
        assertEquals("ai_private", TriggerModus.wirksam("ai_everywhere", ch, true));
        // Land unbekannt: Alt-Installation bleibt (b), ausdrücklich gewähltes (b) streng wie (c)
        assertEquals("shock", TriggerModus.wirksam(null, un, false));
        assertEquals("aus", TriggerModus.wirksam("aus", at, false));
        assertEquals("nicht_gewaehlt", TriggerModus.status(null));
        assertEquals("ai_everywhere", TriggerModus.status("ai_everywhere"));   // gespeicherte Wahl bleibt
        assertEquals("shock", TriggerModus.wirksam("ai_everywhere", un, false));
        assertEquals("ai_private", TriggerModus.wirksam("ai_everywhere", un, true));
        assertFalse(TriggerModus.kiErlaubt("ai_everywhere", un, null, 0, 0, false));
        assertFalse(TriggerModus.kiErlaubt(null, un, null, 0, 0, false));
    }

    @Test
    public void switzerlandPolygon() {
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(47.37, 8.54, true));    // Zürich
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(46.95, 7.45, true));    // Bern
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(46.00, 8.95, true));    // Lugano
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(46.20, 6.14, true));    // Genf
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(47.14, 9.52, true));    // Vaduz (FL)
        assertEquals(Laenderprofil.Land.SCHWEIZ, Laenderprofil.land(46.50, 9.84, true));    // St. Moritz
        assertEquals(Laenderprofil.Land.ANDERES, Laenderprofil.land(47.80, 13.04, true));   // Salzburg
        assertEquals(Laenderprofil.Land.ANDERES, Laenderprofil.land(47.26, 11.39, true));   // Innsbruck
        assertEquals(Laenderprofil.Land.ANDERES, Laenderprofil.land(45.46, 9.19, true));    // Mailand
        assertEquals(Laenderprofil.Land.ANDERES, Laenderprofil.land(48.14, 11.58, true));   // München
        assertEquals(Laenderprofil.Land.ANDERES, Laenderprofil.land(45.76, 4.84, true));    // Lyon
        assertEquals(Laenderprofil.Land.UNBEKANNT, Laenderprofil.land(47.37, 8.54, false));
    }

    @Test
    public void batteryChemistryDefaults() {
        // 0.1.11 (Koordination 22.10.): Nutzer > Profil > Ruhetage-Heuristik (3 Tage ≥ 13,0 V) > Blei vermutet;
        // „DiLink 3 = LFP“ gibt es nicht mehr
        org.json.JSONObject drei = new org.json.JSONObject();
        try {
            drei.put("2026-10-20", 13.2).put("2026-10-21", 13.1).put("2026-10-22", 13.0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals("lfp", AkkuChemie.ermitteln(null, null, drei).chemie);
        assertEquals("vermutet", AkkuChemie.ermitteln(null, null, drei).quelle);
        assertEquals("blei", AkkuChemie.ermitteln(null, null, null).chemie);
        assertEquals("vermutet", AkkuChemie.ermitteln("auto", null, null).quelle);
        assertEquals("agm", AkkuChemie.ermitteln(null, "agm", drei).chemie);
        assertEquals("profil", AkkuChemie.ermitteln(null, "agm", drei).quelle);
        assertEquals("blei", AkkuChemie.ermitteln("blei", "lfp", drei).chemie);
        assertEquals("nutzer", AkkuChemie.ermitteln("blei", "lfp", drei).quelle);
        assertEquals("unbelegter Profilwert zählt nicht", "blei", AkkuChemie.ermitteln(null, "unbekannt", null).chemie);
        assertEquals(12.8, AkkuChemie.v12Standard("lfp")[0], 0);
        assertEquals(13.1, AkkuChemie.v12Standard("lfp")[1], 0);
        assertEquals(12.2, AkkuChemie.v12Standard("agm")[0], 0);
        assertEquals(12.5, AkkuChemie.v12Standard("agm")[1], 0);
        assertEquals(12.2, AkkuChemie.v12Standard("blei")[0], 0);
        assertEquals(12.5, AkkuChemie.v12Standard("blei")[1], 0);
        // guardStop nimmt dieselben Standards
        at.greenchili.surfmate.surveillance.GuardStopPolicy.Config c =
                at.greenchili.surfmate.surveillance.GuardStopPolicy.Config.from(null, AkkuChemie.v12Standard("lfp"));
        assertEquals(12.8, c.v12Below, 0);
        assertEquals(13.1, c.v12Resume, 0);
    }
}
