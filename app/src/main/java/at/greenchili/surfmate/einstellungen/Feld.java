package at.greenchili.surfmate.einstellungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Eine Einstellung im Schema (Vertrag EINSTELLUNGEN_SCHEMA.md v1.1). */
public final class Feld {

    public enum Typ { BOOL, INT, ZAHL, TEXT, AUSWAHL, LISTE, GEOZONE, ZEIT, ZEITFENSTER, FARBE, GEHEIM }

    /** Liest/schreibt den Wert in der Config (Standard: Abschnitt.Schlüssel; Aliasse eigen). */
    /** Feldeigene Prüfung (z. B. Listen der Gruppe „laden“); IllegalArgumentException mit Fehlergrund. */
    public interface Pruefer {
        Object pruefe(Object wert);
    }

    public interface Zugriff {
        /** Wert oder null, wenn nicht gesetzt. */
        Object lese(JSONObject root);
        /** Abschnitt → Werte, die geschrieben werden sollen. */
        java.util.Map<String, java.util.Map<String, Object>> schreibe(Object wert, JSONObject root);
    }

    public static final class Option {
        public final String wert;
        public final String titelKey;
        public boolean gesperrt;
        public String gesperrtGrundKey;
        public Option(String wert, String titelKey) { this.wert = wert; this.titelKey = titelKey; }
    }

    public final String key;
    public final Typ typ;
    public String gruppe = "system";
    public String abschnitt;
    public int reihenfolge;
    public String titelKey;
    public String hilfeKey;
    public Object standard;
    public Double min, max, schritt;
    public String einheit;
    public final List<Option> optionen = new ArrayList<>();
    public boolean nurLesendExtern;
    /** Nirgends schreibbar (z. B. planner.autoId). */
    public boolean nurLesend;
    public boolean neustartNoetig;
    public boolean erweitert;
    public boolean gefahr;
    /** 0 schaltet die Funktion ab (Oberfläche zeichnet Schalter + Zahl). */
    public boolean nullIstAus;
    /** Empfohlener Wert (z. B. für den nullIstAus-Schalter „an“), unabhängig vom Standard. */
    public Object empfohlen;
    /** Aktuell gültiger Wert, gegen den die Oberfläche vergleicht (z. B. Textversion der Haftungshinweise). */
    public Object aktuell;
    /** Nie in generischen Einstellungsseiten zeichnen (sichtbar:false); die Oberfläche hat einen eigenen Dialog. */
    public boolean verborgen;
    public String bestaetigung;
    /** Wird bei PUT mit Bestätigung zusätzlich gesetzt (ISO-Zeit), z. B. surveillance.triggerConsentAt. */
    public String bestaetigungZeitKey;
    public JSONObject nurWenn;
    public String modul;
    public String alias;
    public JSONObject listeVon;
    public Integer maxEintraege;
    public Zugriff zugriff;
    /** null (= „nicht gesetzt“, Standard gilt) ist ein gültiger Wert. */
    public boolean nullErlaubt;
    /** Ersetzt die Typ-Prüfung, wenn gesetzt. */
    public Pruefer pruefer;

    public Feld(String key, Typ typ) {
        this.key = key;
        this.typ = typ;
        this.titelKey = "settings." + key + ".title";
        this.hilfeKey = "settings." + key + ".help";
        this.zugriff = standardZugriff(key);
    }

    public String abschnittConfig() { return key.substring(0, key.indexOf('.')); }

    public String schluesselConfig() { return key.substring(key.indexOf('.') + 1); }

    static Zugriff standardZugriff(final String key) {
        final String section = key.substring(0, key.indexOf('.'));
        final String name = key.substring(key.indexOf('.') + 1);
        return new Zugriff() {
            public Object lese(JSONObject root) {
                JSONObject s = root == null ? null : root.optJSONObject(section);
                if (s == null || !s.has(name) || s.isNull(name)) return null;
                return s.opt(name);
            }
            public java.util.Map<String, java.util.Map<String, Object>> schreibe(Object wert, JSONObject root) {
                java.util.Map<String, Object> v = new java.util.HashMap<>();
                v.put(name, wert == null ? JSONObject.NULL : wert);
                java.util.Map<String, java.util.Map<String, Object>> out = new java.util.HashMap<>();
                out.put(section, v);
                return out;
            }
        };
    }

    public static JSONArray asArray(Object o) {
        if (o instanceof JSONArray) return (JSONArray) o;
        return null;
    }
}
