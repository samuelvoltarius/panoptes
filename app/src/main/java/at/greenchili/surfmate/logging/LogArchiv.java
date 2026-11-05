package at.greenchili.surfmate.logging;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.36 Log-Archiv (03.11.: „logs dürfen bitte auch bei updates nicht überschrieben werden“).
 *
 * <p>Vorher begann {@code cam_daemon.log} nach jedem Daemon-Neustart und jedem Update neu: Das Update löschte alle
 * {@code *.log} ({@code UpdateLifecycle.hardResetDaemons}), die Wächter-Skripte kürzten das Log auf null, sobald es
 * über 5 MB lag, und einige Startwege öffneten es mit {@code >}. Jetzt wird das bisherige Log vor jedem Start
 * <b>umbenannt</b> und in den Ordner {@link #ORDNER} neben den Daemon-Dateien gelegt (übersteht Neustart und Update):
 * {@code <art>.<JJJJMMTT-HHMMSS>-<pid>-<n>.log}, Art = Dateiname bis zum ersten Punkt (z. B. {@code cam_daemon}).
 *
 * <p>Grenzen je Art: höchstens {@link #STANDARD_TAGE} Tage und {@link #STANDARD_MB} MB (einstellbar unter
 * {@code logs.tage} / {@code logs.mb}), dazu höchstens {@link #MAX_DATEIEN} Dateien (Absturzschleife). Älteste fallen
 * zuerst. Bei wenig freiem Platz wird enger begrenzt ({@link #wirksam}). Dieselben Regeln stehen als Shell-Funktionen
 * in {@link #shellFunktionen()}, weil die Wächter-Skripte ohne Java laufen; die Java-Seite schreibt die Grenzen in die
 * Datei {@link #GRENZEN_DATEI}, damit beide gleich zählen.
 */
public final class LogArchiv {

    public static final String ORDNER = "logarchiv";
    public static final String GRENZEN_DATEI = "grenzen";
    public static final String ABSCHNITT = "logs";

    public static final int STANDARD_TAGE = 7;
    public static final int STANDARD_MB = 50;
    public static final int MIN_TAGE = 1, MAX_TAGE = 30;
    public static final int MIN_MB = 5, MAX_MB = 500;
    /** Schutz gegen eine Absturzschleife, die je Neustart eine kleine Datei anlegt. */
    public static final int MAX_DATEIEN = 500;

    /** Unter so viel freiem Platz: höchstens ein Fünftel der MB-Grenze (mindestens {@link #MIN_MB}). */
    public static final long WENIG_FREI_BYTES = 1024L * 1024 * 1024;
    /** Unter so viel freiem Platz: höchstens {@link #KNAPP_MB} MB und {@link #KNAPP_TAGE} Tag je Art. */
    public static final long KNAPP_FREI_BYTES = 200L * 1024 * 1024;
    public static final int KNAPP_MB = 2;
    public static final int KNAPP_TAGE = 1;

    static final long TAG_MS = 86_400_000L;
    static final long MB = 1024L * 1024;

    /** Archivname: Art (Buchstaben, Ziffern, _ und -), Zeitstempel, Prozess, Zähler. */
    static final Pattern NAME = Pattern.compile("([A-Za-z0-9_-]{1,64})\\.(\\d{8}-\\d{6})-(\\d+)-(\\d+)\\.log");

    private LogArchiv() {}

    // ------------------------------------------------------------------ Grenzen

    public static final class Grenzen {
        public final int tage;
        public final int mb;

        public Grenzen(int tage, int mb) {
            this.tage = tage;
            this.mb = mb;
        }

        public long maxAlterMs() { return tage * TAG_MS; }
        public long maxBytes() { return mb * MB; }

        @Override public String toString() { return tage + " Tage, " + mb + " MB"; }
    }

    /** Einstellung {@code logs.tage}/{@code logs.mb}; fehlt oder ungültig = Standard, sonst in die Spanne geklemmt. */
    public static Grenzen grenzen(JSONObject root) {
        JSONObject l = root == null ? null : root.optJSONObject(ABSCHNITT);
        int tage = STANDARD_TAGE, mb = STANDARD_MB;
        if (l != null) {
            Object t = l.opt("tage");
            if (t instanceof Number) tage = klemme(((Number) t).intValue(), MIN_TAGE, MAX_TAGE);
            Object m = l.opt("mb");
            if (m instanceof Number) mb = klemme(((Number) m).intValue(), MIN_MB, MAX_MB);
        }
        return new Grenzen(tage, mb);
    }

    /**
     * Grenzen nach freiem Platz: unter {@link #KNAPP_FREI_BYTES} höchstens 2 MB und 1 Tag je Art, unter
     * {@link #WENIG_FREI_BYTES} höchstens ein Fünftel der MB (mindestens 5, nie mehr als eingestellt).
     * {@code freiBytes} &lt; 0 = unbekannt, dann gelten die Grenzen unverändert.
     */
    public static Grenzen wirksam(Grenzen g, long freiBytes) {
        if (freiBytes < 0) return g;
        if (freiBytes < KNAPP_FREI_BYTES) {
            return new Grenzen(Math.min(g.tage, KNAPP_TAGE), Math.min(g.mb, KNAPP_MB));
        }
        if (freiBytes < WENIG_FREI_BYTES) {
            return new Grenzen(g.tage, Math.min(g.mb, Math.max(MIN_MB, g.mb / 5)));
        }
        return g;
    }

    // ------------------------------------------------------------------ Dateien

    public static final class Datei {
        public final String name;
        public final long groesse;
        public final long zeit;
        public final File datei;

        public Datei(String name, long groesse, long zeit, File datei) {
            this.name = name;
            this.groesse = groesse;
            this.zeit = zeit;
            this.datei = datei;
        }
    }

    /** Art eines Archivnamens oder null, wenn der Name nicht vom Archiv stammt. */
    public static String art(String name) {
        if (name == null) return null;
        Matcher m = NAME.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Was weg muss, getrennt je Art: neueste zuerst gezählt; weg ist, was älter als die Tage ist, was die Summe über
     * die MB-Grenze hebt oder hinter der {@link #MAX_DATEIEN}-ten Datei liegt. Fremde Namen bleiben unberührt.
     */
    public static List<Datei> zuLoeschen(List<Datei> dateien, long jetzt, Grenzen g) {
        Map<String, List<Datei>> jeArt = new TreeMap<>();
        for (Datei d : dateien) {
            String a = art(d.name);
            if (a == null) continue;
            List<Datei> l = jeArt.get(a);
            if (l == null) jeArt.put(a, l = new ArrayList<>());
            l.add(d);
        }
        List<Datei> weg = new ArrayList<>();
        for (List<Datei> l : jeArt.values()) {
            // neueste zuerst; gleiche Zeit: Name absteigend (Zeitstempel und Zähler im Namen)
            Collections.sort(l, (a, b) -> a.zeit != b.zeit ? Long.compare(b.zeit, a.zeit) : b.name.compareTo(a.name));
            long summe = 0;
            int n = 0;
            for (Datei d : l) {
                summe += Math.max(0, d.groesse);
                n++;
                boolean alt = jetzt - d.zeit > g.maxAlterMs();
                if (alt || summe > g.maxBytes() || n > MAX_DATEIEN) weg.add(d);
            }
        }
        return weg;
    }

    /** Ordner am Auto: neben den übrigen Daemon-Dateien. */
    public static File ordner() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.path(ORDNER));
    }

    /**
     * Aufräumen im Ordner: Grenzen aus der Config, enger bei wenig Platz; schreibt die Grenzen für die Shell-Seite.
     *
     * @return Zahl gelöschter Dateien
     */
    public static int pflege(File ordner, JSONObject config, long jetzt) {
        if (ordner == null) return 0;
        Grenzen eingestellt = grenzen(config);
        if (!ordner.isDirectory() && !ordner.mkdirs()) return 0;
        schreibeGrenzen(ordner, eingestellt);
        long frei;
        try {
            frei = ordner.getUsableSpace();
            if (frei <= 0) frei = -1;
        } catch (Throwable t) {
            frei = -1;
        }
        Grenzen g = wirksam(eingestellt, frei);
        File[] liste = ordner.listFiles();
        if (liste == null) return 0;
        List<Datei> dateien = new ArrayList<>();
        for (File f : liste) {
            if (f.isFile() && art(f.getName()) != null) {
                dateien.add(new Datei(f.getName(), f.length(), f.lastModified(), f));
            }
        }
        int geloescht = 0;
        for (Datei d : zuLoeschen(dateien, jetzt, g)) {
            if (d.datei != null && d.datei.delete()) geloescht++;
        }
        return geloescht;
    }

    /** Laufzeit im Kamera-Daemon (Start und 10-min-Takt der Aufbewahrung). */
    public static int pflege() {
        try {
            return pflege(ordner(), at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig(),
                    System.currentTimeMillis());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {@code <tage> <mb>} für die Shell-Funktionen; nur schreiben, wenn sich etwas ändert. */
    static void schreibeGrenzen(File ordner, Grenzen g) {
        File ziel = new File(ordner, GRENZEN_DATEI);
        String inhalt = g.tage + " " + g.mb + "\n";
        try {
            if (ziel.isFile() && ziel.length() == inhalt.length()) {
                byte[] alt = java.nio.file.Files.readAllBytes(ziel.toPath());
                if (Arrays.equals(alt, inhalt.getBytes(StandardCharsets.US_ASCII))) return;
            }
            File tmp = new File(ordner, GRENZEN_DATEI + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(inhalt.getBytes(StandardCharsets.US_ASCII));
            }
            if (!tmp.renameTo(ziel)) {
                ziel.delete();
                tmp.renameTo(ziel);
            }
        } catch (Throwable ignored) {
            // Ohne Datei gelten in der Shell die Standardwerte.
        }
    }

    static int klemme(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ Shell

    /**
     * Shell-Funktionen für Wächter-Skripte und Startbefehle (toybox-sh am Auto, auch POSIX-sh). Erwartet
     * {@code LOGARCHIV} (Ordner); nutzt nur Variablen mit Präfix {@code LA_}. Keine einfachen Anführungszeichen und
     * kein awk, damit die Zeilen in Heredocs, {@code printf}-Schreibwegen und Einzeilern unverändert bleiben.
     *
     * <ul>
     *   <li>{@code log_archivieren DATEI}: nicht leere Datei ins Archiv umbenennen (nur, solange kein Prozess sie
     *       offen hält – also vor dem Start), dann aufräumen.</li>
     *   <li>{@code log_kopieren_kuerzen DATEI}: für das laufende Log (Daemon hält es offen): kopieren, dann an Ort und
     *       Stelle kürzen. Bei knappem Platz wird nicht kopiert, nur gekürzt.</li>
     *   <li>{@code log_archiv_aufraeumen}: Tage, MB und Dateizahl je Art wie {@link #zuLoeschen}.</li>
     * </ul>
     */
    public static List<String> shellFunktionen() {
        long knappKb = KNAPP_FREI_BYTES / 1024, wenigKb = WENIG_FREI_BYTES / 1024;
        return Arrays.asList(
                "LA_Z=0",
                "log_frei_kb() {",
                // Spalte 4 von df -k = frei in KB; ohne awk, damit es auch ohne awk geht
                "  set -- $(df -k \"$1\" 2>/dev/null | tail -n 1)",
                "  LA_FREI=$4",
                "  case \"$LA_FREI\" in \"\"|*[!0-9]*) LA_FREI=-1 ;; esac",
                "}",
                "log_archiv_aufraeumen() {",
                "  [ -d \"$LOGARCHIV\" ] || return 0",
                "  LA_TAGE=" + STANDARD_TAGE + "; LA_MB=" + STANDARD_MB,
                "  if [ -r \"$LOGARCHIV/" + GRENZEN_DATEI + "\" ]; then",
                "    read LA_T LA_M < \"$LOGARCHIV/" + GRENZEN_DATEI + "\"",
                "    case \"$LA_T\" in \"\"|*[!0-9]*) ;; *) LA_TAGE=$LA_T ;; esac",
                "    case \"$LA_M\" in \"\"|*[!0-9]*) ;; *) LA_MB=$LA_M ;; esac",
                "  fi",
                "  log_frei_kb \"$LOGARCHIV\"",
                "  if [ \"$LA_FREI\" -ge 0 ] && [ \"$LA_FREI\" -lt " + knappKb + " ]; then",
                "    [ \"$LA_MB\" -gt " + KNAPP_MB + " ] && LA_MB=" + KNAPP_MB,
                "    [ \"$LA_TAGE\" -gt " + KNAPP_TAGE + " ] && LA_TAGE=" + KNAPP_TAGE,
                "  elif [ \"$LA_FREI\" -ge 0 ] && [ \"$LA_FREI\" -lt " + wenigKb + " ]; then",
                "    LA_K=$((LA_MB / 5)); [ \"$LA_K\" -lt " + MIN_MB + " ] && LA_K=" + MIN_MB,
                "    [ \"$LA_K\" -lt \"$LA_MB\" ] && LA_MB=$LA_K",
                "  fi",
                "  LA_JETZT=$(date +%s)",
                "  for LA_ART in $(for LA_F in \"$LOGARCHIV\"/*.log; do [ -f \"$LA_F\" ] || continue; "
                        + "LA_N=${LA_F##*/}; echo \"${LA_N%%.*}\"; done | sort -u); do",
                "    case \"$LA_ART\" in \"\"|*[!A-Za-z0-9_-]*) continue ;; esac",
                "    LA_SUMME=0; LA_ANZ=0",
                // 0.1.38.10 (HARDRESET 41 s am Auto): EIN stat für alle Dateien der Art (neueste zuerst) statt zwei
                // Prozesse je Archivdatei; bei hunderten Dateien und einem Aufruf je Logdatei waren das tausende Forks.
                "    LA_L=$(ls -t \"$LOGARCHIV\"/\"$LA_ART\".*.log 2>/dev/null)",
                "    [ -n \"$LA_L\" ] || continue",
                "    stat -c \"%s %Y %n\" $LA_L 2>/dev/null | while read LA_SZ LA_MT LA_F; do",
                "      LA_SUMME=$((LA_SUMME + LA_SZ)); LA_ANZ=$((LA_ANZ + 1))",
                "      if [ $((LA_JETZT - LA_MT)) -gt $((LA_TAGE * 86400)) ] || "
                        + "[ \"$LA_SUMME\" -gt $((LA_MB * 1048576)) ] || [ \"$LA_ANZ\" -gt " + MAX_DATEIEN + " ]; then",
                "        rm -f \"$LA_F\"",
                "      fi",
                "    done",
                "  done",
                "}",
                "log_archiv_ziel() {",
                "  LA_N=${1##*/}; LA_ART=${LA_N%%.*}",
                "  case \"$LA_ART\" in \"\"|*[!A-Za-z0-9_-]*) LA_ART=log ;; esac",
                "  LA_STEMPEL=$(date +%Y%m%d-%H%M%S)",
                "  LA_Z=$((LA_Z + 1))",
                "  LA_ZIEL=\"$LOGARCHIV/$LA_ART.$LA_STEMPEL-$$-$LA_Z.log\"",
                "  while [ -e \"$LA_ZIEL\" ]; do LA_Z=$((LA_Z + 1)); LA_ZIEL=\"$LOGARCHIV/$LA_ART.$LA_STEMPEL-$$-$LA_Z.log\"; done",
                "}",
                // nur verschieben, ohne Aufräumen: viele Dateien nacheinander (Hard-Reset), danach EIN Aufräumen
                "log_archivieren_nur() {",
                "  [ -s \"$1\" ] || return 0",
                "  mkdir -p \"$LOGARCHIV\" 2>/dev/null",
                "  log_archiv_ziel \"$1\"",
                "  mv -f \"$1\" \"$LA_ZIEL\" 2>/dev/null",
                "}",
                "log_archivieren() {",
                "  log_archivieren_nur \"$1\"",
                "  log_archiv_aufraeumen",
                "}",
                "log_kopieren_kuerzen() {",
                "  [ -s \"$1\" ] || return 0",
                "  mkdir -p \"$LOGARCHIV\" 2>/dev/null",
                "  LA_GR=$(stat -c %s \"$1\" 2>/dev/null || echo 0)",
                "  log_frei_kb \"$LOGARCHIV\"",
                "  LA_ZIEL=",
                "  if [ \"$LA_FREI\" -lt 0 ] || [ \"$LA_FREI\" -ge " + knappKb + " ]; then",
                "    log_archiv_ziel \"$1\"",
                "    cp \"$1\" \"$LA_ZIEL\" 2>/dev/null || { rm -f \"$LA_ZIEL\"; LA_ZIEL=; }",
                "  fi",
                "  : > \"$1\"",
                "  if [ -n \"$LA_ZIEL\" ]; then",
                "    echo \"[$(date)] Log archiviert nach ${LA_ZIEL##*/} (war ${LA_GR} Bytes)\" >> \"$1\"",
                "  else",
                "    echo \"[$(date)] Log gekuerzt ohne Archiv (wenig Speicher, war ${LA_GR} Bytes)\" >> \"$1\"",
                "  fi",
                "  log_archiv_aufraeumen",
                "}");
    }

    /**
     * Einzeiliger Befehl für Startwege ohne Wächter-Skript ({@code nohup sh -c '…' >> LOG &}): bisheriges Log ins
     * Archiv neben {@code logPfad}, danach weiter mit {@code >>}. Endet mit {@code ; }.
     */
    public static String archivBefehl(String logPfad) {
        StringBuilder sb = new StringBuilder();
        sb.append("LOGARCHIV=\"").append(ordnerFuer(logPfad)).append("\"; ");
        for (String zeile : shellFunktionen()) sb.append(zeile.trim()).append(trenner(zeile)).append(' ');
        sb.append("log_archivieren \"").append(logPfad).append("\"; ");
        return sb.toString();
    }

    /** {@code <Ordner des Logs>/logarchiv}. */
    public static String ordnerFuer(String logPfad) {
        int i = logPfad == null ? -1 : logPfad.lastIndexOf('/');
        return (i > 0 ? logPfad.substring(0, i) : ".") + "/" + ORDNER;
    }

    /** Zeilen zu einer Zeile verbinden: nach {@code {}, {@code then}, {@code do}, {@code else} und {@code ;} kein {@code ;}. */
    private static String trenner(String zeile) {
        String t = zeile.trim();
        if (t.endsWith("{") || t.endsWith(" then") || t.endsWith(" do") || t.equals("else") || t.endsWith(";")) return "";
        return ";";
    }

    /** Für Tests und Logs: Zeitstempel im Archivnamen. */
    static String stempel(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        return f.format(new java.util.Date(ms));
    }
}
