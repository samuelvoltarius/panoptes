package at.greenchili.surfmate.ha;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 0.1.43.21 Bausteine für {@code GET /api/ha/v1/daten} (Feldliste: {@code docs/API_FUER_HA.md}) und zwei Korrekturen in
 * {@code zustand} (Gang, Kilometerstand). Rein und testbar; die Live-Werte holt {@code HaApiHandler}.
 *
 * <p>Grundsatz wie {@link HaZustand}: nur lesen, nie VIN, nie Benutzernamen, keine Orte, keine Clip-Namen oder -Inhalte.
 * Fehlt etwas, steht {@code null} da, nie eine erfundene 0.
 */
public final class HaDaten {

    private HaDaten() {}

    public static final int VERSION = 1;

    // ==================================================================== Gang

    /** Gang für HA: nur P, R, N oder D, sonst {@code null} (nie „UNKNOWN“). S und M sind Vorwärtsgänge → D. */
    public static Object gang(Object roh) {
        if (!(roh instanceof String)) return JSONObject.NULL;
        String g = ((String) roh).trim().toUpperCase(Locale.ROOT);
        switch (g) {
            case "P": case "R": case "N": case "D": return g;
            case "S": case "M": return "D";
            default: return JSONObject.NULL;
        }
    }

    // ==================================================================== Kilometerstand

    /**
     * Kilometerstand, der nie kleiner wird (HA {@code total_increasing}). Ein kleinerer Wert (Wackeln des Registers,
     * Rückfall auf den ganzzahligen Wert) → der höchste bisher gesehene bleibt. Ein Sprung um mehr als
     * {@link #SPRUNG_KM} (nach oben oder unten) gilt erst, wenn er {@link #SPRUNG_BESTAETIGT} Mal hintereinander kommt:
     * ein einzelner Ausreißer friert den Zähler nicht für immer ein, und ein falscher gespeicherter Wert auch nicht.
     */
    public static final class Kilometer {
        public static final double SPRUNG_KM = 2000;
        public static final int SPRUNG_BESTAETIGT = 3;
        private double hoechster;
        private double kandidat = Double.NaN;
        private int kandidatZahl;

        public Kilometer(double gespeichert) {
            this.hoechster = Double.isFinite(gespeichert) && gespeichert > 0 ? gespeichert : 0;
        }

        /** Neuer Messwert → Wert für HA (oder NaN, wenn es noch keinen gibt). */
        public synchronized double melde(double km) {
            if (!Double.isFinite(km) || km <= 0) return hoechster > 0 ? hoechster : Double.NaN;
            km = Math.round(km * 10.0) / 10.0;
            if (hoechster <= 0 || (km >= hoechster && km - hoechster <= SPRUNG_KM)) {
                hoechster = Math.max(hoechster, km);
                kandidat = Double.NaN;
                kandidatZahl = 0;
            } else if (Math.abs(km - hoechster) > SPRUNG_KM) {
                // großer Sprung (auch nach unten: ein falscher gespeicherter Wert darf nicht ewig gelten)
                if (Double.isFinite(kandidat) && Math.abs(km - kandidat) <= SPRUNG_KM) kandidatZahl++;
                else kandidatZahl = 1;
                kandidat = km;
                if (kandidatZahl >= SPRUNG_BESTAETIGT) {
                    hoechster = km;
                    kandidat = Double.NaN;
                    kandidatZahl = 0;
                }
            }
            return hoechster;
        }

        public synchronized double hoechster() { return hoechster; }
    }

    // ==================================================================== Ladekosten / Ersparnis

    /**
     * Ersparnis-Block aus der {@code /api/laden/ersparnis}-Antwort: immer {@code schaetzung=true} mit Hinweis und
     * Vergleichsauto. Plug-in-Hybrid → {@code verfuegbar=false} (ein Teil der km fährt mit Sprit, wie Karte und CSV).
     */
    public static JSONObject ersparnis(JSONObject antwort, boolean phev, String hinweis) throws Exception {
        JSONObject o = new JSONObject();
        o.put("schaetzung", true);
        o.put("hinweis", hinweis == null ? JSONObject.NULL : hinweis);
        o.put("hinweis_schluessel", "ersparnis.schaetzung");
        boolean da = !phev && antwort != null && antwort.optBoolean("ok", false);
        o.put("verfuegbar", da);
        JSONObject v = antwort == null ? null : antwort.optJSONObject("vergleich");
        JSONObject auto = new JSONObject();
        auto.put("kraftstoff", v == null ? JSONObject.NULL : v.opt("kraftstoff"));
        auto.put("verbrauch_l_100km", v == null ? JSONObject.NULL : v.opt("verbrauch"));
        auto.put("preis_l", v == null || v.isNull("preis") ? JSONObject.NULL : v.opt("preis"));
        auto.put("preis_richtwert", v != null && v.optBoolean("preis_richtwert", false));
        auto.put("waehrung", v == null || v.isNull("waehrung") ? JSONObject.NULL : v.opt("waehrung"));
        o.put("vergleichsauto", auto);
        for (String z : new String[] {"monat", "jahr", "gesamt"}) {
            JSONObject e = da ? antwort.optJSONObject(z) : null;
            o.put(z, e == null ? JSONObject.NULL : zeitraumErsparnis(e));
        }
        return o;
    }

