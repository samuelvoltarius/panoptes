package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Quelle „direkt“: eine oder mehrere Wallboxen, je mit eigenem {@link DirektAdapter} (go-e, Shelly, …), Ladepunkt
 * {@code direkt:<schluessel>}. Prüft Werte gegen die Fähigkeiten des Geräts und liest nach jedem Befehl zurück.
 */
public final class DirektQuelle implements WallboxQuelle {

    static final class Geraet {
        final String schluessel;
        final String titel;
        final String adapter;
        final DirektAdapter a;

        Geraet(String schluessel, String titel, String adapter, DirektAdapter a) {
            this.schluessel = schluessel;
            this.titel = titel;
            this.adapter = adapter;
            this.a = a;
        }
    }

    /** Platzhalter für ein Gerät, das (noch) nicht angesprochen werden darf, z. B. Cloud ohne Einwilligung: keine Anfrage. */
    static final class Gesperrt implements DirektAdapter {
        final String grund;

        Gesperrt(String grund) {
            this.grund = grund;
        }

        @Override
        public JSONObject lies() throws JSONException {
            return DirektAdapter.leer().put("fehler", grund).put("faehigkeiten", new JSONObject().put("modus", new JSONArray())
                    .put("ziel", false).put("plan", JSONObject.NULL).put("strom", JSONObject.NULL));
        }

        @Override
        public void setze(String aktion, Object wert) throws WallboxFehler {
            throw new WallboxFehler(grund, 409);
        }
    }

    private final Geraet[] geraete;
    private final Schlaf schlaf;

    DirektQuelle(Geraet[] geraete, Schlaf schlaf) {
        this.geraete = geraete;
        this.schlaf = schlaf;
    }

    @Override
    public JSONObject info() {
        return new JSONObject();
    }

    private static JSONObject mitKopf(Geraet g, JSONObject lp) throws JSONException {
        JSONObject o = new JSONObject().put("id", "direkt:" + g.schluessel)
                .put("titel", g.titel == null || g.titel.isEmpty() ? g.schluessel : g.titel).put("adapter", g.adapter);
        for (java.util.Iterator<String> it = lp.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("id") && !k.equals("titel")) o.put(k, lp.get(k));
        }
        return o;
    }

    @Override
    public JSONArray ladepunkte() throws WallboxFehler, JSONException {
        JSONArray a = new JSONArray();
        for (Geraet g : geraete) a.put(mitKopf(g, g.a.lies()));
        return a;
    }

    @Override
    public JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException {
        Geraet g = null;
        for (Geraet x : geraete) if (("direkt:" + x.schluessel).equals(ladepunktId)) g = x;
        if (g == null) throw new WallboxFehler("ladepunkt_unbekannt", 404);
        JSONObject vorher = g.a.lies();
        if (vorher.has("fehler")) throw new WallboxFehler(vorher.getString("fehler"), 409);
        JSONObject f = vorher.getJSONObject("faehigkeiten");
        String feld;
        Object soll;
        switch (aktion) {
            case "modus": {
                String m = String.valueOf(wert);
                JSONArray modi = f.optJSONArray("modus");
                boolean ok = false;
                for (int i = 0; modi != null && i < modi.length(); i++) ok |= modi.optString(i).equals(m);
                if (!ok) throw new WallboxFehler("wert_ungueltig", 400);
                feld = "modus";
                soll = m;
                break;
            }
            case "strom": {
                JSONObject s = f.optJSONObject("strom");
                if (s == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
                soll = EvccQuelle.ganzzahl(wert, s.getInt("min"), s.getInt("max"));
                feld = "stromA";
                break;
            }
            default:
                throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        g.a.setze(aktion, soll);
        JSONObject nach = null;
        boolean ok = false;
        long[] p = g.a.pausen();
        for (int i = 0; i < p.length && !ok; i++) {
            schlaf.ms(p[i]);
            nach = mitKopf(g, g.a.lies());
            ok = String.valueOf(nach.opt(feld)).equals(String.valueOf(soll));
        }
        // Vertrag §0.6: liefert das Gerät den Wert gar nicht zurück, ist das Ergebnis unbekannt (null), nicht „falsch“
        Object ergebnis = ok ? (Object) true : nach == null || nach.isNull(feld) ? JSONObject.NULL : (Object) false;
        return new JSONObject().put("uebernommen", ergebnis).put("ladepunkt", nach);
    }
}
