package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.1.28 (P3) „Klima läuft wirklich?“: an/aus nur mit Beleg, sonst unbekannt; eine Cloud-Sitzung nach der Uhr ist
 * nur „gestartet, nicht bestätigt“. airRunState-Werte sind bis zur Messung (PLAN_0128 §4) leer.
 */
public class KlimaLaeuftTest {

    @Test
    public void lokalMitAutoAnIstBeleg() {
        KlimaLaeuft.Ergebnis e = KlimaLaeuft.bewerte(true, 1, -1, -1, false);
        assertEquals(KlimaLaeuft.LAEUFT, e.zustand);
        assertEquals(KlimaLaeuft.QUELLE_AUTO, e.quelle);
        assertEquals(KlimaLaeuft.AUS, KlimaLaeuft.bewerte(true, 0, -1, -1, false).zustand);
    }

    @Test
    public void autoAusLokaleWerteZaehlenNicht() {
        // Auto aus: acStartState aus dem Schnappschuss ist kein Beleg
        KlimaLaeuft.Ergebnis e = KlimaLaeuft.bewerte(false, 1, -1, -1, false);
        assertEquals(KlimaLaeuft.UNBEKANNT, e.zustand);
        assertNull(e.quelle);
    }

    @Test
    public void cloudSitzungNachUhrIstNurUnbestaetigt() throws Exception {
        // 0.1.38.7: airRunState 1 = läuft ist gemessen (04.11.2026); ein ungemessener Wert (2, 3 …) ist kein Beleg
        KlimaLaeuft.Ergebnis e = KlimaLaeuft.bewerte(false, -1, 2, 1000, true);
        assertEquals("ungemessener airRunState ist kein Beleg", KlimaLaeuft.UNBEKANNT, e.zustand);
        assertTrue(e.unbestaetigt());
        JSONObject j = e.json();
        assertEquals("unbekannt", j.getString("zustand"));
        assertTrue(j.isNull("quelle"));
        assertTrue(j.getBoolean("unbestaetigt"));
        // frischer Cloud-Wert 1 bei Zündung aus: läuft (Quelle Cloud), geht vor „laut Befehl“
        KlimaLaeuft.Ergebnis c = KlimaLaeuft.bewerte(false, 0, 1, 1000, true);
        assertEquals(KlimaLaeuft.LAEUFT, c.zustand);
        assertEquals(KlimaLaeuft.QUELLE_CLOUD, c.quelle);
        // lokal 0 bei Zündung aus ist nie „aus“
        assertEquals(KlimaLaeuft.UNBEKANNT, KlimaLaeuft.bewerte(false, 0, -1, -1, false).zustand);
        // zu alter Cloud-Wert: kein Beleg mehr
        assertEquals(KlimaLaeuft.UNBEKANNT, KlimaLaeuft.bewerte(false, 0, 1, KlimaLaeuft.CLOUD_MAX_ALTER_MS + 1, true).zustand);
        // Auto an: lokaler Zustand gewinnt vor der Cloud
        assertEquals(KlimaLaeuft.AUS, KlimaLaeuft.bewerte(true, 0, 1, 1000, true).zustand);
    }

    @Test
    public void airRunStateNurLaeuftGemessen() {
        assertFalse("„aus“ ist ungemessen (PLAN_0128 §4): cloudGemessen bleibt falsch, bis beide Seiten belegt sind",
                KlimaLaeuft.cloudGemessen());
        assertEquals("läuft = 1 (gemessen 04.11.2026)", KlimaLaeuft.LAEUFT, KlimaLaeuft.ausCloud(1, 1000));
        for (int w = 0; w <= 5; w++) if (w != 1) assertNull("nur 1 ist belegt, nie aus dem Rest auf aus schließen: " + w, KlimaLaeuft.ausCloud(w, 1000));
    }

    @Test
    public void cloudMitGemessenenWertenUndAlter() {
        int[] an = {1}, aus = {0};
        assertEquals(KlimaLaeuft.LAEUFT, KlimaLaeuft.ausCloud(1, 1000, an, aus));
        assertEquals(KlimaLaeuft.AUS, KlimaLaeuft.ausCloud(0, 1000, an, aus));
        assertNull("unbekannter Wert", KlimaLaeuft.ausCloud(2, 1000, an, aus));
        assertNull("zu alt", KlimaLaeuft.ausCloud(1, KlimaLaeuft.CLOUD_MAX_ALTER_MS + 1, an, aus));
        assertNull("Alter unbekannt", KlimaLaeuft.ausCloud(1, -1, an, aus));
        assertNull("nicht gemeldet", KlimaLaeuft.ausCloud(-1, 1000, an, aus));
    }

    @Test
    public void lokalerBelegBestaetigtBefohlen() {
        KlimaLaeuft.Ergebnis e = KlimaLaeuft.bewerte(true, 1, -1, -1, true);
        assertEquals(KlimaLaeuft.LAEUFT, e.zustand);
        assertFalse(e.unbestaetigt());
    }
}
