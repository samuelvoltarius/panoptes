package at.greenchili.surfmate.uebernahme;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.Einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Vorschau und Übernahme von Overdrive-Einstellungen in die Panoptes-Config (LADEN_VERTRAG.md Teil B).
 * Eigene, Clean-Room-Abbildung: eine Tabelle {@link Regel} „Quelle (Datei + Schlüssel) → Panoptes-Ziel“.
 *
 * <p>Schutz durch Bau: Ein Ziel, das im Panoptes-Schema eine Bestätigung braucht, als Gefahr markiert ist,
 * zur Gruppe Datenschutz gehört, nur lesend oder für gekoppelte Geräte gesperrt ist (Freigaben), wird nie übernommen – es erscheint als „manuell“
 * (Regel 2, Regel A+). Zugangsdaten werden NIE übernommen: die Vorschau sagt nur „vorhanden – bitte neu eingeben“
 * (Aktion {@code neu_eingeben}). Tolerant gegen andere Overdrive-Versionen: fehlende/unbekannte Schlüssel und
 * falsche Typen fallen still weg; die Version steht in der Vorschau.
 */
public final class OverdriveUebernahme {

    private OverdriveUebernahme() {}

    public enum Klasse { EINSTELLUNG, GEHEIM, MANUELL }

    /** Wandelt den Overdrive-Wert in den Panoptes-Wert; null = unbrauchbar (Posten entfällt). */
    public interface Wandler {
        Object wandle(Object roh);
    }

    /** Prüft einen übernehmbaren Wert im Zusammenhang der Quelldatei; null = in Ordnung, sonst Hinweis-Schlüssel. */
    public interface Plausibel {
        String pruefe(Object neu, String dateiText);
    }

    public static final class Regel {
        public final String id, bereich, datei, pfad, ziel;
        public final Klasse klasse;
        public final Wandler wandler;
        /** Unplausibel → nicht übernehmen, nur Hinweis „bitte prüfen“ (aktion manuell). */
        public Plausibel plausibel;
        public Regel(String id, String bereich, String datei, String pfad, String ziel, Klasse klasse, Wandler wandler) {
            this.id = id; this.bereich = bereich; this.datei = datei; this.pfad = pfad; this.ziel = ziel;
            this.klasse = klasse; this.wandler = wandler == null ? r -> r : wandler;
        }
        Regel mitPruefung(Plausibel p) {
            this.plausibel = p;
            return this;
        }
        public String titelKey() { return "uebernahme.posten." + (id.startsWith("mqtt:") ? "mqtt" : id); }
    }

    /** Texte in der Anfragesprache. */
    public interface Texte {
        String text(String key, String rueckfall);
    }

    /** Schreibt Panoptes-Werte. true bei Erfolg. Zugangsdaten gibt es hier bewusst nicht. */
    public interface Schreiber {
        boolean werte(String abschnitt, Map<String, Object> werte);
        /** Neue MQTT-Verbindung (immer aus, ohne Steuerrecht, ohne Zugangsdaten). */
        default boolean mqtt(JSONObject verbindung) { return false; }
    }

    /** Overdrive-Haupt-Config (OVERDRIVE_BESTAND.md: /data/local/tmp, 38 Abschnitte, gleiche Abschnittsnamen). */
    public static final String CONFIG = "overdrive_config.json";
    public static final String ABRP = "abrp_config.properties";
    public static final String MQTT = "mqtt_connections.json";
    public static final String VERSION = "overdrive_version";
    static final int MAX_MQTT = 5;

