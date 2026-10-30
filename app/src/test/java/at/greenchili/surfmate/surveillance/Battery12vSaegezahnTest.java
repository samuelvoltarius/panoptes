package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.AkkuChemie;
import at.greenchili.surfmate.surveillance.Battery12vEvaluator.State;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.31: Die 12 V laufen im Stand als Sägezahn. Fehlalarm vom 30.10.2026 (Stopp + CRITICAL im Tiefpunkt), echte
 * Entladung muss weiter stoppen. Testdaten: Stundenwerte (min, max) aus Home Assistant, 27.10. 21 Uhr bis
 * 30.10. 09 Uhr, Gang P, keine Orte.
 */
public class Battery12vSaegezahnTest {

    private static final long MIN = 60_000L;
    private static final long TAKT = 90_000L;
    private static final long PARK = 1_800_000_000_000L;
    private static final double STOPP = 12.8, WIEDER = 13.1;

    /** {min, max} je Stunde; Lücken im Recorder (29.10. 03/05 Uhr) mit dem Nachbarwert gefüllt. */
    private static final double[][] HA_STUNDEN = {
            {13.1, 13.2}, {13.0, 13.2}, {13.0, 13.0}, {12.9, 13.0}, {12.8, 13.7}, {13.2, 13.8}, {13.1, 13.1},
            {13.0, 13.2}, {12.9, 13.0}, {12.9, 13.0}, {12.8, 13.7}, {13.2, 13.8}, {13.1, 13.2}, {12.8, 13.8},
            {13.2, 13.5}, {13.1, 13.2}, {13.0, 13.0}, {12.9, 13.0}, {12.9, 13.0}, {12.9, 13.0}, {12.9, 13.8},
            {13.7, 13.7}, {13.5, 13.8}, {13.2, 13.8}, {13.1, 13.2}, {13.0, 13.7}, {13.0, 13.0}, {12.9, 13.0},
            {12.9, 13.0}, {13.2, 13.8}, {13.1, 13.2}, {13.0, 13.2}, {13.0, 13.1}, {12.9, 13.0}, {12.9, 12.9},
            {12.9, 13.0}, {13.2, 13.8}, {13.1, 13.1}, {13.0, 13.2}, {12.9, 13.8}, {13.2, 13.8}, {13.1, 13.1},
            {13.1, 13.8}, {13.2, 13.8}, {13.2, 13.4}, {13.1, 13.2}, {13.0, 13.0}, {12.9, 13.0}, {12.9, 13.0},
            {12.8, 13.8}, {13.1, 13.4}, {13.1, 13.2}, {12.9, 13.0}, {12.9, 13.0}, {12.8, 12.8}, {13.7, 13.8},
            {13.1, 13.4}, {13.0, 13.2}, {12.9, 12.9}, {12.9, 13.0}, {12.8, 12.9}, {12.7, 12.8}};

    @After
    public void melderZurueck() {
        GuardStopMonitor.melder12v = GuardStopMonitor::melden12v;
    }

    /** Alle 90 s ein Wert aus den Stundenwerten: mit Sprung nach oben zuerst der Tiefpunkt, sonst fallend. */
    private static List<Double> haVerlauf() {
        List<Double> vs = new ArrayList<>();
        for (double[] h : HA_STUNDEN) {
            boolean sprung = h[1] - h[0] >= 0.4;
            for (int i = 0; i < 40; i++) {
                double anteil = i / 39.0;
                double v = sprung ? (i < 30 ? h[0] : h[1]) : h[1] + (h[0] - h[1]) * anteil;
                vs.add(Math.round(v * 10) / 10.0);
            }
        }
        return vs;
    }

    private static final class Lauf {
        final Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long t = PARK + 21 * MIN;
        int low;

        State wert(double v) {
            e.offer(v, t, PARK);
            State s = e.evaluate(STOPP, WIEDER, t);
            if (s == State.LOW) low++;
            t += TAKT;
            return s;
        }

        State halten(double v, long dauerMs) {
            State s = State.UNKNOWN;
            for (long m = 0; m < dauerMs; m += TAKT) s = wert(v);
            return s;
        }
    }

    // ---------------------------------------------------------------- Kein Fehlalarm

    @Test
    public void echterSaegezahnStopptNie() {
        Lauf l = new Lauf();
        for (double v : haVerlauf()) l.wert(v);
        assertEquals("kein einziger LOW-Takt über 2,5 Tage", 0, l.low);
    }

    /** 30.10. 09:42: Daemon frisch gestartet, Tiefpunkt 12,7 V, wenige Minuten Median. */
    @Test
    public void tiefpunktDirektNachDaemonStartStopptNicht() {
        Lauf l = new Lauf();
        for (int i = 0; i < 20; i++) l.wert(12.7);
        assertEquals(0, l.low);
    }

    /** Lange Pausen kommen vor (16 h), sie enden aber bei 12,8–12,9 V. */
    @Test
    public void langePauseOhneTiefstandStopptNicht() {
        Lauf l = new Lauf();
        l.halten(13.1, 240 * MIN);
        l.halten(13.0, 240 * MIN);
        l.halten(12.9, 240 * MIN);
        l.halten(12.8, 240 * MIN);
        assertEquals(0, l.low);
    }

