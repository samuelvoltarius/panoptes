package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Wallbox-Steuerung (WALLBOX_VERTRAG v1): Einstellungen, Status, Freigabe je Ladepunkt, zweistufige Bestätigung,
 * Fahrt-Sperre und Schreib-Protokoll. Reines Java (keine Android-Klassen), damit es am PC gegen evcc-Demo
 * und Attrappen getestet werden kann; die Android-Anbindung steckt in {@code WallboxApiHandler}.
 */
public final class WallboxDienst {

    public static final int VERSION = 1;
    static final long BESTAETIGUNG_MS = 30_000L;

    /** Fahrzustand: "steht", "faehrt" oder "unbekannt". */
    public interface Fahrzustand {
        String jetzt();
    }

    /** Antwort an den Handler: HTTP-Status + JSON. */
    public static final class Ergebnis {
        public final int status;
        public final JSONObject json;

        Ergebnis(int status, JSONObject json) throws JSONException {
            this.status = status;
            this.json = json;
        }
    }

    private final File konfigDatei;
    private final WallboxKonfig.Geheim geheim;
    private final WallboxHttp http;
    private final WallboxAdresse.Aufloeser aufloeser;
    private final Fahrzustand fahrzustand;
    private final LongSupplier uhr;
    private final WallboxQuelle.Schlaf schlaf;
    private final WallboxProtokoll protokoll;
    private final TuyaLan.Verbinder tuyaVerbinder;
    private final Supplier<JSONObject> tuyaModelle;
    private Supplier<JSONObject> modbusModelle = () -> null;
    private KebaUdpAdapter.Udp kebaUdp = KebaUdpAdapter.Udp.ECHT;
    private OpenWbAdapter.Mqtt openWbMqtt = OpenWbAdapter.PAHO;

    /** Stufe 2c: MQTT-Zugang für openWB (Tests: Test-Broker). */
    public WallboxDienst mitOpenWb(OpenWbAdapter.Mqtt mqtt) {
        this.openWbMqtt = mqtt;
        return this;
    }

    /** Stufe 2b: Modbus-Modelltabelle und KEBA-UDP-Zugang (Tests: andere Ports). */
    public WallboxDienst mitDirekt(Supplier<JSONObject> modbusModelle, KebaUdpAdapter.Udp kebaUdp) {
        this.modbusModelle = modbusModelle;
        this.kebaUdp = kebaUdp;
        return this;
    }
    private final SecureRandom zufall = new SecureRandom();
    private final Map<String, String[]> offen = new HashMap<>();   // Kennung → {Aktionsschlüssel, Ablauf}

    public WallboxDienst(File konfigDatei, File protokollDatei, WallboxKonfig.Geheim geheim, WallboxHttp http,
                         WallboxAdresse.Aufloeser aufloeser, Fahrzustand fahrzustand, LongSupplier uhr,
                         WallboxQuelle.Schlaf schlaf) {
        this(konfigDatei, protokollDatei, geheim, http, aufloeser, fahrzustand, uhr, schlaf, TuyaLan.TCP, () -> null);
    }

    public WallboxDienst(File konfigDatei, File protokollDatei, WallboxKonfig.Geheim geheim, WallboxHttp http,
                         WallboxAdresse.Aufloeser aufloeser, Fahrzustand fahrzustand, LongSupplier uhr,
                         WallboxQuelle.Schlaf schlaf, TuyaLan.Verbinder tuyaVerbinder, Supplier<JSONObject> tuyaModelle) {
        this.tuyaVerbinder = tuyaVerbinder;
        this.tuyaModelle = tuyaModelle;
        this.konfigDatei = konfigDatei;
        this.geheim = geheim;
        this.http = http;
        this.aufloeser = aufloeser;
        this.fahrzustand = fahrzustand;
        this.uhr = uhr;
        this.schlaf = schlaf;
        this.protokoll = new WallboxProtokoll(protokollDatei);
    }

    // ------------------------------------------------------------------ Einstellungen