    /**
     * Die Abbildungstabelle (eigene Auswahl nach OVERDRIVE_BESTAND.md vom 22.10.2026): nur Wächter-Einstellungen,
     * die im Bestand belegt sind und die Panoptes selbst auswertet, mit eigenen Grenzen. Bewusst NICHT:
     * Orts- und Verlaufsdaten (geocache, gps_cache, roadsense_coverage, H2-Datenbanken, Ereignis-Geo),
     * Push-Schlüssel, Laufzeit-Merker. Telegram/ABRP: Zugangsdaten nur „neu eingeben“, ABRP an/aus nur „manuell“
     * (Daten an einen fremden Dienst). MQTT-Verbindungen: eigene Posten (siehe {@link #mqttPosten}).
     */
    public static final List<Regel> REGELN = Collections.unmodifiableList(java.util.Arrays.asList(
            einst("vorlauf", "surveillance.preRecordSeconds", ganz(0, 60)),
            einst("nachlauf", "surveillance.postRecordSeconds", ganz(0, 120)),
            einst("person", "surveillance.detectPerson", jaNein()),
            einst("fahrzeug", "surveillance.detectCar", jaNein()),
            einst("fahrrad", "surveillance.detectBike", jaNein()),
            einst("tier", "surveillance.detectAnimal", jaNein()),
            einst("kiSicherheit", "surveillance.aiConfidence", bruch(0.05, 0.95)),
            einst("mindestgroesse", "surveillance.minObjectSize", bruch(0.01, 0.5)),
            einst("empfindlichkeit", "surveillance.sensitivityLevel", ganz(1, 5)),
            einst("kameraVorne", "surveillance.cameraFront", jaNein()),
            einst("kameraRechts", "surveillance.cameraRight", jaNein()),
            einst("kameraHinten", "surveillance.cameraRear", jaNein()),
            einst("kameraLinks", "surveillance.cameraLeft", jaNein()),
            einst("verweilen", "surveillance.loiteringTimeSeconds", ganz(0, 600)),
            einst("annaeherung", "surveillance.approachTriggerSeconds", ganz(0, 600)),
            new Regel("anzeigeAbschreckung", "waechter", CONFIG, "surveillance.screenDeterrentEnabled",
                    "deterrent.display", Klasse.EINSTELLUNG, jaNein()),
            einst("anzeigeDauer", "surveillance.screenDeterrentDurationSeconds", ganz(1, 120)),
            einst("anzeigeText", "surveillance.screenDeterrentMessage", text(200)),
            // Wirkung am Auto (Licht/Hupe) bzw. Pflichtwahl der Einrichtung: nur Hinweis, nie übernehmen
            new Regel("abschreckungAktion", "waechter", CONFIG, "surveillance.deterrentAction",
                    "deterrent.light", Klasse.MANUELL, text(40)),
            new Regel("waechterAn", "waechter", CONFIG, "surveillance.surveillanceEnabled",
                    "surveillance.surveillanceEnabled", Klasse.MANUELL, jaNein()),
            // Zugangsdaten: nie übernehmen, nur zum Neueingeben anbieten (Koordination 22.10.)
            new Regel("telegramBot", "telegram", CONFIG, "telegram.botToken", "telegram.botToken", Klasse.GEHEIM, vorhanden()),
            new Regel("abrpSchluessel", "abrp", ABRP, "user_token", "abrp.user_token", Klasse.GEHEIM, vorhanden()),
            new Regel("abrpAn", "abrp", ABRP, "enabled", "abrp.enabled", Klasse.MANUELL, jaNein()),
            // Strompreise (TRIPKOSTEN_BEFUND: 0,01 €/kWh kam aus Overdrive): nur übernehmen, was PreisPlausibel für die
            // Währung als plausibel belegt; sonst „in Overdrive war: …, bitte prüfen“.
            new Regel("waehrung", "laden", CONFIG, "tripAnalytics.currency", "tripAnalytics.currency", Klasse.EINSTELLUNG, text(8)),
            new Regel("strompreis", "laden", CONFIG, "tripAnalytics.electricityRate", "tripAnalytics.electricityRate",
                    Klasse.EINSTELLUNG, bruch(0, 100000)).mitPruefung(OverdriveUebernahme::preisPruefen),
            new Regel("strompreisDc", "laden", CONFIG, "chargingAnalytics.dcRate", "chargingAnalytics.dcRate",
                    Klasse.EINSTELLUNG, bruch(0, 100000)).mitPruefung(OverdriveUebernahme::preisPruefen)));

