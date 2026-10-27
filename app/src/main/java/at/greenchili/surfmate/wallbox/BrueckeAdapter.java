package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Panoptes-Wallbox-Brücke über MQTT (0.1.19, Vertrag WALLBOX_BRUECKE_MQTT.md v1, Sitzung wallbox-bruecke): Wallbox ohne
 * Home Assistant – eine Brücke (z. B. Tuya lokal) meldet {@code wallbox/<id>/zustand} (retained) und nimmt
 * {@code wallbox/<id>/befehl} entgegen, Antwort auf {@code wallbox/<id>/quittung}.
 *
 * <ul>
 *   <li>Lesen: {@code zustand} + {@code verfuegbar}; älter als {@link #FRISCH_MS}, {@code offline} oder
 *       {@code erreichbar=false} → nicht erreichbar.</li>
 *   <li>Steuern: {@code {"laden":"start|stop"}} bzw. {@code {"strom":A}} mit Zufallskennung und {@code zeit_ms}; Erfolg nur
 *       mit Quittung {@code ok} (die Brücke prüft selbst per Echo). Danach liest {@link DirektQuelle} zurück. Ohne Quittung
 *       binnen {@link #QUITTUNG_MS} → Fehlschlag.</li>
 *   <li>{@code steuern_erlaubt} der Brücke ist führend; Panoptes schaltet sie nie. Freigabe, Bestätigung und Fahrsperre
 *       von Panoptes gelten zusätzlich (WallboxDienst).</li>
 * </ul>
 */
final class BrueckeAdapter implements DirektAdapter {

    static final long FRISCH_MS = 60_000L;
    static final long QUITTUNG_MS = 25_000L;
    static final String ID_MUSTER = "[a-z0-9]{20,25}";

    private final String host;
    private final int port;
    private final String id;
    private final String benutzer;
    private final String passwort;
    private final OpenWbAdapter.Mqtt mqtt;
    private final LongSupplier uhr;
    private final SecureRandom zufall = new SecureRandom();

    BrueckeAdapter(String host, int port, String id, String benutzer, String passwort, OpenWbAdapter.Mqtt mqtt,
                   LongSupplier uhr) throws WallboxFehler {
        if (id == null || !id.matches(ID_MUSTER)) throw new WallboxFehler("einstellung_ungueltig");
        this.host = host;
        this.port = port;
        this.id = id;
        this.benutzer = benutzer;
        this.passwort = passwort;
        this.mqtt = mqtt;
        this.uhr = uhr;
    }

    private String topic(String t) {
        return "wallbox/" + id + "/" + t;
    }

    /** Aktueller Zustand (wirft, wenn nicht frisch bzw. offline). */
    JSONObject zustand() throws WallboxFehler {
        Map<String, String> w = mqtt.lies(host, port, benutzer, passwort, Arrays.asList(topic("zustand"), topic("verfuegbar")),
                m -> m.containsKey(topic("zustand")) && m.containsKey(topic("verfuegbar")), 3_000L);
        String roh = w.get(topic("zustand"));
        if (roh == null) throw new WallboxFehler("nicht_erreichbar", 502);
        JSONObject z;
        try {
            z = new JSONObject(roh);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
        if (z.optInt("v", 0) != 1) throw new WallboxFehler("nicht_unterstuetzt", 502);
        if ("offline".equals(w.get(topic("verfuegbar"))) || !z.optBoolean("erreichbar", false)) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        }
        long alter = uhr.getAsLong() - z.optLong("zeit_ms", 0);
        if (alter > FRISCH_MS) throw new WallboxFehler("nicht_erreichbar", 502);
        return z;
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        JSONObject z = zustand();
        JSONObject o = DirektAdapter.leer();
        o.put("laedt", z.isNull("laedt") ? JSONObject.NULL : (Object) z.optBoolean("laedt"));
        o.put("verbunden", z.isNull("angesteckt") ? JSONObject.NULL : (Object) z.optBoolean("angesteckt"));
        if (!z.isNull("leistung_kw") && z.has("leistung_kw")) o.put("leistungW", Math.round(z.optDouble("leistung_kw") * 1000.0));
        if (!z.isNull("strom_soll_a") && z.has("strom_soll_a")) o.put("stromA", z.optInt("strom_soll_a"));
        String status = z.optString("status", "");
        o.put("modus", z.optBoolean("laedt") || "waiting".equals(status) ? WallboxQuelle.MODUS_SOFORT : WallboxQuelle.MODUS_AUS);
        o.put("modusRoh", "status=" + status);
        JSONArray stufen = z.optJSONArray("strom_stufen_a");
        int min = 6, max = z.optInt("strom_max_a", 16);
        if (stufen != null && stufen.length() > 0) {
            min = stufen.optInt(0, 6);
            for (int i = 0; i < stufen.length(); i++) min = Math.min(min, stufen.optInt(i, min));
        }
        o.put("faehigkeiten", new JSONObject()
                .put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL)
                .put("strom", new JSONObject().put("min", min).put("max", max)
                        .put("stufen", stufen == null ? JSONObject.NULL : stufen)));
        if (!z.optBoolean("steuern_erlaubt", false)) o.put("hinweis", "bruecke_gesperrt");
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        JSONObject b = new JSONObject();
        if ("modus".equals(aktion)) {
            b.put("laden", WallboxQuelle.MODUS_SOFORT.equals(wert) ? "start" : "stop");
        } else if ("strom".equals(aktion)) {
            JSONObject z = zustand();
            int a = EvccQuelle.ganzzahl(wert, 1, Math.max(1, z.optInt("strom_max_a", 16)));
            JSONArray st = z.optJSONArray("strom_stufen_a");
            boolean erlaubt = st == null;
            for (int i = 0; st != null && i < st.length(); i++) if (st.optInt(i, -1) == a) erlaubt = true;
            if (!erlaubt) throw new WallboxFehler("wert_ungueltig", 400);
            b.put("strom", a);
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        byte[] r = new byte[8];
        zufall.nextBytes(r);
        final String kennung = "panoptes-" + TuyaCloud.hex(r);
        b.put("kennung", kennung).put("zeit_ms", uhr.getAsLong());
        String q = mqtt.frage(host, port, benutzer, passwort, topic("quittung"), topic("befehl"), b.toString(), s -> {
            try {
                return kennung.equals(new JSONObject(s).optString("kennung"));
            } catch (Exception e) {
                return false;
            }
        }, QUITTUNG_MS);
        if (q == null) throw new WallboxFehler("bruecke_keine_quittung", 504);
        JSONObject qj = new JSONObject(q);
        if (!qj.optBoolean("ok", false)) throw new WallboxFehler(fehlercode(qj.optString("grund", "")), 409);
    }

    /** Grund der Brücke → Panoptes-Fehlercode. */
    static String fehlercode(String grund) {
        switch (grund) {
            case "gesperrt": return "bruecke_gesperrt";
            case "wert_ungueltig": return "wert_ungueltig";
            case "nicht_erreichbar": return "nicht_erreichbar";
            case "nicht_angesteckt": return "bruecke_nicht_angesteckt";
            case "fehler_wallbox": return "bruecke_fehler_wallbox";
            default: return "bruecke_abgelehnt";
        }
    }

    /** Die Brücke prüft selbst per Echo; Zurücklesen kurz danach genügt. */
    @Override
    public long[] pausen() {
        return new long[]{300, 1000, 2000};
    }
}
