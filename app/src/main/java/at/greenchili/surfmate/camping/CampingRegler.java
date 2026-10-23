package at.greenchili.surfmate.camping;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Intervall-Heizen als reine Zustandsmaschine (CAMPING_PLAN.md 2.1, 2.1a, 4). Kein Gerätezugriff: Der Regler
 * sagt nur, was zu tun wäre ({@link Entscheidung}); in 0.1.16 Stufe 2a führt niemand das aus (Trockenlauf,
 * Koordination 23.10.2026). Ein späterer Heizweg (H1 lokal / H2 Cloud) setzt die Entscheidung erst nach
 * Freigabe um.
 *
 * <p>Sicherheitsregeln (Abschnitt 4): SoC-Grenze und 12 V sperren das Heizen; Höchstdauer; bei Fahrt endet
 * Camping sofort und der Regler schaltet das Klima <b>nicht</b> ab (Beschlag); bei unbekanntem Fahrzustand
 * keine Schaltentscheidung; nach Neustart kein neues Einschalten vor dem nächsten planmäßigen Zyklus;
 * schaltet das Auto selbst ab, wird nicht neu gestartet, sondern gemeldet und pausiert (nur außerhalb des
 * Trockenlaufs auswertbar).
 */
public final class CampingRegler {

    public enum Fahrt { STEHT, FAEHRT, UNBEKANNT }
    public enum Phase { VORHEIZEN, PAUSE, HEIZEN, MORGEN, GESPERRT, ENDE }
    public enum Aktion { KEINE, EIN, AUS }

    public static final String AUS = "aus", SPAREN = "sparen";

    /** Einstellungen (aus dem Schema, Gruppe camping). */
    public static final class Einstellung {
        public String waerme = HeizTabelle.WARM;          // aus / sparen / mild / warm / sehr_warm
        public int vorheizenMin = 30;
        public boolean morgens;
        public int weckMinuteDesTages = -1;                // −1 = keine Weckzeit
        public int socGrenze = 20;
        public double v12Grenze = Double.NaN;              // NaN = aus
        public int hoechstdauerStunden = 12;
    }

    /** Lage in diesem Takt. */
    public static final class Lage {
        public long jetztMs;
        public int minuteDesTages;
        public Fahrt fahrt = Fahrt.UNBEKANNT;
        public double soc = Double.NaN;
        public double v12Median = Double.NaN;
        public double aussenC = Double.NaN;
        /** Beobachtet: läuft das Klima? null = unbekannt. Nur außerhalb des Trockenlaufs ausgewertet. */
        public Boolean klimaLaeuft;
        /** Raten aus Messung bzw. Kennfeld (NaN = unbekannt). */
        public double grundProzentProStunde = Double.NaN;
        public double heizProzentProStunde = Double.NaN;
        /** Gelernte Verschiebung (K) für das aktuelle Außentemperatur-Band ({@link HeizLernen}). */
        public double gelerntK;
        /** power_level (0 aus, 1 ACC, 2 an; −1 unbekannt) und Alter der Lesung in ms (für den Heizweg). */
        public int powerLevel = -1;
        public long powerLevelAlterMs = Long.MAX_VALUE;
    }

    public static final class Entscheidung {
        public Aktion aktion = Aktion.KEINE;
        public int stufe, geblaese;
        public boolean sitzheizung;
        public Phase phase;
        public long naechsterZyklusMs;
        public long phaseEndeMs;
        public boolean campingBeenden;
        public String endeGrund;
        public String sperrGrund;
        public EnergieBudget.Plan plan;
        public HeizTabelle.Zeile zeile;
        public final List<String> meldungen = new ArrayList<>();
    }

    private final HeizTabelle tabelle;
    private volatile boolean trockenlauf;

    // ---- Zustand (persistiert) ----
    long aktivSeitMs;
    Phase phase = Phase.ENDE;
    long phaseEndeMs, naechsterZyklusMs;
    double heizStundenKumuliert;
    long heizBeginnMs;
    String morgenErledigtTag = "";
    boolean warnSocGegeben, grenzeGemeldet, v12Gemeldet, hoechstGemeldet, gestrecktGemeldet;
    boolean pausiertBisBestaetigung;
    boolean nachNeustart;

    public CampingRegler(HeizTabelle tabelle, boolean trockenlauf) {
        this.tabelle = tabelle;
        this.trockenlauf = trockenlauf;
    }

    public boolean aktiv() { return phase != Phase.ENDE; }
    public boolean trockenlauf() { return trockenlauf; }
    /** Heizweg „probelauf“ ⇔ Trockenlauf (Einstellung camping.heizweg). */
    public void setTrockenlauf(boolean t) { trockenlauf = t; }

