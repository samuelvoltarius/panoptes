package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Rückfallkette (0.1.20, WALLBOX_FERN_KONZEPT §7): eine Wallbox über mehrere Wege – der Reihe nach (lokal → HA → Cloud)
 * gilt der erste erreichbare. Nach außen genau ein Ladepunkt {@link WallboxKonfig#KETTE_ID} mit {@code weg} (welcher
 * Weg gerade gilt) und {@code wege} (alle mit Ergebnis).
 *
 * <p><b>Genau eine Steuerquelle je Befehl:</b> {@link #steuern} schickt den Befehl nur über den Weg, der beim Lesen
 * davor galt. Schlägt er fehl, wird NICHT über einen anderen Weg nachgeschoben (sonst könnten zwei Befehle ankommen).
 */
final class KetteQuelle implements WallboxQuelle {

    /** Baut die Quelle für genau einen Weg (nur dieses Gerät, damit kein anderer Weg mitgefragt wird). */
    interface Wege {
        WallboxQuelle fuer(String ladepunkt) throws WallboxFehler, JSONException;
    }

    private final JSONArray wege;
    private final String titel;
    private final Wege bauer;
    private final java.util.function.Function<String, String> art;
    private WallboxQuelle aktivQuelle;
    private String aktivLp;

    KetteQuelle(JSONArray wege, String titel, Wege bauer, java.util.function.Function<String, String> art) {
        this.wege = wege;
        this.titel = titel;
        this.bauer = bauer;
        this.art = art;
    }

    @Override
    public JSONObject info() throws JSONException {
        return new JSONObject().put("kette", true);
    }

    @Override
    public JSONArray ladepunkte() throws WallboxFehler, JSONException {
        aktivQuelle = null;
        aktivLp = null;
        JSONArray versucht = new JSONArray();
        JSONObject gefunden = null;
        JSONObject weg = null;
        for (int i = 0; i < wege.length(); i++) {
            String lpId = wege.getJSONObject(i).getString("ladepunkt");
            JSONObject v = new JSONObject().put("nr", i + 1).put("ladepunkt", lpId).put("art", art.apply(lpId));
            if (gefunden != null) {
                versucht.put(v.put("ok", JSONObject.NULL));   // nicht gefragt (kein unnötiger Abruf)
                continue;
            }
            try {
                WallboxQuelle q = bauer.fuer(lpId);
                JSONObject lp = EvccQuelle.finde(q.ladepunkte(), lpId);
                if (lp.has("fehler")) {
                    versucht.put(v.put("ok", false).put("fehler", lp.getString("fehler")));
                    continue;
                }
                gefunden = lp;
                weg = new JSONObject(v.toString());
                aktivQuelle = q;
                aktivLp = lpId;
                versucht.put(v.put("ok", true));
            } catch (WallboxFehler f) {
                versucht.put(v.put("ok", false).put("fehler", f.code));
            }
        }
        JSONObject o = new JSONObject().put("id", WallboxKonfig.KETTE_ID);
        if (gefunden == null) {
            JSONObject leer = DirektAdapter.leer().put("fehler", "kein_weg").put("faehigkeiten", new JSONObject()
                    .put("modus", new JSONArray()).put("ziel", false).put("plan", JSONObject.NULL).put("strom", JSONObject.NULL));
            for (Iterator<String> it = leer.keys(); it.hasNext(); ) { String k = it.next(); o.put(k, leer.get(k)); }
            o.put("titel", titel.isEmpty() ? "Wallbox" : titel).put("weg", JSONObject.NULL).put("wege", versucht);
            return new JSONArray().put(o);
        }
        for (Iterator<String> it = gefunden.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!"id".equals(k)) o.put(k, gefunden.get(k));
        }
        if (!titel.isEmpty()) o.put("titel", titel);
        o.put("weg", weg).put("wege", versucht);
        return new JSONArray().put(o);
    }

    @Override
    public JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException {
        if (!WallboxKonfig.KETTE_ID.equals(ladepunktId)) throw new WallboxFehler("ladepunkt_unbekannt", 404);
        if (aktivQuelle == null) ladepunkte();
        if (aktivQuelle == null) throw new WallboxFehler("kein_weg", 502);
        WallboxQuelle q = aktivQuelle;
        String lp = aktivLp;
        JSONObject r = q.steuern(lp, aktion, wert);
        JSONObject nach = r.optJSONObject("ladepunkt");
        if (nach != null) {
            JSONObject o = new JSONObject();
            for (Iterator<String> it = nach.keys(); it.hasNext(); ) { String k = it.next(); o.put(k, nach.get(k)); }
            o.put("id", WallboxKonfig.KETTE_ID).put("weg", new JSONObject().put("ladepunkt", lp).put("art", art.apply(lp)));
            if (!titel.isEmpty()) o.put("titel", titel);
            r.put("ladepunkt", o);
        }
        return r;
    }
}
