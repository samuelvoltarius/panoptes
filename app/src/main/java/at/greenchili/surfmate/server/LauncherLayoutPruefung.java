package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 0.1.38 Launcher-Layout im Kern (LAUNCHER_LAYOUT_API.md §4): prüft ein Layout der Launcher-APK genau wie
 * {@code Raster.fromJson} in {@code :panoptes-launcher} und baut den Standard-Aufbau wie {@code Raster.standard}.
 * Rein, ohne Android, im Unit-Test belegt.
 *
 * <p>Falsches {@code format}/{@code version} = ungültig (null). Alles andere wird bereinigt statt abgelehnt:
 * unbekannte Kacheltypen, Größen außerhalb der Liste, Überlappungen, Ränder, doppelte Widgets und Pakete, die am
 * Gerät nicht installiert und startbar sind, fallen weg; {@link Ergebnis#bereinigt} zählt sie. Android Auto und
 * CarPlay dürfen im Dock stehen, auch wenn sie fehlen (der Launcher zeigt sie dann nicht). Das Haus (Rückweg zum
 * BYD-Startbildschirm) ist nie Teil des Layouts.
 */
public final class LauncherLayoutPruefung {

    public static final String FORMAT = "panoptes-launcher-layout";
    public static final int VERSION = 1;
    public static final int SPALTEN = 6, ZEILEN = 3, MAX_SEITEN = 5, MAX_DOCK = 8, MAX_SCHNELL = 6,
            MAX_ORDNER_APPS = 24, MAX_NAME = 24, MAX_NEBEN_ANDERS = 64;
    public static final String PANOPTES = "at.greenchili.surfmate";
    public static final List<String> PROJEKTION = Collections.unmodifiableList(
            Arrays.asList("com.byd.androidauto", "com.byd.carplay.ui"));
    public static final List<String> HINTERGRUENDE = Collections.unmodifiableList(
            Arrays.asList("panoptes", "cockpit", "bild", "video", "eigen"));
    /** 0.1.42 (L48): eigene, im Launcher per Code gezeichnete Motive „motiv-1“ … „motiv-10“ (Launcher {@code Motive}). */
    public static final int MOTIVE = 10;

    static boolean hintergrundGueltig(String hg) {
        if (hg == null) return false;
        if (HINTERGRUENDE.contains(hg)) return true;
        if (!hg.startsWith("motiv-")) return false;
        try {
            String n = hg.substring(6);
            int i = Integer.parseInt(n);
            return i >= 1 && i <= MOTIVE && n.equals(String.valueOf(i));
        } catch (NumberFormatException e) { return false; }
    }
    /** 0.1.40 (L2) eigenes Design wie {@code Raster.DESIGNS}/{@code ABSTAENDE}/{@code SPALTE_TEILE} im Launcher. */
    public static final List<String> DESIGNS = Collections.unmodifiableList(
            Arrays.asList("auto", "dunkel", "hell", "kontrast", "nacht"));
    public static final List<String> ABSTAENDE = Collections.unmodifiableList(Arrays.asList("eng", "normal", "weit"));
    public static final List<String> SPALTE_TEILE = Collections.unmodifiableList(
            Arrays.asList("akku", "reichweite", "gang", "waechter", "musik", "uhr", "kamera"));
    public static final List<String> SPALTE_STANDARD = Collections.unmodifiableList(
            Arrays.asList("akku", "reichweite", "gang", "waechter", "musik", "kamera"));
    private static final Pattern AKZENT = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    public static final List<String> KACHELN = Collections.unmodifiableList(
            Arrays.asList("fahrzeug", "akku", "navi", "musik", "waechter", "uhr", "schloss", "laden", "kamera",
                    "projektion",     // 0.1.41 (CP): CarPlay / Android Auto
                    "reifen", "fahrt", "tempo",   // 0.1.41 (LK): Reifen, letzte Fahrt, Tempo (nur Kern-Daten)
                    "geraetewidget")); // 0.1.41 (LO): Android-Widget (wid = gebundene Nummer), mehrfach erlaubt
    /** Widget-Größen (Breite × Höhe in Zellen) wie {@code Raster.GROESSEN}. */
    static final int[][] GROESSEN = {{1, 1}, {2, 1}, {2, 2}, {2, 3}, {3, 2}, {3, 3}, {4, 2}, {4, 3}, {6, 3}};

    private static final Pattern ID = Pattern.compile("^[a-z][0-9]{1,6}$");
    private static final Pattern PAKET = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

    private LauncherLayoutPruefung() {}

    /** Geprüftes Layout und Zahl der weggefallenen Einträge. */
    public static final class Ergebnis {
        public final JSONObject layout;
        public final int bereinigt;

        Ergebnis(JSONObject layout, int bereinigt) {
            this.layout = layout;
            this.bereinigt = bereinigt;
        }
    }

    /** Android-Paketname wie {@code Raster.gueltigesPaket}. */
    public static boolean gueltigesPaket(String p) {
        return p != null && p.length() >= 3 && p.length() <= 200 && p.indexOf('.') >= 0 && PAKET.matcher(p).matches();
    }

    /** Ordnername: ohne Steuerzeichen, getrimmt, höchstens 24 Zeichen; leer = null. */
    static String name(String roh) {
        if (roh == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roh.length(); i++) {
            char c = roh.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String n = sb.toString().trim();
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME);
        n = n.trim();
        return n.isEmpty() ? null : n;
    }

    /**
     * Prüft und bereinigt. {@code installiert} = Pakete aus {@code layout/apps}; null oder leer heißt „Liste
     * unbekannt“ (dann nur die Form der Paketnamen, damit ein Fehler im PackageManager kein Layout leert).
     */
    public static Ergebnis pruefen(JSONObject root, Set<String> installiert) {
        if (root == null || !FORMAT.equals(root.optString("format"))) return null;
        int v = root.optInt("version", -1);
        if (v < 1 || v > VERSION) return null;
        boolean liste = installiert != null && !installiert.isEmpty();
        int[] weg = {0};
        try {
            Set<String> ids = new HashSet<>();
            Set<String> widgets = new HashSet<>();
            JSONArray seitenRoh = root.optJSONArray("seiten");
            if (seitenRoh == null) seitenRoh = new JSONArray();
            if (seitenRoh.length() > MAX_SEITEN) weg[0] += seitenRoh.length() - MAX_SEITEN;
            JSONArray seiten = new JSONArray();
            int maxId = 0;
            for (int i = 0; i < Math.min(seitenRoh.length(), MAX_SEITEN); i++) {
                JSONArray a = seitenRoh.optJSONArray(i);
                if (a == null) { weg[0]++; continue; }
                List<int[]> belegt = new ArrayList<>();
                JSONArray s = new JSONArray();
                for (int k = 0; k < a.length(); k++) {
                    JSONObject e = element(a.optJSONObject(k), installiert, liste, weg);
                    if (e == null) { weg[0]++; continue; }
                    String id = e.getString("id");
                    String kachel = e.optString("kachel", null);
                    // Android-Widgets dürfen mehrfach vorkommen, je gebundener Nummer einmal
                    if ("geraetewidget".equals(kachel)) kachel = kachel + ":" + e.optInt("wid", 0);
                    int[] r = {e.getInt("x"), e.getInt("y"), e.getInt("b"), e.getInt("h")};
                    if (ids.contains(id) || (kachel != null && widgets.contains(kachel)) || !passt(belegt, r)) {
                        weg[0]++;
                        continue;
                    }
                    ids.add(id);
                    if (kachel != null) widgets.add(kachel);
                    belegt.add(r);
                    s.put(e);
                    try { maxId = Math.max(maxId, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) { }
                }
                seiten.put(s);
            }
            if (seiten.length() == 0) seiten.put(new JSONArray());
            JSONArray dock = pakete(root.optJSONArray("dock"), MAX_DOCK, installiert, liste, true, weg);
            JSONArray schnell = pakete(root.optJSONArray("schnell"), MAX_SCHNELL, installiert, liste, false, weg);
            JSONArray neben = pakete(root.optJSONArray("nebenAnders"), MAX_NEBEN_ANDERS, installiert, liste, false, weg);
            String hg = root.optString("hintergrund", "panoptes");
            if (!hintergrundGueltig(hg)) { hg = "panoptes"; weg[0]++; }
            JSONObject o = new JSONObject();
            o.put("format", FORMAT).put("version", VERSION).put("seiten", seiten).put("dock", dock)
                    .put("hintergrund", hg).put("zaehler", Math.max(Math.max(0, root.optInt("zaehler", 0)), maxId))
                    .put("schnell", schnell)
                    .put("zeigeStatus", root.optBoolean("zeigeStatus", true))
                    .put("zeigeReiter", root.optBoolean("zeigeReiter", true))
                    .put("zeigeKlima", root.optBoolean("zeigeKlima", true))
                    .put("zeige3d", root.optBoolean("zeige3d", true))
                    .put("nebeneinander", root.optBoolean("nebeneinander", false))
                    .put("nebenAnders", neben);
            // 0.1.40 (L2): Design durchreichen (geprüft), sonst ginge es beim Abgleich mit dem Kern verloren
            String design = root.optString("design", "auto");
            String akzent = root.optString("akzent", "");
            String abstand = root.optString("abstand", "normal");
            o.put("design", DESIGNS.contains(design) ? design : "auto")
                    .put("akzent", AKZENT.matcher(akzent).matches() ? akzent.toUpperCase(java.util.Locale.ROOT) : "")
                    .put("abstand", ABSTAENDE.contains(abstand) ? abstand : "normal");
            JSONArray sp = root.optJSONArray("spalte");
            JSONArray spalte = new JSONArray();
            for (String t : (sp == null ? SPALTE_STANDARD : SPALTE_TEILE)) {
                if (sp == null) { spalte.put(t); continue; }
                for (int i = 0; i < sp.length(); i++) if (t.equals(sp.optString(i))) { spalte.put(t); break; }
            }
            o.put("spalte", spalte);
            return new Ergebnis(o, weg[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean passt(List<int[]> belegt, int[] r) {
        int x = r[0], y = r[1], b = r[2], h = r[3];
        if (b < 1 || h < 1 || x < 0 || y < 0 || x + b > SPALTEN || y + h > ZEILEN) return false;
        for (int[] o : belegt) {
            if (x < o[0] + o[2] && o[0] < x + b && y < o[1] + o[3] && o[1] < y + h) return false;
        }
        return true;
    }

    private static boolean installiert(String p, Set<String> installiert, boolean liste) {
        return gueltigesPaket(p) && (!liste || installiert.contains(p));
    }

    private static JSONArray pakete(JSONArray a, int max, Set<String> installiert, boolean liste, boolean dock,
                                    int[] weg) {
        JSONArray out = new JSONArray();
        Set<String> drin = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            String p = a.optString(i, null);
            boolean ok = dock && PROJEKTION.contains(p) || installiert(p, installiert, liste);
            if (!ok || drin.contains(p) || out.length() >= max) { weg[0]++; continue; }
            drin.add(p);
            out.put(p);
        }
        return out;
    }

    private static JSONObject element(JSONObject o, Set<String> installiert, boolean liste, int[] weg) throws Exception {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (!ID.matcher(id).matches()) return null;
        String typ = o.optString("typ", "");
        int x = o.optInt("x", -1), y = o.optInt("y", -1), b = o.optInt("b", 1), h = o.optInt("h", 1);
        JSONObject e = new JSONObject().put("id", id).put("typ", typ).put("x", x).put("y", y);
        switch (typ) {
            case "widget": {
                String k = o.optString("kachel", "");
                if (!KACHELN.contains(k) || !groesse(b, h)) return null;
                if ("geraetewidget".equals(k)) {
                    int wid = o.optInt("wid", 0);
                    if (wid <= 0) return null;
                    return e.put("b", b).put("h", h).put("kachel", k).put("wid", wid);
                }
                return e.put("b", b).put("h", h).put("kachel", k);
            }
            case "app": {
                String p = o.optString("paket", "");
                if (!installiert(p, installiert, liste)) return null;
                return e.put("b", 1).put("h", 1).put("paket", p);
            }
            case "ordner": {
                JSONArray roh = o.optJSONArray("apps");
                if (roh == null) return null;
                JSONArray apps = pakete(roh, MAX_ORDNER_APPS, installiert, liste, false, weg);
                if (apps.length() == 0) return null;
                String n = name(o.optString("name", null));
                return e.put("b", 1).put("h", 1).put("name", n == null ? "Ordner" : n).put("apps", apps);
            }
            default:
                return null;
        }
    }

    private static boolean groesse(int b, int h) {
        for (int[] g : GROESSEN) if (g[0] == b && g[1] == h) return true;
        return false;
    }

    /**
     * Standard-Aufbau wie {@code Raster.standard(startAnsicht)}: Seite 1 großes Fahrzeug-Feld und Musik, Seite 2 die
     * übrigen Kacheln in der Reihenfolge der Startansicht der Weboberfläche; Dock Panoptes, Android Auto, CarPlay.
     */
    public static JSONObject standard(String startAnsicht) {
        try {
            JSONArray eins = new JSONArray().put(w("w1", "fahrzeug", 0, 0, 4, 3)).put(w("w2", "musik", 4, 0, 2, 3));
            JSONArray zwei = new JSONArray();
            String s = startAnsicht == null ? "" : startAnsicht;
            switch (s) {
                case "fahren":
                    zwei.put(w("w3", "navi", 0, 0, 2, 2)).put(w("w4", "akku", 2, 0, 2, 2))
                            .put(w("w5", "uhr", 4, 0, 2, 1)).put(w("w6", "waechter", 4, 1, 2, 1));
                    break;
                case "fahrzeug":
                    zwei.put(w("w3", "akku", 0, 0, 2, 2)).put(w("w4", "waechter", 2, 0, 2, 2))
                            .put(w("w5", "navi", 4, 0, 2, 1)).put(w("w6", "uhr", 4, 1, 2, 1));
                    break;
                case "kompakt":
                    zwei.put(w("w3", "akku", 0, 0, 2, 2)).put(w("w4", "uhr", 2, 0, 2, 1));
                    break;
                default:
                    zwei.put(w("w3", "akku", 0, 0, 2, 2)).put(w("w4", "navi", 2, 0, 2, 1))
                            .put(w("w5", "waechter", 2, 1, 2, 1)).put(w("w6", "uhr", 4, 0, 2, 1));
            }
            JSONArray dock = new JSONArray().put(PANOPTES);
            for (String p : PROJEKTION) dock.put(p);
            return new JSONObject().put("format", FORMAT).put("version", VERSION)
                    .put("seiten", new JSONArray().put(eins).put(zwei)).put("dock", dock)
                    .put("hintergrund", "cockpit".equals(s) ? "cockpit" : "panoptes").put("zaehler", 10)
                    .put("schnell", new JSONArray()).put("zeigeStatus", true).put("zeigeReiter", true)
                    .put("zeigeKlima", true).put("zeige3d", true).put("nebeneinander", false)
                    .put("nebenAnders", new JSONArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject w(String id, String kachel, int x, int y, int b, int h) throws Exception {
        return new JSONObject().put("id", id).put("typ", "widget").put("x", x).put("y", y).put("b", b).put("h", h)
                .put("kachel", kachel);
    }
}