    static JSONObject zeitraumErsparnis(JSONObject e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ersparnis", e.opt("ersparnis") == null ? JSONObject.NULL : e.opt("ersparnis"));
        o.put("km", e.opt("km") == null ? JSONObject.NULL : e.opt("km"));
        o.put("strom_kosten", e.opt("strom_kosten") == null ? JSONObject.NULL : e.opt("strom_kosten"));
        o.put("sprit_kosten", e.opt("sprit_kosten") == null ? JSONObject.NULL : e.opt("sprit_kosten"));
        o.put("vollstaendig", e.optBoolean("strom_vollstaendig", false));
        o.put("grund", e.opt("grund") == null ? JSONObject.NULL : e.opt("grund"));
        return o;
    }

    // ==================================================================== Dashcam-Clips (Ereignis-Clips)

    /** Ein Ereignis-Clip, nur was HA sehen darf: keine Namen, keine Orte, kein Inhalt. */
    public static final class Clip {
        public final long zeit;
        public final String grund;
        public final Double dauerS;
        public final boolean behalten;

        public Clip(long zeit, String grund, Double dauerS, boolean behalten) {
            this.zeit = zeit; this.grund = grund; this.dauerS = dauerS; this.behalten = behalten;
        }
    }

    public static JSONObject clips(List<Clip> clips, long jetzt, ZoneId zone) throws Exception {
        int behalten = 0, heute = 0;
        Clip letzter = null;
        LocalDate tag = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate();
        for (Clip c : clips) {
            if (c.behalten) behalten++;
            if (c.zeit > 0 && Instant.ofEpochMilli(c.zeit).atZone(zone).toLocalDate().equals(tag)) heute++;
            if (letzter == null || c.zeit > letzter.zeit) letzter = c;
        }
        JSONObject o = new JSONObject();
        o.put("anzahl", clips.size());
        o.put("heute", heute);
        o.put("behalten", behalten);
        if (letzter == null) {
            o.put("letzter", JSONObject.NULL);
        } else {
            JSONObject l = new JSONObject();
            l.put("zeit", letzter.zeit > 0 ? (Object) letzter.zeit : JSONObject.NULL);
            l.put("grund", letzter.grund == null ? JSONObject.NULL : letzter.grund);
            l.put("dauer_s", letzter.dauerS == null ? JSONObject.NULL : (Object) letzter.dauerS);
            l.put("behalten", letzter.behalten);
            o.put("letzter", l);
        }
        return o;
    }