    /**
     * Preis je kWh gegen das Band von {@link at.greenchili.surfmate.charging.PreisPlausibel} (eine Prüfstelle, keine
     * zweite Regel). Strenger als die Anzeige: Ist die Währung unbekannt bzw. leer (nicht prüfbar), wird ebenfalls nicht
     * übernommen.
     */
    static String preisPruefen(Object neu, String configText) {
        if (!(neu instanceof Number)) return "uebernahme.preisPruefen";
        // 0 = „gratis“ gilt nur ausdrücklich bestätigt (Kern 4, tripAnalytics.rateGratis) – aus Overdrive nie still übernehmen
        if (((Number) neu).doubleValue() <= 0) return "uebernahme.preisPruefen";
        Object w = configText == null ? null : wert(configText, CONFIG, "tripAnalytics.currency");
        String waehrung = w instanceof String ? (String) w : "";
        if (!at.greenchili.surfmate.charging.PreisPlausibel.pruefbar(waehrung)) return "uebernahme.preisPruefen";
        return at.greenchili.surfmate.charging.PreisPlausibel.pruefe(((Number) neu).doubleValue(), waehrung, false) == null
                ? null : "uebernahme.preisPruefen";
    }

    private static Regel einst(String id, String schluessel, Wandler w) {
        return new Regel(id, "waechter", CONFIG, schluessel, schluessel, Klasse.EINSTELLUNG, w);
    }

    static Wandler ganz(final int min, final int max) {
        return r -> {
            if (!(r instanceof Number)) return null;
            double d = ((Number) r).doubleValue();
            return d == Math.rint(d) && d >= min && d <= max ? (Object) (int) d : null;
        };
    }

    static Wandler bruch(final double min, final double max) {
        return r -> {
            if (!(r instanceof Number)) return null;
            double d = ((Number) r).doubleValue();
            return d >= min && d <= max ? (Object) (Math.round(d * 1000.0) / 1000.0) : null;
        };
    }

    /** Boolesch, auch als Text aus .properties („true“/„false“). */
    static Wandler jaNein() {
        return r -> {
            if (r instanceof Boolean) return r;
            if ("true".equals(r)) return Boolean.TRUE;
            if ("false".equals(r)) return Boolean.FALSE;
            return null;
        };
    }

    /** Nur „ist vorhanden“ – der Wert selbst wird nie weitergegeben. */
    static Wandler vorhanden() {
        return r -> r instanceof String && !((String) r).trim().isEmpty() ? Boolean.TRUE : null;
    }

    static Wandler text(final int max) {
        return r -> {
            if (!(r instanceof String)) return null;
            String t = ((String) r).replaceAll("\\p{Cntrl}+", " ").trim();
            return t.length() > max ? null : t;
        };
    }

    /** Alle Dateinamen, die die Tabelle liest. */
    public static Set<String> dateinamen(List<Regel> regeln) {
        Set<String> s = new LinkedHashSet<>();
        for (Regel r : regeln) s.add(r.datei);
        s.add(MQTT);
        s.add(VERSION);
        return s;
    }

    // ---------------------------------------------------------------- Vorschau

    public static JSONObject vorschau(OverdriveQuelle.Befund b, JSONObject root, List<Regel> regeln, Texte t) throws Exception {
        return vorschau(b, root, null, regeln, t);
    }

