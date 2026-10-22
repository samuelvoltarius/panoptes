package at.greenchili.surfmate.uebernahme;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overdrive Stufe 2 (Kern 5, 0.1.10.1; Koordination 22.10.: H2 read-only statt eigenem Parser): Fahrten, Ladungen,
 * Ladestand-Verlauf und Zündungs-Ereignisse einer früheren Overdrive-Installation in die Panoptes-Datenbanken übernehmen.
 *
 * <ul>
 *   <li>Das Original wird nur gelesen (Kopie in den Panoptes-Ordner); die Arbeitskopie wird danach IMMER entfernt.</li>
 *   <li>Start nur mit ≥ 2× Dateigröße freiem Speicher und nur im Stand (Gang P). Läuft im Hintergrund, abbrechbar.</li>
 *   <li>Idempotent: Dubletten über Startzeit/Zeitstempel; Panoptes hat Vorrang (nur Overdrive-Zeilen VOR dem ersten
 *       eigenen Eintrag der jeweiligen Tabelle).</li>
 *   <li>Ladungen bekommen keinen Overdrive-Preis: Ortskreis-Tarif, sonst „Preis fehlt“. Fahrten: Kosten neu
 *       (rate_source = fehlt, Anzeige rechnet nach). Nichts geht an einen Server, keine Werte in Protokolle.</li>
 * </ul>
 */
public final class OverdriveVerlauf {

    public static final String SOC = "overdrive_soc_h2.mv.db";
    public static final String FAHRTEN = "overdrive_trips_h2.mv.db";

    /** Außenwelt (Laufzeit bzw. Test). */
    public interface Umgebung {
        File quellOrdner();
        File arbeitsOrdner();
        long freiBytes(File ordner);
        boolean steht();
        VerlaufKopierer.Ziel ladeZiel();
        VerlaufKopierer.Ziel fahrtZiel();
        /** Nach dem Einfügen: Tarif je Ortskreis, sonst bleibt „Preis fehlt“. Rückgabe = Anzahl bepreist. */
        int preiseNachOrtskreis(List<Long> neueLadungen);
        void ladeTageNeu(List<Long> neueLadungen);
        void fahrtSummenNeu(List<Long> neueFahrten);
    }

    /** Was wird wie übernommen (Tabelle, Schlüssel, ausgelassen, feste Werte, fachliche Dublette). */
    static final class Schritt {
        final String datei, tabelle, schluessel;
        final Set<String> aus;
        final Map<String, Object> setzen;
        VerlaufKopierer.Toleranz tol;
        Schritt(String datei, String tabelle, String schluessel, Set<String> aus, Map<String, Object> setzen) {
            this.datei = datei; this.tabelle = tabelle; this.schluessel = schluessel; this.aus = aus; this.setzen = setzen;
        }
        Schritt mit(VerlaufKopierer.Toleranz t) { this.tol = t; return this; }
    }

    /** 0.1.11: Dublette = Beginn ±60 s UND ähnliche Strecke (±5 % bzw. 0,3 km) – Overdrive/Panoptes haben eigene IDs. */
    static final VerlaufKopierer.Toleranz FAHRT_TOL = new VerlaufKopierer.Toleranz(60_000L, "DISTANCE_KM", 0.05, 0.3);
    /** Ladung: Beginn ±60 s UND ähnliche Energie (±10 % bzw. 0,5 kWh). */
    static final VerlaufKopierer.Toleranz LADE_TOL = new VerlaufKopierer.Toleranz(60_000L, "ENERGY_ADDED_KWH", 0.10, 0.5);

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Set<String> s(String... x) {
        return new HashSet<>(Arrays.asList(x));
    }

    /** Ladungen: Overdrive-Preis verwerfen (TRIPKOSTEN_BEFUND), Herkunft vermerken. */
    static final Schritt LADUNGEN = new Schritt(SOC, "CHARGING_SESSIONS", "START_TIME", s("ID"),
            m("ELECTRICITY_RATE", -1.0, "SESSION_COST", -1.0, "TARIFF_ID", "", "TARIFF_LABEL", "", "PREIS_QUELLE", "fehlt",
                    "NACHFRAGE", "", "POST_COMMIT_TARIFF_APPLIED", 1)).mit(LADE_TOL);
    static final Schritt LADEKURVEN = new Schritt(SOC, "CHARGING_POWER_SAMPLES", null, s("ID"), m());
    static final Schritt SOC_VERLAUF = new Schritt(SOC, "SOC_HISTORY", "TIMESTAMP", s("ID"), m());
    static final Schritt SOC_TAGE = new Schritt(SOC, "SOC_DAILY", "DAY_EPOCH", s(), m());
    static final Schritt ZUENDUNG = new Schritt(SOC, "ACC_EVENTS", "TIMESTAMP", s("ID"), m());
    /**
     * Fahrten: Kosten nicht übernehmen; Routen-Verweis leeren. Telemetrie-Pfad = IMPORTED_PATH (Kern 7): „importiert“, nicht
     * „Datei fehlt“ – sonst hält der Speicher-Abgleich die Fahrten für verwaist.
     */
    static final Schritt FAHRTEN_T = new Schritt(FAHRTEN, "TRIPS", "START_TIME", s("ID", "ROUTE_ID"),
            m("TELEMETRY_FILE_PATH", at.greenchili.surfmate.trips.TripDatabase.IMPORTED_PATH, "SIZE_BYTES", 0L, "SIDECAR_SIZE_BYTES", 0L, "RATE_SOURCE", "fehlt", "RATE_LABEL", "",
                    "ELECTRICITY_RATE", 0.0, "ELECTRIC_COST", 0.0, "TRIP_COST", 0.0)).mit(FAHRT_TOL);

