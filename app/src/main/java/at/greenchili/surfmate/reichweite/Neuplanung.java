package at.greenchili.surfmate.reichweite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * F1 Neuplanungs-Vorschlag (F1_KONZEPT.md §2): bei Stufe „Reicht nicht“ höchstens 1× je 5 min
 * {@code POST plan} mit Start = aktuelle Position. Das ist die EINZIGE Stelle, an der die
 * Position des Autos zum Planer geht; der Planer speichert sie nicht. Rein, ohne Netz.
 *
 * <p>Nichts geht automatisch ins Navi (Regel A): der Vorschlag wird nur angezeigt.
 * Verwirft der Fahrer ihn ({@link #ablehnen}), meldet der Dienst {@code auto-status abgelehnt}
 * und schlägt {@link #SPERRE_NACH_ABLEHNUNG_MS} lang nichts Neues vor.
 */
public final class Neuplanung {

    static final long ABSTAND_MS = 5 * 60_000L;
    static final long SPERRE_NACH_ABLEHNUNG_MS = 30 * 60_000L;

    private long letzterVersuch = Long.MIN_VALUE / 2;
    private long gesperrtBis = Long.MIN_VALUE / 2;
    private Vorschlag vorschlag;

    /** Ergebnis einer Neuplanung. */
    public static final class Vorschlag {
        /** {@code stopp} = neuer erster Ladestopp, {@code ohne_stopp} = Ziel laut Planer erreichbar, {@code keiner} = kein Vorschlag. */
        public String art = "keiner";
        public String name;
        public double lat = Double.NaN, lon = Double.NaN, kmAbHier = Double.NaN, kw;
        public double socAn = Double.NaN, socAb = Double.NaN, ankunftProzent = Double.NaN;
        public long erstelltMs;
        /** {@code lokal} (Reicht nicht) oder {@code abweichung} (0.1.16, Planer §17). */
        public String quelle = QUELLE_LOKAL;

        public JSONObject alsJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("art", art);
                o.put("quelle", quelle);
                if (name != null) o.put("name", name);
                if (!Double.isNaN(kmAbHier)) o.put("km", Math.round(kmAbHier * 10) / 10.0);
                if (kw > 0) o.put("kw", kw);
                if (!Double.isNaN(socAn)) o.put("socAn", Math.round(socAn));
                if (!Double.isNaN(socAb)) o.put("socAb", Math.round(socAb));
                if (!Double.isNaN(ankunftProzent)) o.put("ankunftProzent", Math.round(ankunftProzent));
                // Lage des Vorschlags (Säule, nicht das Auto) für „Ziel ins Navi“ im Stand mit Rückfrage.
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    o.put("lat", lat);
                    o.put("lon", lon);
                }
                o.put("erstellt", erstelltMs);
            } catch (Exception ignored) {
            }
            return o;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Vorschlag[%s %s %.1f km]", art, name, kmAbHier);
        }
    }

    /** Ob jetzt neu geplant werden darf (nur „Reicht nicht“, Abstand, keine Sperre nach Ablehnung). */
    public boolean faellig(long now, RangeWatch.Stufe stufe) {
        if (stufe != RangeWatch.Stufe.REICHT_NICHT) return false;
        if (now < gesperrtBis) return false;
        return now - letzterVersuch >= ABSTAND_MS;
    }

    /** Versuch merken (auch wenn das Netz scheitert – das Ratenlimit des Planers gilt je Versuch). */
    public void versucht(long now) {
        letzterVersuch = now;
    }

    public void ergebnis(Vorschlag v) {
        vorschlag = v;
    }

    /**
     * Aktueller Vorschlag oder null. Zurück auf „Passt“ verwirft einen lokalen Vorschlag; einen aus der
     * Plan-Abweichung (§17, z. B. > 3 km neben der Route) nur {@link #abweichungEntwarnt()}.
     */
    public Vorschlag aktuell(RangeWatch.Stufe stufe) {
        if (stufe == RangeWatch.Stufe.PASST && vorschlag != null && !QUELLE_ABWEICHUNG.equals(vorschlag.quelle)) {
            vorschlag = null;
        }
        return vorschlag;
    }

    public static final String QUELLE_LOKAL = "lokal";
    public static final String QUELLE_ABWEICHUNG = "abweichung";

    /** 0.1.16: Darf ein Vorschlag aus der Plan-Abweichung kommen (nicht in der Sperre nach einer Ablehnung)? */
    public boolean abweichungErlaubt(long now) {
        return now >= gesperrtBis;
    }

    /** 0.1.16: Planer meldet „im Plan“ – ein Vorschlag aus der Abweichung gilt nicht mehr. */
    public void abweichungEntwarnt() {
        if (vorschlag != null && QUELLE_ABWEICHUNG.equals(vorschlag.quelle)) vorschlag = null;
    }

    /**
     * 0.1.19: Hinweis „neu planen“ für die Navi-Seite im Auto (nur Panoptes-Oberfläche: kein Band über fremden Apps,
     * kein Ton – A+). Nur wenn der Planer (§17, Einwilligung {@code planner.abweichung}) {@code neu_planen} meldet, der
     * Fahrer nicht gerade verworfen hat und das Ziel bekannt ist. {@code ziel}/{@code via} dienen dem Knopf
     * „Neu planen“, der die Navi-Seite mit dem Ziel neu rechnen lässt (nichts geht automatisch ins Navi, Regel A).
     *
     * @param planZiel {@code ziel} der überwachten Route ({@code {name, lat, lon}})
     * @return {@code {gruende, abweichungPp?, ziel:{name,lat,lon}, via:[…]}} oder null
     */
    public static JSONObject hinweis(PlanAbweichung.Befund b, boolean erlaubt, JSONObject planZiel) {
        if (b == null || !b.ok || !b.neuPlanen || !erlaubt || planZiel == null) return null;
        double lat = planZiel.optDouble("lat", Double.NaN), lon = planZiel.optDouble("lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("gruende", b.gruende);
            if (!Double.isNaN(b.abweichungPp)) o.put("abweichungPp", b.abweichungPp);
            o.put("ziel", new JSONObject().put("name", planZiel.optString("name", "")).put("lat", lat).put("lon", lon));
            JSONArray via = b.neuPlanAnfrage == null ? null : b.neuPlanAnfrage.optJSONArray("via");
            o.put("via", via == null ? new JSONArray() : via);
        } catch (Exception e) {
            return null;
        }
        return o;
    }

    /** 0.1.19: Band/Ansage nur für den lokalen Vorschlag; einer aus der Plan-Abweichung erscheint nur in der Navi (A+). */
    public static Vorschlag fuersBand(Vorschlag v) {
        return v != null && QUELLE_ABWEICHUNG.equals(v.quelle) ? null : v;
    }

    /** Fahrer verwirft den Vorschlag. @return true, wenn einer da war. */
    public boolean ablehnen(long now) {
        boolean war = vorschlag != null;
        vorschlag = null;
        gesperrtBis = now + SPERRE_NACH_ABLEHNUNG_MS;
        return war;
    }

    /** Neue Route: alles zurück. */
    public void zuruecksetzen() {
        vorschlag = null;
        letzterVersuch = Long.MIN_VALUE / 2;
        gesperrtBis = Long.MIN_VALUE / 2;
    }

    /**
     * Body für {@code POST plan} (Vertrag: start/ziel als [lat, lon]). Start auf 3 Stellen
     * (~100 m) gerundet; nur im Body, nie in der URL.
     */
    public static JSONObject anfrage(double lat, double lon, double soc, double zielLat, double zielLon,
                                     double reserve, String fahrzeug, String autoId) {
        JSONObject b = new JSONObject();
        try {
            b.put("start", new JSONArray().put(Math.round(lat * 1000) / 1000.0).put(Math.round(lon * 1000) / 1000.0));
            b.put("ziel", new JSONArray().put(zielLat).put(zielLon));
            b.put("soc_start", Math.max(1, Math.min(100, Math.round(soc))));
            b.put("soc_ziel_min", Math.round(reserve));
            b.put("soc_reserve", Math.round(reserve));
            if (fahrzeug != null && !fahrzeug.isEmpty()) b.put("fahrzeug", fahrzeug);
            if (autoId != null && autoId.matches("^a-[0-9a-f]{16}$")) b.put("auto", autoId);
        } catch (Exception ignored) {
        }
        return b;
    }

    /**
     * Antwort von {@code POST plan} auswerten. Erster Stopp: {@code stopps[0].saeule}
     * {name, lat, lon, kw, km_auf_route}, {@code soc_vorher}, {@code soc_nachher}.
     *
     * @return Vorschlag; {@code art=keiner} bei Fehler/leerer Antwort
     */
    public static Vorschlag auswerten(JSONObject antwort, long now) {
        Vorschlag v = new Vorschlag();
        v.erstelltMs = now;
        if (antwort == null || antwort.has("fehler")) return v;
        v.ankunftProzent = antwort.optDouble("soc_ankunft", Double.NaN);
        JSONArray st = antwort.optJSONArray("stopps");
        if (st == null || st.length() == 0) {
            if (!Double.isNaN(v.ankunftProzent)) v.art = "ohne_stopp";
            return v;
        }
        JSONObject s = st.optJSONObject(0);
        JSONObject saeule = s == null ? null : s.optJSONObject("saeule");
        if (saeule == null) return v;
        double km = saeule.optDouble("km_auf_route", Double.NaN);
        if (Double.isNaN(km) || km < 0) return v;
        v.art = "stopp";
        v.name = saeule.optString("name", "Ladesäule");
        v.lat = saeule.optDouble("lat", Double.NaN);
        v.lon = saeule.optDouble("lon", Double.NaN);
        v.kw = saeule.optDouble("kw", 0);
        v.kmAbHier = km;
        v.socAn = s.optDouble("soc_vorher", Double.NaN);
        v.socAb = s.optDouble("soc_nachher", Double.NaN);
        return v;
    }
}
