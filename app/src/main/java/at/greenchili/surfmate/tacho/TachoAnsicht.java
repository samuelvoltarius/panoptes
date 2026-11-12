package at.greenchili.surfmate.tacho;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Panoptes-Tacho-Ansicht (0.1.37): eine eigene Panoptes-Seite, die wie jede andere App ueber die
 * vorhandene Appwahl ({@link TachoNaviApp#fahrtSperre}) und {@code ClusterCast} →
 * {@code ClusterProjectionController} auf das Fahrerdisplay kommt. Kein eigener Projektionsweg
 * und keine eigenen Befehle an den Tacho.
 *
 * <p>Inhalt: Tempo, Akku %, Reichweite und das naechste Manoever der Panoptes-Navigation. Kein
 * Video, keine Kamera, keine Position. Gilt als Navigation ({@link TachoNaviApp#NAVI_PAKETE}
 * enthaelt das Panoptes-Paket) und darf daher nach Freigabe auch in der Fahrt laufen.
 *
 * <p><b>Flaeche:</b> Die Projektion ist laut {@code ClusterProjectionController} eine volle
 * Uebernahme des Fahrerdisplays. Ein Beleg, wo die BYD-Warnleuchten dabei liegen, fehlt. Deshalb
 * zeichnet die Ansicht ihren Inhalt nur im verkleinerten Mittelbereich ({@link #inhalt}); der
 * Rest bleibt schwarz. Ab 0.1.37 gibt es keine Pflicht-Probe mehr: Der Tacho-Hinweis sagt, beim ersten
 * Start auf Tempo und Warnleuchten zu achten, sonst „Tacho wieder normal“ ({@link TachoNaviApp#tachoNormal}).
 */
public final class TachoAnsicht {

    /** Unter diesem Paketnamen steht die Ansicht in der Appwahl und in den Freigaben. */
    public static final String PAKET = "at.greenchili.surfmate";
    /** Fester Start-Komponentenname. Wird nur von {@code ClusterCast.start} benutzt. */
    public static final String KOMPONENTE = PAKET + "/.tacho.TachoAnsichtActivity";
    public static final String NAME = "Panoptes-Tacho (Tempo, Akku, Navi)";

    public static final String KLAR = "klar", SPORT = "sport", NACHT = "nacht",
            HORIZONT = "horizont", ROUTE = "route";
    /**
     * Designs (0.1.37): alle zeichnet derselbe Renderer {@code web/shared/tacho-designs.js}, am
     * Fahrerdisplay in {@code web/local/tacho-ansicht.html} und in der Web-Vorschau.
     */
    /**
     * 0.1.42 (T2 Tacho-Galerie): 15 weitere eigene Designs aus {@code web/shared/tacho-galerie.js}, gleiche Reihenfolge
     * (Vertragstest vergleicht beide Listen).
     */
    public static final List<String> GALERIE = Collections.unmodifiableList(Arrays.asList(
            "analog", "retro", "minimal", "futur", "rennen", "eco", "gross", "sonne", "karte", "abbiegen",
            "bogen", "saeulen", "chrono", "kacheln", "tunnel"));
    public static final List<String> DESIGNS;
    static {
        List<String> alle = new java.util.ArrayList<>(Arrays.asList(KLAR, SPORT, NACHT, HORIZONT, ROUTE));
        alle.addAll(GALERIE);
        DESIGNS = Collections.unmodifiableList(alle);
    }
    /** Seite fuer die Ansicht, nur aus den App-Assets (kein Netz). */
    public static final String SEITE = "file:///android_asset/web/local/tacho-ansicht.html";
    static final String DESIGN_KEY = "ansichtDesign";

    /** Verkleinerter Bereich: Anteil von Breite und Hoehe, mittig. Nie groesser. */
    public static final double BREITE_ANTEIL = 0.50;
    public static final double HOEHE_ANTEIL = 0.60;

    private TachoAnsicht() {}

    public static boolean istAnsicht(String paket) {
        return paket != null && PAKET.equals(paket.trim());
    }

    /** Gueltiges Design (die fuenf oder 0.1.41 {@link TachoEigen#NAME}), sonst {@link #KLAR}. */
    public static String design(String wert) {
        String d = wert == null ? "" : wert.trim().toLowerCase(Locale.ROOT);
        return DESIGNS.contains(d) || TachoEigen.NAME.equals(d) ? d : KLAR;
    }

    public static String design() {
        return design(TachoNaviApp.sektion().optString(DESIGN_KEY, KLAR));
    }

    public static boolean setzeDesign(String wert) {
        String d = wert == null ? "" : wert.trim().toLowerCase(Locale.ROOT);
        if (!DESIGNS.contains(d) && !TachoEigen.NAME.equals(d)) return false;
        java.util.Map<String, Object> w = new java.util.HashMap<>();
        w.put(DESIGN_KEY, d);
        try {
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateValues(TachoNaviApp.SEKTION, w);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Inhaltsrechteck {links, oben, breite, hoehe} in Pixeln der Projektionsflaeche: mittig und
     * hoechstens {@link #BREITE_ANTEIL} x {@link #HOEHE_ANTEIL}. Bei ungueltiger Groesse leer.
     */
    public static int[] inhalt(int breite, int hoehe) {
        if (breite <= 0 || hoehe <= 0) return new int[] {0, 0, 0, 0};
        int w = (int) Math.floor(breite * BREITE_ANTEIL);
        int h = (int) Math.floor(hoehe * HOEHE_ANTEIL);
        return new int[] {(breite - w) / 2, (hoehe - h) / 2, w, h};
    }

    /**
     * Antwort an die Ansicht (TCP-Befehl {@code tachoAnsicht}). Nur Tempo, Akku, Reichweite,
     * Design und ob die Ansicht gerade die aktive Projektion ist. Keine Position, keine Kennung.
     */
    static JSONObject antwort(boolean aktiv, double kmh, double soc, double reichweiteKm, String design)
            throws Exception {
        return antwort(aktiv, kmh, soc, reichweiteKm, design, Double.NaN, 0, Double.NaN, -1);
    }

    /**
     * Wie oben, dazu Leistung (kW, negativ = Rekuperation), Gang, Aussentemperatur und
     * km-Stand. Werte ausserhalb plausibler Grenzen fallen weg.
     */
    static JSONObject antwort(boolean aktiv, double kmh, double soc, double reichweiteKm, String design,
                              double kw, int gang, double aussenC, long kmStand) throws Exception {
        JSONObject r = new JSONObject().put("status", "ok").put("aktiv", aktiv).put("design", design(design));
        if (!Double.isNaN(kmh) && kmh >= 0 && kmh < 400) r.put("kmh", (int) Math.round(kmh));
        if (!Double.isNaN(soc) && soc > 0 && soc <= 100) r.put("soc", (int) Math.round(soc));
        if (!Double.isNaN(reichweiteKm) && reichweiteKm > 0 && reichweiteKm < 3000)
            r.put("reichweiteKm", (int) Math.round(reichweiteKm));
        if (!Double.isNaN(kw) && kw > -500 && kw < 800) r.put("kw", Math.round(kw * 10) / 10.0);
        String g = gang(gang);
        if (g != null) r.put("gang", g);
        if (!Double.isNaN(aussenC) && aussenC > -50 && aussenC < 70) r.put("aussenC", (int) Math.round(aussenC));
        if (kmStand > 0 && kmStand < 3_000_000) r.put("kmStand", kmStand);
        return r;
    }

    /** Ganganzeige nach {@code GearMonitor} (1..6), sonst null. */
    static String gang(int g) {
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            case 5: return "M";
            case 6: return "S";
            default: return null;
        }
    }

    /** Daemon-Seite des TCP-Befehls. Nur lesend. */
    public static JSONObject daten() throws Exception {
        return daten(null);
    }

    /** Wie oben; 0.1.42: {@code cmd.bereich} = freies Feld der Ansicht fuer Kamera/Video (sonst keins). */
    public static JSONObject daten(JSONObject cmd) throws Exception {
        boolean aktiv = false;
        try {
            aktiv = at.greenchili.surfmate.launcher.ClusterCast.isActive()
                    && istAnsicht(at.greenchili.surfmate.launcher.ClusterCast.getCastPackage());
        } catch (Throwable ignored) { }
        double kmh = Double.NaN, soc = Double.NaN, rw = Double.NaN, kw = Double.NaN, aussen = Double.NaN;
        int gang = 0;
        long km = -1;
        try {
            at.greenchili.surfmate.byd.BydVehicleData v =
                    at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
            if (v != null) {
                kmh = v.speedKmh;
                soc = v.socPercent;
                rw = v.elecRangeKm;
                kw = v.hvBatteryPowerKw;
                gang = v.gearMode;
                aussen = v.outsideTempC;
                if (v.totalMileageKm != at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE)
                    km = v.totalMileageKm;
            }
        } catch (Throwable ignored) { }
        JSONObject r = antwort(aktiv, kmh, soc, rw, design(), kw, gang, aussen, km);
        // 0.1.41: Eigenes Design (geprueft, nur Farben und Anordnung) geht mit, wenn es gewaehlt ist.
        if (TachoEigen.NAME.equals(r.optString("design"))) {
            JSONObject e = TachoEigen.gespeichert();
            r.put("eigen", e != null ? e : TachoEigen.standard());
        }
        // 0.1.42 (T3): Kamera-/Video-Ansicht nach den Regeln in TachoKameraAnsicht (ab Werk aus).
        try {
            JSONObject k = TachoKameraAnsicht.schritt(aktiv, gang(gang), kmh,
                    TachoKameraAnsicht.bereich(cmd != null ? cmd.optJSONArray("bereich") : null));
            r.put("kamera", k.optString("kamera", "")).put("video", k.optBoolean("video", false))
                    .put("videoId", k.optString("videoId", ""));
        } catch (Throwable ignored) {
            r.put("kamera", "").put("video", false).put("videoId", "");
        }
        return r;
    }
}