    // ---------------------------------------------------------------- Zustand (eine Übernahme gleichzeitig)

    private final Umgebung u;
    private volatile boolean abbrechen;
    private volatile JSONObject stand = new JSONObject();
    private volatile Thread laeuft;

    public OverdriveVerlauf(Umgebung u) {
        this.u = u;
        setze("bereit", 0, null);
    }

    public JSONObject stand() {
        return stand;
    }

    public void abbrechen() {
        abbrechen = true;
    }

    /** Vorschau: zählt nur (gleiche Regeln wie der Import), schreibt nichts in Panoptes. */
    public JSONObject vorschau() throws Exception {
        JSONObject o = pruefen();
        if (!o.optBoolean("vorhanden")) return o;
        if (!o.optBoolean("speicherOk")) return o;
        JSONObject r = lauf(true);
        o.put("ergebnis", r);
        return o;
    }

    /** Startet die Übernahme im Hintergrund; Antwort sofort ({ok, grund?}). */
    public synchronized JSONObject starten() throws Exception {
        JSONObject o = pruefen();
        if (laeuft != null && laeuft.isAlive()) return fehler("laeuft_schon");
        if (!o.optBoolean("vorhanden")) return fehler("keine_daten");
        if (!o.optBoolean("speicherOk")) return fehler("zu_wenig_speicher").put("brauchtBytes", o.opt("brauchtBytes"));
        if (!u.steht()) return fehler("nur_im_stand");
        abbrechen = false;
        laeuft = new Thread(() -> {
            try {
                JSONObject r = lauf(false);
                setze(abbrechen ? "abgebrochen" : "fertig", 100, r);
            } catch (Throwable t) {
                setze("fehler", stand.optInt("prozent"), null);
                try { stand.put("grund", t.getClass().getSimpleName()); } catch (Exception ignored) { }
            }
        }, "overdrive-verlauf");
        laeuft.setDaemon(true);
        laeuft.start();
        return new JSONObject().put("ok", true);
    }

    /** Für Tests: synchron laufen. */
    JSONObject laufSynchron() throws Exception {
        abbrechen = false;
        JSONObject r = lauf(false);
        setze(abbrechen ? "abgebrochen" : "fertig", 100, r);
        return r;
    }

    JSONObject pruefen() throws Exception {
        JSONObject o = new JSONObject();
        long groesse = 0;
        boolean da = false;
        for (String n : new String[] {SOC, FAHRTEN}) {
            File f = new File(u.quellOrdner(), n);
            if (f.isFile() && f.canRead()) { da = true; groesse += f.length(); }
        }
        o.put("ok", true);
        o.put("vorhanden", da);
        if (!da) return o.put("grund", "keine_daten");
        long frei = u.freiBytes(u.arbeitsOrdner());
        o.put("brauchtBytes", 2 * groesse);
        o.put("freiBytes", frei);
        o.put("speicherOk", frei >= 2 * groesse);
        o.put("steht", u.steht());
        o.put("stand", stand);
        return o;
    }

    private JSONObject lauf(boolean nurZaehlen) throws Exception {
        JSONObject ergebnis = new JSONObject();
        File arbeit = u.arbeitsOrdner();
        if (!arbeit.isDirectory() && !arbeit.mkdirs()) throw new java.io.IOException("arbeitsordner");
        try {
            List<Long> neueLadungen = Collections.emptyList();
            // ---- Ladestand/Ladungen
            Connection soc = oeffneKopie(SOC, arbeit, nurZaehlen ? "vorschau" : "kopieren");
            if (soc != null) {
                try (Connection c = soc) {
                    VerlaufKopierer.Ergebnis l = schritt(c, LADUNGEN, u.ladeZiel(), null, nurZaehlen, ergebnis, "ladungen", 20);
                    neueLadungen = l.neueSchluessel;
                    final Set<Long> starts = new HashSet<>(neueLadungen);
                    VerlaufKopierer.Ergebnis k = VerlaufKopierer.kopiere(c, LADEKURVEN.tabelle, u.ladeZiel(), null, LADEKURVEN.aus,
                            LADEKURVEN.setzen, z -> {
                                Object v = z.get("SESSION_START_TIME");
                                return v instanceof Number && (nurZaehlen ? true : starts.contains(((Number) v).longValue()));
                            }, () -> abbrechen, nurZaehlen);
                    ergebnis.put("ladekurven", k.neu);
                    if (!abbrechen) schritt(c, SOC_VERLAUF, u.ladeZiel(), null, nurZaehlen, ergebnis, "socVerlauf", 40);
                    if (!abbrechen) schritt(c, SOC_TAGE, u.ladeZiel(), null, nurZaehlen, ergebnis, "socTage", 45);
                    if (!abbrechen) schritt(c, ZUENDUNG, u.ladeZiel(), null, nurZaehlen, ergebnis, "zuendung", 50);
                }
            }
            // ---- Fahrten
            Connection fahrt = abbrechen ? null : oeffneKopie(FAHRTEN, arbeit, nurZaehlen ? "vorschau" : "kopieren");
            List<Long> neueFahrten = Collections.emptyList();
            if (fahrt != null) {
                try (Connection c = fahrt) {
                    neueFahrten = schritt(c, FAHRTEN_T, u.fahrtZiel(), null, nurZaehlen, ergebnis, "fahrten", 80).neueSchluessel;
                }
            }
            if (!nurZaehlen) {
                setze("nacharbeit", 85, null);
                ergebnis.put("bepreistNachOrtskreis", neueLadungen.isEmpty() ? 0 : u.preiseNachOrtskreis(neueLadungen));
                if (!neueLadungen.isEmpty()) u.ladeTageNeu(neueLadungen);
                if (!neueFahrten.isEmpty()) u.fahrtSummenNeu(neueFahrten);
            }
            ergebnis.put("abgebrochen", abbrechen);
            return ergebnis;
        } finally {
            aufraeumen(arbeit);   // Auflage: Arbeitskopie immer entfernen, Original unangetastet
        }
    }