    public synchronized JSONObject konfig() throws JSONException {
        try {
            if (konfigDatei.exists()) {
                // Liegt nur so auf der Platte, wie uebernimm() es geschrieben hat; Geheimnisse bleiben verschlüsselt.
                JSONObject k = new JSONObject(new String(Files.readAllBytes(konfigDatei.toPath()), StandardCharsets.UTF_8));
                if (k.has("evcc") && k.has("ha") && k.has("freigaben")) return k;
            }
        } catch (Exception ignored) { }
        return WallboxKonfig.leer();
    }

    private void speichere(JSONObject k) throws WallboxFehler, JSONException {
        try {
            File tmp = new File(konfigDatei.getPath() + ".tmp");
            Files.write(tmp.toPath(), k.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), konfigDatei.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new WallboxFehler("speichern_fehlgeschlagen", 500);
        }
    }

    public synchronized Ergebnis einstellungen() throws JSONException {
        return new Ergebnis(200, WallboxKonfig.oeffentlich(konfig()).put("ok", true));
    }

    public synchronized Ergebnis speichereEinstellungen(JSONObject neu) throws JSONException {
        try {
            JSONObject alt = konfig();
            JSONObject k = WallboxKonfig.uebernimm(alt, neu, geheim, uhr.getAsLong());
            String q = WallboxKonfig.quelle(k);
            if ("tuya".equals(q)) {
                pruefeTuya(k.getJSONObject("tuya"));
            } else if ("direkt".equals(q)) {
                JSONArray g = k.getJSONObject("direkt").getJSONArray("geraete");
                for (int i = 0; i < g.length(); i++) {
                    JSONObject e = g.getJSONObject(i);
                    if (DirektAdapter.cloud(e.optString("adapter"))) continue;   // feste Herstellerhosts, keine Adresse
                    if (DirektAdapter.hostAdresse(e.optString("adapter"))) WallboxAdresse.pruefeLanHost(e.optString("adresse"), aufloeser);
                    else WallboxAdresse.pruefe(e.optString("adresse"), false, aufloeser);
                }
            } else if (q != null) {
                JSONObject s = k.getJSONObject(q);
                WallboxAdresse.pruefe(s.getString("url"), s.optBoolean("oeffentlichErlaubt"), aufloeser);
                if ("ha".equals(q) && s.optString("token", "").isEmpty()) throw new WallboxFehler("token_fehlt");
            }
            speichere(k);
            offen.clear();
            return new Ergebnis(200, WallboxKonfig.oeffentlich(k).put("ok", true));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    // ------------------------------------------------------------------ Status

    public synchronized Ergebnis status() throws JSONException {
        return new Ergebnis(200, status(konfig()));
    }

    /** Nur lesender Test mit ungespeicherten Einstellungen (Einrichtung). */
    public synchronized Ergebnis test(JSONObject neu) throws JSONException {
        try {
            return new Ergebnis(200, status(WallboxKonfig.uebernimm(konfig(), neu, geheim, uhr.getAsLong())));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    JSONObject status(JSONObject k) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        String q = WallboxKonfig.quelle(k);
        o.put("quelle", q == null ? JSONObject.NULL : q);
        o.put("eingerichtet", q != null);
        o.put("evccVersion", JSONObject.NULL);
        o.put("steuernGesperrt", sperre() == null ? JSONObject.NULL : sperre());
        o.put("stand", uhr.getAsLong());
        o.put("ladepunkte", new JSONArray());
        if (q == null) {
            o.put("erreichbar", false).put("fehler", "nicht_eingerichtet");
            return o;
        }
        try {
            WallboxQuelle quelle = quelle(k);
            JSONArray lps = quelle.ladepunkte();
            JSONArray aus = new JSONArray();
            for (int i = 0; i < lps.length(); i++) {
                JSONObject lp = ohneIntern(lps.getJSONObject(i));
                lp.put("steuernFrei", "wallbox".equals(lp.optString("art")) && WallboxKonfig.freigegeben(k, lp.getString("id")));
                aus.put(lp);
            }
            o.put("ladepunkte", aus);
            JSONObject info = quelle.info();
            for (Iterator<String> it = info.keys(); it.hasNext(); ) { String key = it.next(); o.put(key, info.get(key)); }
            o.put("erreichbar", true).put("fehler", JSONObject.NULL);
        } catch (WallboxFehler f) {
            o.put("erreichbar", false).put("fehler", f.code);
        }
        return o;
    }

    WallboxQuelle quelle(JSONObject k) throws WallboxFehler, JSONException {
        String q = WallboxKonfig.quelle(k);
        if (q == null) throw new WallboxFehler("nicht_eingerichtet", 409);
        if ("tuya".equals(q)) return tuyaQuelle(k.getJSONObject("tuya"));
        if ("direkt".equals(q)) return direktQuelle(k.getJSONObject("direkt"));
        JSONObject s = k.getJSONObject(q);
        String basis = WallboxAdresse.pruefe(s.optString("url"), s.optBoolean("oeffentlichErlaubt"), aufloeser);
        if ("evcc".equals(q)) {
            return new EvccQuelle(basis, geheim.ent(s.optString("apiKey", "")), http, schlaf, uhr);
        }
        String token = geheim.ent(s.optString("token", ""));
        if (token == null || token.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        return new HaQuelle(basis, token, s.optJSONArray("ladepunkte"), http, schlaf);
    }

    /** Direkte Adapter (Stufe 2): nur private Adressen; je Gerät ein Adapter aus {@link DirektAdapter#KENNUNGEN}. */
    private WallboxQuelle direktQuelle(JSONObject d) throws WallboxFehler, JSONException {
        JSONArray g = d.getJSONArray("geraete");
        DirektQuelle.Geraet[] arr = new DirektQuelle.Geraet[g.length()];
        for (int i = 0; i < g.length(); i++) {
            JSONObject e = g.getJSONObject(i);
            if (DirektAdapter.cloud(e.getString("adapter"))) {
                // Regel 2: ohne Einwilligung wird der Cloud-Adapter gar nicht gebaut → keine einzige Anfrage an den Hersteller
                DirektAdapter c = !e.optBoolean("einwilligung", false) ? new DirektQuelle.Gesperrt("einwilligung_fehlt")
                        : new CloudAdapter(e.getString("adapter"), e.optString("geraeteId"), e.optString("benutzer", ""),
                        geheim.ent(e.optString("passwort", "")), http, uhr);
                arr[i] = new DirektQuelle.Geraet(e.getString("schluessel"), e.optString("titel", ""), e.getString("adapter"), c);
                continue;
            }
            boolean host = DirektAdapter.hostAdresse(e.getString("adapter"));
            String basis = host ? WallboxAdresse.pruefeLanHost(e.optString("adresse"), aufloeser)
                    : WallboxAdresse.pruefe(e.optString("adresse"), false, aufloeser);
            DirektAdapter a;
            switch (e.getString("adapter")) {
                case "keba":
                    a = new KebaUdpAdapter(basis, kebaUdp);
                    break;
                case "openwb":
                    a = new OpenWbAdapter(basis, e.isNull("port") ? OpenWbAdapter.PORT : e.optInt("port"), e.optInt("nummer", 1),
                            e.optString("benutzer", ""), geheim.ent(e.optString("passwort", "")), openWbMqtt);
                    break;
                case "modbus": {
                    JSONObject m = modbusModell(e.isNull("modell") ? "" : e.optString("modell"));
                    int port = e.isNull("port") ? m.optInt("port", ModbusTcp.PORT) : e.optInt("port");
                    int einheit = e.isNull("einheit") ? m.optInt("einheit", 1) : e.optInt("einheit");
                    a = new ModbusAdapter(new ModbusTcp(basis, port, einheit, tuyaVerbinder), m);
                    break;
                }
                case "goe":
                    a = new GoeAdapter(basis, http);
                    break;
                case "shelly":
                    a = new ShellyAdapter(basis, e.optInt("kanal", 0), e.optString("benutzer", ""),
                            geheim.ent(e.optString("passwort", "")), http);
                    break;
                default:
                    throw new WallboxFehler("einstellung_ungueltig");
            }
            arr[i] = new DirektQuelle.Geraet(e.getString("schluessel"), e.optString("titel", ""), e.getString("adapter"), a);
        }
        return new DirektQuelle(arr, schlaf);
    }

    private JSONObject modbusModell(String id) throws WallboxFehler, JSONException {
        JSONObject t = null;
        try {
            t = modbusModelle.get();
        } catch (Throwable ignored) { }
        JSONArray m = t == null ? null : t.optJSONArray("modelle");
        for (int i = 0; m != null && i < m.length(); i++) {
            JSONObject e = m.optJSONObject(i);
            if (e != null && id.equals(e.optString("id"))) {
                ModbusAdapter.pruefeModell(e);
                return e;
            }
        }
        throw new WallboxFehler("modbus_modell_unbekannt", 400);
    }

    /** Speichern: LAN-Adressen müssen privat sein (Vertrag §2, Tuya nie öffentlich). */
    private void pruefeTuya(JSONObject t) throws WallboxFehler, JSONException {
        if ("cloud".equals(t.optString("zugang"))) return;
        JSONArray g = t.getJSONArray("geraete");
        for (int i = 0; i < g.length(); i++) WallboxAdresse.pruefeLanHost(g.getJSONObject(i).optString("ip"), aufloeser);
    }

    /**
     * Tuya (Vertrag §4b): Cloud nur mit Einwilligung (Regel 2), sonst 409 {@code einwilligung_fehlt} ohne jede Anfrage.
     * LAN: IP privat, aufgelöst und festgehalten; local_key aus dem verschlüsselten Speicher.
     */
    private WallboxQuelle tuyaQuelle(JSONObject t) throws WallboxFehler, JSONException {
        boolean cloud = "cloud".equals(t.optString("zugang"));
        TuyaCloud tc = null;
        if (cloud) {
            JSONObject c = t.getJSONObject("cloud");
            if (!c.optBoolean("einwilligung", false)) throw new WallboxFehler("einwilligung_fehlt", 409);
            tc = new TuyaCloud(c.optString("region"), c.optString("clientId"), geheim.ent(c.optString("secret", "")), http, uhr);
        }
        JSONArray g = t.getJSONArray("geraete");
        JSONObject tabelle = null;
        try {
            tabelle = tuyaModelle.get();
        } catch (Throwable ignored) { }
        TuyaQuelle.Geraet[] arr = new TuyaQuelle.Geraet[g.length()];
        for (int i = 0; i < g.length(); i++) {
            JSONObject d = g.getJSONObject(i);
            JSONObject zu = d.optJSONObject("zuordnung");
            if (zu == null) zu = TuyaQuelle.modellZuordnung(tabelle, d.isNull("modell") ? null : d.optString("modell"));
            TuyaQuelle.Zugriff z;
            if (cloud) {
                z = TuyaQuelle.cloud(tc, d.getString("geraeteId"));
            } else {
                String ip = WallboxAdresse.pruefeLanHost(d.optString("ip"), aufloeser);
                String key = geheim.ent(d.optString("localKey", ""));
                if (key == null || key.length() != 16) throw new WallboxFehler("tuya_schluessel_fehlt", 502);
                z = TuyaQuelle.lan(new TuyaLan(ip, d.getString("geraeteId"),
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8), d.optString("version", "3.3"), tuyaVerbinder));
            }
            arr[i] = new TuyaQuelle.Geraet(d.getString("schluessel"), d.optString("titel", ""), z, zu);
        }
        return new TuyaQuelle(arr, schlaf, tabelle);
    }

    /** Einrichtungshilfe HA (nur lesend): Kandidaten je Rolle aus allen Entitäten; Einstellungen ungespeichert wie bei /test. */
    public synchronized Ergebnis haVorschlag(JSONObject neu) throws JSONException {
        try {
            JSONObject k = WallboxKonfig.uebernimm(konfig(), neu == null ? new JSONObject() : neu, geheim, uhr.getAsLong());
            if (!"ha".equals(WallboxKonfig.quelle(k))) throw new WallboxFehler("einstellung_ungueltig");
            JSONObject s = k.getJSONObject("ha");
            String basis = WallboxAdresse.pruefe(s.optString("url"), s.optBoolean("oeffentlichErlaubt"), aufloeser);
            String token = geheim.ent(s.optString("token", ""));
            if (token == null || token.isEmpty()) throw new WallboxFehler("token_fehlt");
            return new Ergebnis(200, new HaQuelle(basis, token, new JSONArray(), http, schlaf).vorschlag());
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    /** null = Steuern erlaubt; sonst Grund (Vertrag §5.5, fail-closed). */
    String sperre() {
        String z;
        try {
            z = fahrzustand.jetzt();
        } catch (Throwable t) {
            z = "unbekannt";
        }
        if ("steht".equals(z)) return null;
        return "faehrt".equals(z) ? "fahrt" : "fahrzustand_unbekannt";
    }

    // ------------------------------------------------------------------ Freigabe + Steuern

    public synchronized Ergebnis freigabe(JSONObject b, String herkunft) throws JSONException {
        JSONObject k = konfig();
        String id = b == null ? "" : b.optString("ladepunkt", "");
        boolean an = b != null && b.optBoolean("an", false);
        try {
            if (!an) {
                // Zurücknehmen geht immer sofort (auch während der Fahrt, auch ohne erreichbare Quelle).
                WallboxKonfig.setzeFreigabe(k, id, false);
                speichere(k);
                protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, "freigabe", false, "ok", herkunft);
                return new Ergebnis(200, new JSONObject().put("ok", true).put("ladepunkt", id).put("steuernFrei", false));
            }
            String gesperrt = sperre();
            if (gesperrt != null) throw new WallboxFehler(gesperrt, 423);
            WallboxQuelle quelle = quelle(k);
            JSONObject lp = EvccQuelle.finde(quelle.ladepunkte(), id);
            if (!"wallbox".equals(lp.optString("art"))) throw new WallboxFehler("nicht_steuerbar", 400);
            if ("unbekannt".equals(lp.optString("zuordnung"))) throw new WallboxFehler("tuya_zuordnung_fehlt", 400);
            if (lp.has("fehler")) throw new WallboxFehler(lp.getString("fehler"), 409);
            Ergebnis frage = bestaetigung(b, "freigabe|" + id + "|true", lp, "freigabe", true);
            if (frage != null) return frage;
            WallboxKonfig.setzeFreigabe(k, id, true);
            speichere(k);
            protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, "freigabe", true, "ok", herkunft);
            return new Ergebnis(200, new JSONObject().put("ok", true).put("ladepunkt", id).put("steuernFrei", true));
        } catch (WallboxFehler f) {
            if (an) protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, "freigabe", true, "fehler:" + f.code, herkunft);
            return fehler(f);
        }
    }

    public synchronized Ergebnis steuern(JSONObject b, String herkunft) throws JSONException {
        JSONObject k = konfig();
        String id = b == null ? "" : b.optString("ladepunkt", "");
        String aktion = b == null ? "" : b.optString("aktion", "");
        Object wert = b == null || b.isNull("wert") ? null : b.opt("wert");
        try {
            if (!aktion.matches("modus|ziel|strom|plan|plan_loeschen")) throw new WallboxFehler("aktion_ungueltig", 400);
            String gesperrt = sperre();
            if (gesperrt != null) throw new WallboxFehler(gesperrt, 423);
            WallboxQuelle quelle = quelle(k);
            if (!WallboxKonfig.freigegeben(k, id)) throw new WallboxFehler("nicht_freigegeben", 403);
            JSONObject lp = EvccQuelle.finde(quelle.ladepunkte(), id);
            if (!"wallbox".equals(lp.optString("art"))) throw new WallboxFehler("nicht_steuerbar", 400);
            if ("unbekannt".equals(lp.optString("zuordnung"))) throw new WallboxFehler("tuya_zuordnung_fehlt", 400);
            pruefeWert(aktion, wert, lp);
            Ergebnis frage = bestaetigung(b, aktion + "|" + id + "|" + wertText(wert), lp, aktion, wert);
            if (frage != null) return frage;
            JSONObject r = quelle.steuern(id, aktion, wert);
            boolean unbekannt = r.isNull("uebernommen");
            boolean ok = r.optBoolean("uebernommen", false);
            protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, aktion, wert,
                    unbekannt ? "gesendet_ungeprueft" : ok ? "uebernommen" : "abweichend", herkunft);
            JSONObject nach = ohneIntern(r.getJSONObject("ladepunkt"));
            nach.put("steuernFrei", true);
            return new Ergebnis(200, new JSONObject().put("ok", ok || unbekannt)
                    .put("uebernommen", unbekannt ? JSONObject.NULL : (Object) ok).put("ladepunkt", nach));
        } catch (WallboxFehler f) {
            if (!"bestaetigung_noetig".equals(f.code)) {
                protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, aktion, wert, "fehler:" + f.code, herkunft);
            }
            return fehler(f);
        }
    }

