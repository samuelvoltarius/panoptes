package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * Tuya-Wallboxen (WALLBOX_VERTRAG §4b): lokal im Heimnetz (LAN 3.3/3.4/3.5) oder über die Tuya Cloud (mit Einwilligung).
 * Was welcher Datenpunkt (DP) bedeutet, steht nicht im Code, sondern in einer Datentabelle je Modell
 * ({@code web/shared/wallbox-tuya-modelle.json}) oder in der eigenen Zuordnung des Nutzers.
 * Ohne eigene Zuordnung und ohne Modell wird über die {@code erkennung}-Regeln der Tabelle erkannt (z. B. Tuya-Standardkategorie
 * „qccdz“ an typischen DP-Mustern bzw. Cloud-Codes) → {@code zuordnung = "erkannt"}.
 * <b>Sonst unbekannt → nur lesen</b>: Status zeigt die rohen DPs ({@code rohDps}), Steuern erst nach Zuordnung.
 * <p>Zuordnung (Rollen, je {@code dp} für LAN und {@code code} für Cloud):
 * <pre>
 * schalter {dp, code}                         Laden ein/aus (bool) → Modus Aus/Sofort
 * strom    {dp, code, min, max, faktor}       Ladestrom in A; DP-Wert = A × faktor
 * status   {dp, code, laedt:[…]}              Werte, die „lädt“ bedeuten
 * leistung {dp, code, faktorW}                W = DP-Wert × faktorW
 * </pre>
 */
public final class TuyaQuelle implements WallboxQuelle {