    // ---------------------------------------------------------------- Echte Schwäche

    /** Echte Tiefentladung: nach dem letzten Nachladen fällt die Spannung weiter, der DC-DC kommt nie wieder. */
    @Test
    public void tiefentladungOhneNachladenStopptNachDemZyklus() {
        Lauf l = new Lauf();
        l.halten(13.7, 30 * MIN);
        long start = l.t;
        double v = 13.2;
        State s = State.OK;
        while (s != State.LOW && l.t - start < 12 * 60 * MIN) {
            s = l.wert(Math.round(v * 10) / 10.0);
            v -= 0.085 * TAKT / (60 * MIN);
        }
        assertEquals(State.LOW, s);
        double stunden = (l.t - start) / (60.0 * MIN);
        assertTrue("erst nach dem Zyklus: " + stunden, stunden >= 7.9);
        assertTrue("nicht viel später: " + stunden, stunden <= 8.5);
    }

    /** Fällt die Spannung tiefer als jeder Sägezahn, wird nicht auf den Zyklus gewartet. */
    @Test
    public void notfallStopptOhneZyklus() {
        Lauf l = new Lauf();
        l.halten(13.1, 60 * MIN);
        assertEquals(State.OK, l.halten(12.3, 12 * MIN));
        assertEquals(State.LOW, l.halten(12.3, 25 * MIN));
    }

