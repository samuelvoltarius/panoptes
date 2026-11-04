package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Neubau 2 (0.1.9): anzeige.darstellung leicht|voll, Standard leicht. Seit 0.1.38.7 auch vom PC/Handy aus änderbar. */
public class AnzeigeDarstellungTest {
    @Test
    public void feld() {
        Feld f = null;
        for (Feld x : Katalog.felder(new Katalog.Kontext())) if ("anzeige.darstellung".equals(x.key)) f = x;
        assertTrue(f != null);
        assertEquals(Feld.Typ.AUSWAHL, f.typ);
        assertEquals("leicht", f.standard);
        assertEquals("system", f.gruppe);
        assertFalse("0.1.38.7: Fernzugriff erlaubt, das Auto holt es im Takt", f.nurLesendExtern);
        assertEquals(2, f.optionen.size());
    }
}
