package at.greenchili.surfmate.recording;

import at.greenchili.surfmate.einstellungen.Laenderprofil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Ereignis-Clip (0.1.27, EREIGNISCLIP_0127_PLAN.md, Koordination 29.10.2026): Schalter, Ablage,
 * Obergrenze und offene „Behalten?“-Fragen. Die reinen Teile sind ohne Android testbar.
 *
 * <ul>
 *   <li>Schalter {@code recording.ereignisClip}: nicht gesetzt = Standard nach Land
 *       (Schweiz aus, sonst an). Bewusst gesetzt gilt überall.</li>
 *   <li>Ablage: {@code <recordings>/ereignisse/ereignis_JJJJMMTT_HHMMSS.mp4}. Der Größen-Aufräumer
 *       der Aufnahmen liest nur die oberste Ebene und nur {@code cam*}; der Unterordner ist damit
 *       vor dem Überschreiben geschützt. Eigene Grenze {@link #GRENZE_BYTES}, älteste zuerst.</li>
 *   <li>Aufbewahrung wie alle Clips ({@code surveillance.clipRetentionHours}, empfohlen 72 h),
 *       außer „behalten“.</li>
 *   <li>Nie hochladen: kein Upload-, Brücken- oder Telegram-Videoweg nimmt {@link #PRAEFIX}.</li>
 * </ul>
 */
public final class EreignisClip {

    public static final String ABSCHNITT = "recording";
    public static final String SCHALTER = "ereignisClip";
    public static final String TELEGRAM = "ereignisTelegram";
    public static final int VORHER_S = 20;
    public static final int NACHHER_S = 20;
    /**
     * Reserve im Ring über 20 s + 20 s hinaus. Am Auto (0.1.27) hielt der Ring bei 40 s nur
     * ~38 s, weil er nach Bytes bemessen wird und die echte Bitrate schwankt (~8 MB RAM mehr).
     */
    public static final int RING_RESERVE_S = 4;
    public static final String PRAEFIX = "ereignis_";
    public static final String UNTERORDNER = "ereignisse";
    public static final long GRENZE_BYTES = 2L * 1024 * 1024 * 1024;
    /** Index-/Listen-Typ. */
    public static final String TYP = "ereignis";
    /** Abschnitt mit den offenen „Behalten?“-Fragen. */
    static final String OFFEN_ABSCHNITT = "ereignisse";
    static final int MAX_OFFEN = 20;

    private EreignisClip() {}

    // ---------------------------------------------------------------- Schalter

    /** Standard ohne bewusste Wahl: in der Schweiz aus, sonst an. */
    public static boolean standard(Laenderprofil.Land land) {
        return land != Laenderprofil.Land.SCHWEIZ;
    }

    /** Wirksamer Schalter aus der Config; {@code root} null = Standard. */
    public static boolean aktiv(JSONObject root, Laenderprofil.Land land) {
        JSONObject r = root == null ? null : root.optJSONObject(ABSCHNITT);
        if (r != null && r.has(SCHALTER) && !r.isNull(SCHALTER)) {
            Object v = r.opt(SCHALTER);
            if (v instanceof Boolean) return (Boolean) v;
        }
        return standard(land);
    }

    public static boolean telegram(JSONObject root) {
        JSONObject r = root == null ? null : root.optJSONObject(ABSCHNITT);
        return r != null && r.optBoolean(TELEGRAM, false);
    }

    /** Laufzeit: Schalter jetzt (Config + aktuelles Land aus GPS). */
    public static boolean aktiv() {
        try {
            return aktiv(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig(),
                    Laenderprofil.aktuell());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ringgröße, die der Ereignis-Clip braucht (0 = aus). */
    public static int ringSekunden() {
        return aktiv() ? VORHER_S + NACHHER_S + RING_RESERVE_S : 0;
    }

    // ---------------------------------------------------------------- Dateien

    public static boolean istEreignis(String name) {
        return name != null && name.startsWith(PRAEFIX) && name.endsWith(".mp4");
    }

    /**
     * Ereignis-Clip nach Pfad oder Name? Für die Sperren gegen jedes Weitergeben ohne Zutun
     * (Telegram, Brücke, Cockpit, KI-Paket): Name {@code ereignis_*} oder Ordner {@code ereignisse/}.
     */
    public static boolean istEreignisPfad(String pfad) {
        if (pfad == null) return false;
        String p = pfad.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        return name.startsWith(PRAEFIX) || p.contains("/" + UNTERORDNER + "/");
    }

    /** Unterordner im Aufnahmen-Ordner. */
    public static File ordner(File recordingsDir) {
        return recordingsDir == null ? null : new File(recordingsDir, UNTERORDNER);
    }

    public static final class Datei {
        public final String name;
        public final long groesse;
        public final long zeit;
        /** Beliebiger Bezug des Aufrufers (z. B. die Datei); wird nur durchgereicht. */
        public final Object bezug;
        public Datei(String name, long groesse, long zeit) {
            this(name, groesse, zeit, null);
        }
        public Datei(String name, long groesse, long zeit, Object bezug) {
            this.name = name; this.groesse = groesse; this.zeit = zeit; this.bezug = bezug;
        }
    }

    /**
     * Was beim Pflegen gelöscht wird: zuerst abgelaufene (älter als {@code maxAlterMs}, 0 = nie),
     * dann – solange über {@code grenze} – die ältesten. Behaltene Clips nie; liegen nur noch
     * behaltene über der Grenze, bleibt es dabei (ein Unfallclip wird nicht still gelöscht).
     */
    public static List<Datei> zuLoeschen(List<Datei> dateien, long jetzt, long maxAlterMs,
                                         Set<String> behalten, long grenze) {
        List<Datei> sortiert = new ArrayList<>();
        for (Datei d : dateien) if (istEreignis(d.name)) sortiert.add(d);
        Collections.sort(sortiert, (a, b) -> Long.compare(a.zeit, b.zeit));
        long summe = 0;
        for (Datei d : sortiert) summe += Math.max(0, d.groesse);

        List<Datei> weg = new ArrayList<>();
        for (Datei d : sortiert) {
            if (behalten.contains(stamm(d.name))) continue;
            boolean abgelaufen = maxAlterMs > 0 && d.zeit > 0 && jetzt - d.zeit > maxAlterMs;
            boolean zuViel = summe > grenze;
            if (abgelaufen || zuViel) {
                weg.add(d);
                summe -= Math.max(0, d.groesse);
            }
        }
        return weg;
    }

    public static String stamm(String name) {
        return at.greenchili.surfmate.storage.Aufbewahrung.stamm(name);
    }

    // ---------------------------------------------------------------- offene Fragen

    /** Offene „Behalten?“-Fragen (neueste zuletzt): [{datei, zeit}]. */
    public static JSONArray offen(JSONObject root) {
        JSONObject a = root == null ? null : root.optJSONObject(OFFEN_ABSCHNITT);
        JSONArray l = a == null ? null : a.optJSONArray("offen");
        return l == null ? new JSONArray() : l;
    }

    /** Neuer Abschnitt mit {@code stamm} als offene Frage (hinzu) bzw. ohne (weg). */
    static JSONObject mitOffen(JSONObject root, String stamm, long zeit, boolean hinzu) throws Exception {
        JSONArray alt = offen(root);
        JSONArray neu = new JSONArray();
        for (int i = 0; i < alt.length(); i++) {
            JSONObject o = alt.optJSONObject(i);
            if (o == null || stamm.equals(o.optString("datei"))) continue;
            neu.put(o);
        }
        if (hinzu) {
            JSONObject o = new JSONObject();
            o.put("datei", stamm);
            o.put("zeit", zeit);
            neu.put(o);
        }
        while (neu.length() > MAX_OFFEN) neu.remove(0);
        JSONObject a = new JSONObject();
        a.put("offen", neu);
        return a;
    }

    /** Frage merken (nach dem Sichern). */
    public static synchronized boolean frageMerken(String stamm, long zeit) {
        return aendereOffen(stamm, zeit, true);
    }

    /** Frage erledigt (behalten oder „Nein danke“). */
    public static synchronized boolean frageErledigt(String stamm) {
        return aendereOffen(stamm, 0, false);
    }

    private static boolean aendereOffen(String stamm, long zeit, boolean hinzu) {
        if (!at.greenchili.surfmate.storage.Aufbewahrung.gueltigerStamm(stamm)) return false;
        try {
            JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
            return at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(
                    OFFEN_ABSCHNITT, mitOffen(root, stamm, zeit, hinzu));
        } catch (Throwable t) {
            return false;
        }
    }
}
