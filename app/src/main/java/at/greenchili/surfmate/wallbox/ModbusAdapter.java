package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;

/**
 * Modbus-TCP-Wallbox, gesteuert über eine <b>Datentabelle</b> ({@code web/shared/wallbox-modbus-modelle.json}), nicht über Code
 * je Modell. Register je Rolle: {@code {fc, reg, typ: u16|u32|i16|i32|f32, …}}.
 * <ul>
 *   <li>{@code status} – {@code laedt:[Werte]}, optional {@code verbunden:[Werte]}</li>
 *   <li>{@code verbunden} – {@code bit} (Bitnummer) oder {@code werte:[…]}</li>
 *   <li>{@code leistung} – W = Wert × {@code faktorW}</li>
 *   <li>{@code strom} – A = Wert ÷ {@code faktor}; Schreiben mit {@code schreibFc} 6|16; {@code min}/{@code max} in A</li>
 *   <li>{@code failsafe} – Wert ≠ 0: das Gerät erwartet einen Dauer-Herzschlag → nur Anzeige (Panoptes hält keine Dauerverbindung)</li>
 * </ul>
 * Schalter „strom_null“: Aus = Strom 0, Sofort = Mindeststrom (nur wenn aus); Ladestrom ändern nur, solange eingeschaltet.
 */
public final class ModbusAdapter implements DirektAdapter {

    private final ModbusTcp mb;
    private final JSONObject reg;
    private final String schalter;

    public ModbusAdapter(ModbusTcp mb, JSONObject modell) throws WallboxFehler {
        if (modell == null || modell.optJSONObject("register") == null) throw new WallboxFehler("modbus_modell_unbekannt", 400);
        this.mb = mb;
        this.reg = modell.optJSONObject("register");
        this.schalter = modell.optString("schalter", "");
    }

    /** Prüft einen Tabelleneintrag (Tests und Laden der Tabelle). */
    static void pruefeModell(JSONObject m) throws WallboxFehler, JSONException {
        if (m == null || !m.optString("id").matches("[a-z0-9_-]{1,64}") || m.optString("beleg", "").trim().isEmpty()) {
            throw new WallboxFehler("einstellung_ungueltig");
        }
        JSONObject r = m.optJSONObject("register");
        if (r == null || r.optJSONObject("strom") == null || r.optJSONObject("status") == null) throw new WallboxFehler("einstellung_ungueltig");
        for (java.util.Iterator<String> it = r.keys(); it.hasNext(); ) {
            JSONObject d = r.getJSONObject(it.next());
            if ((d.optInt("fc") != 3 && d.optInt("fc") != 4) || d.optInt("reg", -1) < 0 || d.optInt("reg") > 65535
                    || !d.optString("typ").matches("u16|u32|i16|i32|f32")) {
                throw new WallboxFehler("einstellung_ungueltig");
            }
        }
        JSONObject s = r.getJSONObject("strom");
        if (s.optInt("faktor", 0) < 1 || s.optInt("min") < 1 || s.optInt("max") > 80 || s.optInt("min") > s.optInt("max")
                || (s.optInt("schreibFc") != 6 && s.optInt("schreibFc") != 16) || !"strom_null".equals(m.optString("schalter"))) {
            throw new WallboxFehler("einstellung_ungueltig");
        }
    }

    private double wert(JSONObject d) throws WallboxFehler {
        String typ = d.optString("typ");
        int n = typ.equals("u16") || typ.equals("i16") ? 1 : 2;
        ByteBuffer b = ByteBuffer.wrap(mb.lies(d.optInt("fc"), d.optInt("reg"), n));
        switch (typ) {
            case "u16": return b.getShort() & 0xffff;
            case "i16": return b.getShort();
            case "u32": return b.getInt() & 0xffffffffL;
            case "i32": return b.getInt();
            default: return b.getFloat();
        }
    }

    private static boolean enthaelt(JSONArray a, double v) {
        for (int i = 0; a != null && i < a.length(); i++) if (a.optDouble(i) == v) return true;
        return false;
    }

    private double stromRoh() throws WallboxFehler {
        return wert(reg.optJSONObject("strom"));
    }

    private boolean failsafe() throws WallboxFehler {
        JSONObject f = reg.optJSONObject("failsafe");
        return f != null && wert(f) != 0;
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        JSONObject o = DirektAdapter.leer();
        JSONObject st = reg.getJSONObject("status");
        double s = wert(st);
        o.put("laedt", enthaelt(st.optJSONArray("laedt"), s));
        o.put("modusRoh", "status=" + (long) s);
        JSONObject vb = reg.optJSONObject("verbunden");
        if (vb != null) {
            double v = wert(vb);
            o.put("verbunden", vb.has("bit") ? (((long) v) & (1L << vb.optInt("bit"))) != 0 : enthaelt(vb.optJSONArray("werte"), v));
        } else if (st.has("verbunden")) {
            o.put("verbunden", enthaelt(st.optJSONArray("verbunden"), s));
        }
        JSONObject le = reg.optJSONObject("leistung");
        if (le != null) o.put("leistungW", EvccQuelle.zahl(Math.round(wert(le) * le.optDouble("faktorW", 1))));
        JSONObject sr = reg.getJSONObject("strom");
        double roh = stromRoh();
        o.put("modus", roh == 0 ? WallboxQuelle.MODUS_AUS : WallboxQuelle.MODUS_SOFORT);
        o.put("stromA", roh == 0 ? JSONObject.NULL : EvccQuelle.zahl(roh / sr.optInt("faktor", 1)));
        boolean fs = failsafe();
        JSONArray modi = new JSONArray();
        if (!fs) modi.put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT);
        o.put("faehigkeiten", new JSONObject().put("modus", modi).put("ziel", false).put("plan", JSONObject.NULL)
                .put("strom", fs ? JSONObject.NULL : new JSONObject().put("min", sr.getInt("min")).put("max", sr.getInt("max"))));
        if (fs) o.put("hinweis", "modbus_failsafe");
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        if (failsafe()) throw new WallboxFehler("modbus_failsafe", 409);
        JSONObject sr = reg.getJSONObject("strom");
        int faktor = sr.optInt("faktor", 1);
        double roh = stromRoh();
        double neu;
        if ("modus".equals(aktion)) {
            if (WallboxQuelle.MODUS_AUS.equals(wert)) neu = 0;
            else if (roh != 0) return;                       // schon eingeschaltet
            else neu = sr.getInt("min") * (double) faktor;    // vorsichtig mit dem Mindeststrom einschalten
        } else if ("strom".equals(aktion)) {
            if (roh == 0) throw new WallboxFehler("modbus_erst_einschalten", 400);
            neu = EvccQuelle.ganzzahl(wert, sr.getInt("min"), sr.getInt("max")) * (double) faktor;
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        byte[] b;
        switch (sr.optString("typ")) {
            case "f32": b = ByteBuffer.allocate(4).putFloat((float) neu).array(); break;
            case "u32": case "i32": b = ByteBuffer.allocate(4).putInt((int) neu).array(); break;
            default: b = ByteBuffer.allocate(2).putShort((short) neu).array();
        }
        int fc = sr.optInt("schreibFc", b.length == 2 ? 6 : 16);
        if (fc == 6 && b.length != 2) fc = 16;
        mb.schreibe(fc, sr.getInt("reg"), b);
    }
}
