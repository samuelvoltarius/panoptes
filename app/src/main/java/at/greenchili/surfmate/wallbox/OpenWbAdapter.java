package at.greenchili.surfmate.wallbox;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * openWB über dessen eigenen MQTT-Broker (Port 1883), openWB regelt weiter selbst – wir ändern nur seine Einstellungen.
 * Topics nach dem openWB-Quellcode (nur Namen/Werte übernommen, kein Code):
 * <pre>
 * 1.x (snaptec/openWB runs/mqttsub.py, pubmqtt.sh, regel.sh):
 *   lesen  openWB/global/ChargeMode (0 Sofort, 1 Min+PV, 2 PV, 3 Aus, 4 Standby), openWB/lp/N/ChargePointEnabled,
 *          …/boolChargeStat, …/boolPlugStat, …/W, openWB/config/get/sofort/lp/N/current
 *   setzen openWB/set/ChargeMode (für die ganze openWB!), openWB/set/lp/N/ChargePointEnabled 0|1,
 *          openWB/config/set/sofort/lp/N/current 6–32
 * 2.x (openWB/core chargepoint_data.py, setdata.py, simpleAPI ParameterHandler.php):
 *   lesen  openWB/chargepoint/N/get/charge_state|plug_state|power, openWB/chargepoint/N/set/manual_lock,
 *          openWB/chargepoint/N/set/charge_template (JSON)
 *   setzen wie openWBs eigene simpleAPI: Ladepunkt-Profil lesen, nur chargemode.selected (instant_charging | pv_charging | stop)
 *          bzw. chargemode.instant_charging.current ändern, nach openWB/set/chargepoint/N/set/charge_template schreiben
 * </pre>
 * Version wird erkannt (welche Topics der Broker liefert). Eine Sperre (manual_lock) des Nutzers wird nie aufgehoben.
 */
public final class OpenWbAdapter implements DirektAdapter {

    public static final int PORT = 1883;
    static final long WARTEN_MS = 2500;

    /** MQTT-Zugang; im Test gegen einen Test-Broker. */
    public interface Mqtt {
        /** Abonniert die Topics und sammelt (meist gespeicherte) Werte, bis {@code fertig} gilt oder die Zeit um ist. */
        Map<String, String> lies(String host, int port, String benutzer, String passwort, List<String> topics,
                                 java.util.function.Predicate<Map<String, String>> fertig, long wartenMs) throws WallboxFehler;

        void sende(String host, int port, String benutzer, String passwort, String topic, String wert) throws WallboxFehler;
    }

