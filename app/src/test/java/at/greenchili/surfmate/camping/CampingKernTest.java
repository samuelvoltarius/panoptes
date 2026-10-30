package at.greenchili.surfmate.camping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import at.greenchili.surfmate.camping.CampingRegler.Aktion;
import at.greenchili.surfmate.camping.CampingRegler.Fahrt;
import at.greenchili.surfmate.camping.CampingRegler.Phase;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.16 Camping Stufe 2a (CAMPING_PLAN.md 2.1, 4): Kennfeld, Lernen, Budget, Verbrauch, Regler im Trockenlauf. */
public class CampingKernTest {

    private static final long MIN = 60_000L;
    private HeizTabelle tab;

    static String asset() throws Exception {
        Path p = Paths.get("src/main/assets/camping/heiztabelle.json");
        if (!Files.isRegularFile(p)) p = Paths.get("app/src/main/assets/camping/heiztabelle.json");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Before
    public void lade() throws Exception {
        tab = HeizTabelle.aus(asset());
    }

    // ------------------------------------------------------------ Kennfeld

    @Test
    public void tabellenzeilenAusDemPlan() {
        HeizTabelle.Zeile z = tab.fuer(0, HeizTabelle.WARM, 0);
        assertEquals(45, z.intervallMin); assertEquals(15, z.dauerMin); assertEquals(29, z.stufe);
        z = tab.fuer(-10, HeizTabelle.WARM, 0);
        assertEquals(35, z.intervallMin); assertEquals(33, z.stufe);
        z = tab.fuer(-25, HeizTabelle.WARM, 0);
        assertEquals("unter der Tabelle: letzte Zeile", 35, z.intervallMin);
        z = tab.fuer(20, HeizTabelle.WARM, 0);
        assertEquals(180, z.intervallMin); assertEquals(10, z.dauerMin);
        assertEquals("25 = neutral am Surf, kleinste Heizstufe 26", 26, z.stufe);
    }

    @Test
    public void zwischenDenZeilenWirdInterpoliert() {
        HeizTabelle.Zeile z = tab.fuer(2.5, HeizTabelle.WARM, 0);
        // Takt 33 % → 20 %, halb: 26,7 %; Dauer 15; Intervall 15/0,267 ≈ 56
        assertEquals(15, z.dauerMin);
        assertEquals(56, z.intervallMin);
        assertEquals(28, z.stufe);
    }

    @Test
    public void keineZeileMitNeutralstufe() {
        for (double ta = -20; ta <= 25; ta += 0.5) {
            for (String w : new String[]{HeizTabelle.MILD, HeizTabelle.WARM, HeizTabelle.SEHR_WARM}) {
                assertTrue(ta + " " + w, tab.fuer(ta, w, 0).stufe >= 26);
            }
        }
    }

    @Test
    public void wahlUndLernenVerschieben() {
        assertEquals(tab.fuer(4, HeizTabelle.WARM, 0).intervallMin, tab.fuer(0, HeizTabelle.MILD, 0).intervallMin);
        assertEquals(tab.fuer(-4, HeizTabelle.WARM, 0).intervallMin, tab.fuer(0, HeizTabelle.SEHR_WARM, 0).intervallMin);
        assertTrue("zu kalt (−1,5 K) heizt mehr",
                tab.fuer(0, HeizTabelle.WARM, -1.5).takt() > tab.fuer(0, HeizTabelle.WARM, 0).takt());
    }

    @Test
    public void ohneAussentemperaturGiltErsatzzeile() {
        assertEquals(45, tab.fuer(Double.NaN, HeizTabelle.WARM, 0).intervallMin);
    }

    @Test
    public void leistungJeStufe() {
        // P1 (22.10.): Soll 26 ≈ 1,5 kW, Max-Heizen ≈ 2,4 kW
        assertEquals(1.5, tab.leistungKw(26), 1e-9);
        assertEquals(2.4, tab.leistungKw(33), 1e-9);
        assertEquals(1.9, tab.leistungKw(29), 1e-9);
        assertEquals(2.15, tab.leistungKw(31), 1e-9);
    }

    @Test
    public void unsinnigeTabelleWirdAbgelehnt() {
        for (String j : new String[]{"{}", "{\"zeilen\":[]}",
                "{\"zeilen\":[{\"aussenC\":0,\"intervallMin\":10,\"dauerMin\":20,\"stufe\":29}]}",
                "{\"zeilen\":[{\"aussenC\":0,\"intervallMin\":40,\"dauerMin\":10,\"stufe\":40}]}"}) {
            try {
                HeizTabelle.aus(j);
                fail("angenommen: " + j);
            } catch (Exception erwartet) {
                // gut
            }
        }
    }

    // ------------------------------------------------------------ Lernen

    @Test
    public void lernenMitSperreUndGrenzen() {
        HeizLernen l = new HeizLernen();
        long t = 1_000_000L;
        assertTrue(l.rueckmeldung(HeizLernen.ZU_KALT, 0, t));
        assertEquals(-1.5, l.verschiebungK(0), 1e-9);
        assertFalse("30-min-Sperre", l.rueckmeldung(HeizLernen.ZU_KALT, 0, t + 10 * MIN));
        assertTrue(l.rueckmeldung(HeizLernen.ZU_KALT, 0, t + 31 * MIN));
        assertEquals(-3.0, l.verschiebungK(0), 1e-9);
        assertEquals("anderes Band unberührt", 0, l.verschiebungK(-8), 1e-9);
        for (int i = 2; i < 20; i++) l.rueckmeldung(HeizLernen.ZU_KALT, 0, t + i * 31 * MIN);
        assertEquals(-HeizLernen.GRENZE_K, l.verschiebungK(0), 1e-9);
        assertTrue(l.rueckmeldung(HeizLernen.PASST, 0, t));
        assertFalse(l.rueckmeldung("quatsch", 0, t + 1000 * MIN));
        HeizLernen kopie = HeizLernen.aus(l.alsJson());
        assertEquals(-HeizLernen.GRENZE_K, kopie.verschiebungK(1), 1e-9);
        kopie.zuruecksetzen();
        assertEquals(0, kopie.verschiebungK(1), 1e-9);
    }

    // ------------------------------------------------------------ Budget

    private static EnergieBudget.Eingaben budget(double verf, double grund, double heiz, int iv, int du, double weck) {
        EnergieBudget.Eingaben e = new EnergieBudget.Eingaben();
        e.verfuegbarProzent = verf; e.grundProzentProStunde = grund; e.heizProzentProStunde = heiz;
        e.intervallMin = iv; e.dauerMin = du; e.minutenBisWeck = weck;
        return e;
    }

    @Test
    public void budgetReichtOhneStrecken() {
        // 22 % verfügbar, 0,93 %/h Grund + 33 % × 4,64 %/h ≈ 2,46 %/h → ≈ 8,9 h
        EnergieBudget.Plan p = EnergieBudget.rechnen(budget(22, 0.93, 4.64, 45, 15, 7 * 60));
        assertFalse(p.gestreckt);
        assertEquals(Boolean.TRUE, p.reichtBisWeck);
        assertEquals(22 / (0.93 + 4.64 / 3) * 60, p.reichtMinuten, 0.5);
    }

    @Test
    public void erstDauerKuerzenDannIntervallVerlaengern() {
        EnergieBudget.Plan p = EnergieBudget.rechnen(budget(14, 0.93, 4.64, 45, 15, 7 * 60));
        assertTrue(p.gestreckt);
        assertTrue(p.dauerMin >= 10 && p.dauerMin < 15);
        assertEquals("Dauer reichte noch", 45, p.intervallMin);
        p = EnergieBudget.rechnen(budget(10, 0.93, 4.64, 45, 15, 7 * 60));
        assertEquals(10, p.dauerMin);
        assertTrue(p.intervallMin > 45);
        assertEquals(Boolean.TRUE, p.reichtBisWeck);
        p = EnergieBudget.rechnen(budget(3, 0.93, 4.64, 45, 15, 7 * 60));
        assertEquals("ganz gestreckt", 240, p.intervallMin);
        assertEquals(Boolean.FALSE, p.reichtBisWeck);
    }

    @Test
    public void unbekannteRatenOhneBudget() {
        EnergieBudget.Plan p = EnergieBudget.rechnen(budget(20, Double.NaN, 4, 45, 15, 60));
        assertFalse(p.bekannt);
        assertNull(p.reichtBisWeck);
        assertEquals(45, p.intervallMin);
    }

    // ------------------------------------------------------------ Verbrauch

    @Test
    public void verbrauchWirdGemessenUndZerlegt() {
        VerbrauchSchaetzer v = new VerbrauchSchaetzer();
        long t0 = 0;
        double soc = 50, heiz = 0;
        for (int m = 0; m <= 90; m += 5) {
            boolean heizt = (m % 45) < 15;
            v.probe(t0 + m * MIN, soc, heiz);
            double dtH = 5 / 60.0;
            soc -= dtH * (1.0 + (heizt ? 5.0 : 0));
            if (heizt) heiz += dtH;
        }
        VerbrauchSchaetzer.Ergebnis e = v.schaetzen();
        assertTrue(e.gemessen());
        assertEquals(1.0, e.grundProzentProStunde, 0.15);
        assertEquals(5.0, e.heizZusatzProzentProStunde, 0.6);
    }

    @Test
    public void unterDreissigMinutenKeineSchaetzung() {
        VerbrauchSchaetzer v = new VerbrauchSchaetzer();
        v.probe(0, 50, 0); v.probe(10 * MIN, 49.9, 0); v.probe(20 * MIN, 49.8, 0);
        assertFalse(v.schaetzen().gemessen());
    }

    // ------------------------------------------------------------ Regler

    private CampingRegler.Lage lage(long t, Fahrt f, double soc) {
        CampingRegler.Lage l = new CampingRegler.Lage();
        l.jetztMs = t; l.minuteDesTages = (int) ((t / MIN) % 1440); l.fahrt = f; l.soc = soc; l.aussenC = 0;
        l.grundProzentProStunde = 0.9; l.heizProzentProStunde = 4.6;
        return l;
    }

    @Test
    public void vorheizenDannIntervall() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        long t = 21 * 60 * MIN;
        CampingRegler.Entscheidung d = r.einschalten(e, lage(t, Fahrt.STEHT, 80));
        assertEquals(Aktion.EIN, d.aktion);
        assertEquals(Phase.VORHEIZEN, d.phase);
        assertEquals(33, d.stufe);
        assertTrue(d.meldungen.contains("camping_an"));
        d = r.takt(e, lage(t + 10 * MIN, Fahrt.STEHT, 79));
        assertEquals(Aktion.KEINE, d.aktion);
        d = r.takt(e, lage(t + 30 * MIN, Fahrt.STEHT, 78));
        assertEquals(Aktion.AUS, d.aktion);
        assertEquals(Phase.PAUSE, d.phase);
        assertEquals(t + 60 * MIN, d.naechsterZyklusMs);   // Pause = Intervall − Dauer = 30 min
        d = r.takt(e, lage(t + 60 * MIN, Fahrt.STEHT, 77));
        assertEquals(Aktion.EIN, d.aktion);
        assertEquals(Phase.HEIZEN, d.phase);
        assertEquals(29, d.stufe);
        d = r.takt(e, lage(t + 75 * MIN, Fahrt.STEHT, 76));
        assertEquals(Aktion.AUS, d.aktion);
        assertEquals(t + 105 * MIN, d.naechsterZyklusMs);
        assertTrue(r.heizStundenKumuliert(t + 75 * MIN) > 0.74);
    }

