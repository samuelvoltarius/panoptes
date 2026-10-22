package at.greenchili.surfmate.storage;

import org.json.JSONObject;

import java.io.File;

/**
 * Der sichtbare Ordner der App auf jedem Datenträger (intern, SD-Karte, USB-Stick) – EINE Stelle (0.1.10, Projektinhaber:
 * „die App heißt Panoptes“). Paket- und Java-Namen bleiben {@code at.greenchili.surfmate} (sonst kein Update).
 *
 * <p>Regel je Datenträger (Koordination 22.10.):
 * <ul>
 *   <li>Gibt es {@code <vol>/SurfMate} und noch kein {@code <vol>/Panoptes}: einmal per {@code rename} umbenennen
 *       (gleiches Dateisystem, atomar; nichts kopieren, nichts löschen) und zurücklesen.</li>
 *   <li>Gibt es beide, oder klappt {@code rename} nicht: NICHTS verschieben, weiter {@code SurfMate} nutzen und einen
 *       Eintrag „Noch offen“ setzen.</li>
 * </ul>
 * Daraus folgt für den Pfad: solange {@code SurfMate} existiert, wird er genutzt, sonst {@code Panoptes}.
 */
public final class AppOrdner {

    public static final String NAME = "Panoptes";
    public static final String ALT = "SurfMate";
    /** Wurzel des internen Speichers (Volume, nicht der App-Ordner). */
    public static final String INTERN = "/storage/emulated/0";

    private AppOrdner() {}

    /** Aktueller App-Ordner auf diesem Datenträger (siehe Klassenkommentar). */
    public static File basis(String volumeWurzel) {
        File alt = new File(volumeWurzel, ALT);
        return alt.isDirectory() ? alt : new File(volumeWurzel, NAME);
    }

    /** App-Ordner auf dem internen Speicher. */
    public static File intern() {
        return basis(INTERN);
    }

    /** Unterordner auf dem internen Speicher, z. B. {@code intern("parking")}. */
    public static String intern(String unterordner) {
        return new File(intern(), unterordner).getPath();
    }

    /** Ergebnis einer Umbenennung. */
    public enum Ergebnis { UMBENANNT, NICHTS_ZU_TUN, BEIDE_DA, FEHLGESCHLAGEN }

    /** Schnittstelle zum Dateisystem (Tests ersetzen sie). */
    public interface Fs {
        boolean istOrdner(File f);
        boolean umbenennen(File von, File nach);
        default boolean existiert(File f) { return f.exists(); }
    }

    public static final Fs ECHT = new Fs() {
        @Override public boolean istOrdner(File f) { return f.isDirectory(); }
        @Override public boolean umbenennen(File von, File nach) { return von.renameTo(nach); }
    };

    /** Einmal je Datenträger beim Start (Daemon). Zurücklesen: danach muss Panoptes da und SurfMate weg sein. */
    public static Ergebnis umziehen(String volumeWurzel, Fs fs) {
        File alt = new File(volumeWurzel, ALT);
        File neu = new File(volumeWurzel, NAME);
        if (!fs.istOrdner(alt)) return Ergebnis.NICHTS_ZU_TUN;
        if (fs.istOrdner(neu)) return Ergebnis.BEIDE_DA;
        boolean ok = fs.umbenennen(alt, neu);
        return ok && fs.istOrdner(neu) && !fs.istOrdner(alt) ? Ergebnis.UMBENANNT : Ergebnis.FEHLGESCHLAGEN;
    }

    /**
     * Gespeicherter absoluter Pfad aus der Zeit vor dem Umbenennen ({@code …/SurfMate/…}): liegt die Datei nicht mehr
     * dort, aber unter {@code …/Panoptes/…}, den neuen Pfad liefern. Datenbanken werden dafür nicht umgeschrieben.
     */
    public static String aufloesen(String pfad) {
        return aufloesen(pfad, ECHT);
    }

    static String aufloesen(String pfad, Fs fs) {
        if (pfad == null) return null;
        String seg = "/" + ALT + "/";
        String norm = pfad.replace(File.separatorChar, '/');   // Android: „/“; trennzeichen-unabhängig für Tests
        int i = norm.indexOf(seg);
        if (i < 0) return pfad;
        if (fs.existiert(new File(pfad))) return pfad;
        String neu = norm.substring(0, i) + "/" + NAME + "/" + norm.substring(i + seg.length());
        return fs.existiert(new File(neu)) ? neu : pfad;
    }

    /**
     * Anzeige für die Web-Seiten (statt fest „SurfMate/…“): Teil ab dem App-Ordner, z. B. {@code Panoptes/recordings}.
     * Pfade außerhalb des App-Ordners kommen unverändert zurück.
     */
    public static String anzeige(String absPfad) {
        if (absPfad == null) return null;
        String p = absPfad.replace(File.separatorChar, '/');
        for (String n : new String[] {NAME, ALT}) {
            int i = p.indexOf("/" + n + "/");
            if (i >= 0) return p.substring(i + 1);
            if (p.endsWith("/" + n)) return n;
        }
        return absPfad;
    }

    /** Liegt der Pfad im alten App-Ordner ({@code …/SurfMate/…})? Trennzeichen-unabhängig. */
    public static boolean imAltenOrdner(String pfad) {
        return pfad != null && pfad.replace(File.separatorChar, '/').contains("/" + ALT + "/");
    }

    // ---------------------------------------------------------------- „Noch offen“

    /** Ergebnisse je Datenträger für „Noch offen“ (nur BEIDE_DA/FEHLGESCHLAGEN), gesetzt vom Daemon. */
    private static final JSONObject offen = new JSONObject();

    public static synchronized void merke(String volumeWurzel, Ergebnis e) {
        try {
            if (e == Ergebnis.BEIDE_DA || e == Ergebnis.FEHLGESCHLAGEN) offen.put(volumeWurzel, e.name().toLowerCase());
            else offen.remove(volumeWurzel);
        } catch (Exception ignored) {
        }
    }

    /** Für GET /api/einrichtung noch_offen: {id:"ordner_umbenennen", volume, grund}. */
    public static synchronized org.json.JSONArray offen() {
        org.json.JSONArray a = new org.json.JSONArray();
        java.util.Iterator<String> it = offen.keys();
        while (it.hasNext()) {
            String v = it.next();
            try {
                a.put(new JSONObject().put("id", "ordner_umbenennen").put("volume", v)
                        .put("grund", offen.getString(v)).put("textKey", "einr.hinweis.ordner_umbenennen")
                        .put("alt", ALT).put("neu", NAME));
            } catch (Exception ignored) {
            }
        }
        return a;
    }
}