    /**
     * Der Heizweg konnte nicht einschalten (Fehler): wie eine Abweichung – Phase beenden, melden lassen,
     * erst nach Bestätigung weiter (kein stures Wiederholen, CAMPING_PLAN.md 4.6).
     */
    public synchronized void fehlgeschlagen(long jetztMs) {
        if (heizt()) heizBeginnMs = 0;
        pausiertBisBestaetigung = true;
        if (phase != Phase.ENDE) phase = Phase.PAUSE;
    }

    /** Der Heizweg hat bewusst nichts gesendet (Grenze, Gate, Zustand): Phase ohne Heizzeit beenden. */
    public synchronized void uebersprungen(long jetztMs) {
        if (heizt()) heizBeginnMs = 0;
        if (phase != Phase.ENDE) phase = Phase.PAUSE;
    }
    public Phase phase() { return phase; }
    public double heizStundenKumuliert(long jetztMs) {
        return heizStundenKumuliert + (heizt() && heizBeginnMs > 0 ? (jetztMs - heizBeginnMs) / 3_600_000.0 : 0);
    }
    public long aktivSeitMs() { return aktivSeitMs; }

    boolean heizt() { return phase == Phase.VORHEIZEN || phase == Phase.HEIZEN || phase == Phase.MORGEN; }

    /** Camping einschalten. Startet mit Vorheizen (falls eingestellt), sonst mit einer Pause bis zum ersten Zyklus. */
    public synchronized Entscheidung einschalten(Einstellung e, Lage l) {
        aktivSeitMs = l.jetztMs;
        heizStundenKumuliert = 0;
        heizBeginnMs = 0;
        warnSocGegeben = grenzeGemeldet = v12Gemeldet = hoechstGemeldet = gestrecktGemeldet = false;
        pausiertBisBestaetigung = false;
        nachNeustart = false;
        morgenErledigtTag = "";
        phase = Phase.PAUSE;
        naechsterZyklusMs = l.jetztMs;    // sofort prüfen
        Entscheidung d = takt(e, l, true);
        d.meldungen.add(0, "camping_an");
        return d;
    }

    /** Camping ausschalten (Tipp, Handy, HA). Das Klima bleibt, wie es ist, außer der Regler heizt gerade. */
    public synchronized Entscheidung ausschalten(String grund, long jetztMs) {
        Entscheidung d = new Entscheidung();
        if (heizt()) {
            // Auf Wunsch aus (nicht Fahrt): den eigenen Heizzyklus beenden
            d.aktion = "losgefahren".equals(grund) ? Aktion.KEINE : Aktion.AUS;
            heizStundenKumuliert = heizStundenKumuliert(jetztMs);
            heizBeginnMs = 0;
        }
        phase = Phase.ENDE;
        d.phase = Phase.ENDE;
        d.campingBeenden = true;
        d.endeGrund = grund;
        d.meldungen.add("camping_aus_" + grund);
        return d;
    }

    /** Ein Takt (jede Minute). */
    public synchronized Entscheidung takt(Einstellung e, Lage l) { return takt(e, l, false); }

    /** „Weiter heizen“ nach einer Pause wegen Abweichung (Tipp des Nutzers). */
    public synchronized void bestaetigen() { pausiertBisBestaetigung = false; }

