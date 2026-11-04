package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.server.EinrichtungApiHandler.Antwort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 0.1.38.8 „Wächter Standard an" (REGEL NR.2 hier bewusst aufgehoben): Server-Vertrag für
 * - die Bestand-Entscheidung {@link EinrichtungApiHandler#waechterStandardFragen(JSONObject)},
 * - das Festhalten der Rechtshinweis-Bestätigung (surveillance.guardLegalVersion) und des „bewusst aus"-Merkers,
 * - den Wächter-Schritt gilt als erledigt, sobald der Wächter an ist.
 */
public class EinrichtungWaechterStandard0138Test {

    private static final List<Feld> FELDER = Katalog.felder(new Katalog.Kontext());

    private static JSONObject anwenden(JSONObject root, Antwort a) throws Exception {
        assertEquals(a.body.toString(), 200, a.code);
        JSONObject r = new JSONObject(root.toString());
        for (Map.Entry<String, Map<String, Object>> s : a.schreiben.entrySet()) {
            JSONObject sec = r.optJSONObject(s.getKey());
            if (sec == null) { sec = new JSONObject(); r.put(s.getKey(), sec); }
            for (Map.Entry<String, Object> kv : s.getValue().entrySet()) sec.put(kv.getKey(), kv.getValue());
        }
        return r;
    }

    private static JSONObject schritt(JSONObject root, JSONObject antwort) throws Exception {
        return anwenden(root, EinrichtungApiHandler.schritt(root, "waechter", antwort, FELDER, false, 1_000L));
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i).getString("status");
        throw new AssertionError(id);
    }

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    private static JSONObject surveillance(String triggerMode, Boolean disabledByUser) throws Exception {
        JSONObject sv = new JSONObject();
        if (triggerMode != null) sv.put("triggerMode", triggerMode);
        if (disabledByUser != null) sv.put("disabledByUser", disabledByUser.booleanValue());
        return new JSONObject().put("surveillance", sv);
    }

    @Test
    public void bestandNurFragenWennNieEingerichtetUndNichtBewusstAus() throws Exception {
        assertTrue("kein surveillance → fragen", EinrichtungApiHandler.waechterStandardFragen(new JSONObject()));
        assertTrue("triggerMode nicht gesetzt → fragen", EinrichtungApiHandler.waechterStandardFragen(surveillance(null, null)));
        assertFalse("schon ein Auslöser gewählt → nichts tun", EinrichtungApiHandler.waechterStandardFragen(surveillance("shock", null)));
        assertFalse("ai_private gewählt → nichts tun", EinrichtungApiHandler.waechterStandardFragen(surveillance("ai_private", null)));
        assertFalse("ausdruecklich aus gewaehlt -> bleibt aus", EinrichtungApiHandler.waechterStandardFragen(surveillance("aus", null)));
        assertFalse("bewusst ausgeschaltet → bleibt aus", EinrichtungApiHandler.waechterStandardFragen(surveillance(null, Boolean.TRUE)));
        assertTrue("disabledByUser=false ohne Wahl → fragen", EinrichtungApiHandler.waechterStandardFragen(surveillance(null, Boolean.FALSE)));
    }

    @Test
    public void verstandenSchaltetEinUndHaeltRechtshinweisFest() throws Exception {
        JSONObject r = schritt(new JSONObject(), new JSONObject().put("modus", "shock").put("ton", false).put("ereignisClip", false));
        JSONObject sv = r.getJSONObject("surveillance");
        assertEquals("shock", sv.getString("triggerMode"));
        assertTrue(sv.getBoolean("surveillanceEnabled"));
        assertFalse(sv.getBoolean("disabledByUser"));
        assertEquals(Katalog.GUARD_LEGAL_VERSION, sv.getString("guardLegalVersion"));
    }

    @Test
    public void kiStufeAiPrivatePerEinemTipp() throws Exception {
        JSONObject r = schritt(new JSONObject(), new JSONObject().put("modus", "ai_private").put("ton", false).put("ereignisClip", false));
        JSONObject sv = r.getJSONObject("surveillance");
        assertEquals("ai_private", sv.getString("triggerMode"));
        assertTrue(sv.getBoolean("surveillanceEnabled"));
        assertEquals(Katalog.GUARD_LEGAL_VERSION, sv.getString("guardLegalVersion"));
    }

    @Test
    public void waechterAusMerktBewussteEntscheidung() throws Exception {
        JSONObject r = schritt(new JSONObject(), new JSONObject().put("modus", "aus").put("ton", false).put("ereignisClip", false));
        JSONObject sv = r.getJSONObject("surveillance");
        assertEquals("aus", sv.getString("triggerMode"));
        assertFalse(sv.getBoolean("surveillanceEnabled"));
        assertTrue(sv.getBoolean("disabledByUser"));
        assertFalse("kein Rechtshinweis-Haken bei aus", sv.has("guardLegalVersion"));
        assertFalse("danach nicht mehr fragen", EinrichtungApiHandler.waechterStandardFragen(r));
    }

    @Test
    public void waechterSchrittErledigtWennAn() throws Exception {
        JSONObject root = new JSONObject().put("surveillance", new JSONObject().put("surveillanceEnabled", true).put("triggerMode", "shock"));
        assertTrue(EinrichtungApiHandler.echtEingerichtet(root, null, false).contains("waechter"));
        assertEquals("erledigt", status(sicht(root), "waechter"));
    }
}
