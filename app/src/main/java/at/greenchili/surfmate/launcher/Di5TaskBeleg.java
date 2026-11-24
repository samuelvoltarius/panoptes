package at.greenchili.surfmate.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zweiter, nur lesender Beleg für die DiLink-5-Task-Wache (0.1.43.20, P1).
 *
 * <p>Auf Android 13 (API 33) findet die Task-Suche per Reflection einen laufenden Navi-Task
 * manchmal nicht, obwohl die App sichtbar auf dem Fahrerdisplay läuft. Die alte Wache hat
 * daraus nach fünf Durchläufen einen Zwangsneustart (forceStop) gemacht: Die Karte flackerte.
 * Herkunft: Idee aus DashCast 1.9.78-beta (MIT-Lizenz, Release-Text „Android 13 task check“),
 * Code hier eigen geschrieben:
 * <ul>
 *   <li>Auf API 33+ wird „kein Task gefunden“ zuerst mit genau einem begrenzten
 *       {@code dumpsys activity activities} gegengeprüft.</li>
 *   <li>Ein Treffer zählt nur bei exakt passendem Paket, User 0, Task-Nummer und Display in
 *       vollständig gelesener Ausgabe. Alles Mehrdeutige, Abgeschnittene oder Fehlgeschlagene
 *       ist „unbekannt“.</li>
 *   <li>Ein Zwangsneustart ist auf API 33+ nur bei positivem Beleg „Task auf falschem
 *       Display“ erlaubt, nie wenn unsere App sichtbar vorn ist, höchstens einmal je
 *       Sitzung. Auf API 28–32 bleibt das alte Verhalten.</li>
 * </ul>
 */
final class Di5TaskBeleg {

    /** Ab dieser API-Stufe gilt die Gegenprüfung (DiLink 5 = Android 13). */
    static final int GEGENPRUEF_API = 33;
    /** Wie oft der Placement-Fehler in Folge auftreten muss (unverändert gegenüber vorher). */
    static final int ZWANGSNEUSTART_NACH = 5;
    /** Höchstens so viele Zwangsneustarts je Sitzung auf API 33+. */
    static final int MAX_ZWANGSNEUSTARTS_API33 = 1;
    /** Höchstens so viele Gegenprüfungen je Wachlauf; danach gilt jedes „fehlt“ als unbekannt. */
    static final int MAX_DUMPS_JE_WACHE = 10;

    static final long DUMP_TIMEOUT_MS = 2_000L;
    static final int DUMP_MAX_ZEICHEN = 2_000_000;

    enum Art { AUF_ZIEL, ANDERES_DISPLAY, FEHLT, UNBEKANNT }

    /** Ergebnis des Activity-Dumps für genau ein Paket. */
    static final class Befund {
        final Art art;
        final int taskId;
        final int displayId;

        private Befund(Art art, int taskId, int displayId) {
            this.art = art;
            this.taskId = taskId;
            this.displayId = displayId;
        }

        static Befund unbekannt() { return new Befund(Art.UNBEKANNT, -1, -1); }
        static Befund fehlt() { return new Befund(Art.FEHLT, -1, -1); }

        @Override
        public String toString() {
            return art + "(task=" + taskId + ", display=" + displayId + ")";
        }
    }

    /** Was die Wache aus Reflection-Suche und Gegenprüfung ableitet. */
    static final class Bewertung {
        final ClusterFreeformWindow.TaskLocation ort;
        /** Positiver Beleg: Task existiert, liegt aber auf einem anderen Display. */
        final boolean belegtFalschesDisplay;
        /** Wurde dafür ein Dump gelesen? (zählt gegen {@link #MAX_DUMPS_JE_WACHE}) */
        final boolean dumpGelesen;
        final Befund befund;

        Bewertung(ClusterFreeformWindow.TaskLocation ort, boolean belegtFalschesDisplay,
                  boolean dumpGelesen, Befund befund) {
            this.ort = ort;
            this.belegtFalschesDisplay = belegtFalschesDisplay;
            this.dumpGelesen = dumpGelesen;
            this.befund = befund;
        }
    }

    /** Quelle für den Dump, in Tests ersetzbar. Liefert null bei Fehler/Timeout/Abschneiden. */
    interface DumpQuelle {
        String lies();
    }

    private static final Pattern ACTIVITY_RECORD = Pattern.compile(
            "ActivityRecord\\{[0-9a-fA-F]+ u(\\d+) ([A-Za-z0-9_.]+)/[^ }]+ t(-?\\d+)");
    private static final Pattern DISPLAY_KOPF = Pattern.compile("^Display #(\\d+)\\b");

    private Di5TaskBeleg() {}