    /**
     * Länge eines MP4 in Sekunden aus dem {@code mvhd}-Kasten (nur Kopf lesen, kein Dekodieren); null, wenn unbekannt
     * (fragmentiertes MP4 mit Dauer 0, kaputt, zu groß verschachtelt).
     */
    public static Double dauerSek(File mp4) {
        if (mp4 == null || !mp4.isFile()) return null;
        try (RandomAccessFile f = new RandomAccessFile(mp4, "r")) {
            long moov = kasten(f, 0, f.length(), "moov");
            if (moov < 0) return null;
            long moovGroesse = groesse(f, moov);
            long mvhd = kasten(f, moov + 8, moov + moovGroesse, "mvhd");
            if (mvhd < 0) return null;
            f.seek(mvhd + 8);
            int version = f.readUnsignedByte();
            f.skipBytes(3);
            long skala, dauer;
            if (version == 1) {
                f.skipBytes(16);
                skala = f.readInt() & 0xffffffffL;
                dauer = f.readLong();
            } else {
                f.skipBytes(8);
                skala = f.readInt() & 0xffffffffL;
                dauer = f.readInt() & 0xffffffffL;
            }
            if (skala <= 0 || dauer <= 0 || dauer == 0xffffffffL) return null;
            double s = (double) dauer / skala;
            return s > 0 && s < 86_400 ? Math.round(s * 10.0) / 10.0 : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Anfang des ersten Kastens {@code typ} in [von, bis) oder −1. */
    private static long kasten(RandomAccessFile f, long von, long bis, String typ) throws Exception {
        long p = von;
        for (int i = 0; i < 4096 && p + 8 <= bis; i++) {
            long g = groesse(f, p);
            if (g < 8) return -1;
            f.seek(p + 4);
            byte[] t = new byte[4];
            f.readFully(t);
            if (typ.equals(new String(t, java.nio.charset.StandardCharsets.ISO_8859_1))) return p;
            p += g;
        }
        return -1;
    }

    private static long groesse(RandomAccessFile f, long p) throws Exception {
        f.seek(p);
        long g = f.readInt() & 0xffffffffL;
        if (g == 1) {
            f.seek(p + 8);
            g = f.readLong();
        } else if (g == 0) {
            g = f.length() - p;
        }
        return g;
    }

    // ==================================================================== Meldungen des Autos

    /**
     * Merkt sich, seit wann eine Meldung ansteht, und einen kurzen Verlauf (kommt/geht). Nur im Speicher: nach einem
     * Neustart von Panoptes gilt „seit“ ab dem ersten Lesen danach.
     */
    public static final class MeldungsVerlauf {
        public static final int MAX_VERLAUF = 20;
        private final Map<Integer, Long> seit = new LinkedHashMap<>();
        private final Map<Integer, String> schluessel = new LinkedHashMap<>();
        private final List<JSONObject> verlauf = new ArrayList<>();
        private boolean gelesen;

        /**
         * {@code auto} = Antwort von {@code AutoMeldungen} ({@code verfuegbar}, {@code meldungen[{typ, schluessel}]});
         * {@code text} übersetzt einen Schlüssel (null → Schlüssel selbst).
         */
        public synchronized JSONObject aktualisieren(JSONObject auto, long jetzt, Function<String, String> text)
                throws Exception {
            boolean da = auto != null && auto.optBoolean("verfuegbar", false);
            JSONObject o = new JSONObject();
            o.put("verfuegbar", da);
            if (da) {
                JSONArray m = auto.optJSONArray("meldungen");
                Map<Integer, String> jetztAn = new LinkedHashMap<>();
                for (int i = 0; m != null && i < m.length(); i++) {
                    JSONObject x = m.optJSONObject(i);
                    if (x == null || !x.has("typ")) continue;
                    jetztAn.put(x.optInt("typ"), x.optString("schluessel", ""));
                }
                for (Map.Entry<Integer, String> e : jetztAn.entrySet()) {
                    if (!seit.containsKey(e.getKey())) {
                        seit.put(e.getKey(), jetzt);
                        schluessel.put(e.getKey(), e.getValue());
                        if (gelesen) merke(jetzt, e.getKey(), e.getValue(), "kommt", text);
                    }
                }
                for (Integer typ : new ArrayList<>(seit.keySet())) {
                    if (jetztAn.containsKey(typ)) continue;
                    merke(jetzt, typ, schluessel.get(typ), "geht", text);
                    seit.remove(typ);
                    schluessel.remove(typ);
                }
                gelesen = true;
            }
            JSONArray liste = new JSONArray();
            if (da) {
                for (Map.Entry<Integer, Long> e : seit.entrySet()) {
                    liste.put(eintrag(e.getValue(), e.getKey(), schluessel.get(e.getKey()), text));
                }
            }
            o.put("anzahl", liste.length());
            o.put("liste", liste);
            JSONArray v = new JSONArray();
            for (int i = verlauf.size() - 1; i >= 0; i--) v.put(verlauf.get(i));
            o.put("verlauf", v);
            o.put("stand", auto != null && auto.has("stand") ? auto.opt("stand") : JSONObject.NULL);
            return o;
        }

        private void merke(long zeit, int typ, String s, String was, Function<String, String> text) throws Exception {
            JSONObject e = eintrag(zeit, typ, s, text);
            e.put("ereignis", was);
            verlauf.add(e);
            while (verlauf.size() > MAX_VERLAUF) verlauf.remove(0);
        }

        private static JSONObject eintrag(long zeit, int typ, String s, Function<String, String> text) throws Exception {
            JSONObject e = new JSONObject();
            e.put("zeit", zeit);
            e.put("code", typ);
            e.put("schluessel", s == null || s.isEmpty() ? JSONObject.NULL : s);
            String t = null;
            try { t = s == null || text == null ? null : text.apply(s); } catch (Throwable ignored) { }
            e.put("text", t == null || t.isEmpty() ? (s == null || s.isEmpty() ? JSONObject.NULL : s) : t);
            return e;
        }
    }

    // ==================================================================== Texte

    /** Wert unter {@code a.b.c} aus einem i18n-Katalog oder null. */
    public static String text(JSONObject katalog, String pfad) {
        if (katalog == null || pfad == null) return null;
        Object o = katalog;
        for (String teil : pfad.split("\\.")) {
            if (!(o instanceof JSONObject)) return null;
            o = ((JSONObject) o).opt(teil);
        }
        return o instanceof String ? (String) o : null;
    }
}