    private Entscheidung takt(Einstellung e, Lage l, boolean ersterTakt) {
        Entscheidung d = new Entscheidung();
        if (phase == Phase.ENDE) {
            d.phase = Phase.ENDE;
            return d;
        }
        // 1. Fahrt: Camping endet, Klima bleibt beim Fahrer (nicht abschalten – Beschlag)
        if (l.fahrt == Fahrt.FAEHRT) {
            return ausschalten("losgefahren", l.jetztMs);
        }
        // 2. Unbekannter Fahrzustand: nichts schalten, Zustand halten
        if (l.fahrt == Fahrt.UNBEKANNT) {
            d.phase = phase;
            d.sperrGrund = "fahrzustand_unbekannt";
            d.naechsterZyklusMs = naechsterZyklusMs;
            return d;
        }

        HeizTabelle.Zeile z = tabelle.fuer(l.aussenC, e.waerme, l.gelerntK);
        d.zeile = z;

        // 3. Sperren (Abschnitt 4)
        String sperre = null;
        if (AUS.equals(e.waerme)) sperre = "waerme_aus";
        if (!Double.isNaN(l.soc)) {
            if (l.soc <= e.socGrenze) {
                sperre = "soc_grenze";
                if (!grenzeGemeldet) { d.meldungen.add("akku_grenze_heizen_aus"); grenzeGemeldet = true; }
            } else if (l.soc <= e.socGrenze + 5 && !warnSocGegeben) {
                d.meldungen.add("akku_bald_grenze");
                warnSocGegeben = true;
            }
        } else {
            sperre = sperre == null ? "soc_unbekannt" : sperre;
        }
        if (!Double.isNaN(e.v12Grenze) && !Double.isNaN(l.v12Median) && l.v12Median < e.v12Grenze) {
            sperre = "v12";
            if (!v12Gemeldet) { d.meldungen.add("v12_niedrig_heizen_aus"); v12Gemeldet = true; }
        }
        if (e.hoechstdauerStunden > 0 && l.jetztMs - aktivSeitMs >= e.hoechstdauerStunden * 3_600_000L) {
            sperre = "hoechstdauer";
            if (!hoechstGemeldet) { d.meldungen.add("hoechstdauer_heizen_aus"); hoechstGemeldet = true; }
        }
        if (pausiertBisBestaetigung) sperre = "abweichung_bestaetigen";

        // 4. Abweichung erkennen (nur echter Betrieb): Auto hat selbst abgeschaltet
        if (!trockenlauf && heizt() && Boolean.FALSE.equals(l.klimaLaeuft)
                && l.jetztMs - heizBeginnMs > 2 * 60_000L) {
            d.meldungen.add("auto_hat_abgeschaltet");
            pausiertBisBestaetigung = true;
            heizStundenKumuliert = heizStundenKumuliert(l.jetztMs);
            heizBeginnMs = 0;
            phase = Phase.PAUSE;
            naechsterZyklusMs = l.jetztMs + z.intervallMin * 60_000L;
            d.phase = phase;
            d.sperrGrund = "abweichung_bestaetigen";
            return d;
        }

        boolean sparen = SPAREN.equals(e.waerme);
        if (sperre != null || sparen) {
            if (heizt()) {
                d.aktion = Aktion.AUS;
                heizStundenKumuliert = heizStundenKumuliert(l.jetztMs);
                heizBeginnMs = 0;
            }
            phase = sperre != null ? Phase.GESPERRT : Phase.PAUSE;
            d.phase = phase;
            d.sperrGrund = sperre;
            d.sitzheizung = sparen && sperre == null;
            return d;
        }
        if (phase == Phase.GESPERRT) {
            phase = Phase.PAUSE;
            naechsterZyklusMs = Math.max(naechsterZyklusMs, l.jetztMs);
        }

        // 5. Budget und Strecken
        EnergieBudget.Eingaben be = new EnergieBudget.Eingaben();
        be.verfuegbarProzent = l.soc - e.socGrenze;
        be.grundProzentProStunde = l.grundProzentProStunde;
        be.heizProzentProStunde = l.heizProzentProStunde;
        be.intervallMin = z.intervallMin;
        be.dauerMin = z.dauerMin;
        be.mindestDauerMin = tabelle.mindestDauerMin;
        be.hoechstIntervallMin = tabelle.hoechstIntervallMin;
        int bisWeck = minutenBis(l.minuteDesTages, e.weckMinuteDesTages);
        be.minutenBisWeck = bisWeck;
        if (e.morgens && bisWeck >= 0 && !Double.isNaN(l.heizProzentProStunde)) {
            be.morgenReserveProzent = tabelle.morgensDauerMin / 60.0
                    * (l.heizProzentProStunde + (Double.isNaN(l.grundProzentProStunde) ? 0 : l.grundProzentProStunde));
        }
        EnergieBudget.Plan plan = EnergieBudget.rechnen(be);
        d.plan = plan;
        if (plan.gestreckt && !gestrecktGemeldet) { d.meldungen.add("budget_gestreckt"); gestrecktGemeldet = true; }
        int intervall = plan.intervallMin, dauer = plan.dauerMin;

        // 6. Phasen
        String heute = tag(l.jetztMs);
        boolean morgenFaellig = e.morgens && bisWeck >= 0 && bisWeck <= tabelle.morgensDauerMin
                && !heute.equals(morgenErledigtTag);
        if (ersterTakt && e.vorheizenMin > 0) {
            starte(d, Phase.VORHEIZEN, l.jetztMs, e.vorheizenMin, tabelle.vorheizStufe, tabelle.vorheizGeblaese);
            naechsterZyklusMs = phaseEndeMs + (long) (intervall - dauer) * 60_000L;
        } else if (morgenFaellig && phase != Phase.MORGEN) {
            if (nachNeustart) {
                nachNeustart = false;      // nach Neustart erst lesen
            } else {
                morgenErledigtTag = heute;
                starte(d, Phase.MORGEN, l.jetztMs, Math.max(1, bisWeck), tabelle.morgensStufe, tabelle.morgensGeblaese);
            }
        } else if (heizt()) {
            if (l.jetztMs >= phaseEndeMs) {
                d.aktion = Aktion.AUS;
                heizStundenKumuliert = heizStundenKumuliert(l.jetztMs);
                heizBeginnMs = 0;
                if (phase == Phase.HEIZEN) {
                    naechsterZyklusMs = Math.max(naechsterZyklusMs, l.jetztMs + (long) (intervall - dauer) * 60_000L);
                } else {
                    naechsterZyklusMs = l.jetztMs + (long) (intervall - dauer) * 60_000L;
                }
                phase = Phase.PAUSE;
            } else {
                nachNeustart = false;
            }
        } else if (l.jetztMs >= naechsterZyklusMs) {
            if (nachNeustart) {
                // nach einem Neustart: erst beim nächsten planmäßigen Zyklus wieder einschalten
                nachNeustart = false;
                naechsterZyklusMs = l.jetztMs + (long) intervall * 60_000L;
            } else {
                starte(d, Phase.HEIZEN, l.jetztMs, dauer, z.stufe, z.geblaese);
                naechsterZyklusMs = l.jetztMs + (long) intervall * 60_000L;
            }
        }
        d.phase = phase;
        d.phaseEndeMs = heizt() ? phaseEndeMs : 0;
        d.naechsterZyklusMs = naechsterZyklusMs;
        return d;
    }

