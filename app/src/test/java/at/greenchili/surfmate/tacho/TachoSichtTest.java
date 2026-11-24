package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 0.1.43.20 (P4): Pflichtfrage „Warnleuchten und Tempo sichtbar?“ – nein/Timeout = sofort zurück, Funktion aus. */
public class TachoSichtTest {

    static final class SpeicherAttrappe implements TachoSicht.Speicher {
        JSONObject daten = new JSONObject();

        @Override public JSONObject lies() {
            try { return new JSONObject(daten.toString()); } catch (Exception e) { return new JSONObject(); }
        }

        @Override public boolean schreib(Map<String, Object> werte) {
            try {
                for (Map.Entry<String, Object> e : werte.entrySet()) daten.put(e.getKey(), e.getValue());
                return true;
            } catch (Exception ex) { return false; }
        }
    }

    private static final TachoSicht.Speicher ECHT = TachoSicht.speicher;
    private static final TachoSicht.Rueckweg ECHTER_RUECKWEG = TachoSicht.rueckweg;
    private static final TachoSicht.Uhr ECHTE_UHR = TachoSicht.uhr;
    private static final TachoSicht.Probe ECHTE_PROBE = TachoSicht.probe;
    private final AtomicInteger probeStart = new AtomicInteger();
    private final AtomicInteger probeEnde = new AtomicInteger();

    static TachoSicht.Speicher echterSpeicher() { return ECHT; }

    private SpeicherAttrappe speicher;
    private final AtomicInteger zurueck = new AtomicInteger();
    private final AtomicLong jetzt = new AtomicLong(1_000_000L);

    @Before
    public void vorbereiten() {
        TachoSicht.zuruecksetzenFuerTest();
        speicher = new SpeicherAttrappe();
        TachoSicht.speicher = speicher;
        TachoSicht.rueckweg = zurueck::incrementAndGet;
        TachoSicht.uhr = jetzt::get;
        TachoSicht.probe = new TachoSicht.Probe() {
            @Override public boolean start() { probeStart.incrementAndGet(); return true; }
            @Override public void ende() { probeEnde.incrementAndGet(); }
        };
    }

    @After
    public void aufraeumen() {
        TachoSicht.zuruecksetzenFuerTest();
        TachoSicht.speicher = ECHT;
        TachoSicht.rueckweg = ECHTER_RUECKWEG;
        TachoSicht.uhr = ECHTE_UHR;
        TachoSicht.probe = ECHTE_PROBE;
    }