    /** @param panoptesMqtt vorhandene Panoptes-MQTT-Verbindungen (zum Erkennen von „gleich“), null = keine */
    public static JSONObject vorschau(OverdriveQuelle.Befund b, JSONObject root, JSONArray panoptesMqtt,
                                      List<Regel> regeln, Texte t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        String grund = grund(b);
        if (grund != null) {
            o.put("vorhanden", false);
            o.put("grund", grund);
            return o;
        }
        List<Posten> posten = posten(b, root, panoptesMqtt, regeln);
        JSONArray arr = new JSONArray();
        for (Posten p : posten) {
            JSONObject x = new JSONObject();
            x.put("id", p.regel.id);
            x.put("bereich", p.regel.bereich);
            x.put("titelKey", p.regel.titelKey());
            String titel = t.text(p.regel.titelKey(), p.regel.id);
            if (p.titelZusatz != null) titel = titel + ": " + p.titelZusatz;
            x.put("titel", titel);
            x.put("aktion", p.aktion);
            boolean geheim = p.klasse == Klasse.GEHEIM;
            x.put("overdrive", geheim ? t.text("uebernahme.vorhanden", "vorhanden") : anzeige(p.neu));
            x.put("panoptes", p.alt == null ? t.text("uebernahme.nichtGesetzt", "nicht gesetzt")
                    : geheim ? t.text("uebernahme.vorhanden", "vorhanden") : anzeige(p.alt));
            if (p.hinweisKey != null) x.put("hinweis", t.text(p.hinweisKey, "Bitte prüfen."));
            else if ("manuell".equals(p.aktion)) x.put("hinweis", t.text("uebernahme.manuell", "Bitte selbst wählen."));
            if ("neu_eingeben".equals(p.aktion)) x.put("hinweis", t.text("uebernahme.neuEingeben", "Bitte neu eingeben."));
            if (p.regel.id.startsWith("mqtt:")) x.put("hinweis", t.text("uebernahme.mqttAus", "Kommt ausgeschaltet."));
            arr.put(x);
        }
        o.put("vorhanden", true);
        o.put("overdriveVersion", version(b));
        o.put("quellen", new JSONArray(b.namen()));
        o.put("posten", arr);
        o.put("vorschau_id", vorschauId(posten));
        return o;
    }

    // ---------------------------------------------------------------- Übernahme

    public static JSONObject uebernehmen(OverdriveQuelle.Befund b, JSONObject root, List<Regel> regeln,
                                         String vorschauId, JSONArray ids, Schreiber s) throws Exception {
        return uebernehmen(b, root, null, regeln, vorschauId, ids, s);
    }

    public static JSONObject uebernehmen(OverdriveQuelle.Befund b, JSONObject root, JSONArray panoptesMqtt,
                                         List<Regel> regeln, String vorschauId, JSONArray ids, Schreiber s) throws Exception {
        JSONObject o = new JSONObject();
        String grund = grund(b);
        if (grund != null) {
            o.put("ok", false);
            o.put("grund", grund);
            o.put("status", 409);
            return o;
        }
        List<Feld> felder = Katalog.felder(new Katalog.Kontext());
        List<Posten> posten = posten(b, root, panoptesMqtt, regeln);
        if (vorschauId == null || !vorschauId.equals(vorschauId(posten))) {
            o.put("ok", false);
            o.put("grund", "veraltet");
            o.put("status", 409);
            return o;
        }
        Set<String> gewuenscht = new LinkedHashSet<>();
        for (int i = 0; ids != null && i < ids.length(); i++) gewuenscht.add(ids.optString(i, ""));
        JSONArray uebernommen = new JSONArray();
        JSONObject fehler = new JSONObject();
        Set<String> bekannt = new HashSet<>();
        for (Posten p : posten) {
            bekannt.add(p.regel.id);
            if (!gewuenscht.contains(p.regel.id)) continue;
            if ("manuell".equals(p.aktion) || "neu_eingeben".equals(p.aktion)) { fehler.put(p.regel.id, p.aktion); continue; }
            if ("gleich".equals(p.aktion)) { uebernommen.put(p.regel.id); continue; }
            boolean gut;
            if (p.klasse == Klasse.GEHEIM) {
                gut = false;   // nie erreichbar (neu_eingeben), doppelt abgesichert
            } else if (p.regel.id.startsWith("mqtt:")) {
                gut = s.mqtt((JSONObject) p.neu);
            } else {
                gut = true;
                for (Map.Entry<String, Map<String, Object>> w : schreibweise(p, root, felder).entrySet()) {
                    gut &= s.werte(w.getKey(), w.getValue());
                }
            }
            if (gut) uebernommen.put(p.regel.id);
            else fehler.put(p.regel.id, "speichernFehlgeschlagen");
        }
        for (String id : gewuenscht) if (!bekannt.contains(id)) fehler.put(id, "unbekannt");
        o.put("ok", fehler.length() == 0);
        o.put("uebernommen", uebernommen);
        // Wächter-Werte liest der Dienst beim Start; ob jede sofort greift, ist nicht geprüft → ehrlich empfehlen
        o.put("neustartEmpfohlen", uebernommen.length() > 0);
        o.put("fehler", fehler);
        o.put("status", 200);
        return o;
    }

