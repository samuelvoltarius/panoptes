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

    private WallboxSuche.Netz suchNetz = WallboxSuche.ECHT;
    /** Panoptes-MQTT-Broker ({host, port, benutzer, passwort}) für die Wallbox-Brücke (Suche und Zugang). */
    private Supplier<java.util.List<JSONObject>> broker = java.util.Collections::emptyList;

    /** Ergebnis der Brücken-Suche ohne Knopfdruck, höchstens einmal je {@link #GEFUNDEN_MS}. */
    static final long GEFUNDEN_MS = 60_000L;
    private JSONArray gefunden = new JSONArray();
    private JSONArray gefundenHinweise = new JSONArray();
    private long gefundenStand;

    private JSONArray brueckenGefunden() {
        long jetzt = uhr.getAsLong();
        // Nichts gefunden → nach 15 s erneut (eine eben eingeschaltete Brücke erscheint ohne Knopfdruck bald)
        long frist = gefunden.length() > 0 ? GEFUNDEN_MS : GEFUNDEN_LEER_MS;
        if (gefundenStand == 0 || jetzt - gefundenStand >= frist) {
            gefundenStand = jetzt;
            try {
                WallboxSuche s = new WallboxSuche(suchNetz, http).mitBruecke(broker.get(), openWbMqtt);
                gefunden = s.nurBruecken();
                gefundenHinweise = s.brueckeHinweise();
            } catch (Throwable t) {
                gefunden = new JSONArray();
                try {
                    gefundenHinweise = new JSONArray().put(new JSONObject().put("broker", "").put("name", "")
                            .put("fehler", "suche_fehler"));
                } catch (JSONException ignored) { }
            }
        }
        return gefunden;
    }

    /** Nach einer erfolglosen Brücken-Suche schneller erneut versuchen. */
    static final long GEFUNDEN_LEER_MS = 15_000L;

    /** 0.1.19 Wallbox-Brücke: Broker der Panoptes-MQTT-Verbindungen. */
    public WallboxDienst mitBroker(Supplier<java.util.List<JSONObject>> broker) {
        this.broker = broker;
        return this;
    }
    private final java.util.concurrent.atomic.AtomicBoolean sucheLaeuft = new java.util.concurrent.atomic.AtomicBoolean();

    /** 0.1.19 „Wallbox finden“: Netzzugang der Suche (Tests: Attrappe). */
    public WallboxDienst mitSuche(WallboxSuche.Netz netz) {
        this.suchNetz = netz;
        return this;
    }

    /**
     * „Wallbox finden“ im WLAN (nur finden, nichts speichern, keine Befehle). Bewusst nicht synchronized – dauert einige
     * Sekunden; höchstens eine Suche gleichzeitig.
     */
    public Ergebnis suche() throws JSONException {
        if (!sucheLaeuft.compareAndSet(false, true)) return fehler(new WallboxFehler("suche_laeuft", 409));
        try {
            return new Ergebnis(200, new WallboxSuche(suchNetz, http).mitBruecke(broker.get(), openWbMqtt).suche());
        } finally {
            sucheLaeuft.set(false);
        }
    }

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
    /** 0.1.19 „Mit Home Assistant anmelden“ (OAuth). */
    private final HaAnmeldung haAnmeldung;
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
        this.haAnmeldung = new HaAnmeldung(http, uhr);
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
            if ("kette".equals(q)) {
                // 0.1.20 Rückfallkette: jeder Weg muss in seinem Abschnitt eingerichtet sein
                JSONArray w = k.getJSONObject("kette").getJSONArray("wege");
                for (int i = 0; i < w.length(); i++) wegKonfig(k, w.getJSONObject(i).getString("ladepunkt"));
                if (k.getJSONObject("tuya").getJSONArray("geraete").length() > 0) pruefeTuya(k.getJSONObject("tuya"));
            } else if ("tuya".equals(q)) {
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
            // 0.1.19: noch keine Wallbox → Brücken auf dem Panoptes-Broker von selbst anbieten („Wallbox gefunden“)
            o.put("gefunden", brueckenGefunden());
            o.put("sucheHinweise", gefundenHinweise);   // 0.1.20: warum (nicht) gefunden – je Broker im Klartext
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
        // 0.1.21: welcher Weg zum MQTT-Broker (Brücke/openWB) trug bzw. woran jeder Weg scheiterte
        JSONObject v = OpenWbAdapter.verbindung();
        if (v != null) o.put("mqttVerbindung", v);
        return o;
    }

    WallboxQuelle quelle(JSONObject k) throws WallboxFehler, JSONException {
        String q = WallboxKonfig.quelle(k);
        if (q == null) throw new WallboxFehler("nicht_eingerichtet", 409);
        if ("tuya".equals(q)) return tuyaQuelle(k.getJSONObject("tuya"));
        if ("direkt".equals(q)) return direktQuelle(k.getJSONObject("direkt"));
        if ("kette".equals(q)) {
            JSONObject kt = k.getJSONObject("kette");
            return new KetteQuelle(kt.getJSONArray("wege"), kt.optString("titel", ""), lp -> quelle(wegKonfig(k, lp)), lp -> wegArt(k, lp));
        }
        JSONObject s = k.getJSONObject(q);
        String basis = WallboxAdresse.pruefe(s.optString("url"), s.optBoolean("oeffentlichErlaubt"), aufloeser);
        if ("evcc".equals(q)) {
            return new EvccQuelle(basis, geheim.ent(s.optString("apiKey", "")), http, schlaf, uhr);
        }
        String token = haToken(s, basis);
        if (token == null || token.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        return new HaQuelle(basis, token, s.optJSONArray("ladepunkte"), http, schlaf);
    }

    /**
     * Konfiguration nur für einen Weg der Rückfallkette: Quelle = Abschnitt des Ladepunkts, darin nur dieses Gerät bzw.
     * diese Zuordnung (kein Abruf der anderen Wege). Wirft {@code kette_ungueltig}, wenn der Weg nicht eingerichtet ist.
     */
    static JSONObject wegKonfig(JSONObject k, String lp) throws WallboxFehler, JSONException {
        int p = lp.indexOf(':');
        String q = p < 0 ? "" : lp.substring(0, p), s = p < 0 ? "" : lp.substring(p + 1);
        String liste = "direkt".equals(q) || "tuya".equals(q) ? "geraete" : "ha".equals(q) ? "ladepunkte" : null;
        if (liste == null) throw new WallboxFehler("kette_ungueltig");
        JSONObject c = new JSONObject(k.toString());
        c.put("quelle", q);
        JSONArray alle = c.getJSONObject(q).optJSONArray(liste);
        JSONArray nur = new JSONArray();
        for (int i = 0; alle != null && i < alle.length(); i++) {
            if (s.equals(alle.getJSONObject(i).optString("schluessel"))) nur.put(alle.getJSONObject(i));
        }
        if (nur.length() != 1) throw new WallboxFehler("kette_ungueltig");
        c.getJSONObject(q).put(liste, nur);
        return c;
    }

    /** Anzeige des Wegs: „wlan“, „ha“, „cloud“ oder „bruecke“ (WALLBOX_FERN_KONZEPT §7). */
    static String wegArt(JSONObject k, String lp) {
        try {
            JSONObject c = wegKonfig(k, lp);
            if (lp.startsWith("ha:")) return "ha";
            if (lp.startsWith("tuya:")) return "cloud".equals(c.getJSONObject("tuya").optString("zugang")) ? "cloud" : "wlan";
            String a = c.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).optString("adapter");
            return DirektAdapter.cloud(a) ? "cloud" : "bruecke".equals(a) ? "bruecke" : "wlan";
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    /** HA-Zugang: OAuth-Anmeldung (Refresh-Token → Access-Token) vor dem alten Langzeit-Token. */
    private String haToken(JSONObject s, String basis) throws WallboxFehler, JSONException {
        String refresh = geheim.ent(s.optString("refresh", ""));
        if (refresh != null && !refresh.isEmpty()) return haAnmeldung.access(basis, refresh, s.optString("clientId", ""));
        return geheim.ent(s.optString("token", ""));
    }

    /** „Mit Home Assistant anmelden“, Schritt 1: Adresse der HA-Anmeldeseite (0.1.19). */
    public synchronized Ergebnis haAnmeldungStart(JSONObject req) throws JSONException {
        try {
            if (req == null) throw new WallboxFehler("einstellung_ungueltig");
            String url = req.optString("url", "").trim();
            if (url.length() > 300) throw new WallboxFehler("adresse_ungueltig");
            boolean oeff = req.optBoolean("oeffentlichErlaubt", false);
            String basis = WallboxAdresse.pruefe(url, oeff, aufloeser);
            String ziel = haAnmeldung.start(url, basis, req.optString("herkunft", ""), oeff);
            return new Ergebnis(200, new JSONObject().put("ok", true).put("anmeldeUrl", ziel));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    /**
     * Schritt 2 (von der angemeldeten Seite nach der Weiterleitung): Code tauschen, Refresh-Token verschlüsselt speichern,
     * Quelle = HA. Ein alter Langzeit-Token wird gelöscht; Zuordnungen bleiben nur bei gleichem Ziel.
     */
    public synchronized Ergebnis haAnmeldungRueckruf(JSONObject req) throws JSONException {
        try {
            if (req == null) throw new WallboxFehler("einstellung_ungueltig");
            if (!req.optString("fehler", "").isEmpty()) throw new WallboxFehler("anmeldung_abgebrochen", 400);
            HaAnmeldung.Offen o = haAnmeldung.pruefeState(req.optString("state", ""));
            HaAnmeldung.Token t = haAnmeldung.tausche(o, req.optString("code", ""));
            JSONObject alt = konfig();
            JSONObject altH = alt.optJSONObject("ha") == null ? new JSONObject() : alt.optJSONObject("ha");
            boolean gleich = WallboxAdresse.ziel(altH.optString("url", "")).equals(WallboxAdresse.ziel(o.url));
            JSONObject neuH = new JSONObject().put("url", o.url).put("oeffentlichErlaubt", o.oeffentlich)
                    .put("token", "").put("refresh", t.refresh).put("clientId", o.clientId)
                    .put("ladepunkte", gleich && altH.optJSONArray("ladepunkte") != null ? altH.optJSONArray("ladepunkte") : new JSONArray());
            JSONObject k = WallboxKonfig.uebernimm(alt, new JSONObject().put("quelle", "ha").put("ha", neuH), geheim, uhr.getAsLong());
            speichere(k);
            return new Ergebnis(200, new JSONObject().put("ok", true).put("einstellungen", WallboxKonfig.oeffentlich(k)));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    // ------------------------------------------------------------------ 0.1.20 Tuya D′ und Token per Handy

    /**
     * Tuya D′ (WALLBOX_FERN_KONZEPT §4): {@code {schluessel}} – holt den local_key des Geräts einmal über das eigene
     * IoT-Projekt (Cloud-Zugang mit Einwilligung muss eingerichtet sein), speichert ihn verschlüsselt und stellt auf
     * „lan“ um, wenn die IP im Heimnetz bekannt ist. Der Schlüssel erscheint in keiner Antwort.
     */
    public synchronized Ergebnis tuyaSchluesselHolen(JSONObject req) throws JSONException {
        try {
            String s = req == null ? "" : req.optString("schluessel", "");
            JSONObject alt = konfig();
            JSONObject t = alt.getJSONObject("tuya");
            JSONObject c = t.getJSONObject("cloud");
            if (!c.optBoolean("einwilligung", false)) throw new WallboxFehler("einwilligung_fehlt", 409);
            if (c.optString("clientId", "").isEmpty() || geheim.ent(c.optString("secret", "")).isEmpty()) {
                throw new WallboxFehler("tuya_cloud_fehlt", 409);
            }
            JSONArray g = t.getJSONArray("geraete");
            JSONObject neuT = new JSONObject(t.toString());
            neuT.getJSONObject("cloud").remove("secret");   // behalten
            JSONArray ng = new JSONArray();
            JSONObject ziel = null;
            for (int i = 0; i < g.length(); i++) {
                JSONObject d = new JSONObject(g.getJSONObject(i).toString());
                d.remove("localKey");   // behalten
                if (s.equals(d.optString("schluessel"))) ziel = d;
                ng.put(d);
            }
            if (ziel == null) throw new WallboxFehler("ladepunkt_unbekannt", 404);
            TuyaCloud tc = new TuyaCloud(c.optString("region"), c.optString("clientId"), geheim.ent(c.optString("secret", "")), http, uhr);
            ziel.put("localKey", tc.lokalerSchluessel(ziel.getString("geraeteId")));
            boolean lan = !ziel.optString("ip", "").isEmpty();
            if (lan) neuT.put("zugang", "lan");
            neuT.put("geraete", ng);
            JSONObject k = WallboxKonfig.uebernimm(alt, new JSONObject().put("tuya", neuT), geheim, uhr.getAsLong());
            if (lan && "tuya".equals(WallboxKonfig.quelle(k))) pruefeTuya(k.getJSONObject("tuya"));
            speichere(k);
            protokoll.schreibe(uhr.getAsLong(), "tuya", "tuya:" + s, "schluessel_geholt", null, "ok", "panoptes");
            return new Ergebnis(200, new JSONObject().put("ok", true).put("lokal", lan)
                    .put("einstellungen", WallboxKonfig.oeffentlich(k)));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    static final long EINGABE_MS = 10 * 60_000L;
    static final int EINGABE_FEHLER_MAX = 20;
    /** Kennung → {Geräte-Schlüssel, Ablauf, Zustand}. Nur im Speicher. */
    private final Map<String, Object[]> eingaben = new HashMap<>();
    private int eingabeFehler;
    private long eingabeFehlerSeit;

    /**
     * go-e Cloud „Token per Handy“ (WALLBOX_FERN_KONZEPT §6): {@code {schluessel}} eines gespeicherten go-e-Cloud-Geräts →
     * Einmal-Kennung (128 Bit, 10 min, einmal gültig). Das Handy öffnet {@code /shared/wallbox-eingabe.html#k=…} und schickt
     * den Token an {@code POST /auth/wallbox-eingabe} – nie über unsere Server, kein Abtippen am Display.
     */
    public synchronized Ergebnis eingabeStart(JSONObject req) throws JSONException {
        try {
            String s = req == null ? "" : req.optString("schluessel", "");
            if (direktGeraet(konfig(), s, "goecloud") == null) throw new WallboxFehler("ladepunkt_unbekannt", 404);
            long jetzt = uhr.getAsLong();
            eingaben.entrySet().removeIf(e -> (Long) e.getValue()[1] < jetzt);
            if (eingaben.size() >= 3) throw new WallboxFehler("zu_viele", 429);
            byte[] z = new byte[16];
            zufall.nextBytes(z);
            String kennung = TuyaCloud.hex(z);
            eingaben.put(kennung, new Object[]{s, jetzt + EINGABE_MS, "offen"});
            return new Ergebnis(200, new JSONObject().put("ok", true).put("kennung", kennung).put("gueltigBis", jetzt + EINGABE_MS));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    /** Status für das Display: offen | empfangen | abgelaufen. */
    public synchronized Ergebnis eingabeStatus(String kennung) throws JSONException {
        Object[] e = eingaben.get(kennung == null ? "" : kennung);
        String z = e == null || (Long) e[1] < uhr.getAsLong() && !"empfangen".equals(e[2]) ? "abgelaufen" : (String) e[2];
        return new Ergebnis(200, new JSONObject().put("ok", true).put("zustand", z));
    }

    /** Vom Handy, ohne Login: {@code {kennung, token}}. Ratenbegrenzt; falsche Kennungen zählen. */
    public synchronized Ergebnis eingabe(JSONObject req) throws JSONException {
        long jetzt = uhr.getAsLong();
        if (jetzt - eingabeFehlerSeit > EINGABE_MS) {
            eingabeFehler = 0;
            eingabeFehlerSeit = jetzt;
        }
        try {
            if (eingabeFehler >= EINGABE_FEHLER_MAX) throw new WallboxFehler("zu_viele", 429);
            String kennung = req == null ? "" : req.optString("kennung", "");
            Object[] e = eingaben.get(kennung);
            if (e == null || (Long) e[1] < jetzt || !"offen".equals(e[2])) {
                eingabeFehler++;
                throw new WallboxFehler("abgelaufen", 410);
            }
            String token = req.optString("token", "").trim();
            if (!token.matches("[A-Za-z0-9._~+/=-]{8,256}")) throw new WallboxFehler("token_ungueltig", 400);
            JSONObject alt = konfig();
            JSONObject d = alt.getJSONObject("direkt");
            JSONObject neuD = new JSONObject(d.toString());
            JSONArray g = neuD.getJSONArray("geraete");
            boolean gefunden = false;
            for (int i = 0; i < g.length(); i++) {
                JSONObject x = g.getJSONObject(i);
                if (x.optString("schluessel").equals(e[0]) && "goecloud".equals(x.optString("adapter"))) {
                    x.put("passwort", token);
                    gefunden = true;
                } else {
                    x.remove("passwort");   // behalten
                }
            }
            if (!gefunden) throw new WallboxFehler("ladepunkt_unbekannt", 404);
            JSONObject k = WallboxKonfig.uebernimm(alt, new JSONObject().put("direkt", neuD), geheim, jetzt);
            speichere(k);
            e[2] = "empfangen";
            protokoll.schreibe(jetzt, "direkt", "direkt:" + e[0], "token_per_handy", null, "ok", "handy");
            return new Ergebnis(200, new JSONObject().put("ok", true));
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    private static JSONObject direktGeraet(JSONObject k, String schluessel, String adapter) throws JSONException {
        JSONArray g = k.getJSONObject("direkt").getJSONArray("geraete");
        for (int i = 0; i < g.length(); i++) {
            JSONObject x = g.getJSONObject(i);
            if (x.optString("schluessel").equals(schluessel) && adapter.equals(x.optString("adapter"))) return x;
        }
        return null;
    }

    /** Direkte Adapter (Stufe 2): nur private Adressen; je Gerät ein Adapter aus {@link DirektAdapter#KENNUNGEN}. */
    private WallboxQuelle direktQuelle(JSONObject d) throws WallboxFehler, JSONException {
        JSONArray g = d.getJSONArray("geraete");
        DirektQuelle.Geraet[] arr = new DirektQuelle.Geraet[g.length()];
        for (int i = 0; i < g.length(); i++) {
            JSONObject e = g.getJSONObject(i);
            if (DirektAdapter.cloud(e.getString("adapter"))) {
                // Regel 2: ohne Einwilligung wird der Cloud-Adapter gar nicht gebaut → keine einzige Anfrage an den Hersteller
                String ad = e.getString("adapter");
                DirektAdapter c = !e.optBoolean("einwilligung", false) ? new DirektQuelle.Gesperrt("einwilligung_fehlt")
                        // 0.1.20: go-e Cloud (Seriennummer + Token im Passwortfeld), Wallbox Pulsar (Konto)
                        : "goecloud".equals(ad) ? GoeAdapter.cloud(e.optString("geraeteId"), geheim.ent(e.optString("passwort", "")), http)
                        : "pulsar".equals(ad) ? new PulsarAdapter(e.optString("geraeteId"), e.optString("benutzer", ""),
                                geheim.ent(e.optString("passwort", "")), http, uhr)
                        : new CloudAdapter(ad, e.optString("geraeteId"), e.optString("benutzer", ""),
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
                case "bruecke": {
                    // Ohne eigenen Benutzer: Zugang der Panoptes-MQTT-Verbindung zum selben Broker (kein zweites Geheimnis)
                    String b = e.optString("benutzer", "");
                    String p = geheim.ent(e.optString("passwort", ""));
                    if (b.isEmpty()) {
                        for (JSONObject z : broker.get()) {
                            if (basis.equalsIgnoreCase(z.optString("host"))) {
                                b = z.optString("benutzer", "");
                                p = z.optString("passwort", "");
                                break;
                            }
                        }
                    }
                    a = new BrueckeAdapter(basis, e.isNull("port") || e.optInt("port") == 0 ? OpenWbAdapter.PORT : e.optInt("port"),
                            e.optString("geraeteId"), b, p, openWbMqtt, uhr);
                    break;
                }
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
            String token = haToken(s, basis);
            if (token == null || token.isEmpty()) throw new WallboxFehler("token_fehlt");
            return new Ergebnis(200, new HaQuelle(basis, token, new JSONArray(), http, schlaf).vorschlag());
        } catch (WallboxFehler f) {
            return fehler(f);
        }
    }

    /** Für den Ladeplan: null = steht (in P), sonst Grund der Fahrsperre. */
    public String fahrsperre() {
        return sperre();
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
            if (ok || unbekannt) at.greenchili.surfmate.gewohnheiten.BefehlsHaken.wallbox(aktion, wert);   // 0.1.22 Gewohnheiten
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

    /** Herkunft der Befehle des Ladeplans im Protokoll. */
    public static final String HERKUNFT_LADEPLAN = "ladeplan";

    /**
     * 0.1.20 Ladeplan (LADEPLAN_KONZEPT §5.2): eng begrenzter Steuerweg ohne Zwei-Schritt-Bestätigung – nur
     * {@code modus aus/sofort} und {@code strom}, nur für einen freigegebenen Ladepunkt, nicht während der Fahrt, jeder
     * Befehl im Protokoll mit Herkunft „ladeplan“. Die einmalige Plan-Freigabe prüft der Aufrufer (LadeplanDienst).
     * Erfolg nur mit Echo ({@code uebernommen == true}); „gesendet, ungeprüft“ zählt für den Plan als Fehlschlag.
     */
    public synchronized Ergebnis steuernPlan(String id, String aktion, Object wert) throws JSONException {
        return steuernOhneFrage(id, aktion, wert, HERKUNFT_LADEPLAN);
    }

    /** Herkunft der Befehle von Automationen im Protokoll (0.1.23 Gewohnheiten E5). */
    public static final String HERKUNFT_AUTOMATION = "automation";

    /**
     * 0.1.23 Gewohnheiten E5: derselbe eng begrenzte Weg wie der Ladeplan, für Automationen ({@code POST
     * /api/wallbox/automation}) – nur {@code modus aus/sofort} und {@code strom}, nur für einen freigegebenen Ladepunkt,
     * nicht während der Fahrt, jeder Befehl im Protokoll mit Herkunft „automation“. Ohne {@code ladepunkt} gilt der
     * einzige freigegebene; bei mehreren muss er genannt sein.
     */
    public synchronized Ergebnis steuernAutomatik(JSONObject b) throws JSONException {
        String id = b == null ? "" : b.optString("ladepunkt", "");
        String aktion = b == null ? "" : b.optString("aktion", "");
        Object wert = b == null || b.isNull("wert") ? null : b.opt("wert");
        if (id.isEmpty()) {
            JSONObject k = konfig();
            try {
                JSONArray alle = quelle(k).ladepunkte();
                for (int i = 0; i < alle.length(); i++) {
                    JSONObject lp = alle.optJSONObject(i);
                    if (lp == null || !"wallbox".equals(lp.optString("art")) || !WallboxKonfig.freigegeben(k, lp.optString("id"))) continue;
                    if (!id.isEmpty()) return fehler(new WallboxFehler("ladepunkt_mehrdeutig", 400));
                    id = lp.optString("id");
                }
            } catch (WallboxFehler f) {
                return fehler(f);
            }
            if (id.isEmpty()) return fehler(new WallboxFehler("nicht_freigegeben", 403));
        }
        return steuernOhneFrage(id, aktion, wert, HERKUNFT_AUTOMATION);
    }

    private Ergebnis steuernOhneFrage(String id, String aktion, Object wert, String herkunft) throws JSONException {
        JSONObject k = konfig();
        try {
            boolean modus = "modus".equals(aktion)
                    && (WallboxQuelle.MODUS_AUS.equals(wert) || WallboxQuelle.MODUS_SOFORT.equals(wert));
            if (!modus && !"strom".equals(aktion)) throw new WallboxFehler("aktion_ungueltig", 400);
            String gesperrt = sperre();
            if (gesperrt != null) throw new WallboxFehler(gesperrt, 423);
            if (!WallboxKonfig.freigegeben(k, id)) throw new WallboxFehler("nicht_freigegeben", 403);
            WallboxQuelle quelle = quelle(k);
            JSONObject lp = EvccQuelle.finde(quelle.ladepunkte(), id);
            if (!"wallbox".equals(lp.optString("art"))) throw new WallboxFehler("nicht_steuerbar", 400);
            if (lp.has("fehler")) throw new WallboxFehler(lp.getString("fehler"), 409);
            if ("bruecke_gesperrt".equals(lp.optString("hinweis"))) throw new WallboxFehler("bruecke_gesperrt", 409);
            pruefeWert(aktion, wert, lp);
            JSONObject r = quelle.steuern(id, aktion, wert);
            boolean ok = !r.isNull("uebernommen") && r.optBoolean("uebernommen", false);
            protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, aktion, wert,
                    r.isNull("uebernommen") ? "gesendet_ungeprueft" : ok ? "uebernommen" : "abweichend", herkunft);
            JSONObject nach = ohneIntern(r.getJSONObject("ladepunkt"));
            return new Ergebnis(200, new JSONObject().put("ok", ok).put("uebernommen", ok).put("ladepunkt", nach));
        } catch (WallboxFehler f) {
            protokoll.schreibe(uhr.getAsLong(), WallboxKonfig.quelle(k), id, aktion, wert, "fehler:" + f.code, herkunft);
            return fehler(f);
        }
    }

    /**
     * „Hand geht vor Plan“: Zeitpunkt des letzten Steuerbefehls für diesen Ladepunkt, der NICHT vom Ladeplan kam und
     * angekommen ist (aus dem Protokoll, damit auch Befehle aus anderen Prozessen zählen); 0 = keiner.
     */
    public long letzteHandSteuerung(String id) throws JSONException {
        JSONArray e = protokoll.lies(50);
        for (int i = 0; i < e.length(); i++) {
            JSONObject z = e.getJSONObject(i);
            if (!id.equals(z.optString("ladepunkt")) || HERKUNFT_LADEPLAN.equals(z.optString("herkunft"))) continue;
            String a = z.optString("aktion");
            String erg = z.optString("ergebnis");
            if (!"modus".equals(a) && !"strom".equals(a)) continue;
            if (erg.equals("uebernommen") || erg.equals("gesendet_ungeprueft")) return z.optLong("zeit", 0);
        }
        return 0;
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