    private VerlaufKopierer.Ergebnis schritt(Connection quelle, Schritt s, VerlaufKopierer.Ziel ziel, Object unused,
                                             boolean nurZaehlen, JSONObject ergebnis, String name, int prozent) throws Exception {
        setze(name, prozent - 10, null);
        // Regel 1 (andere Stände): fehlt die Tabelle auf einer Seite, wird der Schritt übersprungen
        if (!VerlaufKopierer.tabelleDa(quelle, s.tabelle) || ziel.spalten(s.tabelle).isEmpty()) {
            ergebnis.put(name, new JSONObject().put("gesamt", 0).put("neu", 0).put("uebersprungen", true));
            return new VerlaufKopierer.Ergebnis();
        }
        // 0.1.11 (Koordination): keine „vor dem ersten Panoptes-Eintrag“-Grenze mehr – eine einzige frühe Panoptes-Zeile
        // (z. B. aus dem Umzug) hätte sonst den ganzen Overdrive-Verlauf verworfen. Dubletten sind fachlich (Toleranz).
        VerlaufKopierer.Ergebnis e = VerlaufKopierer.kopiere(quelle, s.tabelle, ziel, s.schluessel, s.aus, s.setzen,
                null, () -> abbrechen, nurZaehlen, s.tol);
        JSONObject x = new JSONObject();
        x.put("gesamt", e.gesamt);
        x.put("neu", e.neu);
        x.put("dubletten", e.dubletten);
        x.put("panoptesBestand", e.bestand);   // Diagnose: wie viele Zeilen hat Panoptes schon, und von wann bis wann
        if (e.bestand > 0) {
            x.put("panoptesVon", e.bestandVon);
            x.put("panoptesBis", e.bestandBis);
        }
        if (e.neu > 0 && s.schluessel != null && s.schluessel.endsWith("TIME")) {
            x.put("von", e.kleinster);
            x.put("bis", e.groesster);
        }
        ergebnis.put(name, x);
        setze(name, prozent, null);
        return e;
    }

    /** Original nur lesend kopieren, Kopie read-only öffnen. null = Datei fehlt. */
    Connection oeffneKopie(String name, File arbeit, String phase) throws Exception {
        File quelle = new File(u.quellOrdner(), name);
        if (!quelle.isFile() || !quelle.canRead()) return null;
        setze(phase, stand.optInt("prozent"), null);
        File kopie = new File(arbeit, name);
        Files.copy(quelle.toPath(), kopie.toPath(), StandardCopyOption.REPLACE_EXISTING);
        String basis = kopie.getAbsolutePath().substring(0, kopie.getAbsolutePath().length() - ".mv.db".length());
        Class.forName("org.h2.Driver");
        return DriverManager.getConnection("jdbc:h2:file:" + basis
                + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0;IFEXISTS=TRUE", "sa", "");
    }

    static void aufraeumen(File arbeit) {
        File[] l = arbeit.listFiles();
        for (int i = 0; l != null && i < l.length; i++) {
            String n = l[i].getName();
            if (n.startsWith("overdrive_") && (n.endsWith(".mv.db") || n.endsWith(".trace.db") || n.endsWith(".lock.db"))) {
                //noinspection ResultOfMethodCallIgnored
                l[i].delete();
            }
        }
    }

    private void setze(String phase, int prozent, JSONObject ergebnis) {
        try {
            JSONObject s = new JSONObject();
            s.put("phase", phase);
            s.put("prozent", Math.max(0, Math.min(100, prozent)));
            if (ergebnis != null) s.put("ergebnis", ergebnis);
            stand = s;
        } catch (Exception ignored) {
        }
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }
}
