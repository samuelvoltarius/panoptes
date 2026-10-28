package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 0.1.26 Klima-Sollwert als Stufe 1–17 (Display/BYD-App). Lokal SDK = Stufe + 16 (Foto 23.10.), Cloud Roh = Stufe
 * (Annahme der Koordination, Messung folgt). Anzeige: 1 = Kühlen 8, 9 = Neutral, 10–17 = Heizen 1–8.
 */
public class KlimaStufeTest {

    @Test
    public void lokaleUmrechnungMitGrenzen() {
        assertEquals(1, KlimaStufe.ausSdk(17));
        assertEquals(9, KlimaStufe.ausSdk(25));
        assertEquals(10, KlimaStufe.ausSdk(26));
        assertEquals(17, KlimaStufe.ausSdk(33));
        assertEquals(17, KlimaStufe.zuSdk(1));
        assertEquals(26, KlimaStufe.zuSdk(KlimaStufe.HEIZEN_AB));
        assertEquals(33, KlimaStufe.zuSdk(17));
        for (int s = 1; s <= 17; s++) assertEquals(s, KlimaStufe.ausSdk(KlimaStufe.zuSdk(s)));
        assertEquals(-1, KlimaStufe.ausSdk(16));
        assertEquals(-1, KlimaStufe.ausSdk(34));
        assertEquals(-1, KlimaStufe.ausSdk(Double.NaN));
        assertEquals(-1, KlimaStufe.zuSdk(0));
        assertEquals(-1, KlimaStufe.zuSdk(18));
    }

    @Test
    public void cloudRohGleichStufeMitGrenzen() {
        assertEquals(1, KlimaStufe.zuCloudRoh(1));
        assertEquals(10, KlimaStufe.zuCloudRoh(10));
        assertEquals(17, KlimaStufe.zuCloudRoh(17));
        assertEquals(-1, KlimaStufe.zuCloudRoh(0));
        assertEquals(-1, KlimaStufe.zuCloudRoh(18));
        // der Befund: alter SDK-Wert 27 ist Stufe 11, nicht 13 wie mit °C − 14
        assertEquals(11, KlimaStufe.zuCloudRoh(KlimaStufe.ausSdk(27)));
    }

    @Test
    public void anzeigeWieImDisplay() {
        assertEquals("Kühlen Stufe 8", KlimaStufe.text(1));
        assertEquals("Kühlen Stufe 1", KlimaStufe.text(8));
        assertEquals("Neutral", KlimaStufe.text(9));
        assertEquals("Heizen Stufe 1", KlimaStufe.text(10));
        assertEquals("Heizen Stufe 4", KlimaStufe.text(13));
        assertEquals("Heizen Stufe 8", KlimaStufe.text(17));
        assertEquals("Heizen Stufe 2", KlimaStufe.textAusSdk(27));
        assertEquals("unbekannt", KlimaStufe.text(0));
        assertNull(KlimaStufe.art(18));
        assertEquals("Heizen", KlimaStufe.textOhneStufe(11));
        assertEquals("Kühlen", KlimaStufe.textOhneStufe(3));
        assertEquals("Neutral", KlimaStufe.textOhneStufe(9));
    }

    @Test
    public void sdkEingabeExakt() {
        assertTrue(KlimaStufe.sdkGanzGueltig(17));
        assertTrue(KlimaStufe.sdkGanzGueltig(33));
        assertFalse(KlimaStufe.sdkGanzGueltig(16));
        assertFalse(KlimaStufe.sdkGanzGueltig(25.5));
    }

    @org.junit.After
    public void ab() {
        KlimaStufe.fuerTest(null);
    }

    @Test
    public void modellListeNurSurfUndKonfiguration() throws Exception {
        assertTrue(KlimaStufe.istStufenModell("seagull", null));
        assertTrue(KlimaStufe.istStufenModell(" DolphinSurf ", null));
        assertFalse(KlimaStufe.istStufenModell("dolphin", null));
        assertFalse(KlimaStufe.istStufenModell("seal", null));
        assertFalse("ohne Modell wie bisher", KlimaStufe.istStufenModell(null, null));
        org.json.JSONObject k = new org.json.JSONObject().put("stufenModelle", new org.json.JSONArray().put("atto2"));
        assertTrue("nach Community-Meldung per Konfiguration", KlimaStufe.istStufenModell("Atto2", k));
    }

    @Test
    public void cloudUndAnzeigeJeModell() {
        KlimaStufe.fuerTest(Boolean.TRUE);
        assertEquals(1, KlimaStufe.cloudRoh(17));
        assertEquals(17, KlimaStufe.cloudRoh(33));
        assertEquals(26, KlimaStufe.sdkAusCloudRoh(10));
        assertEquals("Heizen Stufe 2", KlimaStufe.anzeige(27));
        assertEquals("Heating level 2", KlimaStufe.anzeigeEn(27));
        KlimaStufe.fuerTest(Boolean.FALSE);
        assertEquals("pyBYD wie bis 0.1.25", 13, KlimaStufe.cloudRoh(27));
        assertEquals(1, KlimaStufe.cloudRoh(15));
        assertEquals(-1, KlimaStufe.cloudRoh(32));
        assertEquals(24, KlimaStufe.sdkAusCloudRoh(10));
        assertEquals("27 °C", KlimaStufe.anzeige(27));
        assertEquals("27 °C", KlimaStufe.anzeigeEn(27));
    }

    @Test
    public void vorklimaNachAussentemperatur() {
        assertEquals(12, KlimaStufe.vorklimaStufe(-5));
        assertEquals(12, KlimaStufe.vorklimaStufe(14.9));
        assertEquals(10, KlimaStufe.vorklimaStufe(15));
        assertEquals(10, KlimaStufe.vorklimaStufe(24));
        assertEquals(6, KlimaStufe.vorklimaStufe(24.1));
        assertEquals(6, KlimaStufe.vorklimaStufe(35));
        assertEquals("unbekannt", 12, KlimaStufe.vorklimaStufe(Double.NaN));
        assertEquals("unplausibel = unbekannt", 12, KlimaStufe.vorklimaStufe(-129));
        assertEquals(28, KlimaStufe.vorklimaSdk(3));
        assertEquals("Heizen Stufe 3", KlimaStufe.text(KlimaStufe.vorklimaStufe(3)));
        assertEquals("Kühlen Stufe 3", KlimaStufe.text(KlimaStufe.vorklimaStufe(30)));
    }
}