    /**
     * Reiner Parser. Sucht in den Task-Historien ({@code Hist #n: ActivityRecord{…}}) jedes
     * {@code Display #N}-Blocks nach {@code pkg} für User 0.
     */
    static Befund auswerten(String dump, String pkg, int zielDisplay) {
        if (dump == null || pkg == null || pkg.isEmpty() || zielDisplay < 0) {
            return Befund.unbekannt();
        }
        if (dump.length() > DUMP_MAX_ZEICHEN
                || !dump.contains("ACTIVITY MANAGER ACTIVITIES")) {
            return Befund.unbekannt();
        }
        int aktuellesDisplay = -1;
        boolean displaySeen = false;
        int zielTask = -1;
        int fremdTask = -1;
        int fremdDisplay = -1;
        boolean widerspruch = false;
        String[] zeilen = dump.split("\r?\n", -1);
        for (String zeile : zeilen) {
            Matcher kopf = DISPLAY_KOPF.matcher(zeile);
            if (kopf.find()) {
                try {
                    aktuellesDisplay = Integer.parseInt(kopf.group(1));
                } catch (NumberFormatException e) {
                    return Befund.unbekannt();
                }
                displaySeen = true;
                continue;
            }
            // Eine Zeile ohne Einrückung, die kein Display-Kopf ist, beendet den Block
            // (z. B. „ActivityTaskSupervisor state:“). Danach zählt nichts mehr als Task.
            if (!zeile.isEmpty() && !Character.isWhitespace(zeile.charAt(0))) {
                aktuellesDisplay = -1;
                continue;
            }
            if (aktuellesDisplay < 0) continue;
            String low = zeile.toLowerCase(Locale.US);
            if (!low.contains("hist") || !low.contains("activityrecord{")) continue;
            Matcher m = ACTIVITY_RECORD.matcher(zeile);
            if (!m.find()) {
                // Paket steht drin, aber das Format ist nicht sicher erkannt → unbekannt.
                if (zeile.contains(" " + pkg + "/")) return Befund.unbekannt();
                continue;
            }
            if (!pkg.equals(m.group(2))) continue;
            if (!"0".equals(m.group(1))) continue;   // nur User 0
            int task;
            try {
                task = Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) {
                return Befund.unbekannt();
            }
            if (task <= 0) return Befund.unbekannt();
            if (aktuellesDisplay == zielDisplay) {
                if (zielTask < 0) zielTask = task;
            } else if (fremdTask < 0) {
                fremdTask = task;
                fremdDisplay = aktuellesDisplay;
            } else if (fremdDisplay != aktuellesDisplay) {
                widerspruch = true;
            }
        }
        if (!displaySeen) return Befund.unbekannt();
        if (widerspruch) return Befund.unbekannt();
        if (zielTask > 0 && fremdTask > 0) return Befund.unbekannt();   // mehrdeutig
        if (zielTask > 0) return new Befund(Art.AUF_ZIEL, zielTask, zielDisplay);
        if (fremdTask > 0) return new Befund(Art.ANDERES_DISPLAY, fremdTask, fremdDisplay);
        return Befund.fehlt();
    }

    /**
     * Verbindet die Reflection-Suche mit der Gegenprüfung. Auf API < 33 wird nie ein Dump
     * gelesen; das Ergebnis ist dann genau die alte Reflection-Sicht.
     */
    static Bewertung bewerte(int sdk, ClusterFreeformWindow.TaskLocation ort, String pkg,
                             int zielDisplay, int bisherigeDumps, DumpQuelle quelle) {
        if (ort == null) ort = ClusterFreeformWindow.TaskLocation.unknown();
        boolean reflektiertFalsch = ort.known && ort.taskId > 0
                && ort.displayId >= 0 && ort.displayId != zielDisplay;
        if (sdk < GEGENPRUEF_API || !ort.known || ort.taskId > 0) {
            return new Bewertung(ort, reflektiertFalsch, false, null);
        }
        // API 33+: „kein Task“ ist erst einmal unbekannt.
        if (bisherigeDumps >= MAX_DUMPS_JE_WACHE || quelle == null) {
            return new Bewertung(ClusterFreeformWindow.TaskLocation.unknown(), false, false,
                    Befund.unbekannt());
        }
        String dump;
        try {
            dump = quelle.lies();
        } catch (Throwable t) {
            dump = null;
        }
        Befund befund = auswerten(dump, pkg, zielDisplay);
        switch (befund.art) {
            case AUF_ZIEL:
                return new Bewertung(new ClusterFreeformWindow.TaskLocation(
                        true, befund.taskId, zielDisplay), false, true, befund);
            case ANDERES_DISPLAY:
                return new Bewertung(new ClusterFreeformWindow.TaskLocation(
                        true, befund.taskId, befund.displayId), true, true, befund);
            case FEHLT:
                return new Bewertung(ClusterFreeformWindow.TaskLocation.absent(),
                        false, true, befund);
            default:
                return new Bewertung(ClusterFreeformWindow.TaskLocation.unknown(),
                        false, true, befund);
        }
    }

    /** Darf die Wache jetzt forceStop + Neustart machen? */
    static boolean zwangsneustartErlaubt(int sdk, boolean placementNeedsCorrection,
                                         int placementFehlerInFolge,
                                         boolean belegtFalschesDisplay,
                                         boolean foregroundOurs,
                                         int bisherigeZwangsneustarts) {
        if (!placementNeedsCorrection || placementFehlerInFolge < ZWANGSNEUSTART_NACH) {
            return false;
        }
        if (sdk < GEGENPRUEF_API) return true;   // API 28–32: Verhalten wie bisher
        return belegtFalschesDisplay
                && !foregroundOurs
                && bisherigeZwangsneustarts < MAX_ZWANGSNEUSTARTS_API33;
    }

    /** Echter, begrenzter, nur lesender Dump. null bei Fehler, Timeout oder Übergröße. */
    static String liesActivityDump() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
            final Process laufend = p;
            final StringBuilder sb = new StringBuilder();
            final boolean[] zuGross = {false};
            Thread leser = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        laufend.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) >= 0) {
                        if (sb.length() + n > DUMP_MAX_ZEICHEN) {
                            zuGross[0] = true;
                            break;
                        }
                        sb.append(buf, 0, n);
                    }
                } catch (Throwable ignored) {
                    zuGross[0] = true;
                }
            }, "Di5TaskBelegDump");
            leser.setDaemon(true);
            leser.start();
            boolean fertig = p.waitFor(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            leser.join(500L);
            if (!fertig || leser.isAlive() || zuGross[0] || p.exitValue() != 0) {
                return null;
            }
            return sb.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }
    }
}