    // ---------------------------------------------------------------- intern

    static final class Posten {
        Regel regel;
        Klasse klasse;
        Object neu, alt;
        String aktion;
        String rohHash;
        String titelZusatz;
        String hinweisKey;
    }

    static List<Posten> posten(OverdriveQuelle.Befund b, JSONObject root, List<Regel> regeln) {
        return posten(b, root, null, regeln);
    }

    static List<Posten> posten(OverdriveQuelle.Befund b, JSONObject root, JSONArray panoptesMqtt, List<Regel> regeln) {
        List<Feld> felder = Katalog.felder(new Katalog.Kontext());
        List<Posten> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Regel r : regeln) {
            if (!ids.add(r.id)) continue;
            String text = b.dateien.get(r.datei);
            if (text == null) continue;
            Object roh = wert(text, r.datei, r.pfad);
            if (roh == null) continue;
            Object neu;
            try {
                neu = r.wandler.wandle(roh);
            } catch (Exception e) {
                neu = null;
            }
            if (neu == null) continue;
            Posten p = new Posten();
            p.regel = r;
            p.klasse = gesperrt(r, felder) ? Klasse.MANUELL : r.klasse;
            Feld f = Katalog.finde(felder, r.ziel);
            if (p.klasse == Klasse.EINSTELLUNG && f != null) {
                try {
                    neu = Einstellungen.pruefe(f, neu);   // gleiche Prüfung wie PUT /api/einstellungen
                } catch (IllegalArgumentException ex) {
                    continue;
                }
            }
            p.neu = neu;
            if (p.klasse == Klasse.EINSTELLUNG && r.plausibel != null) {
                p.hinweisKey = r.plausibel.pruefe(neu, text);
                if (p.hinweisKey != null) p.klasse = Klasse.MANUELL;
            }
            p.alt = p.klasse == Klasse.GEHEIM ? geheimAlt(root, r.ziel) : zielWert(root, r.ziel, f);
            if (p.klasse == Klasse.MANUELL) p.aktion = "manuell";
            else if (p.klasse == Klasse.GEHEIM) p.aktion = "neu_eingeben";
            else if (p.alt == null) p.aktion = "neu";
            else if (p.klasse != Klasse.GEHEIM && String.valueOf(p.alt).equals(String.valueOf(p.neu))) p.aktion = "gleich";
            else p.aktion = "ueberschreiben";
            p.rohHash = hash((p.klasse == Klasse.GEHEIM ? "x" : String.valueOf(roh)) + "|" + String.valueOf(p.alt));
            out.add(p);
        }
        out.addAll(mqttPosten(b.dateien.get(MQTT), panoptesMqtt));
        return out;
    }

    /**
     * MQTT-Verbindungen aus Overdrive: je Verbindung ein Posten. Übernommen werden nur Adresse und Takt-Einstellungen,
     * immer ausgeschaltet, ohne Steuerrecht (Regel A) und ohne Benutzer/Passwort (bitte neu eingeben).
     * „gleich“, wenn Panoptes schon eine Verbindung mit gleicher Adresse, Port und Topic hat.
     */
    static List<Posten> mqttPosten(String text, JSONArray panoptes) {
        List<Posten> out = new ArrayList<>();
        if (text == null) return out;
        JSONArray liste;
        try {
            liste = new JSONArray(text);
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < liste.length() && out.size() < MAX_MQTT; i++) {
            JSONObject v = liste.optJSONObject(i);
            JSONObject neu = mqttSauber(v);
            if (neu == null) continue;
            Posten p = new Posten();
            p.regel = new Regel("mqtt:" + (i + 1), "smarthome", MQTT, "", "mqtt", Klasse.EINSTELLUNG, null);
            p.klasse = Klasse.EINSTELLUNG;
            p.neu = neu;
            p.titelZusatz = neu.optString("name", "") + " (" + neu.optString("brokerUrl", "") + ")";
            p.alt = gleicheMqtt(neu, panoptes);
            p.aktion = p.alt != null ? "gleich" : "neu";
            p.rohHash = hash(neu.toString() + "|" + (p.alt != null));
            out.add(p);
        }
        return out;
    }

    static JSONObject mqttSauber(JSONObject v) {
        if (v == null) return null;
        String url = v.optString("brokerUrl", "").trim();
        int port = v.optInt("port", -1);
        if (url.isEmpty() || url.length() > 200 || port < 1 || port > 65535) return null;
        try {
            JSONObject n = new JSONObject();
            String name = v.optString("name", "").replaceAll("\\p{Cntrl}+", " ").trim();
            n.put("name", name.isEmpty() ? "MQTT" : (name.length() > 60 ? name.substring(0, 60) : name));
            n.put("brokerUrl", url);
            n.put("port", port);
            n.put("topic", v.optString("topic", "").trim());
            for (String k : new String[] {"qos", "publishIntervalSeconds", "minIntervalSeconds", "maxIntervalSeconds",
                    "parkedIntervalSeconds", "chargingIntervalSeconds"}) {
                if (v.opt(k) instanceof Number) n.put(k, v.optInt(k));
            }
            for (String k : new String[] {"adaptiveInterval", "retainMessages", "changeOnly", "homeAssistantDiscovery",
                    "heartbeatSendAll", "flushOnStateChange", "trustAllCerts"}) {
                if (v.opt(k) instanceof Boolean) n.put(k, v.optBoolean(k));
            }
            if (v.opt("discoveryPrefix") instanceof String) n.put("discoveryPrefix", v.optString("discoveryPrefix").trim());
            n.put("enabled", false);        // nie still Daten an einen Broker schicken
            n.put("allowControl", false);   // Regel A: kein Steuern über MQTT nach einer Übernahme
            n.put("username", "");          // Zugangsdaten nie übernehmen: bitte neu eingeben
            n.put("password", "");
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object gleicheMqtt(JSONObject neu, JSONArray panoptes) {
        for (int i = 0; panoptes != null && i < panoptes.length(); i++) {
            JSONObject x = panoptes.optJSONObject(i);
            if (x == null) continue;
            if (neu.optString("brokerUrl").equalsIgnoreCase(x.optString("brokerUrl", "").trim())
                    && neu.optInt("port") == x.optInt("port", -1)
                    && neu.optString("topic").equals(x.optString("topic", "").trim())) {
                return x.optString("name", "MQTT");
            }
        }
        return null;
    }

    /** Sind nutzbare Overdrive-Daten da? (Einrichtung: Schritt extra_overdrive) */
    public static boolean vorhanden(OverdriveQuelle.Befund b) {
        return b != null && grund(b) == null;
    }

    /** null = Daten da; sonst Grund. Die Versionsdatei allein zählt nicht; eine kaputte Haupt-Config allein = nicht lesbar. */
    static String grund(OverdriveQuelle.Befund b) {
        if (!b.vorhanden()) return b.grund == null ? OverdriveQuelle.KEINE_DATEN : b.grund;
        Set<String> nutzbar = new HashSet<>(b.dateien.keySet());
        nutzbar.remove(VERSION);
        String cfg = b.dateien.get(CONFIG);
        if (cfg != null) {
            try {
                new JSONObject(cfg);
            } catch (Exception e) {
                nutzbar.remove(CONFIG);
                if (nutzbar.isEmpty()) return OverdriveQuelle.NICHT_LESBAR;
            }
        }
        return nutzbar.isEmpty() ? OverdriveQuelle.KEINE_DATEN : null;
    }

    /** Overdrive-Version für die Vorschau (Versionsdatei, sonst Config-Feld „version“), oder null. */
    static Object version(OverdriveQuelle.Befund b) {
        String v = b.dateien.get(VERSION);
        if (v != null) {
            String t = v.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
            if (!t.isEmpty() && t.length() <= 40) return t;
        }
        String cfg = b.dateien.get(CONFIG);
        Object c = cfg == null ? null : wert(cfg, CONFIG, "version");
        return c instanceof Number || c instanceof String ? String.valueOf(c) : JSONObject.NULL;
    }


    /** Katalog-Felder über ihren Zugriff (Aliasse), sonst Abschnitt.Schlüssel. */
    static Map<String, Map<String, Object>> schreibweise(Posten p, JSONObject root, List<Feld> felder) {
        Feld f = Katalog.finde(felder, p.regel.ziel);
        if (f != null) return f.zugriff.schreibe(p.neu, root);
        int punkt = p.regel.ziel.indexOf('.');
        Map<String, Object> v = new java.util.HashMap<>();
        v.put(p.regel.ziel.substring(punkt + 1), p.neu);
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        out.put(p.regel.ziel.substring(0, punkt), v);
        return out;
    }

    /** Einwilligung, Gefahr, Datenschutz, nur lesend → nie übernehmen. */
    static boolean gesperrt(Regel r, List<Feld> felder) {
        if (r.klasse == Klasse.MANUELL) return true;
        Feld f = Katalog.finde(felder, r.ziel);
        if (f == null) return false;
        // nurLesendExtern kennzeichnet im Katalog die Freigaben/Einwilligungen (Wetter, Position, Fahrtenbuch …)
        return f.bestaetigung != null || f.gefahr || f.nurLesend || f.nurLesendExtern || "datenschutz".equals(f.gruppe);
    }

    private static Object zielWert(JSONObject root, String ziel, Feld f) {
        if (f != null) return f.zugriff.lese(root);
        int punkt = ziel.indexOf('.');
        JSONObject s = root == null ? null : root.optJSONObject(ziel.substring(0, punkt));
        String k = ziel.substring(punkt + 1);
        return s == null || !s.has(k) || s.isNull(k) ? null : s.opt(k);
    }

    private static Object geheimAlt(JSONObject root, String ziel) {
        Object v = zielWert(root, ziel, null);
        return v == null || String.valueOf(v).isEmpty() ? null : "x";
    }

    /** Wert aus JSON (Punkt-Pfad) oder .properties (Schlüssel). */
    static Object wert(String text, String datei, String pfad) {
        try {
            if (datei.endsWith(".properties")) {
                Properties p = new Properties();
                p.load(new StringReader(text));
                String v = p.getProperty(pfad);
                return v == null || v.isEmpty() ? null : v;
            }
            Object cur = new JSONObject(text);
            for (String teil : pfad.split("\\.")) {
                if (!(cur instanceof JSONObject)) return null;
                JSONObject o = (JSONObject) cur;
                if (!o.has(teil) || o.isNull(teil)) return null;
                cur = o.get(teil);
            }
            return cur;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object anzeige(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof JSONArray) return ((JSONArray) v).length() + " ×";
        return v;
    }

    static String vorschauId(List<Posten> posten) {
        StringBuilder sb = new StringBuilder();
        for (Posten p : posten) sb.append(p.regel.id).append('=').append(p.aktion).append(':').append(p.rohHash).append(';');
        return hash(sb.toString());
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Für Tests und Handler: Regeln als Map nach id. */
    static Map<String, Regel> nachId(List<Regel> regeln) {
        Map<String, Regel> m = new java.util.LinkedHashMap<>();
        for (Regel r : regeln) m.put(r.id, r);
        return m;
    }
}
