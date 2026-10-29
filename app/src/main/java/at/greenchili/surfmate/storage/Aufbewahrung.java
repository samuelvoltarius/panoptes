package at.greenchili.surfmate.storage;

import at.greenchili.surfmate.config.UnifiedConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Aufbewahrung der Clips (Koordination 21.10.2026, RECHTSLAGE_WAECHTER Nr. 7, Pflichttext B):
 * Wächter-, Fahrt- und Annäherungs-Clips in den Panoptes-Ordnern werden nach
 * {@code surveillance.clipRetentionHours} gelöscht (empfohlen 72 h, beim Einrichten gesetzt;
 * nicht gesetzt = Alt-Installation = keine Löschung nach Alter), außer der Nutzer hat sie
 * „behalten“. Behalten gilt {@code surveillance.keepDays} (Standard 30 Tage); danach wird nicht
 * gelöscht, sondern nachgefragt ({@code rueckfrage} in der Liste, Text guardLegal.keep.reminder).
 * Behaltene Clips überspringt auch der Größen-Reaper; nur die Notfall-Löschung bei vollem
 * Datenträger darf sie noch treffen.
 *
 * <p>Fremde Ordner (z. B. {@code Overdrive/} am Stick) werden nie angefasst.
 */
public final class Aufbewahrung {

    public static final int STUNDEN_STANDARD = 72;
    public static final int BEHALTEN_TAGE_STANDARD = 30;
    public static final int MAX_BEHALTEN = 200;
    static final long TAKT_MS = 10L * 60 * 1000;
    static final int MAX_JE_TAKT = 100;
    /** Kategorien des StorageManagers; „recordings“ umfasst auch die Annäherungs-Clips. */
    static final String[] KATEGORIEN = {"surveillance", "recordings"};

    private static final String ABSCHNITT = "aufbewahrung";

    private Aufbewahrung() {}

    // ---------------------------------------------------------------- reine Logik

    /** Stamm eines Clips: Dateiname ohne Endung ({@code event_20261021_021120}). */
    public static String stamm(String name) {
        if (name == null) return "";
        int p = name.indexOf('.');
        return p < 0 ? name : name.substring(0, p);
    }

    /** Gültiger Stamm für die API (nur Buchstaben, Ziffern, _ und -; keine Pfade). */
    public static boolean gueltigerStamm(String s) {
        return s != null && s.matches("^[A-Za-z0-9_-]{1,80}$");
    }

    /** Liegt die Datei in einem fremden Ordner (nicht von Panoptes verwaltet)? */
    static boolean fremderOrdner(File f) {
        String p = f.getAbsolutePath().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return p.contains("/overdrive/");
    }

    /**
     * Fällige Clips, älteste zuerst: älter als {@code maxAgeMs}, nicht behalten, nicht fremd.
     */
    static List<File> faellig(List<File> clips, long now, long maxAgeMs, Map<String, Long> behalten) {
        List<File> out = new ArrayList<>();
        if (maxAgeMs <= 0) return out;
        for (File f : clips) {
            long lm = f.lastModified();
            if (lm <= 0 || now - lm <= maxAgeMs) continue;
            if (behalten.containsKey(stamm(f.getName()))) continue;
            if (fremderOrdner(f)) continue;
            out.add(f);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return out;
    }

    static List<File> faellig(List<File> clips, long now, long maxAgeMs) {
        return faellig(clips, now, maxAgeMs, behaltenMap());
    }

    /**
     * Wirksame Aufbewahrung in ms; 0 = keine Löschung nach Alter. Nicht gesetzt (Alt-Installation)
     * = 0, also exakt wie bisher nur nach Größe (Koordination 21.10.2026: Löschen ist unumkehrbar).
     * Neue Installationen bekommen {@link #STUNDEN_STANDARD} beim Einrichten gesetzt.
     */
    public static long maxAlterMs(JSONObject root) {
        JSONObject s = root == null ? null : root.optJSONObject("surveillance");
        if (s == null || !s.has("clipRetentionHours")) return 0;
        int h = s.optInt("clipRetentionHours", 0);
        if (h <= 0) return 0;
        return Math.min(h, 24 * 90) * 3_600_000L;
    }

    public static int behaltenTage(JSONObject root) {
        JSONObject s = root == null ? null : root.optJSONObject("surveillance");
        int d = s == null ? BEHALTEN_TAGE_STANDARD : s.optInt("keepDays", BEHALTEN_TAGE_STANDARD);
        return Math.max(1, Math.min(90, d));
    }

    static Map<String, Long> leseBehalten(JSONObject root) {
        Map<String, Long> m = new HashMap<>();
        JSONObject a = root == null ? null : root.optJSONObject(ABSCHNITT);
        JSONObject b = a == null ? null : a.optJSONObject("behalten");
        if (b == null) return m;
        Iterator<String> it = b.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (gueltigerStamm(k)) m.put(k, b.optLong(k, 0));
        }
        return m;
    }