    /** Plausibilität vor der Bestätigungsfrage, damit der Dialog nie Unsinn fragt. */
    void pruefeWert(String aktion, Object wert, JSONObject lp) throws WallboxFehler, JSONException {
        JSONObject f = lp.getJSONObject("faehigkeiten");
        switch (aktion) {
            case "modus": {
                JSONArray m = f.optJSONArray("modus");
                boolean ok = false;
                for (int i = 0; m != null && i < m.length(); i++) ok |= m.optString(i).equals(String.valueOf(wert));
                if (!ok) throw new WallboxFehler("wert_ungueltig", 400);
                break;
            }
            case "ziel":
                if (!f.optBoolean("ziel")) throw new WallboxFehler("nicht_unterstuetzt", 400);
                EvccQuelle.ganzzahl(wert, lp.optInt("zielMin", 20), lp.optInt("zielMax", 100));
                break;
            case "strom": {
                JSONObject s = f.optJSONObject("strom");
                if (s == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
                EvccQuelle.ganzzahl(wert, s.optInt("min", 6), s.optInt("max", 16));
                break;
            }
            case "plan": {
                if (f.isNull("plan")) throw new WallboxFehler("nicht_unterstuetzt", 400);
                if (!(wert instanceof JSONObject)) throw new WallboxFehler("wert_ungueltig", 400);
                JSONObject p = (JSONObject) wert;
                if ("ziel_bis".equals(f.optString("plan"))) {
                    EvccQuelle.ganzzahl(p.opt("soc"), 20, 100);
                    new EvccQuelle("", null, http, schlaf, uhr).zeitpunkt(p.optString("zeit", ""));
                } else if (!HaQuelle.UHRZEIT.matcher(p.optString("zeit", "")).matches()) {
                    throw new WallboxFehler("wert_ungueltig", 400);
                }
                break;
            }
            case "plan_loeschen":
                if (!"ziel_bis".equals(f.optString("plan"))) throw new WallboxFehler("nicht_unterstuetzt", 400);
                break;
            default:
                throw new WallboxFehler("aktion_ungueltig", 400);
        }
    }

    /** Zweistufige Bestätigung (Vertrag §5.2): null = bestätigt, sonst die 409-Frage. Kennung gilt einmal, 30 s, nur für genau diese Aktion. */
    private Ergebnis bestaetigung(JSONObject b, String schluessel, JSONObject lp, String aktion, Object wert) throws JSONException {
        long jetzt = uhr.getAsLong();
        for (Iterator<Map.Entry<String, String[]>> it = offen.entrySet().iterator(); it.hasNext(); ) {
            if (Long.parseLong(it.next().getValue()[1]) < jetzt) it.remove();
        }
        String kennung = b == null ? "" : b.optString("bestaetigung", "");
        if (!kennung.isEmpty()) {
            String[] e = offen.remove(kennung);
            if (e != null && e[0].equals(schluessel)) return null;
        }
        byte[] z = new byte[16];
        zufall.nextBytes(z);
        StringBuilder sb = new StringBuilder();
        for (byte x : z) sb.append(String.format("%02x", x));
        String neu = sb.toString();
        offen.put(neu, new String[]{schluessel, Long.toString(jetzt + BESTAETIGUNG_MS)});
        JSONObject frage = new JSONObject()
                .put("ladepunkt", lp.optString("id"))
                .put("titel", lp.optString("titel"))
                .put("aktion", aktion)
                .put("wert", wert == null ? JSONObject.NULL : wert);
        return new Ergebnis(409, new JSONObject().put("ok", false).put("grund", "bestaetigung_noetig")
                .put("bestaetigung", neu).put("gueltigMs", BESTAETIGUNG_MS).put("frage", frage));
    }

    public Ergebnis protokoll() throws JSONException {
        return new Ergebnis(200, new JSONObject().put("ok", true).put("eintraege", protokoll.lies(50)));
    }

    // ------------------------------------------------------------------ Hilfen

    private static String wertText(Object w) throws JSONException {
        if (w instanceof JSONObject) {
            JSONObject o = (JSONObject) w;
            return o.optString("zeit", "") + "|" + o.opt("soc");
        }
        return String.valueOf(w);
    }

    static JSONObject ohneIntern(JSONObject lp) throws JSONException {
        JSONObject o = new JSONObject(lp.toString());
        for (Iterator<String> it = lp.keys(); it.hasNext(); ) { String key = it.next(); if (key.startsWith("_")) o.remove(key); }
        return o;
    }

    private static Ergebnis fehler(WallboxFehler f) throws JSONException {
        return new Ergebnis(f.http, new JSONObject().put("ok", false).put("grund", f.code));
    }

    /** Telemetrie mit Zeitstempel älter als das gilt als unbekannt. */
    static final long TELEMETRIE_MAX_ALTER_MS = 30_000L;

    /**
     * Fahrzustand aus der Telemetrie, fail-closed wie AppUpdater/HaApiHandler (Kern 4, 22.10.): nur ein frischer Wert
     * mit Geschwindigkeit unter 1 km/h UND Gang P heißt „steht“. Fehlt etwas, ist es unklar oder zu alt → „unbekannt“
     * (gesperrt); Geschwindigkeit über 3 km/h oder ein anderer Gang → „faehrt“.
     */
    public static String fahrzustandAus(JSONObject telemetrie, long jetzt) {
        if (telemetrie == null) return "unbekannt";
        Object ts = telemetrie.opt("timestamp");
        if (ts instanceof Number) {
            long t = ((Number) ts).longValue();
            if (t < 1_000_000_000_000L) t *= 1000;   // Sekunden → ms
            if (jetzt - t > TELEMETRIE_MAX_ALTER_MS || t - jetzt > TELEMETRIE_MAX_ALTER_MS) return "unbekannt";
        }
        Object v = telemetrie.opt("speed");
        Object g = telemetrie.opt("gear");
        boolean vBekannt = v instanceof Number && !Double.isNaN(((Number) v).doubleValue());
        boolean gBekannt = g instanceof String && ((String) g).matches("[PRNDMS]");
        if (vBekannt && ((Number) v).doubleValue() > 3) return "faehrt";
        if (gBekannt && !"P".equals(g)) return "faehrt";
        if (vBekannt && gBekannt && ((Number) v).doubleValue() < 1) return "steht";
        return "unbekannt";
    }

    /** Standard-Lieferant für Tests und den Daemon. */
    public static Fahrzustand aus(Supplier<JSONObject> telemetrie, LongSupplier uhr) {
        return () -> fahrzustandAus(telemetrie.get(), uhr.getAsLong());
    }
}
