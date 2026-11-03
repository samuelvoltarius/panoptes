package at.greenchili.surfmate.telegram;

import at.greenchili.surfmate.trips.TripRecord;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Abschaltbericht per Telegram (03.11.2026, Projektinhaber freigegeben): Beim Abstellen eine kurze Nachricht mit
 * Fahrt (Strecke, Dauer, Verbrauch), Akku vorher/nachher, Reichweite, 12 V, Wächter scharf, verriegelt.
 *
 * <p><b>Ohne Ort und ohne Koordinaten.</b> {@link Daten} hat dafür bewusst keine Felder; die Fahrten liefern zwar
 * Start/Ziel, die werden hier nie gelesen ({@code AbschaltBerichtTest} prüft das am fertigen Text).
 *
 * <p>Schalter {@code telegram.abschaltBericht}, Standard an (wirkt nur, wenn Telegram eingerichtet ist).
 * Keine Meldung nach kurzen Rangierfahrten: unter {@code telegram.abschaltBerichtMinMeter} (Standard 300 m)
 * ODER unter {@code telegram.abschaltBerichtMinMinuten} (Standard 2 min).
 *
 * <p>Ablauf: Zündung aus → {@link #VERZOEGERUNG_MS} warten (Fahrt abgeschlossen, Wächter scharf, Schloss zu) →
 * Wächter-Zustand für „Verlust im Stand“ an der Zündungs-Zeile nachtragen (immer, auch ohne Telegram) →
 * Bericht senden, falls eingeschaltet. Geht die Zündung vorher wieder an, entfällt beides.
 *
 * <p>0.1.36.1 (Befund Auto nach dem Update auf 0.1.36): Ein Daemon-Start meldet „Zündung aus“ als
 * ersten Zustand ({@code CameraDaemon.onAccStateChanged}: vorheriger Zustand {@code null} zählt als
 * Wechsel). Das löste einen Bericht über die letzte Fahrt vor dem Update aus. Jetzt gilt:
 * <ul>
 *   <li>Bericht nur nach einem echten Übergang an → aus, den <b>dieser Prozess</b> gesehen hat
 *       ({@link #beiAccAn} vor {@link #beiAccAus}). Start, Neustart und Update melden nichts; ihre
 *       Fahrten werden still als gemeldet gemerkt.</li>
 *   <li>Einmal je Fahrt: höchste gemeldete Fahrt-ID in {@code abschalt_bericht.json} und im Speicher.</li>
 *   <li>Kategorie {@code PARKING} (normale Info wie andere Park-Meldungen, Schalter „Park-Meldungen“,
 *       Ruhezeit gilt), nicht mehr {@code CRITICAL}.</li>
 *   <li>Schloss nach 90 s frisch über {@code VehicleControlApiHandler.sperrStand} (OTA-Fahrertür, frische
 *       Cloud, SDK – dieselbe Quelle wie die Fahrzeugseite), ohne Cloud-Abruf anzustoßen. Unbekannt:
 *       zweimal nach {@link #NACHFASSEN_MS} nachfassen, sonst fällt „Zugesperrt“ weg. Vorher kam der Wert
 *       nur aus {@code BydVehicleData.doorLockStatus}, das {@code BydDataCollector.collectDoorLock} auf
 *       den meisten Autos mit -1 füllt – daher immer „unbekannt“.</li>
 * </ul>
 */
public final class AbschaltBericht {

    static final long VERZOEGERUNG_MS = 90_000L;
    static final int STANDARD_MIN_METER = 300;
    static final int STANDARD_MIN_MINUTEN = 2;
    /** Ohne bekanntes „Zündung an“: nur Fahrten, die in diesem Fenster vor dem Abstellen endeten. */
    static final long FENSTER_OHNE_AN_MS = 6 * 3_600_000L;

    /** Schloss unbekannt: so lange warten und neu lesen (zweimal). */
    static final long NACHFASSEN_MS = 15_000L;
    static final int NACHFASSEN_VERSUCHE = 2;
    /** Kategorie wie andere Park-Meldungen (keine Warnstufe). */
    static final String KATEGORIE = "PARKING";

    private static final Object LOCK = new Object();
    private static volatile long letztesAus;
    /** Letztes „Zündung an“, das DIESER Prozess gesehen hat (0 = noch keins, z. B. direkt nach Start). */
    private static volatile long letztesAn;
    /** Höchste gemeldete Fahrt-ID im Speicher (falls die Datei nicht geschrieben werden konnte). */
    private static volatile long gemeldetImSpeicher = -1;
    private static ScheduledExecutorService exec;

    private AbschaltBericht() {}

    /** Zusammengefasste Werte; absichtlich ohne Ort. NaN/null = unbekannt. */
    static final class Daten {
        double km;
        long sekunden;
        double kwh = Double.NaN;
        Double socVorher;
        Double socNachher;
        Integer reichweiteKm;
        Double volt12;
        Boolean scharf;
        Boolean verriegelt;
        long letzteFahrtId = -1;
        int fahrten;
    }

    /** Text-Quelle (Laufzeit: {@link TelegramMessages}; Test: Katalog-Datei). */
    interface Texte {
        String get(String key, Object... args);
    }

    // ---------------- Schalter ----------------

    static boolean schalter(JSONObject root) {
        JSONObject t = root == null ? null : root.optJSONObject("telegram");
        return t == null || t.optBoolean("abschaltBericht", true);
    }

    static int minMeter(JSONObject root) {
        JSONObject t = root == null ? null : root.optJSONObject("telegram");
        int v = t == null ? STANDARD_MIN_METER : t.optInt("abschaltBerichtMinMeter", STANDARD_MIN_METER);
        return Math.max(0, Math.min(5000, v));
    }

    static int minMinuten(JSONObject root) {
        JSONObject t = root == null ? null : root.optJSONObject("telegram");
        int v = t == null ? STANDARD_MIN_MINUTEN : t.optInt("abschaltBerichtMinMinuten", STANDARD_MIN_MINUTEN);
        return Math.max(0, Math.min(30, v));
    }

    /** Rangierfahrt: kürzer als {@code minMeter} ODER kürzer als {@code minMinuten}. */
    static boolean zuKurz(double km, long sekunden, int minMeter, int minMinuten) {
        if (!(km >= 0) || Double.isInfinite(km)) return true;
        return km * 1000.0 < minMeter || sekunden < minMinuten * 60L;
    }

    // ---------------- Rechnen ----------------

    /**
     * Fahrten seit dem letzten Einschalten zu einer zusammenfassen (Halt mit P zwischendurch teilt eine Fahrt).
     * Liest nur Strecke, Dauer, Energie und Akku-Stand.
     */
    static Daten zusammenfassen(List<TripRecord> fahrten) {
        Daten d = new Daten();
        if (fahrten == null || fahrten.isEmpty()) return d;
        List<TripRecord> l = new ArrayList<>(fahrten);
        l.sort(Comparator.comparingLong(t -> t.startTime));
        double kwh = 0;
        boolean kwhBekannt = false;
        for (TripRecord t : l) {
            d.km += Math.max(0, t.distanceKm);
            d.sekunden += Math.max(0, t.durationSeconds);
            double e = t.getResolvedEnergyKwh();
            if (e > 0) { kwh += e; kwhBekannt = true; }
            d.letzteFahrtId = Math.max(d.letzteFahrtId, t.id);
            d.fahrten++;
        }
        if (kwhBekannt) d.kwh = kwh;
        TripRecord erste = l.get(0), letzte = l.get(l.size() - 1);
        if (gueltigeSoc(erste.socStart)) d.socVorher = erste.socStart;
        if (gueltigeSoc(letzte.socEnd)) d.socNachher = letzte.socEnd;
        return d;
    }

    static boolean gueltigeSoc(double soc) {
        return !Double.isNaN(soc) && soc > 0 && soc <= 100;
    }

    /**
     * Zeilen als {Schlüssel, Werte…}; Zahlen schon passend zur Sprache formatiert (Komma außer Englisch).
     * Fehlende Werte lassen ihre Zeile weg, nur Wächter/Schloss steht immer da (mit „unbekannt“).
     */
    static List<Object[]> zeilen(Daten d, Locale sprache) {
        List<Object[]> z = new ArrayList<>();
        z.add(new Object[] {"abschalt.titel"});
        String km = zahl(d.km, d.km < 100 ? 1 : 0, sprache);
        String dauer = ZurueckAmAuto.dauer(d.sekunden * 1000L);
        if (!Double.isNaN(d.kwh) && d.km >= 0.5) {
            z.add(new Object[] {"abschalt.fahrt", km, dauer, zahl(d.kwh / d.km * 100.0, 1, sprache)});
        } else {
            z.add(new Object[] {"abschalt.fahrt_ohne_verbrauch", km, dauer});
        }
        if (d.socVorher != null && d.socNachher != null) {
            z.add(new Object[] {"abschalt.akku", zahl(d.socVorher, 0, sprache), zahl(d.socNachher, 0, sprache)});
        } else if (d.socNachher != null) {
            z.add(new Object[] {"abschalt.akku_jetzt", zahl(d.socNachher, 0, sprache)});
        }
        if (d.reichweiteKm != null) z.add(new Object[] {"abschalt.reichweite", String.valueOf(d.reichweiteKm)});
        if (d.volt12 != null) z.add(new Object[] {"abschalt.spannung", zahl(d.volt12, 1, sprache)});
        if (d.verriegelt != null) {
            z.add(new Object[] {"abschalt.sicherheit", janein(d.scharf), janein(d.verriegelt)});
        } else {
            // 0.1.36.1: Schloss auch nach Nachfassen unbekannt -> Zeile ohne „Zugesperrt“.
            z.add(new Object[] {"abschalt.sicherheit_ohne_schloss", janein(d.scharf)});
        }
        return z;
    }

    static String janein(Boolean b) {
        return b == null ? "abschalt.unbekannt" : (b ? "abschalt.ja" : "abschalt.nein");
    }

    /** Fertiger Text; Werte, die selbst Schlüssel sind ({@code abschalt.ja} …), werden übersetzt. */
    static String text(List<Object[]> zeilen, Texte texte) {
        StringBuilder sb = new StringBuilder();
        for (Object[] z : zeilen) {
            Object[] args = new Object[z.length - 1];
            for (int i = 1; i < z.length; i++) {
                Object a = z[i];
                args[i - 1] = (a instanceof String && ((String) a).startsWith("abschalt.")) ? texte.get((String) a) : a;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(texte.get((String) z[0], args));
        }
        return sb.toString();
    }

    static String zahl(double v, int stellen, Locale sprache) {
        String s = String.format(Locale.ROOT, "%." + stellen + "f", v);
        boolean englisch = sprache != null && "en".equals(sprache.getLanguage());
        return englisch ? s : s.replace('.', ',');
    }

    /** Welche Fahrten gehören zu diesem Abstellen? */
    static List<TripRecord> auswahl(List<TripRecord> kandidaten, long ausMs, long letztesAnMs, long gemeldetBisId) {
        List<TripRecord> out = new ArrayList<>();
        if (kandidaten == null) return out;
        for (TripRecord t : kandidaten) {
            if (t == null || t.endTime <= 0 || t.id <= gemeldetBisId) continue;
            if (t.endTime > ausMs + VERZOEGERUNG_MS + 60_000L) continue;
            if (letztesAnMs > 0) {
                if (t.startTime < letztesAnMs - 60_000L) continue;
            } else if (t.endTime < ausMs - FENSTER_OHNE_AN_MS) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    // ---------------- Laufzeit (Kamera-Daemon) ----------------

    /**
     * Echter Übergang an → aus in diesem Prozess? Nur dann gibt es einen Bericht. Direkt nach Daemon-Start,
     * Neustart oder Update ist {@code anInProzess} 0 (erste Meldung ist „aus“), dann nie.
     */
    static boolean berichtVorgesehen(long anInProzess, long ausMs) {
        return anInProzess > 0 && anInProzess <= ausMs;
    }

    /** Zündung aus vermerken; true = für diesen Nachlauf ist ein Bericht vorgesehen. */
    static boolean merkeAus(long jetzt) {
        letztesAus = jetzt;
        return berichtVorgesehen(letztesAn, jetzt);
    }

    /** Nur für Tests: Zustand wie nach einem frischen Daemon-Start. */
    static void zuruecksetzenFuerTest() {
        letztesAus = 0;
        letztesAn = 0;
        gemeldetImSpeicher = -1;
    }

    /** Zündung aus: Nachlauf planen (blockiert den Übergang nie). */
    public static void beiAccAus(long jetzt) {
        final boolean bericht = merkeAus(jetzt);
        try {
            // 0.1.36.1: sichtbar machen, ob dieses „aus“ einen Bericht bekommt (Prüfpunkt am Auto/Emulator).
            at.greenchili.surfmate.daemon.CameraDaemon.log("Abschaltbericht: Zuendung aus, "
                    + (bericht ? "Bericht vorgesehen (echter Uebergang an -> aus)"
                               : "kein Bericht (Start/Neustart/Update, kein 'an' in diesem Prozess)"));
        } catch (Throwable ignored) {
        }
        try {
            ScheduledExecutorService e;
            synchronized (LOCK) {
                if (exec == null) {
                    exec = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "AbschaltBericht");
                        t.setDaemon(true);
                        return t;
                    });
                }
                e = exec;
            }
            e.schedule(() -> nachlauf(jetzt, bericht), VERZOEGERUNG_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    /** Zündung an: ein noch wartender Nachlauf entfällt. */
    public static void beiAccAn(long jetzt) {
        letztesAn = jetzt;
    }

    /**
     * Welche Fahrten meldet dieses Abstellen? Leer, wenn kein echter Übergang ({@code bericht} false)
     * oder alles schon gemeldet ist.
     */
    static List<TripRecord> zuMelden(List<TripRecord> kandidaten, long ausMs, long letztesAnMs,
                                     long gemeldetBisId, boolean bericht) {
        List<TripRecord> f = auswahl(kandidaten, ausMs, letztesAnMs, gemeldetBisId);
        return bericht ? f : new ArrayList<>();
    }

    static long hoechsteId(List<TripRecord> fahrten) {
        long id = -1;
        if (fahrten != null) for (TripRecord t : fahrten) if (t != null) id = Math.max(id, t.id);
        return id;
    }

    private static void nachlauf(long ausMs, boolean bericht) {
        try {
            if (letztesAus != ausMs || letztesAn > ausMs) return;
            Boolean scharf = at.greenchili.surfmate.daemon.CameraDaemon.waechterZustandBelegt();
            at.greenchili.surfmate.monitor.SocHistoryDatabase db =
                    at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance();
            if (scharf != null) db.markiereLetztesAusWaechter(scharf, ausMs - 60_000L);

            JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
            if (!schalter(root)) return;
            if (!at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.hasBotToken()) return;

            at.greenchili.surfmate.trips.TripAnalyticsManager tam =
                    at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam == null || tam.getDatabase() == null) return;
            long gemeldet = Math.max(gemeldetBis(), gemeldetImSpeicher);
            List<TripRecord> kandidaten = tam.getDatabase().getTrips(2, 30);
            long anVor = db.letztesAccAnVor(ausMs);
            if (!bericht) {
                // Start/Neustart/Update: nichts senden, aber diese Fahrten als gemeldet merken.
                merkeGemeldet(hoechsteId(auswahl(kandidaten, ausMs, anVor, gemeldet)));
                return;
            }
            List<TripRecord> fahrten = zuMelden(kandidaten, ausMs, anVor, gemeldet, true);
            if (fahrten.isEmpty()) return;
            Daten d = zusammenfassen(fahrten);
            merkeGemeldet(d.letzteFahrtId);
            if (zuKurz(d.km, d.sekunden, minMeter(root), minMinuten(root))) return;

            d.scharf = scharf;
            try {
                at.greenchili.surfmate.byd.BydDataCollector c = at.greenchili.surfmate.byd.BydDataCollector.getInstance();
                at.greenchili.surfmate.byd.BydVehicleData v = c != null && c.isInitialized() ? c.getData() : null;
                if (v != null) {
                    if (gueltigeSoc(v.socPercent)) d.socNachher = v.socPercent;
                    if (v.elecRangeKm != at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE && v.elecRangeKm >= 0) {
                        d.reichweiteKm = v.elecRangeKm;
                    }
                    if (!Double.isNaN(v.voltage12v) && v.voltage12v > 5 && v.voltage12v < 20) d.volt12 = v.voltage12v;
                }
            } catch (Throwable ignored) {
            }
            d.verriegelt = mitNachfassen(AbschaltBericht::schlossJetzt, NACHFASSEN_VERSUCHE, NACHFASSEN_MS,
                    () -> letztesAus != ausMs || letztesAn > ausMs);
            if (letztesAus != ausMs || letztesAn > ausMs) return;
            Locale sprache = Locale.forLanguageTag(String.valueOf(at.greenchili.surfmate.server.LocaleManager.get()));
            String text = text(zeilen(d, sprache), TelegramMessages::get);
            TelegramNotifier.sendMessage(text, KATEGORIE);
        } catch (Throwable ignored) {
        }
    }

    interface Leser { Boolean lies(); }
    interface Abbruch { boolean jetzt(); }

    /** Lesen; ist es unbekannt, bis zu {@code versuche} Mal nach {@code warteMs} neu lesen. */
    static Boolean mitNachfassen(Leser leser, int versuche, long warteMs, Abbruch abbruch) {
        Boolean b = sicher(leser);
        for (int i = 0; b == null && i < versuche; i++) {
            try {
                Thread.sleep(warteMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (abbruch != null && abbruch.jetzt()) return null;
            b = sicher(leser);
        }
        return b;
    }

    private static Boolean sicher(Leser l) {
        try {
            return l.lies();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Schloss frisch lesen, ohne Cloud-Abruf anzustoßen (dieselbe Zusammenführung wie die Fahrzeugseite). */
    private static Boolean schlossJetzt() {
        try {
            at.greenchili.surfmate.byd.BydDataCollector c = at.greenchili.surfmate.byd.BydDataCollector.getInstance();
            at.greenchili.surfmate.byd.BydVehicleData v = c != null && c.isInitialized() ? c.getData() : null;
            if (v == null) return null;
            return schlossAusSperrStand(
                    at.greenchili.surfmate.server.VehicleControlApiHandler.sperrStand(v, false));
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code sperrStand}-Ergebnis: overall 1 = zu, 2 = offen, sonst unbekannt. */
    static Boolean schlossAusSperrStand(JSONObject doors) {
        if (doors == null) return null;
        int o = doors.optInt("overall", -1);
        if (o == 1) return Boolean.TRUE;
        if (o == 2) return Boolean.FALSE;
        return null;
    }

    /** Gesamtschloss wie die Startseite: alt 1 = zu, 2 = offen; DiLink 5 (Cloud-Kodierung) 2 = zu, 1 = offen. */
    static Boolean verriegelt(int[] status, boolean diLink5) {
        if (status == null || status.length < 7) return null;
        int v = status[6];
        if (diLink5) {
            if (v == 2) return Boolean.TRUE;
            if (v == 1) return Boolean.FALSE;
            return null;
        }
        if (v == 1) return Boolean.TRUE;
        if (v == 2) return Boolean.FALSE;
        return null;
    }

    private static File datei() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.path("abschalt_bericht.json"));
    }

    private static long gemeldetBis() {
        try {
            File f = datei();
            if (!f.exists()) return -1;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .optLong("bisFahrt", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void merkeGemeldet(long id) {
        if (id < 0) return;
        if (id > gemeldetImSpeicher) gemeldetImSpeicher = id;
        if (id <= gemeldetBis()) return;
        try {
            File f = datei();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), new JSONObject().put("bisFahrt", id).toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) Files.write(f.toPath(), Files.readAllBytes(tmp.toPath()));
        } catch (Throwable ignored) {
        }
    }
}
