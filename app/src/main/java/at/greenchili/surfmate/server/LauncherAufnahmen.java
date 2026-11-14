package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 0.1.43.4 (Kamera im Launcher): Liste der Aufnahmen für den Launcher, NUR LESEN.
 *
 * <p>Quelle ist der vorhandene Aufnahmen-Index ({@link RecordingsIndex#queryRecordings}); der Launcher bekommt nur
 * Anzeigefelder: Kennung, Kamera, Quelle (Fahrt/Allein), Auslöser (Manuell/Auto, nur wenn bekannt), Zeit, Größe und
 * ob die Datei noch da ist. Keine Pfade, keine Orte, keine Koordinaten, keine Löschen-Wege. Die Dauer steht nicht im
 * Index und wird deshalb nicht erfunden. Das Abspielen geschieht in Panoptes (Fahrsperre dort und im Launcher).
 *
 * <p>Der Weg ist nur für die Launcher-APK mit Panoptes-Signatur ({@code launcherDarf}) und nur GET. Rein, im Unit-Test belegt.
 */
public final class LauncherAufnahmen {

    private LauncherAufnahmen() {}

    public static final String PFAD = "/api/launcher/v1/aufnahmen";
    /** Höchstzahl der gelieferten Zeilen. */
    public static final int MAX_ZEILEN = 100;
    private static final long TAG_MS = 24L * 3600_000L;

    /** Zeitfenster-Kennung (alle, heute, 7, 30); unbekannt = alle. */
    public static String zeit(String roh) {
        if ("heute".equals(roh) || "7".equals(roh) || "30".equals(roh)) return roh;
        return "alle";
    }

    /** Sortierung (neu, alt); unbekannt = neu. */
    public static String sortierung(String roh) {
        return "alt".equals(roh) ? "alt" : "neu";
    }

    /** Untergrenze der Zeit in ms oder null (alle). „heute“ = ab Mitternacht {@code mitternachtMs}. */
    public static Long vonMs(String zeit, long jetztMs, long mitternachtMs) {
        if ("heute".equals(zeit)) return mitternachtMs;
        if ("7".equals(zeit)) return jetztMs - 7 * TAG_MS;
        if ("30".equals(zeit)) return jetztMs - 30 * TAG_MS;
        return null;
    }

    /** „fahrt“ (unterwegs) oder „allein“ (geparkt, Wächter/Nähe). */
    public static String quelleArt(String typ) {
        return "sentry".equals(typ) || "proximity".equals(typ) ? "allein" : "fahrt";
    }

    /** „manuell“, „auto“ oder null, wenn die Art nicht belegt ist. */
    public static String ausloeser(String typ) {
        if ("replay".equals(typ)) return "manuell";
        if ("ereignis".equals(typ) || "sentry".equals(typ) || "proximity".equals(typ)) return "auto";
        return null;
    }

    /** Ein Index-Eintrag als schmale Anzeigezeile. */
    public static JSONObject zeile(JSONObject r) throws org.json.JSONException {
        String typ = r.optString("type", "");
        JSONObject z = new JSONObject();
        z.put("id", r.optString("id"));
        z.put("kamera", r.optInt("cameraId", 0));
        z.put("quelle", quelleArt(typ));
        String a = ausloeser(typ);
        if (a != null) z.put("ausloeser", a);
        z.put("zeit", r.optLong("timestamp", 0L));
        z.put("groesse", r.optLong("size", 0L));
        z.put("da", r.optBoolean("available", true));
        return z;
    }

    /** Antwort aus Index-Zeilen: sortiert, auf {@link #MAX_ZEILEN} begrenzt, mit Zählern. */
    public static JSONObject antwort(List<JSONObject> reihen, String sort) throws org.json.JSONException {
        List<JSONObject> l = new ArrayList<>();
        for (JSONObject r : reihen) {
            if (r == null || r.optString("id").isEmpty()) continue;
            l.add(zeile(r));
        }
        final boolean alt = "alt".equals(sort);
        Collections.sort(l, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject x, JSONObject y) {
                int c = Long.compare(x.optLong("zeit"), y.optLong("zeit"));
                return alt ? c : -c;
            }
        });
        int gesamt = l.size();
        JSONArray a = new JSONArray();
        for (int i = 0; i < l.size() && i < MAX_ZEILEN; i++) a.put(l.get(i));
        return new JSONObject().put("ok", true).put("gespeichert", gesamt).put("angezeigt", a.length()).put("aufnahmen", a);
    }

    /** Aufnahme-Kennung: nur harmlose Zeichen (kommt aus dem Netz, nie ein Pfad). */
    public static boolean idGueltig(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,120}");
    }

    /**
     * Quelle für den QR-Download einer Aufnahme aufs Handy ({@code DateiQrApiHandler.holenQuelle}, Art "aufnahme").
     * Nur eine Kennung aus dem Index, nie ein freier Pfad; nur wenn die Datei noch da ist. null = unbekannt.
     */
    public static at.greenchili.surfmate.storage.DateiQr.Quelle quelle(String id) {
        if (!idGueltig(id)) return null;
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (idx == null || !idx.isAvailable()) return null;
            RecordingsIndex.RecordingRef ref = idx.resolveById(id);
            if (ref == null) return null;
            final java.io.File f = ref.file();
            if (!f.isFile()) return null;
            return new at.greenchili.surfmate.storage.DateiQr.Quelle() {
                @Override public String name() { return f.getName(); }
                @Override public long laenge() { return f.length(); }
                @Override public java.io.InputStream oeffnen() throws java.io.IOException { return new java.io.FileInputStream(f); }
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** Antwort für den Weg: liest den Index (nicht verfügbar = ok:false, Launcher zeigt „nicht lesbar“). */
    public static JSONObject lesen(String zeitRoh, String sortRoh, long jetztMs, long mitternachtMs) {
        try {
            String z = zeit(zeitRoh);
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (idx == null || !idx.isAvailable()) return new JSONObject().put("ok", false).put("grund", "index_nicht_bereit");
            RecordingsIndex.Filter f = new RecordingsIndex.Filter();
            f.fromMs = vonMs(z, jetztMs, mitternachtMs);
            List<JSONObject> reihen = idx.queryRecordings(f, 500, 0);
            return antwort(reihen, sortierung(sortRoh));
        } catch (Throwable t) {
            try { return new JSONObject().put("ok", false).put("grund", "nicht_lesbar"); } catch (Exception e) { return new JSONObject(); }
        }
    }
}