    private void starte(Entscheidung d, Phase p, long jetzt, int minuten, int stufe, int geblaese) {
        if (heizt()) heizStundenKumuliert = heizStundenKumuliert(jetzt);
        phase = p;
        phaseEndeMs = jetzt + minuten * 60_000L;
        heizBeginnMs = jetzt;
        d.aktion = Aktion.EIN;
        d.stufe = stufe;
        d.geblaese = geblaese;
    }

    /** Minuten von jetzt bis zur nächsten Weckzeit (0…1439), oder −1 ohne Weckzeit. */
    static int minutenBis(int jetzt, int weck) {
        if (weck < 0 || jetzt < 0) return -1;
        int m = weck - jetzt;
        return m < 0 ? m + 1440 : m;
    }

    private static String tag(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
    }

    // ---------------------------------------------------------------- Persistenz

    public synchronized JSONObject zustand() {
        JSONObject o = new JSONObject();
        try {
            o.put("aktivSeitMs", aktivSeitMs).put("phase", phase.name()).put("phaseEndeMs", phaseEndeMs)
                    .put("naechsterZyklusMs", naechsterZyklusMs).put("heizStunden", heizStundenKumuliert)
                    .put("heizBeginnMs", heizBeginnMs).put("morgenErledigtTag", morgenErledigtTag)
                    .put("pausiert", pausiertBisBestaetigung);
            JSONArray m = new JSONArray();
            if (warnSocGegeben) m.put("warnSoc");
            if (grenzeGemeldet) m.put("grenze");
            if (v12Gemeldet) m.put("v12");
            if (hoechstGemeldet) m.put("hoechst");
            if (gestrecktGemeldet) m.put("gestreckt");
            o.put("gemeldet", m);
        } catch (Exception ignored) { }
        return o;
    }

    /** Zustand nach einem Neustart laden; danach wird bis zum nächsten planmäßigen Zyklus nichts eingeschaltet. */
    public synchronized void laden(JSONObject o) {
        if (o == null) return;
        try {
            phase = Phase.valueOf(o.optString("phase", Phase.ENDE.name()));
        } catch (IllegalArgumentException e) {
            phase = Phase.ENDE;
        }
        aktivSeitMs = o.optLong("aktivSeitMs", 0);
        phaseEndeMs = o.optLong("phaseEndeMs", 0);
        naechsterZyklusMs = o.optLong("naechsterZyklusMs", 0);
        heizStundenKumuliert = o.optDouble("heizStunden", 0);
        heizBeginnMs = o.optLong("heizBeginnMs", 0);
        morgenErledigtTag = o.optString("morgenErledigtTag", "");
        pausiertBisBestaetigung = o.optBoolean("pausiert", false);
        JSONArray m = o.optJSONArray("gemeldet");
        String s = m == null ? "" : m.toString();
        warnSocGegeben = s.contains("warnSoc");
        grenzeGemeldet = s.contains("grenze");
        v12Gemeldet = s.contains("v12");
        hoechstGemeldet = s.contains("hoechst");
        gestrecktGemeldet = s.contains("gestreckt");
        nachNeustart = phase != Phase.ENDE;
    }
}