    /** Blei/AGM: Die Schwelle 12,2 V liegt unter dem Notfall, sie bleibt die Grenze. */
    @Test
    public void bleiStopptAnSeinerSchwelleOhneZyklus() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long t = PARK + 21 * MIN;
        State s = State.OK;
        for (int i = 0; i < 24; i++, t += TAKT) {
            e.offer(12.1, t, PARK);
            s = e.evaluate(12.2, 12.5, t);
        }
        assertEquals(State.LOW, s);
    }

    /** Lädt das Auto noch nach, bricht die Batterie aber sofort wieder ein, zählt der Anteil der Zeit unten. */
    @Test
    public void einbrechendeBatterieTrotzNachladenStoppt() {
        Lauf l = new Lauf();
        State s = State.OK;
        for (int z = 0; z < 8 && s != State.LOW; z++) {
            l.halten(13.7, 6 * MIN);
            s = l.halten(12.7, 54 * MIN);
        }
        assertEquals(State.LOW, s);
    }

    // ---------------------------------------------------------------- Wieder-Ein

    private static Lauf gestoppt() {
        Lauf l = new Lauf();
        l.halten(12.3, 45 * MIN);
        assertEquals(State.LOW, l.e.state());
        return l;
    }

    /** 30.10. 09:50: 13,7 V, der Median hing an alten Werten, der Wächter blieb aus. */
    @Test
    public void nachladenHebtStoppBinnenMinutenAuf() {
        Lauf l = gestoppt();
        l.wert(13.7);
        l.wert(13.7);
        assertEquals("drei Werte in Folge, Median noch unten", State.OK, l.wert(13.7));
        assertTrue(l.e.lastMedian() < 13.1);
        assertEquals("kein Rückfall durch den alten Tiefstand", State.OK, l.halten(13.2, 120 * MIN));
    }

    @Test
    public void einzelnerAusreisserHebtStoppNichtAuf() {
        Lauf l = gestoppt();
        l.wert(13.7);
        assertEquals(State.LOW, l.wert(12.3));
    }

    @Test
    public void wiederEinUnterDerSchwelleGiltNicht() {
        Lauf l = gestoppt();
        assertEquals(State.LOW, l.halten(13.0, 30 * MIN));
    }

    /** Schwelle höher eingestellt als jedes Nachladen dauerhaft hält: 13,7 V muss trotzdem aufheben. */
    @Test
    public void hoheWiederEinSchwelleSperrtNicht() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long t = PARK + 21 * MIN;
        for (int i = 0; i < 30; i++, t += TAKT) {
            e.offer(12.3, t, PARK);
            e.evaluate(12.8, 13.9, t);
        }
        assertEquals(State.LOW, e.state());
        State s = State.LOW;
        for (int i = 0; i < 3; i++, t += TAKT) {
            e.offer(13.5, t, PARK);
            s = e.evaluate(12.8, 13.9, t);
        }
        assertEquals(State.OK, s);
    }

    // ---------------------------------------------------------------- Neustart

    private static Lauf tiefSeitSiebenStunden() {
        Lauf l = new Lauf();
        l.halten(13.0, 7 * 60 * MIN);
        l.halten(12.7, 30 * MIN);
        return l;
    }

    @Test
    public void neustartZaehltDenZyklusWeiterAberNieSofort() {
        Lauf vorher = tiefSeitSiebenStunden();
        Lauf nachher = new Lauf();
        nachher.t = vorher.t + 5 * MIN;
        nachher.e.wiederherstellen(vorher.e.stand(), nachher.t);
        assertEquals("erst eine Stunde Tiefstand", State.OK, nachher.halten(12.7, 30 * MIN));
        assertEquals(State.LOW, nachher.halten(12.7, 60 * MIN));
    }

    @Test
    public void langeLueckeVerwirftDenStand() {
        Lauf vorher = tiefSeitSiebenStunden();
        Lauf nachher = new Lauf();
        nachher.t = vorher.t + 3 * 60 * MIN;
        nachher.e.wiederherstellen(vorher.e.stand(), nachher.t);
        assertEquals(State.OK, nachher.halten(12.7, 90 * MIN));
    }

    @Test
    public void stoppUeberlebtNeustart() {
        Lauf vorher = gestoppt();
        Lauf nachher = new Lauf();
        nachher.t = vorher.t + MIN;
        nachher.e.wiederherstellen(vorher.e.stand(), nachher.t);
        assertEquals(State.LOW, nachher.e.state());
        nachher.wert(13.7);
        nachher.wert(13.7);
        nachher.wert(13.7);
        assertEquals(State.OK, nachher.wert(13.7));
    }

    // ---------------------------------------------------------------- Chemie und Warnung

    @Test
    public void oberkanteIstDerObereTeilDesSaegezahns() {
        Lauf l = new Lauf();
        List<Double> vs = haVerlauf();
        for (double v : vs) l.wert(v);
        assertTrue("" + l.e.ruheOben(), l.e.ruheOben() >= AkkuChemie.LFP_RUHE_V);
        assertTrue("" + l.e.ruheOben(), l.e.ruheOben() < Battery12vEvaluator.NACHLADEN_V);
    }

    @Test
    public void oberkanteBrauchtGenugVerlauf() {
        Lauf l = new Lauf();
        l.halten(12.9, 5 * 60 * MIN);
        assertTrue(Double.isNaN(l.e.ruheOben()));
        l.halten(12.9, 8 * 60 * MIN);
        assertEquals(12.9, l.e.ruheOben(), 1e-9);
    }

    @Test
    public void bleiRuhtUnterDerLfpGrenze() {
        Lauf l = new Lauf();
        l.halten(12.6, 13 * 60 * MIN);
        assertTrue(l.e.ruheOben() < AkkuChemie.BLEI_RUHE_V);
    }

    @Test
    public void ruhetageBleiUeberstimmtKeineBelege() throws Exception {
        JSONObject tage = new JSONObject()
                .put("2026-10-24", 12.7).put("2026-10-25", 12.8).put("2026-10-26", 12.8)
                .put("2026-10-30", 13.1);
        AkkuChemie.Ergebnis e = AkkuChemie.ermitteln(null, null, tage, Double.NaN, true);
        assertEquals(AkkuChemie.LFP, e.chemie);
        assertFalse("ruhetage_blei".equals(e.grund));
    }

    @Test
    public void sehrSchwachNurMitUrteil() {
        assertEquals(Starterbatterie12v.Stufe.VOR,
                Starterbatterie12v.bestaetigt(Starterbatterie12v.Stufe.NIEDRIG, false));
        assertEquals(Starterbatterie12v.Stufe.NIEDRIG,
                Starterbatterie12v.bestaetigt(Starterbatterie12v.Stufe.KEINE, true));
    }

    private static final class Still implements GuardStopMonitor.Surveillance {
        public boolean isRunning() { return false; }
        public void stop() { }
        public void start() { }
    }

    private static GuardStopPolicy.Config cfg() {
        GuardStopPolicy.Config c = new GuardStopPolicy.Config();
        c.v12Below = STOPP;
        c.v12Resume = WIEDER;
        return c;
    }

    @Test
    public void saegezahnMeldetNichts() throws Exception {
        List<String> gemeldet = new ArrayList<>();
        GuardStopMonitor.melder12v = (s, v) -> gemeldet.add(s.name());
        GuardStopMonitor m = new GuardStopMonitor(new Still(), Battery12vEvaluator.Profile.DILINK3);
        m.merker12vForTest(Starterbatterie12v.Merker.leer());
        long t = PARK + 21 * MIN;
        for (double v : haVerlauf()) {
            m.v12ForTest(v, t, PARK, STOPP, WIEDER);
            m.warnung12vPruefen(t, cfg(), "lfp", null);
            t += TAKT;
        }
        assertEquals("[]", gemeldet.toString());
    }

    @Test
    public void tiefentladungMeldetSehrSchwach() {
        List<String> gemeldet = new ArrayList<>();
        GuardStopMonitor.melder12v = (s, v) -> gemeldet.add(s.name());
        GuardStopMonitor m = new GuardStopMonitor(new Still(), Battery12vEvaluator.Profile.DILINK3);
        m.merker12vForTest(Starterbatterie12v.Merker.leer());
        long t = PARK + 21 * MIN;
        for (int i = 0; i < 9 * 40; i++, t += TAKT) {
            m.v12ForTest(13.0 - 0.45 * i / (9.0 * 40), t, PARK, STOPP, WIEDER);
            m.warnung12vPruefen(t, cfg(), "lfp", null);
        }
        assertEquals("[NIEDRIG]", gemeldet.toString());
    }
}
