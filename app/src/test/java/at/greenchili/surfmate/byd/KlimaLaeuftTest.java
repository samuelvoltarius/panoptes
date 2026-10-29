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
        KlimaLaeuft.Ergebnis e = KlimaLaeuft.bewerte(false, -1, 1, 1000, true);
        assertEquals("ungemessener airRunState ist kein Beleg", KlimaLaeuft.UNBEKANNT, e.zustand);
        assertTrue(e.unbestaetigt());
        JSONObject j = e.json();
        assertEquals("unbekannt", j.getString("zustand"));
        assertTrue(j.isNull("quelle"));
        assertTrue(j.getBoolean("unbestaetigt"));
    }

    @Test
    public void airRunStateUngemessen() {
        assertFalse("Messung PLAN_0128 §4 steht aus – Tabelle muss leer bleiben, bis sie belegt ist",
                KlimaLaeuft.cloudGemessen());
        for (int w = 0; w <= 5; w++) assertNull(KlimaLaeuft.ausCloud(w, 1000));
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