    /** Liste für die Oberfläche. */
    public static JSONArray liste(JSONObject root, long now) {
        int tage = behaltenTage(root);
        JSONArray out = new JSONArray();
        List<Map.Entry<String, Long>> e = new ArrayList<>(leseBehalten(root).entrySet());
        Collections.sort(e, (x, y) -> Long.compare(x.getValue(), y.getValue()));
        for (Map.Entry<String, Long> x : e) {
            JSONObject o = new JSONObject();
            try {
                long bis = x.getValue() + tage * 86_400_000L;
                o.put("datei", x.getKey());
                o.put("seit", x.getValue());
                o.put("bis", bis);
                o.put("rueckfrage", now >= bis);
            } catch (Exception ignored) {
            }
            out.put(o);
        }
        return out;
    }

    /** Neuer Abschnitt mit {@code stamm} behalten (seit now) bzw. entfernt (now &lt; 0). */
    static JSONObject mitAenderung(JSONObject root, String stamm, long now) throws Exception {
        Map<String, Long> m = leseBehalten(root);
        if (now < 0) m.remove(stamm);
        else m.put(stamm, now);
        JSONObject b = new JSONObject();
        for (Map.Entry<String, Long> x : m.entrySet()) b.put(x.getKey(), x.getValue());
        JSONObject a = new JSONObject();
        a.put("behalten", b);
        return a;
    }

    // ---------------------------------------------------------------- Laufzeit

    private static volatile Map<String, Long> cache = Collections.emptyMap();
    private static volatile long cacheMtime = Long.MIN_VALUE;

    private static Map<String, Long> behaltenMap() {
        long mt;
        try {
            mt = UnifiedConfigManager.getLastModified();
        } catch (Throwable t) {
            return cache;
        }
        if (mt != cacheMtime) {
            try {
                cache = leseBehalten(UnifiedConfigManager.loadConfig());
                cacheMtime = mt;
            } catch (Throwable ignored) {
            }
        }
        return cache;
    }

    /** Für den Größen-Reaper: wird dieser Clip behalten? */
    public static boolean istBehalten(String fileName) {
        Map<String, Long> m = behaltenMap();
        return !m.isEmpty() && m.containsKey(stamm(fileName));
    }

    /** Behalten (auch „Weiter behalten“ nach der Rückfrage: setzt die Frist neu). */
    public static synchronized boolean behalten(String stamm, long now) {
        if (!gueltigerStamm(stamm)) return false;
        try {
            JSONObject root = UnifiedConfigManager.forceReload();
            Map<String, Long> m = leseBehalten(root);
            if (!m.containsKey(stamm) && m.size() >= MAX_BEHALTEN) return false;
            boolean ok = UnifiedConfigManager.updateSection(ABSCHNITT, mitAenderung(root, stamm, now));
            cacheMtime = Long.MIN_VALUE;
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Nicht mehr behalten; der Clip fällt dann wieder unter die Aufbewahrungsfrist. */
    public static synchronized boolean freigeben(String stamm) {
        if (!gueltigerStamm(stamm)) return false;
        try {
            JSONObject root = UnifiedConfigManager.forceReload();
            if (!leseBehalten(root).containsKey(stamm)) return true;
            boolean ok = UnifiedConfigManager.updateSection(ABSCHNITT, mitAenderung(root, stamm, -1));
            cacheMtime = Long.MIN_VALUE;
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ScheduledExecutorService exec;

    /** Takt alle 10 min (erster Lauf nach 2 min). */
    public static synchronized void start() {
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Aufbewahrung");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        exec.scheduleWithFixedDelay(Aufbewahrung::takt, 2 * 60_000L, TAKT_MS, TimeUnit.MILLISECONDS);
    }

    static void takt() {
        // Ereignis-Clips: eigener Ordner, eigene Grenze – auch ohne gesetzte Frist pflegen.
        at.greenchili.surfmate.recording.EreignisAblage.pflege();
        try {
            long maxAge = maxAlterMs(UnifiedConfigManager.loadConfig());
            if (maxAge <= 0) return;
            StorageManager sm = StorageManager.getInstance();
            for (String k : KATEGORIEN) sm.reapByAge(k, maxAge, MAX_JE_TAKT);
        } catch (Throwable t) {
            android.util.Log.w("Aufbewahrung", "takt: " + t.getMessage());
        }
    }
}
