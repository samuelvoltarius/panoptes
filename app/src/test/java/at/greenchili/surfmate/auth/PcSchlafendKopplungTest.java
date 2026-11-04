package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * 0.1.38.9 „schlafend“ (Entscheid Projektinhaber, Koordination 04.11. Nacht): Solange „Am PC anmelden“ aus ist (Standard), gibt es
 * keine PC-Kopplung und keine gilt – weder Kopplungs-JWT noch Web-Sitzung noch Brücken-Kanal (Bruecke nimmt nur, was
 * {@link Kopplungen#pruefe} zulässt). Gegenstück für die Endpunkte: {@code server.PcSchlafendTest}.
 */
public class PcSchlafendKopplungTest {

    static boolean an;

    @Before
    public void auf() {
        an = true;
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return an; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        PcZugang.leeren();
        Kopplungen.besitzerMelder = null;
    }

    @After
    public void ab() {
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    private static void schalter(boolean ein) {
        an = ein;
        PcZugang.vergessen();
    }

    @Test
    public void fuenfAbgelehnteHandshakesJeStundeEineMeldungNurBeiSchalterAn() {
        schalter(false);
        for (int i = 0; i < 10; i++) assertFalse("schlafend: zählt nicht", PcZugang.handshakeAbgelehnt(1000L + i));
        schalter(true);
        long t = 10_000_000L;
        for (int i = 0; i < PcZugang.HANDSHAKE_GRENZE - 1; i++) assertFalse(PcZugang.handshakeAbgelehnt(t + i));
        assertTrue("der fünfte meldet", PcZugang.handshakeAbgelehnt(t + 10));
        assertEquals(PcZugang.HANDSHAKE_GRENZE, PcZugang.handshakesLetzteStunde());
        assertFalse("höchstens einmal je Stunde", PcZugang.handshakeAbgelehnt(t + 20));
        long spaeter = t + PcZugang.HANDSHAKE_FENSTER_MS + 30;
        for (int i = 0; i < PcZugang.HANDSHAKE_GRENZE - 1; i++) assertFalse(PcZugang.handshakeAbgelehnt(spaeter + i));
        assertTrue("neue Stunde, wieder 5", PcZugang.handshakeAbgelehnt(spaeter + 10));
    }

    @Test
    public void standardQuelleOhneKonfigurationIstAus() {
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
        assertFalse("ohne Konfiguration (Test-JVM) wie im Auslieferungszustand: aus", PcZugang.erlaubt());
    }

    @Test
    public void schalterAusKeinePcKopplungUndBestehendeGiltNirgends() throws Exception {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Collections.singletonList("web"), 1L, 0L);
        schalter(false);
        assertNull("neue PC-Kopplung abgelehnt", k.anlegenGebunden("PC2", Kopplungen.ART_PC,
                Collections.singletonList("web"), 2L, 0L));
        assertFalse("Brücken-Kanal und jede Anfrage: pruefe() nein", k.pruefe(pc, 3L));
        AuthManager.JwtValidation kopplung = AuthManager.JwtValidation.success("dev");
        kopplung.scope = PairingCodes.SCOPE;
        assertFalse("Kopplungs-JWT ungültig", AuthManager.kopplungPruefen(kopplung, new JSONObject().put("kid", pc), k, 4L).valid);
        AuthManager.JwtValidation web = AuthManager.JwtValidation.success("dev");
        web.scope = WebSitzung.SCOPE;
        assertFalse("Web-Sitzung ungültig", AuthManager.kopplungPruefen(web, new JSONObject().put("kid", pc), k, 5L).valid);
        // Handys sind davon nicht betroffen
        String h = k.anlegen("Handy", "handy", 6L, true);
        assertTrue(k.pruefe(h, 7L));
        assertEquals(1, k.pcIds().size());
        assertEquals("aus = alle PCs widerrufen", 1, k.pcAlleWiderrufen());
        assertTrue(k.pcIds().isEmpty());
    }
}
