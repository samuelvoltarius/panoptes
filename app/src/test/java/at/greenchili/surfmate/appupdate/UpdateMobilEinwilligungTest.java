package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.32: Suche über Mobilfunk ja, Download dort nur mit Einwilligung für genau diese Version. */
public class UpdateMobilEinwilligungTest {

    private static UpdateRegel.Lage lage(boolean nurWlan, boolean einmal, String netz) throws Exception {
        UpdateRegel.Lage l = new UpdateRegel.Lage();
        l.installiert = 8;
        l.verfuegbar = Freigabe.lies(AppUpdateTest.vj(9, "0.1.8", "stabil", "v0.1.8", AppUpdateTest.SHA0));
        l.autoAus = true;
        l.nurWlan = nurWlan;
        l.mobilEinmal = einmal;
        l.netz = netz;
        return l;
    }

    @Test
    public void downloadOhneEinwilligungWartetAufsWlan() throws Exception {
        assertEquals("wartet_wlan", UpdateRegel.nichtWeil(lage(true, false, UpdateRegel.NETZ_MOBIL)));
        assertEquals("unbekanntes Netz zählt nie als WLAN", "wartet_wlan",
                UpdateRegel.nichtWeil(lage(true, false, UpdateRegel.NETZ_UNBEKANNT)));
        assertNull(UpdateRegel.nichtWeil(lage(true, false, UpdateRegel.NETZ_WLAN)));
    }

    @Test
    public void einwilligungOeffnetNurDiesenDownload() throws Exception {
        assertNull(UpdateRegel.nichtWeil(lage(true, true, UpdateRegel.NETZ_MOBIL)));
        UpdateRegel.Lage faehrt = lage(true, true, UpdateRegel.NETZ_MOBIL);
        faehrt.kmh = 30;
        assertEquals("sonst bleibt alles", "faehrt", UpdateRegel.nichtWeil(faehrt));
    }

    @Test
    public void einwilligungGiltNurFuerDieseVersionUndBegrenzt() {
        UpdateHinweis.Stand s = new UpdateHinweis.Stand();
        assertFalse(UpdateHinweis.mobilGilt(s, 9, 1_000L));
        UpdateHinweis.mobilErlauben(s, 9, 1_000L);
        assertTrue(UpdateHinweis.mobilGilt(s, 9, 2_000L));
        assertFalse("andere Version", UpdateHinweis.mobilGilt(s, 10, 2_000L));
        assertFalse("Uhr rückwärts", UpdateHinweis.mobilGilt(s, 9, 500L));
        assertFalse("abgelaufen", UpdateHinweis.mobilGilt(s, 9, 1_000L + UpdateHinweis.VORGEMERKT_GILT_MS));
        UpdateHinweis.vergessen(s);
        assertFalse("Zurücknehmen nimmt die Einwilligung mit", UpdateHinweis.mobilGilt(s, 9, 2_000L));
    }

    @Test
    public void karteNenntDieGroesseNurWennGefragtWird() {
        assertTrue(UpdateHinweis.mobilFragen(true, true, UpdateRegel.NETZ_MOBIL));
        assertFalse("WLAN: keine Frage", UpdateHinweis.mobilFragen(true, true, UpdateRegel.NETZ_WLAN));
        assertFalse("Bestand ohne Merker", UpdateHinweis.mobilFragen(false, true, UpdateRegel.NETZ_MOBIL));
        assertFalse("Mobilfunk erlaubt", UpdateHinweis.mobilFragen(true, false, UpdateRegel.NETZ_MOBIL));
        assertEquals("", UpdateHinweis.mobilSatz(false, 70_000_000L));
        assertTrue(UpdateHinweis.mobilSatz(true, 83_000_000L).contains("83 MB"));
        assertTrue("Rückfall", UpdateHinweis.mobilSatz(true, -1).contains("70 MB"));
    }
}