    @Test
    public void beiFahrtEndetCampingOhneKlimaAbzuschalten() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));                     // heizt (Vorheizen)
        CampingRegler.Entscheidung d = r.takt(e, lage(5 * MIN, Fahrt.FAEHRT, 80));
        assertTrue(d.campingBeenden);
        assertEquals("losgefahren", d.endeGrund);
        assertEquals("Klima bleibt beim Fahrer", Aktion.KEINE, d.aktion);
        assertFalse(r.aktiv());
    }

    @Test
    public void unbekannterFahrzustandSchaltetNichts() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0;
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Entscheidung d = r.takt(e, lage(200 * MIN, Fahrt.UNBEKANNT, 80));
        assertEquals(Aktion.KEINE, d.aktion);
        assertEquals("fahrzustand_unbekannt", d.sperrGrund);
        assertTrue(r.aktiv());
    }

    @Test
    public void socGrenzeUndWarnungJeEinmal() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        r.einschalten(e, lage(0, Fahrt.STEHT, 30));
        CampingRegler.Entscheidung d = r.takt(e, lage(MIN, Fahrt.STEHT, 24));
        assertTrue(d.meldungen.contains("akku_bald_grenze"));
        d = r.takt(e, lage(2 * MIN, Fahrt.STEHT, 24));
        assertFalse(d.meldungen.contains("akku_bald_grenze"));
        d = r.takt(e, lage(3 * MIN, Fahrt.STEHT, 20));
        assertEquals("heizt (Vorheizen) → aus", Aktion.AUS, d.aktion);
        assertEquals("soc_grenze", d.sperrGrund);
        assertTrue(d.meldungen.contains("akku_grenze_heizen_aus"));
        d = r.takt(e, lage(4 * MIN, Fahrt.STEHT, 19));
        assertEquals(Aktion.KEINE, d.aktion);
        assertFalse(d.meldungen.contains("akku_grenze_heizen_aus"));
        assertTrue("Camping selbst bleibt an", r.aktiv());
    }

    @Test
    public void zwoelfVoltUndHoechstdauerSperren() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0; e.hoechstdauerStunden = 2;
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Lage l = lage(10 * MIN, Fahrt.STEHT, 80);
        l.v12Low = true;
        CampingRegler.Entscheidung d = r.takt(e, l);
        assertEquals("v12", d.sperrGrund);
        assertTrue(d.meldungen.contains("v12_niedrig_heizen_aus"));
        d = r.takt(e, lage(121 * MIN, Fahrt.STEHT, 80));
        assertEquals("hoechstdauer", d.sperrGrund);
    }

    /** 0.1.33: Der Tiefpunkt des Sägezahns (Median unter jeder alten Grenze, kein LOW) sperrt nicht mehr. */
    @Test
    public void saegezahnTiefpunktOhneLowSperrtNicht() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0;
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Lage l = lage(10 * MIN, Fahrt.STEHT, 80);
        l.v12Median = 12.5;
        assertFalse("v12".equals(r.takt(e, l).sperrGrund));
    }

    @Test
    public void zwoelfVoltSchutzLaesstSichAusschalten() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0; e.v12Schutz = false;
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Lage l = lage(10 * MIN, Fahrt.STEHT, 80);
        l.v12Low = true;
        assertFalse("v12".equals(r.takt(e, l).sperrGrund));
    }

    @Test
    public void unbekannterSocHeiztNicht() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Entscheidung d = r.einschalten(new CampingRegler.Einstellung(),
                lage(0, Fahrt.STEHT, Double.NaN));
        assertEquals(Aktion.KEINE, d.aktion);
        assertEquals("soc_unbekannt", d.sperrGrund);
    }

    @Test
    public void nachNeustartErstBeimNaechstenZyklus() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0;
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));            // heizt sofort (Zyklus fällig)
        r.takt(e, lage(15 * MIN, Fahrt.STEHT, 80));             // aus, nächster bei 45
        CampingRegler neu = new CampingRegler(tab, true);
        neu.laden(r.zustand());
        CampingRegler.Entscheidung d = neu.takt(e, lage(46 * MIN, Fahrt.STEHT, 80));
        assertEquals("nach Neustart nicht sofort", Aktion.KEINE, d.aktion);
        assertEquals(46 * MIN + 45 * MIN, d.naechsterZyklusMs);
        d = neu.takt(e, lage(91 * MIN, Fahrt.STEHT, 80));
        assertEquals(Aktion.EIN, d.aktion);
    }

    @Test
    public void sparenNurSitzheizung() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.waerme = CampingRegler.SPAREN;
        CampingRegler.Entscheidung d = r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        assertEquals(Aktion.KEINE, d.aktion);
        assertTrue(d.sitzheizung);
    }

    @Test
    public void morgensVorDerWeckzeit() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        e.vorheizenMin = 0; e.morgens = true; e.weckMinuteDesTages = 7 * 60;
        long t = 6 * 60 * MIN;
        r.einschalten(e, lage(t, Fahrt.STEHT, 80));
        r.takt(e, lage(t + 15 * MIN, Fahrt.STEHT, 80));         // Zyklus aus
        CampingRegler.Entscheidung d = r.takt(e, lage(t + 40 * MIN, Fahrt.STEHT, 79));
        assertEquals(Aktion.EIN, d.aktion);
        assertEquals(Phase.MORGEN, d.phase);
        assertEquals(33, d.stufe);
        d = r.takt(e, lage(t + 60 * MIN, Fahrt.STEHT, 78));
        assertEquals(Aktion.AUS, d.aktion);
    }

    @Test
    public void trockenlaufIgnoriertBeobachtetesKlima() {
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        CampingRegler trocken = new CampingRegler(tab, true);
        trocken.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Lage l = lage(5 * MIN, Fahrt.STEHT, 80);
        l.klimaLaeuft = Boolean.FALSE;
        assertFalse(trocken.takt(e, l).meldungen.contains("auto_hat_abgeschaltet"));

        CampingRegler echt = new CampingRegler(tab, false);
        echt.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Entscheidung d = echt.takt(e, l);
        assertTrue(d.meldungen.contains("auto_hat_abgeschaltet"));
        assertEquals("kein Neustart gegen das Auto", Aktion.KEINE, d.aktion);
        d = echt.takt(e, lage(200 * MIN, Fahrt.STEHT, 80));
        assertEquals("abweichung_bestaetigen", d.sperrGrund);
        echt.bestaetigen();
        assertNotNull(echt.takt(e, lage(201 * MIN, Fahrt.STEHT, 80)));
    }

    @Test
    public void ausschaltenPerTippBeendetEigenenZyklus() {
        CampingRegler r = new CampingRegler(tab, true);
        CampingRegler.Einstellung e = new CampingRegler.Einstellung();
        r.einschalten(e, lage(0, Fahrt.STEHT, 80));
        CampingRegler.Entscheidung d = r.ausschalten("tipp", 5 * MIN);
        assertEquals(Aktion.AUS, d.aktion);
        assertTrue(d.meldungen.contains("camping_aus_tipp"));
        assertFalse(r.aktiv());
    }
}
