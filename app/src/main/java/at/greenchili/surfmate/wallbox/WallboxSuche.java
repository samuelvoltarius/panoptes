package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * „Wallbox finden“ (0.1.19, WALLBOX_FERN_KONZEPT §3, REGEL DAU): ein Knopf, Panoptes sucht im eigenen WLAN gleichzeitig nach
 * allen bekannten Arten und liefert Treffer mit fertigem Einstellungs-Vorschlag. <b>Nur lesen</b>: keine Befehle an eine
 * Wallbox, kein Schreiben von Einstellungen (das macht erst „Übernehmen“ über {@code /api/wallbox/einstellungen}).
 *
 * <ul>
 *   <li>Tuya: UDP-Broadcasts auf 6666 (3.1, Klartext) und 6667 (3.3/3.4 AES-ECB, 3.5 AES-GCM mit dem öffentlich bekannten
 *       UDP-Schlüssel) mithören → Geräte-ID, IP, Version. Steuern braucht zusätzlich den local_key (nicht hier).</li>
 *   <li>KEBA P30: UDP „i“ an 7090 (Broadcast), die Box antwortet auf 7090.</li>
 *   <li>mDNS: {@code _home-assistant._tcp}, {@code _http._tcp} (go-e, Shelly, evcc, openWB, Wattpilot am Namen),
 *       {@code _shelly._tcp}.</li>
 *   <li>Nachbarn aus {@code /proc/net/arp} (nur eigenes privates /24, höchstens {@link #MAX_NACHBARN}): go-e
 *       ({@code /api/status}), Shelly ({@code /shelly}), evcc (7070 {@code /api/state}), HA (8123), Modbus (502),
 *       MQTT (1883 → vielleicht openWB).</li>
 * </ul>
 * Dauer höchstens etwa {@link #DAUER_MS}; nur auf Knopfdruck.
 */
public final class WallboxSuche {

    static final long DAUER_MS = 5_000L;
    static final int MAX_NACHBARN = 64;
    static final int TCP_MS = 400;
    /** Öffentlich bekannter Tuya-UDP-Schlüssel: md5("yGAdlopoPVldABfn") (tinytuya). */
    static final byte[] TUYA_UDP_SCHLUESSEL = md5("yGAdlopoPVldABfn");

    /** Netzzugang (Tests: Attrappe). */
    public interface Netz {
        /** UDP auf {@code port} mithören (Broadcasts), {@code ms} lang. */
        List<Paket> lausche(int port, long ms);

        /** {@code daten} als Broadcast an {@code zielPort} senden (von {@code eigenerPort}), Antworten sammeln. */
        List<Paket> rufe(byte[] daten, int zielPort, int eigenerPort, long ms);

        /** mDNS-Anfrage an 224.0.0.251:5353, Antworten sammeln. */
        List<Paket> mdns(byte[] anfrage, long ms);

        /** IPv4-Nachbarn im eigenen WLAN (ohne die eigene Adresse). */
        List<String> nachbarn();

        boolean tcpOffen(String ip, int port, int ms);
    }

    public static final class Paket {
        final String ip;
        final byte[] daten;
        public Paket(String ip, byte[] daten) { this.ip = ip; this.daten = daten; }
    }

    private final Netz netz;
    private final WallboxHttp http;
    private java.util.List<JSONObject> broker = Collections.emptyList();
    private OpenWbAdapter.Mqtt mqtt;

    /** 0.1.19 Wallbox-Brücke: auf diesen Brokern nach {@code wallbox/+/zustand} (v=1) suchen. */
    public WallboxSuche mitBruecke(java.util.List<JSONObject> broker, OpenWbAdapter.Mqtt mqtt) {
        this.broker = broker == null ? Collections.<JSONObject>emptyList() : broker;
        this.mqtt = mqtt;
        return this;
    }

    public WallboxSuche(Netz netz, WallboxHttp http) {
        this.netz = netz;
        this.http = http;
    }

    // ------------------------------------------------------------------ Suche

    public JSONObject suche() throws JSONException {
        Map<String, JSONObject> treffer = Collections.synchronizedMap(new LinkedHashMap<>());
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> f = new ArrayList<>();
        f.add(ex.submit(() -> tuya(6666, treffer)));
        f.add(ex.submit(() -> tuya(6667, treffer)));
        f.add(ex.submit(() -> keba(treffer)));
        f.add(ex.submit(() -> mdns(treffer)));
        f.add(ex.submit(() -> nachbarn(treffer)));
        f.add(ex.submit(() -> bruecken(treffer)));
        ex.shutdown();
        try {
            ex.awaitTermination(DAUER_MS + 3_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.shutdownNow();
        JSONArray a = new JSONArray();
        synchronized (treffer) {
            for (JSONObject t : treffer.values()) a.put(t);
        }
        return new JSONObject().put("ok", true).put("treffer", a).put("anzahl", a.length());
    }

    private static void dazu(Map<String, JSONObject> m, JSONObject t) {
        if (t == null) return;
        String schl = t.optString("art") + "|" + t.optString("adresse");
        synchronized (m) {
            JSONObject alt = m.get(schl);
            // ein sicherer Treffer schlägt einen unsicheren
            if (alt == null || (alt.optBoolean("unsicher") && !t.optBoolean("unsicher"))) m.put(schl, t);
        }
    }

    private void tuya(int port, Map<String, JSONObject> m) {
        Map<String, Boolean> gesehen = new HashMap<>();
        for (Paket p : netz.lausche(port, DAUER_MS)) {
            JSONObject d = tuyaBroadcast(p.daten);
            if (d == null) continue;
            String gw = d.optString("gwId", "");
            if (gesehen.put(gw + p.ip, true) != null) continue;
            try {
                dazu(m, tuyaTreffer(d, p.ip));
            } catch (JSONException ignored) { }
        }
    }

    private void keba(Map<String, JSONObject> m) {
        for (Paket p : netz.rufe("i".getBytes(StandardCharsets.US_ASCII), 7090, 7090, 2_000L)) {
            String s = new String(p.daten, StandardCharsets.ISO_8859_1);
            if (!s.contains("Firmware") && !s.contains("Product")) continue;
            try {
                dazu(m, direkt("keba", "KEBA", p.ip, null));
            } catch (JSONException ignored) { }
        }
    }

    private void mdns(Map<String, JSONObject> m) {
        byte[] anfrage = mdnsAnfrage("_home-assistant._tcp.local", "_http._tcp.local", "_shelly._tcp.local");
        for (Paket p : netz.mdns(anfrage, 3_000L)) {
            try {
                for (JSONObject d : mdnsAntwort(p.daten, p.ip)) dazu(m, mdnsTreffer(d));
            } catch (Exception ignored) { }
        }
    }

    /** Nur die Brücken auf den Panoptes-Brokern (ohne WLAN-Suche) – für „Wallbox gefunden“ ohne Knopfdruck. */
    public JSONArray nurBruecken() {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        bruecken(m);
        JSONArray a = new JSONArray();
        for (JSONObject t : m.values()) a.put(t);
        return a;
    }

    /** Wallbox-Brücken auf den Panoptes-Brokern (WALLBOX_BRUECKE_MQTT §1: retained zustand mit v=1). */
    private void bruecken(Map<String, JSONObject> m) {
        if (mqtt == null) return;
        for (JSONObject b : broker) {
            try {
                Map<String, String> w = mqtt.lies(b.optString("host"), b.optInt("port", OpenWbAdapter.PORT),
                        b.optString("benutzer", ""), b.optString("passwort", ""),
                        Collections.singletonList("wallbox/+/zustand"), x -> false, 2_500L);
                for (Map.Entry<String, String> e : w.entrySet()) dazu(m, brueckeTreffer(e.getKey(), e.getValue(), b));
            } catch (Exception ignored) {
                // Broker nicht erreichbar: keine Brücke, kein Fehler
            }
        }
    }

    /** Ein {@code wallbox/<id>/zustand} → Treffer (oder null). Der Vorschlag nutzt den Panoptes-Zugang zum Broker. */
    static JSONObject brueckeTreffer(String topic, String roh, JSONObject broker) throws JSONException {
        String[] t = topic.split("/");
        if (t.length != 3 || !"wallbox".equals(t[0]) || !"zustand".equals(t[2]) || !t[1].matches(BrueckeAdapter.ID_MUSTER)) return null;
        JSONObject z;
        try {
            z = new JSONObject(roh);
        } catch (Exception e) {
            return null;
        }
        if (z.optInt("v", 0) != 1) return null;
        String name = z.optString("name", "Wallbox");
        String modell = z.optString("modell", "");
        JSONObject g = new JSONObject().put("schluessel", "lp1").put("titel", kurz(name)).put("adapter", "bruecke")
                .put("adresse", broker.optString("host")).put("port", broker.optInt("port", OpenWbAdapter.PORT))
                .put("geraeteId", t[1]).put("kanal", 0).put("modell", JSONObject.NULL);
        JSONObject x = new JSONObject().put("art", "bruecke")
                .put("name", modell.isEmpty() ? name : name + " (" + modell + ")")
                .put("adresse", broker.optString("host") + " · " + t[1])
                .put("details", new JSONObject().put("geraeteId", t[1]).put("steuernErlaubt", z.optBoolean("steuern_erlaubt", false)))
                .put("vorschlag", new JSONObject().put("quelle", "direkt")
                        .put("direkt", new JSONObject().put("geraete", new JSONArray().put(g))))
                .put("braucht", new JSONArray());
        if (!z.optBoolean("steuern_erlaubt", false)) x.put("hinweis", "bruecke_gesperrt");
        return x;
    }

    private void nachbarn(Map<String, JSONObject> m) {
        List<String> n = netz.nachbarn();
        if (n.size() > MAX_NACHBARN) n = n.subList(0, MAX_NACHBARN);
        ExecutorService ex = Executors.newFixedThreadPool(16);
        for (String ip : n) {
            if (!privat(ip)) continue;
            ex.submit(() -> {
                try {
                    probe(ip, m);
                } catch (Exception ignored) { }
            });
        }
        ex.shutdown();
        try {
            ex.awaitTermination(DAUER_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.shutdownNow();
    }

    /** Ein Nachbar: bekannte Antworten der Wallbox-Arten (nur GET bzw. Verbindungsaufbau). */
    void probe(String ip, Map<String, JSONObject> m) throws JSONException {
        if (netz.tcpOffen(ip, 80, TCP_MS)) {
            JSONObject goe = getJson("http://" + ip + "/api/status");
            if (goe != null && goe.has("car") && (goe.has("amp") || goe.has("fwv") || goe.has("sse"))) {
                dazu(m, direkt("goe", "go-e Charger", ip, null));
            }
            JSONObject sh = getJson("http://" + ip + "/shelly");
            if (sh != null && (sh.has("type") || sh.has("gen") || sh.has("app"))) {
                String name = sh.optString("name", "");
                dazu(m, direkt("shelly", name.isEmpty() ? "Shelly " + sh.optString("app", sh.optString("type", "")) : name, ip, null));
            }
        }
        if (netz.tcpOffen(ip, 7070, TCP_MS)) {
            JSONObject ev = getJson("http://" + ip + ":7070/api/state");
            if (ev != null && (ev.has("loadpoints") || ev.optJSONObject("result") != null && ev.optJSONObject("result").has("loadpoints"))) {
                dazu(m, evcc("http://" + ip + ":7070"));
            }
        }
        if (netz.tcpOffen(ip, 8123, TCP_MS)) dazu(m, ha("http://" + ip + ":8123", "Home Assistant", true));
        if (netz.tcpOffen(ip, 502, TCP_MS)) {
            JSONObject t = direkt("modbus", "Modbus-Gerät", ip, null);
            t.put("braucht", new JSONArray().put("modell"));
            t.put("unsicher", true);
            dazu(m, t);
        }
        if (netz.tcpOffen(ip, 1883, TCP_MS)) {
            JSONObject t = direkt("openwb", "MQTT-Gerät (vielleicht openWB)", ip, null);
            t.put("unsicher", true);
            dazu(m, t);
        }
    }

    private JSONObject getJson(String url) {
        try {
            WallboxHttp.Antwort a = http.anfrage("GET", url, new HashMap<>(), null);
            if (a.code != 200 || a.text == null) return null;
            String t = a.text.trim();
            return t.startsWith("{") ? new JSONObject(t) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Treffer + Vorschläge

    /** Vorschlag für einen direkten Adapter (WallboxKonfig „direkt“). */
    static JSONObject direkt(String adapter, String name, String ip, String modell) throws JSONException {
        JSONObject g = new JSONObject().put("schluessel", "lp1").put("titel", kurz(name)).put("adapter", adapter)
                .put("adresse", ip).put("kanal", 0).put("modell", modell == null ? JSONObject.NULL : modell);
        return new JSONObject().put("art", adapter).put("name", name).put("adresse", ip)
                .put("vorschlag", new JSONObject().put("quelle", "direkt")
                        .put("direkt", new JSONObject().put("geraete", new JSONArray().put(g))))
                .put("braucht", new JSONArray());
    }

    static JSONObject tuyaTreffer(JSONObject d, String ipPaket) throws JSONException {
        String ip = d.optString("ip", ipPaket);
        String gw = d.optString("gwId", "");
        String version = d.optString("version", "3.3");
        if (!version.matches("3\\.[345]")) version = "3.3";
        JSONObject t = new JSONObject().put("art", "tuya").put("name", "Tuya / Smart Life").put("adresse", ip)
                .put("details", new JSONObject().put("geraeteId", gw).put("version", version)
                        .put("productKey", d.optString("productKey", "")))
                .put("braucht", new JSONArray().put("localKey"))
                .put("hinweis", "tuya_schluessel");
        if (gw.matches("[A-Za-z0-9]{6,64}")) {
            JSONObject g = new JSONObject().put("schluessel", "lp1").put("titel", "Tuya").put("geraeteId", gw)
                    .put("ip", ip).put("version", version);
            t.put("vorschlag", new JSONObject().put("quelle", "tuya")
                    .put("tuya", new JSONObject().put("zugang", "lan").put("geraete", new JSONArray().put(g))));
        } else {
            t.put("vorschlag", JSONObject.NULL);
        }
        return t;
    }

    static JSONObject ha(String url, String name, boolean unsicher) throws JSONException {
        return new JSONObject().put("art", "ha").put("name", name).put("adresse", url).put("url", url)
                .put("vorschlag", JSONObject.NULL).put("braucht", new JSONArray().put("anmeldung"))
                .put("unsicher", unsicher);
    }

    static JSONObject evcc(String url) throws JSONException {
        return new JSONObject().put("art", "evcc").put("name", "evcc").put("adresse", url)
                .put("vorschlag", new JSONObject().put("quelle", "evcc").put("evcc", new JSONObject().put("url", url)))
                .put("braucht", new JSONArray());
    }

    /** mDNS-Dienst → Treffer (oder null). */
    static JSONObject mdnsTreffer(JSONObject d) throws JSONException {
        String dienst = d.optString("dienst").toLowerCase(Locale.ROOT);
        String inst = d.optString("instanz").toLowerCase(Locale.ROOT);
        String ip = d.optString("ip", "");
        int port = d.optInt("port", 80);
        if (ip.isEmpty() || !privat(ip)) return null;
        String hostPort = port == 80 ? ip : ip + ":" + port;
        if (dienst.startsWith("_home-assistant.")) return ha("http://" + ip + ":" + port, "Home Assistant", false);
        if (dienst.startsWith("_shelly.") || inst.startsWith("shelly")) return direkt("shelly", d.optString("instanz"), ip, null);
        if (inst.contains("go-e") || inst.startsWith("goe")) return direkt("goe", "go-e Charger", ip, null);
        if (inst.contains("evcc")) return evcc("http://" + hostPort);
        if (inst.contains("openwb")) return direkt("openwb", "openWB", ip, null);
        if (inst.contains("wattpilot")) {
            return new JSONObject().put("art", "wattpilot").put("name", "Fronius Wattpilot").put("adresse", ip)
                    .put("vorschlag", JSONObject.NULL).put("braucht", new JSONArray()).put("hinweis", "nur_ueber_ha");
        }
        return null;
    }

    private static String kurz(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** Nur private IPv4-Adressen (RFC 1918). */
    static boolean privat(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] p = ip.split("\\.");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    // ------------------------------------------------------------------ Tuya-Broadcast

    /** Tuya-UDP-Broadcast entschlüsseln (3.1 Klartext, 3.3/3.4 AES-ECB, 3.5 AES-GCM); null, wenn kein Tuya-Paket. */
    static JSONObject tuyaBroadcast(byte[] d) {
        try {
            if (d == null || d.length < 28) return null;
            ByteBuffer b = ByteBuffer.wrap(d);
            int prefix = b.getInt(0);
            byte[] klar;
            if (prefix == 0x000055AA) {
                int len = b.getInt(12);
                int ende = 16 + len - 8;   // ohne CRC + Suffix
                if (ende > d.length || ende <= 20) return null;
                byte[] nutz = java.util.Arrays.copyOfRange(d, 20, ende);
                if (nutz.length > 0 && nutz[0] == '{') {
                    klar = nutz;
                } else {
                    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TUYA_UDP_SCHLUESSEL, "AES"));
                    klar = c.doFinal(nutz);
                }
            } else if (prefix == 0x00006699) {
                // 3.5: Präfix(4) unbekannt(2) Seq(4) Cmd(4) Länge(4) | IV(12) Daten+Tag | Suffix(4) [Format ungeprüft]
                int len = b.getInt(14);
                int start = 18;
                if (start + len > d.length || len < 28) return null;
                byte[] iv = java.util.Arrays.copyOfRange(d, start, start + 12);
                byte[] ct = java.util.Arrays.copyOfRange(d, start + 12, start + len);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(TUYA_UDP_SCHLUESSEL, "AES"), new GCMParameterSpec(128, iv));
                c.updateAAD(java.util.Arrays.copyOfRange(d, 4, start));
                klar = c.doFinal(ct);
                // 3.5-Nutzdaten beginnen mit 4 Byte Rückgabecode
                if (klar.length > 4 && klar[0] != '{') klar = java.util.Arrays.copyOfRange(klar, 4, klar.length);
            } else {
                return null;
            }
            String s = new String(klar, StandardCharsets.UTF_8).trim();
            int i = s.indexOf('{');
            if (i < 0) return null;
            JSONObject o = new JSONObject(s.substring(i));
            return o.has("gwId") || o.has("devId") ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ mDNS

    /** mDNS-Anfrage (PTR, Antwort per Unicast erwünscht). */
    static byte[] mdnsAnfrage(String... dienste) {
        ByteBuffer b = ByteBuffer.allocate(512);
        b.putShort((short) 0).putShort((short) 0).putShort((short) dienste.length).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0);
        for (String d : dienste) {
            for (String teil : d.split("\\.")) {
                byte[] t = teil.getBytes(StandardCharsets.UTF_8);
                b.put((byte) t.length).put(t);
            }
            b.put((byte) 0).putShort((short) 12).putShort((short) 0x8001);
        }
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    /** mDNS-Antwort → Liste {dienst, instanz, host, ip, port}; ohne A-Satz gilt die Absenderadresse. */
    static List<JSONObject> mdnsAntwort(byte[] d, String absender) throws Exception {
        List<JSONObject> aus = new ArrayList<>();
        if (d == null || d.length < 12) return aus;
        ByteBuffer b = ByteBuffer.wrap(d);
        int qd = b.getShort(4) & 0xffff;
        int rr = (b.getShort(6) & 0xffff) + (b.getShort(8) & 0xffff) + (b.getShort(10) & 0xffff);
        int[] pos = {12};
        for (int i = 0; i < qd; i++) {
            name(d, pos);
            pos[0] += 4;
        }
        Map<String, String> ptr = new LinkedHashMap<>();      // Instanz → Dienst
        Map<String, Object[]> srv = new HashMap<>();          // Instanz → {Host, Port}
        Map<String, String> a = new HashMap<>();              // Host → IP
        for (int i = 0; i < rr && pos[0] + 10 <= d.length; i++) {
            String n = name(d, pos);
            int typ = b.getShort(pos[0]) & 0xffff;
            int len = b.getShort(pos[0] + 8) & 0xffff;
            int daten = pos[0] + 10;
            if (daten + len > d.length) break;
            if (typ == 12) {
                int[] q = {daten};
                ptr.put(name(d, q), n);
            } else if (typ == 33 && len >= 7) {
                int port = b.getShort(daten + 4) & 0xffff;
                int[] q = {daten + 6};
                srv.put(n, new Object[]{name(d, q), port});
            } else if (typ == 1 && len == 4) {
                a.put(n, (d[daten] & 0xff) + "." + (d[daten + 1] & 0xff) + "." + (d[daten + 2] & 0xff) + "." + (d[daten + 3] & 0xff));
            }
            pos[0] = daten + len;
        }
        for (Map.Entry<String, String> e : ptr.entrySet()) {
            String inst = e.getKey();
            Object[] s = srv.get(inst);
            String host = s == null ? "" : (String) s[0];
            String ip = a.containsKey(host) ? a.get(host) : absender;
            String instName = inst.endsWith("." + e.getValue()) ? inst.substring(0, inst.length() - e.getValue().length() - 1) : inst;
            aus.add(new JSONObject().put("dienst", e.getValue()).put("instanz", instName).put("host", host)
                    .put("ip", ip == null ? "" : ip).put("port", s == null ? 80 : (int) s[1]));
        }
        return aus;
    }

    /** DNS-Name lesen (mit Kompression), {@code pos[0]} steht danach hinter dem Namen. */
    static String name(byte[] d, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean gesprungen = false;
        int sprung = 0;
        while (p < d.length) {
            int l = d[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xc0) == 0xc0) {
                if (p + 1 >= d.length || ++sprung > 16) break;
                int ziel = ((l & 0x3f) << 8) | (d[p + 1] & 0xff);
                if (!gesprungen) pos[0] = p + 2;
                gesprungen = true;
                p = ziel;
                continue;
            }
            if (p + 1 + l > d.length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, l, StandardCharsets.UTF_8));
            p += 1 + l;
        }
        if (!gesprungen) pos[0] = p;
        return sb.toString();
    }

    /** Nachbarn aus der ARP-Tabelle (Text von /proc/net/arp), nur vollständige Einträge. */
    static List<String> arp(List<String> zeilen) {
        List<String> aus = new ArrayList<>();
        for (String z : zeilen) {
            String[] t = z.trim().split("\\s+");
            if (t.length < 4 || !t[0].matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
            if (!"0x2".equals(t[2]) || "00:00:00:00:00:00".equals(t[3])) continue;
            if (!aus.contains(t[0])) aus.add(t[0]);
        }
        return aus;
    }

    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ echtes Netz

    public static final Netz ECHT = new Netz() {
        @Override
        public List<Paket> lausche(int port, long ms) {
            List<Paket> aus = new ArrayList<>();
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(port));
                sammle(s, ms, aus);
            } catch (Exception ignored) { }
            return aus;
        }

        @Override
        public List<Paket> rufe(byte[] daten, int zielPort, int eigenerPort, long ms) {
            List<Paket> aus = new ArrayList<>();
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(eigenerPort));
                for (InetAddress bc : broadcasts()) s.send(new DatagramPacket(daten, daten.length, bc, zielPort));
                sammle(s, ms, aus);
            } catch (Exception ignored) { }
            return aus;
        }

        @Override
        public List<Paket> mdns(byte[] anfrage, long ms) {
            List<Paket> aus = new ArrayList<>();
            try (MulticastSocket s = new MulticastSocket(0)) {
                s.setTimeToLive(1);
                s.send(new DatagramPacket(anfrage, anfrage.length, InetAddress.getByName("224.0.0.251"), 5353));
                sammle(s, ms, aus);
            } catch (Exception ignored) { }
            return aus;
        }

        @Override
        public List<String> nachbarn() {
            List<String> z = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
                String l;
                while ((l = r.readLine()) != null) z.add(l);
            } catch (Exception ignored) { }
            return arp(z);
        }

        @Override
        public boolean tcpOffen(String ip, int port, int ms) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), ms);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void sammle(DatagramSocket s, long ms, List<Paket> aus) throws Exception {
            long ende = System.currentTimeMillis() + ms;
            byte[] puffer = new byte[4096];
            while (System.currentTimeMillis() < ende && aus.size() < 200) {
                s.setSoTimeout((int) Math.max(50, ende - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(puffer, puffer.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException t) {
                    break;
                }
                aus.add(new Paket(p.getAddress().getHostAddress(), java.util.Arrays.copyOf(p.getData(), p.getLength())));
            }
        }

        private List<InetAddress> broadcasts() throws Exception {
            List<InetAddress> aus = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null
                            && privat(ia.getAddress().getHostAddress())) aus.add(ia.getBroadcast());
                }
            }
            return aus;
        }
    };
}