    /** Paho (schon Teil von Panoptes, EPL-2.0/EDL-1.0). Je Vorgang eine kurze Verbindung. */
    public static final Mqtt PAHO = new Mqtt() {
        private MqttClient verbinde(String host, int port, String b, String p) throws Exception {
            byte[] z = new byte[6];
            new SecureRandom().nextBytes(z);
            MqttClient c = new MqttClient("tcp://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port,
                    "panoptes-wb-" + TuyaCloud.hex(z), new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setCleanSession(true);
            o.setConnectionTimeout(4);
            o.setKeepAliveInterval(10);
            o.setAutomaticReconnect(false);
            if (b != null && !b.isEmpty()) {
                o.setUserName(b);
                o.setPassword(p == null ? new char[0] : p.toCharArray());
            }
            c.connect(o);
            return c;
        }

        @Override
        public Map<String, String> lies(String host, int port, String b, String p, List<String> topics,
                                        java.util.function.Predicate<Map<String, String>> fertig, long wartenMs) throws WallboxFehler {
            Map<String, String> werte = new ConcurrentHashMap<>();
            MqttClient c = null;
            try {
                c = verbinde(host, port, b, p);
                String[] t = topics.toArray(new String[0]);
                int[] q = new int[t.length];
                org.eclipse.paho.client.mqttv3.IMqttMessageListener[] l = new org.eclipse.paho.client.mqttv3.IMqttMessageListener[t.length];
                Arrays.fill(l, (org.eclipse.paho.client.mqttv3.IMqttMessageListener)
                        (topic, m) -> werte.put(topic, new String(m.getPayload(), StandardCharsets.UTF_8)));
                long ende = System.currentTimeMillis() + wartenMs;
                c.subscribe(t, q, l);                       // ein SUBSCRIBE für alle Topics (eine Rundreise)
                while (System.currentTimeMillis() < ende && !fertig.test(werte)) Thread.sleep(20);
                return werte;
            } catch (org.eclipse.paho.client.mqttv3.MqttSecurityException e) {
                throw new WallboxFehler("anmeldung", 502);
            } catch (Exception e) {
                throw new WallboxFehler("nicht_erreichbar", 502);
            } finally {
                schliesse(c);
            }
        }

        @Override
        public void sende(String host, int port, String b, String p, String topic, String wert) throws WallboxFehler {
            MqttClient c = null;
            try {
                c = verbinde(host, port, b, p);
                c.publish(topic, wert.getBytes(StandardCharsets.UTF_8), 1, false);
            } catch (org.eclipse.paho.client.mqttv3.MqttSecurityException e) {
                throw new WallboxFehler("anmeldung", 502);
            } catch (Exception e) {
                throw new WallboxFehler("nicht_erreichbar", 502);
            } finally {
                schliesse(c);
            }
        }

        private void schliesse(MqttClient c) {
            if (c == null) return;
            try {
                if (c.isConnected()) c.disconnectForcibly(0, 0, false);   // nichts mehr unterwegs (QoS-1-Bestätigung schon da) → sofort trennen
            } catch (Exception ignored) { }
            try {
                c.close();
            } catch (Exception ignored) { }
        }
    };

    private final String host;
    private final int port;
    private final int lp;
    private final String benutzer;
    private final String passwort;
    private final Mqtt mqtt;
    private Integer version;

    public OpenWbAdapter(String host, int port, int lp, String benutzer, String passwort, Mqtt mqtt) throws WallboxFehler {
        if (lp < 1 || lp > 8) throw new WallboxFehler("einstellung_ungueltig");
        this.host = host;
        this.port = port;
        this.lp = lp;
        this.benutzer = benutzer;
        this.passwort = passwort;
        this.mqtt = mqtt;
    }

    private List<String> topics1() {
        String b = "openWB/lp/" + lp + "/";
        return Arrays.asList("openWB/global/ChargeMode", b + "ChargePointEnabled", b + "boolChargeStat", b + "boolPlugStat", b + "W",
                "openWB/config/get/sofort/lp/" + lp + "/current");
    }

    private List<String> topics2() {
        String b = "openWB/chargepoint/" + lp + "/";
        return Arrays.asList(b + "get/charge_state", b + "get/plug_state", b + "get/power", b + "set/manual_lock", b + "set/charge_template");
    }

    /** Liest beide Topic-Sätze; erkennt daran die Version (1 oder 2). */
    private Map<String, String> werte() throws WallboxFehler {
        List<String> alle = new ArrayList<>();
        if (version == null || version == 1) alle.addAll(topics1());
        if (version == null || version == 2) alle.addAll(topics2());
        List<String> t1 = topics1(), t2 = topics2();
        Map<String, String> w = mqtt.lies(host, port, benutzer, passwort, alle,
                m -> m.keySet().containsAll(t1) || m.keySet().containsAll(t2), WARTEN_MS);
        if (version == null) {
            if (w.containsKey("openWB/global/ChargeMode")) version = 1;
            else if (w.containsKey("openWB/chargepoint/" + lp + "/get/charge_state")) version = 2;
            else throw new WallboxFehler("openwb_keine_daten", 502);
        }
        return w;
    }

    private static boolean wahr(String s) {
        return s != null && (s.trim().equals("1") || s.trim().equalsIgnoreCase("true"));
    }

    private static Object zahlOderNull(String s) {
        try {
            return s == null ? JSONObject.NULL : EvccQuelle.zahl(Math.round(Double.parseDouble(s.trim())));
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        Map<String, String> w = werte();
        JSONObject o = DirektAdapter.leer();
        JSONArray modi = new JSONArray();
        if (version == 1) {
            String b = "openWB/lp/" + lp + "/";
            o.put("laedt", w.containsKey(b + "boolChargeStat") ? (Object) wahr(w.get(b + "boolChargeStat")) : JSONObject.NULL);
            o.put("verbunden", w.containsKey(b + "boolPlugStat") ? (Object) wahr(w.get(b + "boolPlugStat")) : JSONObject.NULL);
            o.put("leistungW", zahlOderNull(w.get(b + "W")));
            String m = w.getOrDefault("openWB/global/ChargeMode", "").trim();
            boolean an = !"0".equals(w.getOrDefault(b + "ChargePointEnabled", "1").trim());
            String modus = !an ? WallboxQuelle.MODUS_AUS : "0".equals(m) ? WallboxQuelle.MODUS_SOFORT
                    : "1".equals(m) ? WallboxQuelle.MODUS_MINPV : "2".equals(m) ? WallboxQuelle.MODUS_PV
                    : "3".equals(m) || "4".equals(m) ? WallboxQuelle.MODUS_AUS : null;
            o.put("modus", modus == null ? JSONObject.NULL : modus);
            o.put("modusRoh", "ChargeMode=" + m + ",ChargePointEnabled=" + (an ? 1 : 0));
            o.put("stromA", zahlOderNull(w.get("openWB/config/get/sofort/lp/" + lp + "/current")));
            for (String x : WallboxQuelle.MODI) modi.put(x);
            o.put("hinweis", "openwb1_modus_global");
        } else {
            String b = "openWB/chargepoint/" + lp + "/";
            o.put("laedt", wahr(w.get(b + "get/charge_state")));
            o.put("verbunden", wahr(w.get(b + "get/plug_state")));
            o.put("leistungW", zahlOderNull(w.get(b + "get/power")));
            boolean gesperrt = wahr(w.get(b + "set/manual_lock"));
            JSONObject t = profil(w);
            String sel = t == null ? "" : t.optJSONObject("chargemode") == null ? "" : t.getJSONObject("chargemode").optString("selected", "");
            String modus = gesperrt || "stop".equals(sel) ? WallboxQuelle.MODUS_AUS : "instant_charging".equals(sel) ? WallboxQuelle.MODUS_SOFORT
                    : "pv_charging".equals(sel) ? WallboxQuelle.MODUS_PV : null;
            o.put("modus", modus == null ? JSONObject.NULL : modus);
            o.put("modusRoh", "selected=" + sel + ",manual_lock=" + gesperrt);
            if (t != null && t.optJSONObject("chargemode") != null && t.getJSONObject("chargemode").optJSONObject("instant_charging") != null) {
                o.put("stromA", zahlOderNull(String.valueOf(t.getJSONObject("chargemode").getJSONObject("instant_charging").opt("current"))));
            }
            if (t != null) modi.put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT).put(WallboxQuelle.MODUS_PV);
            if (gesperrt) o.put("hinweis", "openwb_gesperrt");
        }
        o.put("faehigkeiten", new JSONObject().put("modus", modi).put("ziel", false).put("plan", JSONObject.NULL)
                .put("strom", modi.length() == 0 ? JSONObject.NULL : new JSONObject().put("min", 6).put("max", 32)));
        return o;
    }

    private JSONObject profil(Map<String, String> w) {
        try {
            String s = w.get("openWB/chargepoint/" + lp + "/set/charge_template");
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        Map<String, String> w = werte();
        if (version == 1) {
            String b = "openWB/set/lp/" + lp + "/";
            if ("modus".equals(aktion)) {
                String m = String.valueOf(wert);
                if (WallboxQuelle.MODUS_AUS.equals(m)) {
                    sende(b + "ChargePointEnabled", "0");
                    return;
                }
                String nr = WallboxQuelle.MODUS_SOFORT.equals(m) ? "0" : WallboxQuelle.MODUS_MINPV.equals(m) ? "1" : WallboxQuelle.MODUS_PV.equals(m) ? "2" : null;
                if (nr == null) throw new WallboxFehler("wert_ungueltig", 400);
                sende("openWB/set/ChargeMode", nr);
                sende(b + "ChargePointEnabled", "1");
            } else if ("strom".equals(aktion)) {
                sende("openWB/config/set/sofort/lp/" + lp + "/current", String.valueOf(EvccQuelle.ganzzahl(wert, 6, 32)));
            } else {
                throw new WallboxFehler("nicht_unterstuetzt", 400);
            }
            return;
        }
        // 2.x: Ladepunkt-Profil lesen, nur ein Feld ändern, zurückschreiben (wie openWBs simpleAPI)
        JSONObject t = profil(w);
        if (t == null) throw new WallboxFehler("openwb_profil_fehlt", 502);
        JSONObject cm = t.optJSONObject("chargemode");
        if (cm == null) throw new WallboxFehler("openwb_profil_fehlt", 502);
        if ("modus".equals(aktion)) {
            String m = String.valueOf(wert);
            String sel = WallboxQuelle.MODUS_AUS.equals(m) ? "stop" : WallboxQuelle.MODUS_SOFORT.equals(m) ? "instant_charging"
                    : WallboxQuelle.MODUS_PV.equals(m) ? "pv_charging" : null;
            if (sel == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
            cm.put("selected", sel);
        } else if ("strom".equals(aktion)) {
            JSONObject ic = cm.optJSONObject("instant_charging");
            if (ic == null) throw new WallboxFehler("openwb_profil_fehlt", 502);
            ic.put("current", EvccQuelle.ganzzahl(wert, 6, 32));
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        sende("openWB/set/chargepoint/" + lp + "/set/charge_template", t.toString());
    }

    private void sende(String topic, String wert) throws WallboxFehler {
        mqtt.sende(host, port, benutzer, passwort, topic, wert);
    }
}
