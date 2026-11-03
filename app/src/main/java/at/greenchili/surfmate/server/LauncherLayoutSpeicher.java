package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 0.1.38 Launcher-Layout im Kern (LAUNCHER_LAYOUT_API.md §1): {@code launcher_layout.json} im Daemon-Ordner ist die
 * Quelle der Wahrheit für Launcher-APK, Web, Handy und Cockpit.
 *
 * <ul>
 *   <li>Atomar geschrieben (Temp-Datei, {@code fsync}, rename), höchstens {@link #MAX_BYTES}.</li>
 *   <li>Neueste Änderung gewinnt: Wer schreibt, schickt {@code basis} (das {@code geaendert}, das er kannte). Ist der
 *       Kern-Stand neuer, kommt 409 mit dem aktuellen Stand; keine Zusammenführung einzelner Kacheln.</li>
 *   <li>Ohne Datei gilt der Standard-Aufbau ({@code standard: true}, {@code geaendert: 0}); niemand muss etwas
 *       einrichten. „Auf Standard zurücksetzen“ schreibt die Datei mit {@code standard: true}, damit ein Launcher mit
 *       altem lokalem Layout es danach nicht wieder hochlädt.</li>
 * </ul>
 */
public final class LauncherLayoutSpeicher {

    public static final String DATEI = "launcher_layout.json";
    public static final int MAX_BYTES = 64 * 1024;
    public static final String QUELLE_AUTO = "auto", QUELLE_WEB = "web", QUELLE_HANDY = "handy",
            QUELLE_COCKPIT = "cockpit";

    private static final Object SPERRE = new Object();

    private LauncherLayoutSpeicher() {}

    /** Antwort eines Schreibversuchs: HTTP-Status und Körper. */
    public static final class Antwort {
        public final int status;
        public final JSONObject koerper;

        Antwort(int status, JSONObject koerper) {
            this.status = status;
            this.koerper = koerper;
        }
    }

    /** {@code {layout, geaendert, quelle, standard}}; ohne (lesbare) Datei der Standard-Aufbau. */
    public static JSONObject lesen(File datei, String startAnsicht) {
        synchronized (SPERRE) {
            JSONObject s = gespeichert(datei);
            try {
                if (s == null) {
                    return new JSONObject().put("layout", LauncherLayoutPruefung.standard(startAnsicht))
                            .put("geaendert", 0L).put("quelle", QUELLE_AUTO).put("standard", true);
                }
                return s;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Nur der Zeitstempel (für {@code summary.launcherLayout}); 0 ohne Datei. */
    public static long geaendert(File datei) {
        synchronized (SPERRE) {
            JSONObject s = gespeichert(datei);
            return s == null ? 0L : s.optLong("geaendert", 0L);
        }
    }

    /**
     * PUT {@code {layout, basis}}. 200 {@code {ok, geaendert, bereinigt}}, 409 {@code {ok:false, grund:"neuer",
     * layout, geaendert, quelle}}, 400 {@code {grund}}, 413 zu groß, 500 nicht gespeichert.
     */
    public static Antwort schreiben(File datei, String body, Set<String> installiert, String quelle, long jetzt) {
        try {
            if (body != null && body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return fehler(413, "zu_gross");
            JSONObject b;
            try {
                b = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                return fehler(400, "kein_json");
            }
            JSONObject roh = b.optJSONObject("layout");
            if (roh == null) return fehler(400, "layout_fehlt");
            if (!b.has("basis")) return fehler(400, "basis_fehlt");
            long basis = b.optLong("basis", -1L);
            LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh, installiert);
            if (e == null) return fehler(400, "format");
            synchronized (SPERRE) {
                JSONObject alt = gespeichert(datei);
                long altZeit = alt == null ? 0L : alt.optLong("geaendert", 0L);
                if (altZeit > basis) {
                    return new Antwort(409, new JSONObject().put("ok", false).put("grund", "neuer")
                            .put("layout", alt.getJSONObject("layout")).put("geaendert", altZeit)
                            .put("quelle", alt.optString("quelle", QUELLE_AUTO))
                            .put("standard", alt.optBoolean("standard", false)));
                }
                long neu = Math.max(jetzt, altZeit + 1);
                JSONObject stand = new JSONObject().put("layout", e.layout).put("geaendert", neu)
                        .put("quelle", quelle(quelle)).put("standard", false);
                if (!speichern(datei, stand)) return fehler(500, "nicht_gespeichert");
                return new Antwort(200, new JSONObject().put("ok", true).put("geaendert", neu)
                        .put("bereinigt", e.bereinigt).put("layout", e.layout));
            }
        } catch (Exception ex) {
            return fehler(500, "intern");
        }
    }

    /** POST {@code layout/standard}: zurück auf den Standard-Aufbau (gilt sofort, ohne {@code basis}). */
    public static Antwort standard(File datei, String startAnsicht, String quelle, long jetzt) {
        try {
            synchronized (SPERRE) {
                JSONObject alt = gespeichert(datei);
                long neu = Math.max(jetzt, (alt == null ? 0L : alt.optLong("geaendert", 0L)) + 1);
                JSONObject layout = LauncherLayoutPruefung.standard(startAnsicht);
                JSONObject stand = new JSONObject().put("layout", layout).put("geaendert", neu)
                        .put("quelle", quelle(quelle)).put("standard", true);
                if (!speichern(datei, stand)) return fehler(500, "nicht_gespeichert");
                return new Antwort(200, new JSONObject().put("ok", true).put("geaendert", neu).put("layout", layout)
                        .put("standard", true));
            }
        } catch (Exception ex) {
            return fehler(500, "intern");
        }
    }

    static String quelle(String q) {
        return QUELLE_WEB.equals(q) || QUELLE_HANDY.equals(q) || QUELLE_COCKPIT.equals(q) ? q : QUELLE_AUTO;
    }

    private static Antwort fehler(int status, String grund) {
        try {
            return new Antwort(status, new JSONObject().put("ok", false).put("grund", grund));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Gespeicherter, gültiger Stand oder null (fehlt, zu groß, kaputt: dann gilt der Standard). */
    private static JSONObject gespeichert(File datei) {
        try {
            if (datei == null || !datei.isFile() || datei.length() <= 0 || datei.length() > MAX_BYTES) return null;
            byte[] b = java.nio.file.Files.readAllBytes(datei.toPath());
            JSONObject s = new JSONObject(new String(b, StandardCharsets.UTF_8));
            JSONObject layout = s.optJSONObject("layout");
            if (layout == null || LauncherLayoutPruefung.pruefen(layout, null) == null) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean speichern(File datei, JSONObject stand) {
        File ordner = datei.getAbsoluteFile().getParentFile();
        File tmp = new File(ordner, datei.getName() + ".tmp");
        try {
            byte[] b = stand.toString().getBytes(StandardCharsets.UTF_8);
            if (b.length > MAX_BYTES) return false;
            if (ordner != null && !ordner.isDirectory() && !ordner.mkdirs()) return false;
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(b);
                o.getFD().sync();
            }
            java.nio.file.Files.move(tmp.toPath(), datei.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }
}