    @Test
    public void jaSpeichertBestaetigungOhneRueckweg() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        assertTrue(TachoSicht.stand("k1").getBoolean("offen"));
        JSONObject r = TachoSicht.beantworte("ja");
        assertTrue(r.getBoolean("ok"));
        assertEquals(TachoSicht.JA, TachoSicht.antwort("k1"));
        assertEquals(0, zurueck.get());
        assertFalse(TachoSicht.stand("k1").getBoolean("offen"));
    }

    @Test
    public void neinHoltSofortDieBydAnzeigeZurueck() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        JSONObject r = TachoSicht.beantworte("nein");
        assertTrue(r.getBoolean("zurueck"));
        assertEquals(1, zurueck.get());
        assertEquals(TachoSicht.NEIN, TachoSicht.antwort("k1"));
    }

    @Test
    public void weissNichtZaehltAlsUnbekannt() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        TachoSicht.beantworte("unbekannt");
        assertEquals(1, zurueck.get());
        assertEquals(TachoSicht.UNBEKANNT, TachoSicht.antwort("k1"));
    }

    @Test
    public void fristAbgelaufenIstUnbekanntUndZurueck() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        long bis = jetzt.get() + TachoSicht.FRIST_MS;
        jetzt.addAndGet(TachoSicht.FRIST_MS + 1);
        TachoSicht.fristAbgelaufen("k1", bis);
        assertEquals(TachoSicht.UNBEKANNT, TachoSicht.antwort("k1"));
        assertEquals(1, zurueck.get());
        // späte Antwort ändert nichts mehr
        assertFalse(TachoSicht.beantworte("ja").getBoolean("ok"));
        assertEquals(TachoSicht.UNBEKANNT, TachoSicht.antwort("k1"));
    }

    @Test
    public void spaeteAntwortNachFristZaehltNicht() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        jetzt.addAndGet(TachoSicht.FRIST_MS);
        assertEquals("keine_frage_offen", TachoSicht.beantworte("ja").getString("grund"));
        assertEquals("", TachoSicht.antwort("k1"));
    }

    @Test
    public void ungueltigeAntwortUndOhneFrage() throws Exception {
        assertEquals("ungueltige_antwort", TachoSicht.beantworte("vielleicht").getString("grund"));
        assertEquals("keine_frage_offen", TachoSicht.beantworte("ja").getString("grund"));
        assertEquals(0, zurueck.get());
    }

    @Test
    public void bereitsBeantwortetFragtNichtNochmal() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        TachoSicht.beantworte("ja");
        TachoSicht.oeffnungGesendet("k1");
        assertFalse(TachoSicht.stand("k1").getBoolean("offen"));
    }

    @Test
    public void neuPruefenLoeschtNurDiesenSchluessel() throws Exception {
        TachoSicht.oeffnungGesendet("k1");
        TachoSicht.beantworte("nein");
        TachoSicht.oeffnungGesendet("k2");
        TachoSicht.beantworte("ja");
        TachoSicht.zuruecksetzen("k1");
        assertEquals("", TachoSicht.antwort("k1"));
        assertEquals(TachoSicht.JA, TachoSicht.antwort("k2"));
    }

    @Test
    public void hinweisSagtTimeoutIstUnbekannt() throws Exception {
        JSONObject s = TachoSicht.stand("k1");
        assertEquals("Warnleuchten und Tempo sichtbar?", s.getString("pflicht"));
        assertTrue(s.getString("hinweis").contains("unbekannt"));
    }

    @Test
    public void testerFreigabeIstGeraetegebundenUndFaelltNachFirmwareUpdate() {
        assertFalse(TachoSicht.testerFreigabe("k1"));
        assertFalse("ohne Schlüssel keine Freigabe", TachoSicht.setzeTesterFreigabe(true, ""));
        assertTrue(TachoSicht.setzeTesterFreigabe(true, "k1"));
        assertTrue(TachoSicht.testerFreigabe("k1"));
        // neue Firmware = neuer Schlüssel → aus
        assertFalse(TachoSicht.testerFreigabe("k1-neue-firmware"));
        TachoSicht.setzeTesterFreigabe(false, "k1");
        assertFalse(TachoSicht.testerFreigabe("k1"));
    }

    @Test
    public void sichtprobeStartetNurOhneAntwortUndEndetBeiJa() throws Exception {
        assertEquals("probe", TachoSicht.starteSichtprobe("k1"));
        assertEquals(1, probeStart.get());
        TachoSicht.oeffnungGesendet("k1");
        TachoSicht.beantworte("ja");
        assertEquals("Probe endet auch nach ja", 1, probeEnde.get());
        assertEquals("schon_beantwortet", TachoSicht.starteSichtprobe("k1"));
        assertEquals(1, probeStart.get());
        TachoSicht.probe = new TachoSicht.Probe() {
            @Override public boolean start() { return false; }
            @Override public void ende() { }
        };
        assertEquals("DiLink 5: Frage beim ersten Start", "beim_ersten_start", TachoSicht.starteSichtprobe("k2"));
    }

    @Test
    public void bisherGenutztNurAusEigenenEinstellungen() throws Exception {
        assertFalse(TachoSicht.bisherGenutzt(null));
        assertFalse(TachoSicht.bisherGenutzt(new JSONObject()));
        assertTrue(TachoSicht.bisherGenutzt(new JSONObject("{\"tachoNavi\":{\"an\":true}}")));
        assertTrue(TachoSicht.bisherGenutzt(new JSONObject("{\"tachoNavi\":{\"hinweisVerstanden\":5}}")));
        assertTrue(TachoSicht.bisherGenutzt(new JSONObject("{\"tachoNavi\":{\"freigaben\":{\"x.y\":{}}}}")));
        assertTrue(TachoSicht.bisherGenutzt(new JSONObject("{\"blindspot\":{\"target\":\"cluster\"}}")));
        assertTrue(TachoSicht.bisherGenutzt(new JSONObject("{\"blindspot\":{\"clusterSizeProfile\":31}}")));
        assertFalse(TachoSicht.bisherGenutzt(new JSONObject("{\"blindspot\":{\"target\":\"head_unit\"}}")));
    }

    @Test
    public void pausiertHinweisEinmalNurBeiUngepruefterHardware() {
        TachoKohorte.Urteil unbekannt = new TachoKohorte.Urteil(TachoKohorte.GESPERRT, "hardware_unbekannt", "x");
        TachoKohorte.Urteil frei = new TachoKohorte.Urteil(TachoKohorte.FREI, "bestaetigt", "x");
        assertFalse("nie genutzt", TachoSicht.pausiertHinweisFaellig(false, unbekannt));
        assertFalse("geprüft", TachoSicht.pausiertHinweisFaellig(true, frei));
        assertTrue(TachoSicht.pausiertHinweisFaellig(true, unbekannt));
        TachoSicht.pausiertGesehen();
        assertFalse("nur einmal", TachoSicht.pausiertHinweisFaellig(true, unbekannt));
    }
}