    static final Pattern DP = Pattern.compile("[1-9][0-9]{0,2}");
    static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,47}");
    static final String[] ROLLEN = {"schalter", "strom", "status", "leistung"};

    /** Zugriff auf ein Gerät: LAN oder Cloud. */
    interface Zugriff {
        JSONObject lies() throws WallboxFehler, JSONException;

        void setze(JSONObject werte) throws WallboxFehler, JSONException;

        /** true = Schlüssel sind DP-Nummern (LAN), false = Codes (Cloud). */
        boolean lan();
    }

    /** Ein konfiguriertes Gerät mit Zugriff und wirksamer Zuordnung (null = unbekannt, nur lesen). */
    static final class Geraet {
        final String schluessel;
        final String titel;
        final Zugriff zugriff;
        final JSONObject zuordnung;

        Geraet(String schluessel, String titel, Zugriff zugriff, JSONObject zuordnung) {
            this.schluessel = schluessel;
            this.titel = titel;
            this.zugriff = zugriff;
            this.zuordnung = zuordnung;
        }
    }

    private final Geraet[] geraete;
    private final Schlaf schlaf;
    private final JSONObject tabelle;

    TuyaQuelle(Geraet[] geraete, Schlaf schlaf, JSONObject tabelle) {
        this.geraete = geraete;
        this.schlaf = schlaf;
        this.tabelle = tabelle;
    }

    /**
     * Erkennung aus der Tabelle (Daten, nicht Code): Regeln je Modell {@code erkennung.lan} (DP-Nummer → Regel) bzw.
     * {@code erkennung.cloud} (Code → Regel); Regel "bool" | "int" | "text" | Regex auf den Textwert. Alle Regeln müssen passen.
     * @return {modell, zuordnung} oder null
     */
    static JSONObject erkenne(JSONObject tabelle, JSONObject werte, boolean lan) throws JSONException {
        JSONArray m = tabelle == null ? null : tabelle.optJSONArray("modelle");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject e = m.optJSONObject(i);
            JSONObject er = e == null || e.optJSONObject("erkennung") == null ? null : e.optJSONObject("erkennung").optJSONObject(lan ? "lan" : "cloud");
            if (er == null || er.length() == 0) continue;
            boolean alle = true;
            for (Iterator<String> it = er.keys(); it.hasNext() && alle; ) {
                String k = it.next();
                alle = passt(werte.opt(k), er.optString(k, ""));
            }
            if (!alle) continue;
            JSONObject zu;
            try {
                zu = pruefeZuordnung(e.optJSONObject("dp"));
            } catch (WallboxFehler f) {
                continue;
            }
            if (zu != null) return new JSONObject().put("modell", e.optString("id")).put("zuordnung", zu);
        }
        return null;
    }

    static boolean passt(Object v, String regel) {
        if (v == null || v == JSONObject.NULL) return false;
        switch (regel) {
            case "bool": return v instanceof Boolean;
            case "int": return v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue());
            case "text": return v instanceof String;
            default:
                if (!(v instanceof String) || regel.isEmpty() || regel.length() > 100) return false;
                try {
                    return Pattern.compile(regel).matcher((String) v).find();
                } catch (Exception ex) {
                    return false;
                }
        }
    }

    /** Wirksame Zuordnung: eigene/Modell, sonst Erkennung. [zuordnung|null, "bekannt"|"erkannt"|"unbekannt", modell|null] */
    private Object[] wirksam(Geraet g, JSONObject werte) throws JSONException {
        if (g.zuordnung != null) return new Object[]{g.zuordnung, "bekannt", null};
        JSONObject er = erkenne(tabelle, werte, g.zugriff.lan());
        if (er != null) return new Object[]{er.getJSONObject("zuordnung"), "erkannt", er.getString("modell")};
        return new Object[]{null, "unbekannt", null};
    }

    @Override
    public JSONObject info() {
        return new JSONObject();
    }

    // ------------------------------------------------------------------ Zuordnung

    /** Prüft eine Zuordnung (Tabelle oder Nutzer); null bleibt null. Mindestens eine Rolle muss gesetzt sein. */
    static JSONObject pruefeZuordnung(JSONObject z) throws WallboxFehler, JSONException {
        if (z == null) return null;
        JSONObject o = new JSONObject();
        for (String rolle : ROLLEN) {
            JSONObject r = z.optJSONObject(rolle);
            if (r == null) continue;
            JSONObject s = new JSONObject();
            String dp = r.isNull("dp") ? "" : r.optString("dp", "");
            String code = r.isNull("code") ? "" : r.optString("code", "");
            if (dp.isEmpty() && code.isEmpty()) throw new WallboxFehler("einstellung_ungueltig");
            if (!dp.isEmpty() && !DP.matcher(dp).matches()) throw new WallboxFehler("einstellung_ungueltig");
            if (!code.isEmpty() && !CODE.matcher(code).matches()) throw new WallboxFehler("einstellung_ungueltig");
            s.put("dp", dp.isEmpty() ? JSONObject.NULL : dp);
            s.put("code", code.isEmpty() ? JSONObject.NULL : code);
            switch (rolle) {
                case "strom": {
                    int min = r.optInt("min", -1), max = r.optInt("max", -1), faktor = r.optInt("faktor", 1);
                    if (min < 1 || max > 80 || min > max || faktor < 1 || faktor > 1000) throw new WallboxFehler("einstellung_ungueltig");
                    s.put("min", min).put("max", max).put("faktor", faktor);
                    break;
                }
                case "status": {
                    JSONArray l = r.optJSONArray("laedt");
                    JSONArray sauber = new JSONArray();
                    for (int i = 0; l != null && i < l.length() && i < 10; i++) {
                        String v = l.optString(i, "");
                        if (!v.isEmpty() && v.length() <= 40) sauber.put(v);
                    }
                    s.put("laedt", sauber);
                    break;
                }
                case "leistung": {
                    double f = r.optDouble("faktorW", 1);
                    if (Double.isNaN(f) || f <= 0 || f > 1_000_000) throw new WallboxFehler("einstellung_ungueltig");
                    s.put("faktorW", f);
                    break;
                }
                default:
                    break;
            }
            o.put(rolle, s);
        }
        if (o.length() == 0) throw new WallboxFehler("einstellung_ungueltig");
        return o;
    }

    /** Modell aus der Tabelle; null wenn unbekannt oder nicht in der Tabelle. */
    static JSONObject modellZuordnung(JSONObject tabelle, String modellId) {
        if (tabelle == null || modellId == null || modellId.isEmpty()) return null;
        JSONArray m = tabelle.optJSONArray("modelle");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject e = m.optJSONObject(i);
            if (e != null && modellId.equals(e.optString("id"))) {
                try {
                    return pruefeZuordnung(e.optJSONObject("dp"));
                } catch (Exception ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String feld(JSONObject rolle, boolean lan) {
        if (rolle == null) return null;
        String k = lan ? (rolle.isNull("dp") ? "" : rolle.optString("dp", "")) : (rolle.isNull("code") ? "" : rolle.optString("code", ""));
        return k.isEmpty() ? null : k;
    }

    // ------------------------------------------------------------------ lesen

    @Override
    public JSONArray ladepunkte() throws WallboxFehler, JSONException {
        JSONArray a = new JSONArray();
        for (Geraet g : geraete) {
            JSONObject werte = g.zugriff.lies();
            Object[] w = wirksam(g, werte);
            a.put(baue(g, werte, g.zugriff.lan(), (JSONObject) w[0], (String) w[1], (String) w[2]));
        }
        return a;
    }

    static JSONObject baue(Geraet g, JSONObject werte, boolean lan) throws JSONException {
        return baue(g, werte, lan, g.zuordnung, g.zuordnung == null ? "unbekannt" : "bekannt", null);
    }

    static JSONObject baue(Geraet g, JSONObject werte, boolean lan, JSONObject z, String zuStand, String modell) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", "tuya:" + g.schluessel);
        o.put("titel", g.titel == null || g.titel.isEmpty() ? g.schluessel : g.titel);
        o.put("art", "wallbox");
        o.put("verbunden", JSONObject.NULL);
        JSONObject f = new JSONObject();
        JSONArray modi = new JSONArray();
        Object laedt = JSONObject.NULL, leistung = JSONObject.NULL, modus = JSONObject.NULL, strom = JSONObject.NULL;
        JSONObject stromF = null;
        if (z != null) {
            String ks = feld(z.optJSONObject("schalter"), lan);
            if (ks != null) {
                modi.put(MODUS_AUS).put(MODUS_SOFORT);
                Object v = werte.opt(ks);
                if (v instanceof Boolean) modus = (Boolean) v ? MODUS_SOFORT : MODUS_AUS;
            }
            JSONObject st = z.optJSONObject("status");
            String kst = feld(st, lan);
            if (kst != null && werte.has(kst)) {
                String v = String.valueOf(werte.opt(kst));
                boolean an = false;
                JSONArray l = st.optJSONArray("laedt");
                for (int i = 0; l != null && i < l.length(); i++) an |= l.optString(i).equals(v);
                laedt = an;
            }
            JSONObject le = z.optJSONObject("leistung");
            String kle = feld(le, lan);
            if (kle != null && werte.opt(kle) instanceof Number) {
                leistung = EvccQuelle.zahl(Math.round(((Number) werte.opt(kle)).doubleValue() * le.optDouble("faktorW", 1)));
            }
            JSONObject sr = z.optJSONObject("strom");
            String ksr = feld(sr, lan);
            if (ksr != null) {
                stromF = new JSONObject().put("min", sr.getInt("min")).put("max", sr.getInt("max"));
                if (werte.opt(ksr) instanceof Number) {
                    strom = EvccQuelle.zahl(((Number) werte.opt(ksr)).doubleValue() / sr.optInt("faktor", 1));
                }
            }
        }
        o.put("laedt", laedt);
        o.put("leistungW", leistung);
        o.put("modus", modus);
        o.put("modusRoh", JSONObject.NULL);
        o.put("zielSoc", JSONObject.NULL);
        o.put("zielMin", 20);
        o.put("zielMax", 100);
        o.put("stromA", strom);
        o.put("fahrzeug", JSONObject.NULL);
        o.put("fahrzeugSoc", JSONObject.NULL);
        o.put("plan", JSONObject.NULL);
        f.put("modus", modi);
        f.put("ziel", false);
        f.put("plan", JSONObject.NULL);
        f.put("strom", stromF == null ? JSONObject.NULL : stromF);
        o.put("faehigkeiten", f);
        o.put("zuordnung", zuStand);
        o.put("modell", modell == null ? JSONObject.NULL : modell);
        // Unbekanntes Modell: rohe DPs zeigen, damit der Nutzer zuordnen kann (Werte, keine Schlüssel/Adressen)
        o.put("rohDps", z == null ? kopie(werte) : JSONObject.NULL);
        return o;
    }

    private static JSONObject kopie(JSONObject w) throws JSONException {
        JSONObject o = new JSONObject();
        int n = 0;
        for (Iterator<String> it = w.keys(); it.hasNext() && n < 60; n++) {
            String k = it.next();
            Object v = w.opt(k);
            if (v instanceof String && ((String) v).length() > 64) v = ((String) v).substring(0, 64) + "…";
            o.put(k, v);
        }
        return o;
    }

    // ------------------------------------------------------------------ steuern

    @Override
    public JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException {
        Geraet g = geraet(ladepunktId);
        boolean lan = g.zugriff.lan();
        Object[] wz = wirksam(g, g.zugriff.lies());
        JSONObject zu = (JSONObject) wz[0];
        if (zu == null) throw new WallboxFehler("tuya_zuordnung_fehlt", 400);
        String k;
        Object roh;
        switch (aktion) {
            case "modus": {
                k = feld(zu.optJSONObject("schalter"), lan);
                String m = String.valueOf(wert);
                if (k == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
                if (!m.equals(MODUS_AUS) && !m.equals(MODUS_SOFORT)) throw new WallboxFehler("wert_ungueltig", 400);
                roh = m.equals(MODUS_SOFORT);
                break;
            }
            case "strom": {
                JSONObject sr = zu.optJSONObject("strom");
                k = feld(sr, lan);
                if (k == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
                roh = EvccQuelle.ganzzahl(wert, sr.getInt("min"), sr.getInt("max")) * sr.optInt("faktor", 1);
                break;
            }
            case "ziel":
            case "plan":
            case "plan_loeschen":
                throw new WallboxFehler("nicht_unterstuetzt", 400);
            default:
                throw new WallboxFehler("aktion_ungueltig", 400);
        }
        g.zugriff.setze(new JSONObject().put(k, roh));
        JSONObject nach = null;
        boolean ok = false;
        for (int i = 0; i < 3 && !ok; i++) {
            schlaf.ms(i == 0 ? 500 : 1000);
            JSONObject werte = g.zugriff.lies();
            nach = baue(g, werte, lan, zu, (String) wz[1], (String) wz[2]);
            ok = TuyaLan.gleich(werte.opt(k), roh);
        }
        return new JSONObject().put("uebernommen", ok).put("ladepunkt", nach);
    }

    private Geraet geraet(String id) throws WallboxFehler {
        for (Geraet g : geraete) if (("tuya:" + g.schluessel).equals(id)) return g;
        throw new WallboxFehler("ladepunkt_unbekannt", 404);
    }

    // ------------------------------------------------------------------ Zugriffe

    static Zugriff lan(TuyaLan lan) {
        return new Zugriff() {
            @Override public JSONObject lies() throws WallboxFehler, JSONException { return lan.dps(); }
            @Override public void setze(JSONObject w) throws WallboxFehler, JSONException { lan.setze(w); }
            @Override public boolean lan() { return true; }
        };
    }

    static Zugriff cloud(TuyaCloud c, String geraeteId) {
        return new Zugriff() {
            @Override public JSONObject lies() throws WallboxFehler, JSONException { return c.status(geraeteId); }
            @Override public void setze(JSONObject w) throws WallboxFehler, JSONException { c.befehle(geraeteId, w); }
            @Override public boolean lan() { return false; }
        };
    }
}
